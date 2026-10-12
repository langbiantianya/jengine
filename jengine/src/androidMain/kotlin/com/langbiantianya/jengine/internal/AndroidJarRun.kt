// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.langbiantianya.jengine.JarLogLine
import com.langbiantianya.jengine.JarRun
import com.langbiantianya.jengine.JarRunResult
import com.langbiantianya.jengine.LogSource
import com.langbiantianya.jengine.RunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "jengine"

/** How often the front end reads the files and probes the binder. */
private const val POLL_MILLIS = 150L

/**
 * One run as the front end sees it.
 *
 * The guest is a process of its own, so this is a view onto something that
 * exists independently: there are no callbacks from it, only a binder that can
 * be asked whether it holds a guest and a pair of files on disk. One loop reads
 * all three — the binder, the status file, the exit file — and is the only
 * writer of [state] and [result], which is what keeps them consistent.
 *
 * The run ends when the guest's process ends. A JAR whose `main` returned but
 * whose threads keep running is still a run: it stays [RunState.RUNNING] until
 * it is stopped, because the thing that would stop it is the process, not
 * `main`.
 */
internal class AndroidJarRun(
    private val context: Context,
    private val onFinished: (AndroidJarRun) -> Unit,
    override val id: String,
    override val jarPath: String,
    override val label: String,
    private val log: File,
    private val statusFile: File,
    private val exitFile: File,
    private val recordFile: File,
    /** False when adopting a guest that already exists, so binding never creates one. */
    autoCreate: Boolean = true,
) : JarRun {

    private val _state = MutableStateFlow(RunState.STARTING)
    override val state: StateFlow<RunState> = _state.asStateFlow()

    override val logFile: String get() = log.absolutePath

    /**
     * Whether the run is still going. A run whose `main` returned is still
     * going — the guest is serving — and one that is being stopped is still
     * going until its process is gone, so this is the same question on both
     * platforms: has the run finished?
     */
    override val isRunning: Boolean get() = _state.value != RunState.FINISHED

    private val finished = CompletableDeferred<JarRunResult>()

    /** Completed when the guest's process is seen to be gone. */
    private val guestGone = CompletableDeferred<Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var bound = false
    private var cleanedUp = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                onGuestDied()
                return
            }
            val recipient = IBinder.DeathRecipient { onGuestDied() }
            try {
                service.linkToDeath(recipient, 0)
            } catch (e: RemoteException) {
                // The process was gone by the time we bound to it.
                onGuestDied()
                return
            }
            binder = service
            deathRecipient = recipient
            maybeMarkRunning()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    init {
        bound = runCatching {
            context.bindService(
                Intent(context, GuestJvmService::class.java),
                connection,
                if (autoCreate) Context.BIND_AUTO_CREATE else 0,
            )
        }.getOrElse { error ->
            Log.w(TAG, "could not bind the guest service: ${error.message}")
            false
        }
        scope.launch { poll() }
    }

    /** Whether the run has finished; the engine uses it to prune the active run. */
    internal val isFinished: Boolean get() = finished.isCompleted

    override val lines: Flow<JarLogLine> =
        tailLogLines(log) { _state.value == RunState.FINISHED }
            .map { (seq, text) ->
                // Android redirects the guest's stdout and stderr to one file by
                // file descriptor, so the two cannot be told apart; that is what
                // LogSource.UNKNOWN documents.
                JarLogLine(seq, text, LogSource.UNKNOWN)
            }

    override suspend fun output(): String = withContext(Dispatchers.IO) { readWholeFile(log) }

    override suspend fun result(): JarRunResult = finished.await()

    override suspend fun stop(): JarRunResult {
        if (finished.isCompleted) return finished.await()
        _state.value = RunState.STOPPING
        requestGuestStop()
        val result = finished.await()
        // The status file reaches us a moment before the process is actually
        // gone, so wait for the death as well where it is knowable — a caller
        // that starts the next run immediately after a stop would otherwise
        // race a process that is still holding its VM.
        withTimeoutOrNull(GUEST_GONE_TIMEOUT_MS) { guestGone.await() }
        return result
    }

    /** Best-effort [stop] for a caller that cannot suspend; used by `close`. */
    internal fun requestStop() {
        if (finished.isCompleted) return
        scope.launch { runCatching { stop() } }
    }

    /**
     * Reads the binder and both result files, completing the run on the first
     * terminal answer. A still-running outcome is deliberately not terminal:
     * the guest is serving, so the run goes on until it is stopped or dies.
     */
    private suspend fun poll() {
        if (!bound) {
            // Binding failed, so there is no process to watch and no result file
            // will appear: the run could never have started.
            finish(JarRunResult.Failed("the guest service could not be bound"))
            return
        }
        while (scope.isActive && !finished.isCompleted) {
            maybeMarkRunning()
            val outcome = GuestOutcome.read(statusFile)
            val exitCode = readExitCode()
            when {
                outcome == null -> Unit

                outcome.isError ->
                    return finish(JarRunResult.Failed(outcome.message.ifEmpty { "the guest failed" }))

                outcome.status == GuestProtocol.STATUS_OK ->
                    return finish(JarRunResult.Completed(exitCode ?: 0))

                outcome.isStopped -> return finish(JarRunResult.Stopped(exitCode))

                outcome.isStillRunning -> {
                    // `main` returned and the guest is still serving; the run is
                    // not over, but it is unquestionably running. A stop already
                    // asked for is not undone by this stale-as-of-one-tick file.
                    val current = _state.value
                    if (current == RunState.STARTING || current == RunState.RUNNING) {
                        _state.value = RunState.RUNNING
                    }
                }
            }
            delay(POLL_MILLIS)
        }
    }

    /**
     * The guest process went away.
     *
     * A death is the only thing that can explain a run whose `main` returned
     * while its threads kept running, and it also words a run that died without
     * saying anything. The files are read once more first, so an outcome written
     * just before the process ended still counts.
     */
    private fun onGuestDied() {
        if (finished.isCompleted) return
        guestGone.complete(Unit)
        val outcome = GuestOutcome.read(statusFile)
        val exitCode = readExitCode()
        val result = when {
            // A stop that arrived before the guest had a status file to write —
            // the run was still starting — is still a stop, and saying "crashed"
            // about the process the caller asked to end would be a lie.
            _state.value == RunState.STOPPING -> JarRunResult.Stopped(exitCode)

            outcome == null && exitCode != null -> JarRunResult.Completed(exitCode)

            outcome == null -> JarRunResult.Crashed(
                exitCode = null,
                detail = "the guest process ended without reporting; see $logFile",
            )

            outcome.isError -> JarRunResult.Failed(outcome.message.ifEmpty { "the guest failed" })

            outcome.isStopped -> JarRunResult.Stopped(exitCode)

            outcome.status == GuestProtocol.STATUS_OK -> JarRunResult.Completed(exitCode ?: 0)

            // It was serving when the process went away, so this is how a run
            // that outlived its `main` finally ended.
            outcome.isStillRunning -> JarRunResult.StillRunning(outcome.message.ifEmpty { null })

            else -> JarRunResult.Crashed(
                exitCode = exitCode,
                detail = "the guest ended with '${outcome.status}'; see $logFile",
            )
        }
        finish(result)
    }

    private fun finish(result: JarRunResult) {
        if (!finished.complete(result)) return
        _state.value = RunState.FINISHED
        scope.launch { cleanup() }
    }

    private suspend fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        releaseBinding()
        recordFile.delete()
        onFinished(this)
        scope.cancel()
    }

    private fun releaseBinding() {
        val service = binder
        val recipient = deathRecipient
        if (service != null && recipient != null) {
            runCatching { service.unlinkToDeath(recipient, 0) }
        }
        binder = null
        deathRecipient = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    /** Moves to RUNNING once the guest process answers that it holds a VM. */
    private fun maybeMarkRunning() {
        if (_state.value != RunState.STARTING) return
        val service = binder ?: return
        if (hostsGuest(service)) _state.value = RunState.RUNNING
    }

    private fun requestGuestStop() {
        // A service that is not there is not an error: the guest may already
        // have ended, and the death watch is what settles the run in that case.
        runCatching {
            context.startService(
                Intent(context, GuestJvmService::class.java).setAction(GuestProtocol.ACTION_STOP),
            )
        }.onFailure { Log.w(TAG, "could not ask the guest to stop: ${it.message}") }
    }

    private fun readExitCode(): Int? =
        exitFile.readTextOrNull()?.trim()?.toIntOrNull()

    private companion object {
        /** How long to wait for a stopped guest's process to actually go. */
        const val GUEST_GONE_TIMEOUT_MS = 5_000L
    }
}
