// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

/**
 * Runs JARs, and fetches the runtimes they run on.
 *
 * An engine is scoped to a home directory it owns: the runtimes it installs,
 * the log and status files of its runs, and the working directory of each JAR.
 * It hosts one run at a time — on Android because a process holds one guest VM
 * and a VM accumulates state that can be set once, on a desktop so that the two
 * platforms behave the same — so a second [run] while one is active throws
 * [RunAlreadyActiveException]. Two engines with different home directories are
 * independent.
 *
 * Create one with the platform's factory:
 * `com.langbiantianya.jengine.jengine(context, config)` on Android,
 * `com.langbiantianya.jengine.jengine(config)` on a desktop JVM.
 */
public interface JEngine : AutoCloseable {

    /** The runtimes this engine can fetch, and the ones it has. */
    public val jres: JreRepository

    /** The directory the engine owns: runtimes, logs, and run directories. */
    public val homeDir: String

    /**
     * Starts a run and returns a handle to it as soon as the guest is being
     * started, so output can be collected from the first line.
     *
     * Throws [RunAlreadyActiveException] if a run is already active,
     * [JarNotFoundException] if the JAR is not there, [MainClassNotFoundException]
     * if no main class can be determined, and [JreNotInstalledException] if the
     * runtime is missing and [JarRunRequest.installJreIfMissing] is off. The
     * runtime itself is installed before this returns when the request allows
     * it, so a first run waits for a download; watch [JreRepository.install]
     * separately to report that progress.
     */
    public suspend fun run(request: JarRunRequest): JarRun

    /**
     * The run in flight, if any.
     *
     * On Android this also adopts a guest the engine did not itself start in
     * this process: the guest outlives the app's UI, so reopening an app on a
     * JAR that is still serving finds it here rather than offering nothing to
     * stop.
     */
    public suspend fun activeRun(): JarRun?

    /** Ends the active run, if there is one. True when something was stopped. */
    public suspend fun stop(): Boolean

    /** Ends the active run and releases this engine's resources. */
    public suspend fun shutdown()

    /**
     * Best-effort [shutdown] for a caller that cannot wait — a `finally` block,
     * an `Activity.onDestroy`. Prefer [shutdown] where suspending is possible.
     */
    override fun close()
}
