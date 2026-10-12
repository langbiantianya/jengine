// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import android.content.Intent
import java.io.File
import java.util.Properties

/**
 * What the engine's front end and its guest process say to each other.
 *
 * The two are separate processes, so nothing is shared: a run is handed over as
 * an intent, and its outcome comes back as a file — a status file the service
 * writes as it finishes, and an exit file the VM writes as it is ending. There
 * is deliberately no broadcast and no cross-process callback: a library cannot
 * know which app is holding it, and a result file outlives the process that
 * wrote it, which is exactly what a run needs.
 */
internal object GuestProtocol {

    const val ACTION_RUN = "com.langbiantianya.jengine.action.RUN"
    const val ACTION_STOP = "com.langbiantianya.jengine.action.STOP"

    /**
     * Ends the guest process whether or not a run is in flight. The process is
     * the unit of reuse: a JVM accumulates state that can be set once, so this
     * is what the front end sends before starting the next run.
     */
    const val ACTION_SHUTDOWN = "com.langbiantianya.jengine.action.SHUTDOWN"

    const val EXTRA_RUN_ID = "runId"
    const val EXTRA_JAR = "jar"
    const val EXTRA_MAIN_CLASS = "mainClass"
    const val EXTRA_CLASS_PATH = "classPath"
    const val EXTRA_ARGS = "args"
    const val EXTRA_WORK_DIR = "workDir"
    const val EXTRA_LOG = "log"
    const val EXTRA_STATUS_FILE = "statusFile"
    const val EXTRA_JRE_HOME = "jreHome"
    const val EXTRA_VM_LIBRARY = "vmLibrary"
    const val EXTRA_VM_ARGS = "vmArgs"
    const val EXTRA_ENVIRONMENT = "environment"
    const val EXTRA_LABEL = "label"
    const val EXTRA_NOTIFICATION_TITLE = "notificationTitle"
    const val EXTRA_NOTIFICATION_TEXT = "notificationText"
    const val EXTRA_NOTIFICATION_STOP = "notificationStop"
    const val EXTRA_NOTIFICATION_ICON = "notificationIcon"

    /**
     * Binder transaction asking the guest process whether it holds a guest VM
     * at all, answered with an int that is non-zero when it does.
     *
     * The VM outlives `main` and outlives a UI process Android has killed, so
     * "is a JAR running?" is a question about the service process rather than
     * about anything the UI remembers having started. Numbered explicitly
     * because the service and its callers are compiled separately.
     */
    const val TRANSACTION_HOSTS_GUEST = 1

    /** The run finished and the guest exited by itself. */
    const val STATUS_OK = "OK"

    /**
     * The JAR's `main` returned and left non-daemon threads behind. The guest
     * is still serving, and stays up until it is stopped.
     */
    const val STATUS_STILL_RUNNING = "STILL_RUNNING"

    /** The run could not be started, or the guest failed. The message follows. */
    const val STATUS_ERROR = "ERROR"

    /** The caller stopped the run. */
    const val STATUS_STOPPED = "STOPPED"

    /** Reads back an action, for the service's `when`. */
    fun actionOf(intent: Intent?): String? = intent?.action

    fun putRun(intent: Intent, descriptor: GuestRun): Intent = intent.apply {
        action = ACTION_RUN
        putExtra(EXTRA_RUN_ID, descriptor.runId)
        putExtra(EXTRA_JAR, descriptor.jarPath)
        putExtra(EXTRA_MAIN_CLASS, descriptor.mainClass)
        putExtra(EXTRA_CLASS_PATH, descriptor.classPath)
        putExtra(EXTRA_ARGS, descriptor.args.toTypedArray())
        putExtra(EXTRA_WORK_DIR, descriptor.workDir)
        putExtra(EXTRA_LOG, descriptor.logFile)
        putExtra(EXTRA_STATUS_FILE, descriptor.statusFile)
        putExtra(EXTRA_JRE_HOME, descriptor.jreHome)
        putExtra(EXTRA_VM_LIBRARY, descriptor.vmLibrary)
        putExtra(EXTRA_VM_ARGS, descriptor.vmArgs.toTypedArray())
        putExtra(EXTRA_ENVIRONMENT, descriptor.environment.toTypedArray())
        putExtra(EXTRA_LABEL, descriptor.label)
    }
}

