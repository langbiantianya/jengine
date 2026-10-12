// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What to run, on what, and with what.
 *
 * @property jarPath the JAR to run. Its manifest supplies the class path
 *   (`Class-Path` is honoured) and, unless [mainClass] says otherwise, the
 *   `Main-Class` to launch.
 * @property mainClass the class to run, instead of the manifest's `Main-Class`.
 * @property args arguments for the JAR's `main`, in order.
 * @property jre which runtime to run on, or null for the engine's default — see
 *   [JreRepository.defaultSpec].
 * @property jvm what to start the VM with.
 * @property workingDirectory the directory the run works in, created if needed.
 *   Defaults to a directory named after the JAR inside the engine's own run
 *   root, reused between runs so the data a JAR writes survives a restart. On
 *   Android that path is under the app's external files directory when there is
 *   one, so the files a JAR writes can also be reached over USB.
 * @property label what to call this run where a human sees it — the notification
 *   on Android, the log file's name. Defaults to the JAR's file name.
 * @property installJreIfMissing whether a run may fetch a runtime it needs. Off
 *   turns a missing runtime into a [JreNotInstalledException] instead.
 */
public data class JarRunRequest(
    public val jarPath: String,
    public val mainClass: String? = null,
    public val args: List<String> = emptyList(),
    public val jre: JreRef? = null,
    public val jvm: JvmOptions = JvmOptions(),
    public val workingDirectory: String? = null,
    public val label: String? = null,
    public val installJreIfMissing: Boolean = true,
)

/** Where a lifecycle stage of a run has got to. */
public enum class RunState {
    /** The runtime is being made ready and the guest is being started. */
    STARTING,

    /** The guest is up: either running `main`, or serving after it returned. */
    RUNNING,

    /** [JarRun.stop] has been asked for and the guest is being ended. */
    STOPPING,

    /** The run is over; [JarRun.result] answers with how. */
    FINISHED,
}

/** Which of the guest's streams a line came from, where the platform can tell. */
public enum class LogSource {
    STDOUT,
    STDERR,

    /**
     * The platform merges the guest's two streams into one, so the engine
     * cannot tell them apart.
     *
     * A run is defined by its log file, and both platforms write exactly one:
     * on Android the guest's stdout and stderr are redirected to that file by
     * file descriptor — which is also why output from a native library the JAR
     * loaded lands there — and a desktop run's stdout and stderr are appended
     * to the same file. Everything a run printed is therefore in one place and
     * in order, which is what makes [JarRun.output] and [JarRun.logFile]
     * meaningful across platforms; what is lost is which stream a line came
     * from.
     */
    UNKNOWN,
}

/**
 * One line of a run's output, as it was written.
 *
 * [seq] counts lines within the run from 0, so a caller that keeps a
 * subscription can tell what it has already seen after reconnecting.
 */
public data class JarLogLine(
    public val seq: Long,
    public val text: String,
    public val source: LogSource = LogSource.UNKNOWN,
)

/** How a run ended. */
public sealed interface JarRunResult {

    /**
     * The guest exited normally, with the JVM's exit status.
     *
     * A non-zero [exitCode] is the JAR's own decision — `System.exit(3)` — not
     * the engine failing; that is [Failed].
     */
    public data class Completed(public val exitCode: Int) : JarRunResult

    /**
     * The JAR's `main` returned and the guest is still up, because the
     * application left non-daemon threads behind — a web server, a scheduler.
     * `java -jar` would stay up here too, and so does the guest, until
     * [JarRun.stop] ends its process.
     *
     * Android only: on a desktop the runner waits for the process to exit, so a
     * JAR that behaves this way simply keeps running.
     */
    public data class StillRunning(public val detail: String? = null) : JarRunResult

    /** The caller stopped the run. */
    public data class Stopped(public val exitCode: Int? = null) : JarRunResult

    /**
     * The guest died without exiting: it aborted the VM, tripped an assertion,
     * corrupted its heap, or was killed.
     */
    public data class Crashed(
        public val exitCode: Int? = null,
        public val detail: String,
    ) : JarRunResult

    /** The engine could not get the run started or could not keep it going. */
    public data class Failed(
        public val message: String,
        public val cause: Throwable? = null,
    ) : JarRunResult
}

/**
 * One JAR running, or one that has finished.
 *
 * A run outlives the caller that started it in the ways the platform allows: on
 * Android the guest is a process of its own that keeps serving after `main`
 * returns and after the app's UI process is gone, so a run handle is a view onto
 * something that exists independently of it.
 */
public interface JarRun {

    /** Unique within the engine's lifetime and its home directory. */
    public val id: String

    public val jarPath: String

    /** What this run is called where a human sees it; see [JarRunRequest.label]. */
    public val label: String

    /** The file everything the guest writes is appended to, for tailing by hand. */
    public val logFile: String

    public val state: StateFlow<RunState>

    /** Whether the guest is up, which includes a run whose `main` has returned. */
    public val isRunning: Boolean

    /**
     * The guest's output, from the beginning of the run and then as it arrives.
     *
     * Cold: each collection replays the run's log from the start and follows it
     * until the run ends, so a caller that subscribes late — or subscribes
     * twice — sees everything either way.
     */
    public val lines: Flow<JarLogLine>

    /** The whole log as text, as far as it has got. */
    public suspend fun output(): String

    /** Suspends until the run finishes, then answers how it ended. */
    public suspend fun result(): JarRunResult

    /**
     * Ends the run and waits for it to be over.
     *
     * There is no way to ask arbitrary Java code to stop, so this ends the
     * process the guest VM lives in — a real VM running its own threads, whose
     * own shutdown path is a no-op on Android. Doing nothing when the run is
     * already finished is not an error; the result of the finished run is
     * returned either way.
     */
    public suspend fun stop(): JarRunResult
}
