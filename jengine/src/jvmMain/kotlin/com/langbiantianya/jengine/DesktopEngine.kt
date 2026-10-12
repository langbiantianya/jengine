// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import com.langbiantianya.jengine.internal.DesktopJarRun
import com.langbiantianya.jengine.internal.EngineLayout
import com.langbiantianya.jengine.internal.JarManifest
import com.langbiantianya.jengine.internal.mergeJvmArgs
import com.langbiantianya.jengine.internal.newRunId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The desktop engine: runs JARs on a runtime this library installed or the
 * caller supplies.
 *
 * A desktop has no guest process to adopt, because a JVM cannot be loaded into
 * a running process and unloaded again, so a run here is a `java` process the
 * engine started itself and nothing else can be found in its place. That makes
 * [activeRun] simply "the run I started and have not seen end".
 */
public fun jengine(config: DesktopEngineConfig = DesktopEngineConfig()): JEngine =
    DesktopJEngine(config)

internal class DesktopJEngine(
    private val config: DesktopEngineConfig,
) : JEngine {

    private val layout = EngineLayout(config.homeDir, config.homeDir)

    override val jres: JreRepository = DesktopJreRepository(config, layout)

    override val homeDir: String get() = config.homeDir.absolutePath

    /** Serialises the one-run-at-a-time rule and the active-run field. */
    private val mutex = Mutex()

    private var active: DesktopJarRun? = null

    override suspend fun run(request: JarRunRequest): JarRun {
        val jar = File(request.jarPath)
        if (!jar.isFile || !jar.canRead()) throw JarNotFoundException(request.jarPath)
        val mainClass = request.mainClass?.takeIf { it.isNotBlank() }
            ?: JarManifest.mainClassOf(jar)
            ?: throw MainClassNotFoundException(request.jarPath)

        return mutex.withLock {
            val current = active
            if (current != null && current.state.value != RunState.FINISHED) {
                throw RunAlreadyActiveException(current.id)
            }
            if (current != null) current.dispose()
            active = null

            // The home is created here rather than in the constructor, so that
            // building an engine — or a config — does not touch the disk.
            layout.ensureDirectories()

            // Installing here, before the run is registered, means a first run
            // waits for a download while it holds the engine's single-run slot —
            // which is correct, because that is the run in flight.
            val resolved = resolveRuntime(request)

            val label = request.label?.takeIf { it.isNotBlank() } ?: jar.name
            val workDir = request.workingDirectory?.let { File(it) } ?: layout.workDir(label)
            workDir.mkdirs()
            if (!workDir.isDirectory) {
                throw IOException("could not create the working directory $workDir")
            }

            val options = mergeOptions(config.defaultJvm, request.jvm)
            val run = DesktopJarRun(
                id = newRunId(),
                jarPath = jar.absolutePath,
                label = label,
                layout = layout,
                jre = resolved,
                classPath = JarManifest.classPathOf(jar),
                mainClass = mainClass,
                args = request.args,
                vmArgs = mergeJvmArgs(emptyList(), options),
                workDir = workDir.absolutePath,
                environment = options.environment,
            )
            active = run
            run
        }
    }

    override suspend fun activeRun(): JarRun? = mutex.withLock {
        active?.takeIf { it.state.value != RunState.FINISHED }
    }

    override suspend fun stop(): Boolean {
        val run = mutex.withLock { active?.takeIf { it.state.value != RunState.FINISHED } }
            ?: return false
        run.stop()
        mutex.withLock { if (active === run) active = null }
        return true
    }

    override suspend fun shutdown() {
        val run = mutex.withLock { active } ?: return
        run.stop()
        run.dispose()
        mutex.withLock { if (active === run) active = null }
    }

    override fun close() {
        // Best effort: the caller is a `finally` or a teardown that cannot
        // suspend, and failing to stop a run is not worth losing the teardown.
        runCatching { runBlocking { shutdown() } }
    }

    /**
     * Which runtime this run uses, installing the one it names when the request
     * allows it.
     *
     * The fallback order is the request's own choice, then the engine's
     * configured default, then the newest installed runtime. A request that
     * names a runtime which is not there is only an install when the request
     * asked for one; otherwise it is the caller's to fix.
     */
    private suspend fun resolveRuntime(request: JarRunRequest): ResolvedJre {
        val ref: JreRef = request.jre
            ?: config.defaultJreId?.let { JreRef.Managed(it) }
            ?: jres.defaultSpec()?.let { JreRef.Managed(it.id) }
            ?: throw JreNotInstalledException(specId = null)

        return try {
            jres.resolve(ref)
        } catch (missing: JreNotInstalledException) {
            if (!request.installJreIfMissing || missing.specId == null) throw missing
            jres.installNow(missing.specId)
            jres.resolve(ref)
        }
    }

    /**
     * The engine's defaults under the request's options: the request wins where
     * it says something, and the engine supplies what it does not. The maps are
     * unioned with the request last, so one `os.name` replaces the other rather
     * than joining it.
     */
    private fun mergeOptions(base: JvmOptions, request: JvmOptions): JvmOptions = JvmOptions(
        heapMb = request.heapMb ?: base.heapMb,
        initialHeapMb = request.initialHeapMb ?: base.initialHeapMb,
        systemProperties = base.systemProperties + request.systemProperties,
        extraArgs = base.extraArgs + request.extraArgs,
        environment = base.environment + request.environment,
    )
}