/**
 * Everything the guest process needs to run one JAR, as it crosses the intent
 * boundary.
 *
 * Assembled by the front end, which is where the runtime is resolved, the
 * manifest is read and the arguments are merged; the service is then only
 * about hosting a VM.
 */
internal data class GuestRun(
    val runId: String,
    val label: String,
    val jarPath: String,
    val mainClass: String,
    val classPath: String,
    val args: List<String>,
    val workDir: String,
    val logFile: String,
    val statusFile: String,
    val jreHome: String,
    val vmLibrary: String,
    val vmArgs: List<String>,
    val environment: List<String>,
)

/**
 * The outcome of a run, as the status file holds it: a status word on the first
 * line, and whatever the writer wants to say about it after that.
 */
internal data class GuestOutcome(
    val status: String,
    val message: String,
) {
    val isError: Boolean get() = status == GuestProtocol.STATUS_ERROR
    val isStillRunning: Boolean get() = status == GuestProtocol.STATUS_STILL_RUNNING
    val isStopped: Boolean get() = status == GuestProtocol.STATUS_STOPPED

    fun encode(): String = if (message.isEmpty()) status else "$status\n$message"

    companion object {
        /**
         * Reads a status file, or null while it does not hold a complete
         * outcome.
         *
         * A file that exists but is empty is a run that is still writing it —
         * which is why it is never written in place: the service writes it to a
         * temporary file and renames it over this one, and a reader that finds
         * the file finds all of it.
         */
        fun read(file: File): GuestOutcome? {
            val text = file.readTextOrNull() ?: return null
            if (text.isBlank()) return null
            val lines = text.trimEnd('\n').split('\n')
            val status = lines.first().trim()
            if (status.isEmpty()) return null
            return GuestOutcome(status, lines.drop(1).joinToString("\n"))
        }

        fun write(file: File, outcome: GuestOutcome) {
            writeTextAtomically(file, outcome.encode())
        }
    }
}

/**
 * What a later process needs to take charge of a run it did not start.
 *
 * The app's own process can be ended while a guest keeps serving — Android may
 * kill a backgrounded UI, and the user can swipe the app away — so "is a JAR
 * running?" is a question about the guest process, and the answer has to be on
 * disk. Written before the service is started, removed once the run ends.
 */
internal data class RunRecord(
    val runId: String,
    val label: String,
    val jarPath: String,
    val logFile: String,
    val statusFile: String,
    val exitFile: String,
) {
    fun save(file: File) {
        val properties = Properties().apply {
            setProperty("runId", runId)
            setProperty("label", label)
            setProperty("jarPath", jarPath)
            setProperty("logFile", logFile)
            setProperty("statusFile", statusFile)
            setProperty("exitFile", exitFile)
        }
        file.parentFile?.mkdirs()
        // Written through a temporary file as well: this is read by a process
        // that may start at any moment, after the app process was killed
        // mid-write.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, null) }
        deleteTree(file)
        if (!temporary.renameTo(file)) {
            file.outputStream().use { properties.store(it, null) }
            deleteTree(temporary)
        }
    }

    fun delete(file: File) = deleteTree(file)

    companion object {
        fun load(file: File): RunRecord? {
            if (!file.isFile) return null
            return runCatching {
                val properties = Properties()
                file.inputStream().use { properties.load(it) }
                val runId = properties.getProperty("runId") ?: return null
                return RunRecord(
                    runId = runId,
                    label = properties.getProperty("label").orEmpty(),
                    jarPath = properties.getProperty("jarPath").orEmpty(),
                    logFile = properties.getProperty("logFile").orEmpty(),
                    statusFile = properties.getProperty("statusFile").orEmpty(),
                    exitFile = properties.getProperty("exitFile").orEmpty(),
                )
            }.getOrNull()
        }
    }
}
