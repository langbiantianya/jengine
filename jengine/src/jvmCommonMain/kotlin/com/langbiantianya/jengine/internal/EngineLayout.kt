// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File

/**
 * Where an engine keeps everything it owns.
 *
 * Two roots, because on Android they belong in different places and on a
 * desktop they are the same directory: the runtimes must live in the app's
 * private storage — Android 10 forbids executing binaries unpacked into shared
 * storage, and the guest's libraries are loaded by absolute path anyway — while
 * the directories a JAR works in are more useful on shared storage, where the
 * files it writes can also be reached over USB.
 *
 * The layout is flat and named after ids rather than nested by type, so that a
 * directory listing says what a run was and when it happened:
 *
 * ```
 * <runtimeRoot>/jre/jre21/                       the unpacked runtime
 * <runtimeRoot>/jre/jre21.archive                the download, deleted once unpacked
 * <runtimeRoot>/jre/.staging-jre21/              the unpack while it is happening
 * <runtimeRoot>/jre/.install-jre21.lock          the cross-process install lock
 * <workRoot>/logs/<runId>.log                    everything the guest printed
 * <workRoot>/logs/<runId>.status                 how the run ended, once it has
 * <workRoot>/logs/<runId>.log.exit               the exit status, when the guest chose it
 * <workRoot>/logs/<runId>.run                    what a later process needs to adopt the run
 * <workRoot>/jars/<jar name>/                    the directory a JAR works in, reused
 * ```
 */
internal class EngineLayout(
    /** App-private storage: runtimes are unpacked and executed here. */
    val runtimeRoot: File,
    /** Where a run works, and where its log is written. */
    val workRoot: File,
) {

    val jreRoot: File get() = File(runtimeRoot, "jre")

    val logsDir: File get() = File(workRoot, "logs")

    val jarsDir: File get() = File(workRoot, "jars")

    fun jreHome(specId: String): File = File(jreRoot, safeFileName(specId, fallback = "jre"))

    /** The downloaded archive of a runtime, kept only until it is unpacked. */
    fun runtimeArchive(specId: String): File = File(jreRoot, "${safeFileName(specId, "jre")}.archive")

    /** Where an unpack lands before it replaces the runtime home. */
    fun staging(specId: String): File = File(jreRoot, ".staging-${safeFileName(specId, "jre")}")

    /** The lock two callers installing the same runtime serialise on. */
    fun installLock(specId: String): File = File(jreRoot, ".install-${safeFileName(specId, "jre")}.lock")

    fun logFile(runId: String): File = File(logsDir, "$runId.log")

    /** How a run ended, written by whoever watched it end. */
    fun statusFile(runId: String): File = File(logsDir, "$runId.status")

    /**
     * The exit status a guest recorded on its way out.
     *
     * Only a VM that ends the process itself — `System.exit` — writes this, and
     * it is written beside the log the guest was redirected into: `<runId>.log.exit`.
     * A run that simply returns from `main` reports through [statusFile] instead.
     */
    fun exitFileFor(logFile: File): File = File(logFile.absolutePath + ".exit")

    /** What a process that did not start the run needs to take it over. */
    fun descriptorFile(runId: String): File = File(logsDir, "$runId.run")

    /**
     * The directory a JAR named [label] works in, reused between runs.
     *
     * A JAR that writes relative files — a server its world, a framework a file
     * it generates at startup — needs somewhere writable that is still there
     * the next time, because a server's data has to survive being restarted.
     */
    fun workDir(label: String): File = File(jarsDir, safeJarStem(label))

    fun ensureDirectories() {
        jreRoot.mkdirs()
        logsDir.mkdirs()
        jarsDir.mkdirs()
    }
}
