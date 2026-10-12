// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.langbiantianya.jengine.internal.AndroidJarRun
import com.langbiantianya.jengine.internal.EngineLayout
import com.langbiantianya.jengine.internal.GuestJvmService
import com.langbiantianya.jengine.internal.GuestProtocol
import com.langbiantianya.jengine.internal.GuestRun
import com.langbiantianya.jengine.internal.JarManifest
import com.langbiantianya.jengine.internal.RunRecord
import com.langbiantianya.jengine.internal.checkReadable
import com.langbiantianya.jengine.internal.deleteTree
import com.langbiantianya.jengine.internal.environmentEntries
import com.langbiantianya.jengine.internal.hostsGuest
import com.langbiantianya.jengine.internal.mergeJvmArgs
import com.langbiantianya.jengine.internal.newRunId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

private const val TAG = "jengine"

/**
 * Creates an engine scoped to [context]'s app.
 *
 * The context is only read for the two directories the engine needs — its
 * private one for runtimes, and the external one for the files a JAR writes —
 * and only its application context is kept, so passing an Activity cannot leak
 * it.
 */
public fun jengine(context: Context, config: AndroidEngineConfig = AndroidEngineConfig()): JEngine {
    val app = context.applicationContext ?: context
    val home = config.homeDir ?: File(app.filesDir, "jengine")
    // External first: both are the app's, neither needs a permission, but the
    // external one is reachable over USB, so a JAR's files are too.
    val work = config.workRoot ?: File(app.getExternalFilesDir(null) ?: app.filesDir, "jengine")
    return AndroidJEngine(app, config, home, work)
}

/**
 * The Android engine.
 *
 * It hosts one run at a time — a process holds one guest VM, and a VM
 * accumulates state that can be set once — and it does that in a process of its
 * own, so the run outlives the app's UI. What lives here is the front half: it
 * resolves the runtime, reads the JAR's manifest, merges the arguments and hands
 * the result to [GuestJvmService].
 */
