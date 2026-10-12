// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.langbiantianya.jengine.JarLogLine
import com.langbiantianya.jengine.JarRun
import com.langbiantianya.jengine.JarRunResult
import com.langbiantianya.jengine.LogSource
import com.langbiantianya.jengine.ResolvedJre
import com.langbiantianya.jengine.RunState
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A JAR running in a process of its own, launched with the runtime's own
 * `java`.
 *
 * The process is the unit of the run: a desktop JVM cannot be unloaded, and a
 * JAR that leaves non-daemon threads behind keeps its process alive, so ending
 * the run means ending the process — and anything it spawned — rather than
 * asking the JAR to stop.
 *
 * Both streams are read here and appended to one log file, which is also what
 * `lines` and `output()` answer from, so a desktop run and an Android guest
 * report their output the same way even though the Android guest writes it by
 * file descriptor rather than through a Java stream.
 */
internal class DesktopJarRun(
    override val id: String,
    override val jarPath: String,
    override val label: String,
    private val layout: EngineLayout,
    private val jre: ResolvedJre,
    private val classPath: String,
    private val mainClass: String,
    private val args: List<String>,
    private val vmArgs: List<String>,
    private val workDir: String,
    private val environment: Map<String, String>,
) : JarRun {

    private val log: File = layout.logFile(id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val resultDeferred = CompletableDeferred<JarRunResult>()
    private val _state = MutableStateFlow(RunState.STARTING)

    @Volatile
    private var stopRequested = false

    /**
     * The process, or null when it could not be started. A run that failed to
     * start is over the moment it is constructed: it has no process to read,
     * and its result already says why.
     */
    private val process: Process?

    override val logFile: String = log.absolutePath

    override val state: StateFlow<RunState> = _state.asStateFlow()

    /**
     * Whether the run is still going, which is a question about the run rather
     * than about the process: once the process has exited its output is still
     * being drained into the log, and the run is over only when that is done.
     */
    override val isRunning: Boolean get() = _state.value != RunState.FINISHED

    override val lines: Flow<JarLogLine> =
        tailLogLines(log, isFinished = { _state.value == RunState.FINISHED })
            .map { (sequence, text) -> JarLogLine(sequence, text, LogSource.UNKNOWN) }

    init {
        log.parentFile?.mkdirs()
        // Both platforms record where the run worked as the log's first line, so
        // a log pasted into a bug report says which directory the paths in it
        // are relative to.
        appendLine(log, "=== working directory: ${File(workDir).absolutePath} ===")

        process = try {
            startProcess()
        } catch (e: Throwable) {
            _state.value = RunState.FINISHED
            resultDeferred.complete(JarRunResult.Failed(startFailureMessage(e), e))
            null
        }

        val started = process
        if (started != null) {
            _state.value = RunState.RUNNING
            val stdout = scope.launch { pump(started.inputStream) }
            val stderr = scope.launch { pump(started.errorStream) }
            scope.launch {
                val exitCode = withContext(Dispatchers.IO) { started.waitFor() }
                // The readers see end of stream once the process is gone; let
                // them drain before the run is called finished, or a collector
                // could stop before the last lines were written.
                stdout.join()
                stderr.join()
                _state.value = RunState.FINISHED
                resultDeferred.complete(
                    if (stopRequested) JarRunResult.Stopped(exitCode) else JarRunResult.Completed(exitCode),
                )
            }
        }
    }

    override suspend fun output(): String = readWholeFile(log)

    override suspend fun result(): JarRunResult = resultDeferred.await()

    override suspend fun stop(): JarRunResult {
        val started = process ?: return resultDeferred.await()
        if (_state.value == RunState.FINISHED) return resultDeferred.await()

        stopRequested = true
        _state.value = RunState.STOPPING
        withContext(Dispatchers.IO) {
            started.destroy()
            if (!started.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly()
                started.waitFor()
            }
            // A JAR that spawned a helper — a shell, another JVM — leaves it
            // behind otherwise, and a run that is over should not keep a
            // process tree alive.
            killDescendants(started)
        }
        return resultDeferred.await()
    }

    /** Releases the coroutines watching this run; called once the run is over. */
    internal fun dispose() {
        scope.cancel()
    }

    private fun startProcess(): Process {
        val executable = jre.javaExecutable
            ?: throw IOException("the resolved runtime has no java launcher")
        val command = ArrayList<String>(vmArgs.size + args.size + 4)
        command += executable
        command += vmArgs
        // The class path and the main class are the engine's decision, not the
        // caller's VM options; the launcher derives java.class.path and
        // java.home itself, so neither is passed as a property.
        command += "-cp"
        command += classPath
        command += mainClass
        command += args

        val builder = ProcessBuilder(command)
        builder.directory(File(workDir))
        builder.redirectErrorStream(false)
        environment.forEach { (key, value) -> builder.environment()[key] = value }
        return builder.start()
    }

    /** Appends one line of output, serialised so the two streams cannot interleave. */
    private suspend fun pump(stream: InputStream) {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            writeMutex.withLock { appendLine(log, line) }
        }
    }

    private fun killDescendants(process: Process) {
        runCatching {
            val iterator = process.descendants().iterator()
            while (iterator.hasNext()) {
                runCatching { iterator.next().destroy() }
            }
        }
    }

    private fun startFailureMessage(error: Throwable): String {
        val executable = jre.javaExecutable ?: "java"
        return "could not start $executable for $jarPath: ${error.message ?: error.javaClass.simpleName}"
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