internal class AndroidJEngine(
    private val context: Context,
    private val config: AndroidEngineConfig,
    home: File,
    workRoot: File,
) : JEngine {

    private val layout = EngineLayout(home, workRoot).apply { ensureDirectories() }

    override val jres: JreRepository = AndroidJreRepository(layout, config.defaultJreId)

    /** The internal root: where the runtime the engine runs on is unpacked. */
    override val homeDir: String = layout.runtimeRoot.absolutePath

    @Volatile private var current: AndroidJarRun? = null

    /** Serialises the one-run-at-a-time rule and the field above. */
    private val runLock = Mutex()

    override suspend fun run(request: JarRunRequest): JarRun = withContext(Dispatchers.IO) {
        val jar = File(request.jarPath)
        try {
            checkReadable(jar, "JAR")
        } catch (e: IOException) {
            throw JarNotFoundException(request.jarPath, e)
        }
        val mainClass = request.mainClass
            ?: runCatching { JarManifest.mainClassOf(jar) }.getOrNull()
            ?: throw MainClassNotFoundException(jar.absolutePath)

        // One run at a time is the engine's rule, and it is a rule about this
        // engine's bookkeeping as much as about the guest process: two callers
        // racing here would otherwise both write a run record and both start
        // the service, and only one of them would ever be reported on.
        runLock.withLock { startRun(request, jar, mainClass) }
    }

    /** The run setup proper, which only ever runs under [runLock]. */
    private suspend fun startRun(request: JarRunRequest, jar: File, mainClass: String): AndroidJarRun {
        current?.let { run ->
            if (!run.isFinished) throw RunAlreadyActiveException(run.id)
        }

        // A guest this process did not start — one left serving by a UI process
        // Android killed — must go before a new VM can exist, because this
        // process will host the next one.
        retireForeignGuest()

        val ref = request.jre ?: JreRef.Managed(config.defaultJreId)
        val resolved = resolveForRun(ref, request.installJreIfMissing)

        val label = request.label?.takeIf { it.isNotBlank() } ?: jar.name
        val workDir = request.workingDirectory?.let { File(it) } ?: layout.workDir(label)
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw JEngineException("cannot create the working directory $workDir")
        }

        val options = mergeOptions(config.defaultJvm, request.jvm)
        val classPath = JarManifest.classPathOf(jar)
        val vmArgs = buildVmArgs(resolved, workDir, classPath, options)
        val environment = environmentEntries(options.environment)

        val runId = newRunId()
        val logFile = layout.logFile(runId)
        val statusFile = layout.statusFile(runId)
        // The native bridge writes the exit status beside the log it redirects
        // the guest into, so the sidecar's name follows the log's rather than
        // the run id's.
        val exitFile = layout.exitFileFor(logFile)
        val recordFile = layout.descriptorFile(runId)

        val descriptor = GuestRun(
            runId = runId,
            label = label,
            jarPath = jar.absolutePath,
            mainClass = mainClass,
            classPath = classPath,
            args = request.args,
            workDir = workDir.absolutePath,
            logFile = logFile.absolutePath,
            statusFile = statusFile.absolutePath,
            jreHome = resolved.home ?: throw JEngineException("the runtime has no home directory"),
            vmLibrary = resolved.vmLibrary ?: throw JEngineException("the runtime has no VM library"),
            vmArgs = vmArgs,
            environment = environment,
        )

        RunRecord(
            runId = runId,
            label = label,
            jarPath = jar.absolutePath,
            logFile = logFile.absolutePath,
            statusFile = statusFile.absolutePath,
            exitFile = exitFile.absolutePath,
        ).save(recordFile)

        val intent = Intent(context, GuestJvmService::class.java)
        GuestProtocol.putRun(intent, descriptor)
        applyNotificationOptions(intent, label, config.notification)

        try {
            startGuest(intent)
        } catch (e: Exception) {
            deleteTree(recordFile)
            throw JEngineException("could not start the guest service: ${e.message}", e)
        }

        val run = AndroidJarRun(
            context = context,
            onFinished = { finished -> if (current === finished) current = null },
            id = runId,
            jarPath = jar.absolutePath,
            label = label,
            log = logFile,
            statusFile = statusFile,
            exitFile = exitFile,
            recordFile = recordFile,
        )
        current = run
        return run
    }

    override suspend fun activeRun(): JarRun? = withContext(Dispatchers.IO) {
        current?.let { if (!it.isFinished) return@withContext it }
        current = null

        val records = layout.logsDir
            .listFiles { file -> file.isFile && file.name.endsWith(".run") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (records.isEmpty()) return@withContext null

        val probe = GuestProbe()
        val intent = Intent(context, GuestJvmService::class.java)
        // Flags 0: ask about a service that exists, never start one.
        val bound = runCatching { context.bindService(intent, probe, 0) }.getOrDefault(false)
        if (!bound) {
            deleteStaleRecords(records)
            return@withContext null
        }
        try {
            val binder = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.connected.await() }
            if (binder == null || !hostsGuest(binder)) {
                deleteStaleRecords(records)
                return@withContext null
            }
            // Newest first, and the first one that still parses: a record can be
            // left half-written by a process that died mid-save, and the run it
            // belonged to is not necessarily the one still serving.
            val matched = records.firstNotNullOfOrNull { file ->
                RunRecord.load(file)?.let { record -> file to record }
            }
            if (matched == null) {
                deleteStaleRecords(records)
                return@withContext null
            }
            val (recordFile, record) = matched
            val adopted = AndroidJarRun(
                context = context,
                onFinished = { finished -> if (current === finished) current = null },
                id = record.runId,
                jarPath = record.jarPath,
                label = record.label,
                log = File(record.logFile),
                statusFile = File(record.statusFile),
                exitFile = File(record.exitFile),
                recordFile = recordFile,
                autoCreate = false,
            )
            current = adopted
            adopted
        } finally {
            runCatching { context.unbindService(probe) }
        }
    }

    override suspend fun stop(): Boolean {
        val run = activeRun() ?: return false
        run.stop()
        return true
    }

    override suspend fun shutdown() {
        val run = current
        current = null
        if (run != null) {
            run.stop()
        } else {
            endForeignGuest()
        }
    }

    override fun close() {
        // Non-suspend: the stop is asked for and left to the guest process,
        // which is the thing that actually ends a run.
        val run = current
        current = null
        if (run != null) {
            run.requestStop()
        } else {
            endForeignGuest()
        }
    }

    /**
     * Resolves the runtime, installing it first when the request allows it.
     *
     * A missing runtime is the one failure a run can undo for itself: the
     * request says whether it may fetch one, and a first run on a fresh install
     * would otherwise always fail.
     */
    private suspend fun resolveForRun(ref: JreRef, installIfMissing: Boolean): ResolvedJre {
        return try {
            jres.resolve(ref)
        } catch (e: JreNotInstalledException) {
            val id = (ref as? JreRef.Managed)?.id ?: throw e
            if (!installIfMissing) throw e
            jres.installNow(id)
            jres.resolve(ref)
        }
    }

    /**
     * Ends a guest process this engine did not start, and waits for it to go.
     *
     * The process is the unit of reuse: a VM accumulates state that can be set
     * once, so a process that has already hosted a guest is not a clean place
     * for the next one. The probe binds *without* creating the service, so a
     * run that finds no guest pays nothing.
     */
    private suspend fun retireForeignGuest() {
        val probe = GuestProbe()
        val intent = Intent(context, GuestJvmService::class.java)
        if (!runCatching { context.bindService(intent, probe, 0) }.getOrDefault(false)) return
        try {
            val binder = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.connected.await() } ?: return
            if (!hostsGuest(binder)) return
            endForeignGuest()
            withTimeoutOrNull(GUEST_SHUTDOWN_TIMEOUT_MS) { probe.died.await() }
                ?: Log.w(TAG, "the previous guest process did not end in time")
        } finally {
            runCatching { context.unbindService(probe) }
        }
    }

    private fun endForeignGuest() {
        runCatching { context.startService(shutdownIntent()) }
            .onFailure { Log.w(TAG, "could not ask the guest process to end: ${it.message}") }
    }

    private fun shutdownIntent(): Intent =
        Intent(context, GuestJvmService::class.java).setAction(GuestProtocol.ACTION_SHUTDOWN)

    private fun deleteStaleRecords(records: List<File>) {
        records.forEach { deleteTree(it) }
    }

    /**
     * The defaults a run's options are merged over: the engine's own, and then
     * the request's on top, so the request wins where the two collide.
     */
    private fun mergeOptions(base: JvmOptions, requested: JvmOptions): JvmOptions = JvmOptions(
        heapMb = requested.heapMb ?: base.heapMb,
        initialHeapMb = requested.initialHeapMb ?: base.initialHeapMb,
        systemProperties = base.systemProperties + requested.systemProperties,
        extraArgs = base.extraArgs + requested.extraArgs,
        environment = base.environment + requested.environment,
    )

    /**
     * The VM arguments a run starts with: what this platform must supply for any
     * JAR to run at all, merged with the caller's options by key.
     *
     * The heap lines are in the platform defaults so that [mergeJvmArgs] can
     * drop them when the caller names a heap of their own — the platform's 16 MB
     * initial and 256 MB maximum are what a phone can spare for a JAR that may
     * only print.
     */
    private fun buildVmArgs(
        resolved: ResolvedJre,
        workDir: File,
        classPath: String,
        options: JvmOptions,
    ): List<String> {
        val home = resolved.home ?: throw JEngineException("the runtime has no home directory")
        val defaults = listOf(
            "-Djava.home=$home",
            "-Djava.class.path=$classPath",
            "-Djava.library.path=$home/lib",
            "-Djava.io.tmpdir=${context.cacheDir.absolutePath}",
            // `~` is the run's directory too, so everything a JAR writes for
            // itself lands where the user can reach it; the temp directory stays
            // private because scratch is not something a user goes looking for.
            "-Duser.home=${workDir.absolutePath}",
            "-Dfile.encoding=UTF-8",
            // The guest has no display, so AWT must not try to reach one even
            // for a JAR that never opens a window.
            "-Djava.awt.headless=true",
            // Java libraries branch on os.name, and several refuse to load their
            // native code when it says Android; the kernel underneath is Linux.
            "-Dos.name=Linux",
            "-Dos.version=Android-${Build.VERSION.RELEASE}",
            // POSIX_SPAWN needs jspawnhelper, which Android does not ship, so a
            // JAR that shells out cannot start a process without this.
            "-Djdk.lang.Process.launchMechanism=FORK",
            // HotSpot would otherwise size its thread pools from the host's
            // core count, which says nothing useful about a phone.
            "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors().coerceAtMost(4)}",
            "-Xms16M",
            "-Xmx256M",
            // Serial GC keeps the guest's footprint flat where a parallel
            // collector only adds threads and RSS, not throughput.
            "-XX:+UseSerialGC",
        )
        return mergeJvmArgs(defaults, options)
    }

    private fun applyNotificationOptions(intent: Intent, label: String, options: NotificationOptions) {
        intent.putExtra(GuestProtocol.EXTRA_NOTIFICATION_ICON, options.smallIconRes)
        intent.putExtra(GuestProtocol.EXTRA_NOTIFICATION_TITLE, String.format(options.titleFormat, label))
        intent.putExtra(GuestProtocol.EXTRA_NOTIFICATION_TEXT, options.text)
        intent.putExtra(GuestProtocol.EXTRA_NOTIFICATION_STOP, options.stopActionLabel)
        intent.putExtra(GuestJvmService.EXTRA_NOTIFICATION_ENABLED, options.enabled)
        intent.putExtra(GuestJvmService.EXTRA_CHANNEL_NAME, options.channelName)
        intent.putExtra(GuestJvmService.EXTRA_CHANNEL_DESCRIPTION, options.channelDescription)
    }

    private fun startGuest(intent: Intent) {
        // A service that may outlive the app must be started as a foreground
        // one from API 26 on; below that there is no such distinction.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Watches for a guest process without requiring one to exist. */
    private inner class GuestProbe : ServiceConnection, IBinder.DeathRecipient {
        val connected = CompletableDeferred<IBinder?>()
        val died = CompletableDeferred<Unit>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                died.complete(Unit)
                connected.complete(null)
                return
            }
            try {
                binder.linkToDeath(this, 0)
                connected.complete(binder)
            } catch (e: RemoteException) {
                died.complete(Unit)
                connected.complete(null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        override fun binderDied() {
            died.complete(Unit)
        }
    }

    private companion object {
        /** How long to wait for the probe to say whether a guest process exists. */
        const val PROBE_TIMEOUT_MS = 2_000L

        /** How long to wait for a stale guest process to go before running anyway. */
        const val GUEST_SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
