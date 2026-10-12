// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "jengine"

/**
 * The guest process: it holds one VM for as long as it lives.
 *
 * The VM is not hosted here because the app process might not be a 64-bit one —
 * ART picks the app's primary ABI and a 32-bit process cannot map a 64-bit
 * `libjvm.so` at all — and because a JAR that calls `System.exit`, trips an
 * assertion or corrupts its heap should take down only this process. What the
 * service does is narrow on purpose: it needs no runtime of its own, installs
 * nothing, reads no JAR. The front end has already resolved the runtime, read
 * the manifest and merged the arguments, and this end only hosts a VM.
 *
 * The process is the unit of reuse because a JVM accumulates state that can be
 * set once and because OpenJDK's shutdown path is a no-op on the Android port:
 * ending the process is also the only honest way to stop a guest.
 */
internal class GuestJvmService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jengine-guest").apply { isDaemon = true }
    }

    /**
     * Whether this process holds a guest VM. Set as the VM is asked for and
     * never cleared: OpenJDK builds one VM per process and cannot undo it, so a
     * process that has hosted a guest hosts exactly that guest for the rest of
     * its life. This is what Stop acts on and what a restarted front end asks
     * about, whether or not `main` has returned.
     */
    private val guestHosted = AtomicBoolean(false)

    private val running = AtomicBoolean(false)

    /** The run's status file, remembered so a stop request can write to it. */
    @Volatile private var statusFile: File? = null

    /** The blocking `executeRun` task, so a stop can interrupt it. */
    @Volatile private var runFuture: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        NativeBridge.load()
        ensureChannel(name = null, description = null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (GuestProtocol.actionOf(intent)) {
            GuestProtocol.ACTION_RUN -> handleRun(intent ?: return START_NOT_STICKY, startId)
            GuestProtocol.ACTION_STOP -> handleStop()
            GuestProtocol.ACTION_SHUTDOWN -> endProcess()
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * One call: whether this process holds a guest.
     *
     * The guest cannot outlive the process it lives in, and a JAR is free to end
     * that process with `System.exit`, so the front end learns a run ended by
     * observing the death of this process rather than by a return value. This
     * transaction is how a front end that has been restarted — and so has
     * forgotten the run it started — takes charge of a guest still serving.
     */
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == GuestProtocol.TRANSACTION_HOSTS_GUEST) {
                reply?.writeInt(if (guestHosted.get()) 1 else 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun handleRun(intent: Intent, startId: Int) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "a run is already in flight in this process")
            stopSelfResult(startId)
            return
        }

        val mainClass = intent.getStringExtra(GuestProtocol.EXTRA_MAIN_CLASS).orEmpty()
        val args = intent.getStringArrayExtra(GuestProtocol.EXTRA_ARGS) ?: emptyArray()
        val workDir = intent.getStringExtra(GuestProtocol.EXTRA_WORK_DIR).orEmpty()
        val logPath = intent.getStringExtra(GuestProtocol.EXTRA_LOG).orEmpty()
        val statusPath = intent.getStringExtra(GuestProtocol.EXTRA_STATUS_FILE).orEmpty()
        val jreHome = intent.getStringExtra(GuestProtocol.EXTRA_JRE_HOME).orEmpty()
        val vmLibrary = intent.getStringExtra(GuestProtocol.EXTRA_VM_LIBRARY).orEmpty()
        val vmArgs = intent.getStringArrayExtra(GuestProtocol.EXTRA_VM_ARGS) ?: emptyArray()
        val environment = intent.getStringArrayExtra(GuestProtocol.EXTRA_ENVIRONMENT) ?: emptyArray()
        val label = intent.getStringExtra(GuestProtocol.EXTRA_LABEL).orEmpty()

        statusFile = File(statusPath)
        val guestRun = GuestProcessRun(
            mainClass = mainClass,
            args = args,
            workDir = workDir,
            logPath = logPath,
            jreHome = jreHome,
            vmLibrary = vmLibrary,
            vmArgs = vmArgs,
            environment = environment,
            label = label,
            notification = notificationOf(intent, label),
        )

        runFuture = worker.submit(Runnable { executeRun(guestRun, startId) })
    }

    /**
     * Hosts the VM, then reports how it ended.
     *
     * The service is put in the foreground first, before anything can take long:
     * a JAR that serves keeps serving because this process is exempt from being
     * frozen, and its listening sockets would otherwise stay open while nothing
     * accepted on them.
     */
    private fun executeRun(run: GuestProcessRun, startId: Int) {
        var keepAlive = false
        try {
            startForegroundNotification(run.notification, run.label)
            // Said in the console as well as in logcat: a JAR that writes files
            // is the reason the run has a directory of its own, and the user has
            // to know where those files went.
            appendLine(File(run.logPath), "=== working directory: ${File(run.workDir).absolutePath} ===")

            // From here on this process holds a VM.
            guestHosted.set(true)

            val outcome = NativeBridge.nativeBootstrap(
                jvmPath = run.vmLibrary,
                javaHome = run.jreHome,
                nativeLibDir = applicationInfo.nativeLibraryDir,
                vmArgs = run.vmArgs,
                appArgs = run.args,
                mainClass = run.mainClass,
                workDir = run.workDir,
                outPath = run.logPath,
                environment = run.environment,
            )
            when {
                outcome == GuestProtocol.STATUS_OK -> {
                    writeOutcome(GuestProtocol.STATUS_OK, "")
                    endProcess()
                }

                outcome == STILL_RUNNING_RESULT -> {
                    // `main` returned but left non-daemon threads behind. The
                    // guest is still serving, so it stays foreground and stays
                    // started — the app may be swiped away next.
                    writeOutcome(GuestProtocol.STATUS_STILL_RUNNING, "")
                    keepAlive = true
                }

                outcome.startsWith(GuestProtocol.STATUS_ERROR) -> {
                    writeOutcome(GuestProtocol.STATUS_ERROR, outcome.removePrefix(GuestProtocol.STATUS_ERROR).trim())
                    endProcess()
                }

                // Any other return value is an error the bridge did not name.
                else -> {
                    Log.e(TAG, "the bridge returned an unrecognised outcome: $outcome")
                    writeOutcome(GuestProtocol.STATUS_ERROR, outcome)
                    endProcess()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "the run aborted", e)
            writeOutcome(GuestProtocol.STATUS_ERROR, e.message.orEmpty())
            endProcess()
        } finally {
            running.set(false)
            if (!keepAlive) {
                stopForegroundCompat()
                stopSelfResult(startId)
            }
        }
    }

    /**
     * Abandons the current run.
     *
     * There is no way to ask arbitrary Java code to stop, and OpenJDK's own
     * shutdown path is a no-op on the Android port, so the only honest stop is
     * to end the process the VM lives in. That is true for as long as the guest
     * exists, not only while `main` is on the stack: a web application's `main`
     * returns as soon as its server is listening, and what serves afterwards is
     * the guest.
     *
     * The outcome goes to the status file first: the front end should hear why
     * the run ended, and it cannot once the process is gone.
     */
    private fun handleStop() {
        runFuture?.cancel(true)
        if (guestHosted.get()) {
            writeOutcome(GuestProtocol.STATUS_STOPPED, "")
        } else {
            Log.i(TAG, "stop requested with no guest in this process")
        }
        endProcess()
    }

    private fun writeOutcome(status: String, message: String) {
        val file = statusFile
        if (file == null) {
            Log.w(TAG, "no status file is known; the outcome '$status' cannot be reported")
            return
        }
        GuestOutcome.write(file, GuestOutcome(status, message))
    }

    /**
     * Ends the process hosting the guest VM.
     *
     * Delayed so that anything already handed to the front end — the status
     * file, a binder reply — is on its way before the sender is gone.
     */
    private fun endProcess() {
        Log.i(TAG, "ending the guest process")
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, PROCESS_END_GRACE_MS)
    }

    private fun stopForegroundCompat() {
        // The constant that names the removal arrived in API 24; before it the
        // boolean meant the same thing and `true` is remove rather than keep.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundNotification(options: NotificationOptions, label: String) {
        ensureChannel(options.extrasChannelName, options.extrasChannelDescription)
        val notification = buildNotification(options, label)
        // The three-argument form arrived in API 29, and the type with it: a
        // foreground service is started by type, and this one has to declare the
        // same type its manifest does or the platform refuses it. The
        // `specialUse` constant itself is newer than this branch's API level,
        // but it is a compile-time int that is inlined here, and the manifest
        // this library contributes declares the same value — which is what the
        // platform actually compares it against.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(options: NotificationOptions, label: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(options.icon)
            .setOngoing(true)
            .setShowWhen(false)

        if (options.enabled) {
            builder.setContentTitle(options.title)
                .setContentText(options.text)
            launchIntent()?.let { builder.setContentIntent(it) }
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, GuestJvmService::class.java).setAction(GuestProtocol.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, options.stopLabel, stop).build())
        } else {
            // Android requires the notification itself; "disabled" can only mean
            // the plainest one that still names the run.
            builder.setContentTitle(label)
        }
        return builder.build()
    }

    /**
     * The intent that opens the app that owns the guest, so tapping the
     * notification returns to it.
     *
     * A library cannot know which Activity is the app's main one, so this asks
     * the platform for the launcher the way the home screen would.
     */
    private fun launchIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(name: String?, description: String?) {
        // Before API 26 there are no channels: a notification is identified by
        // its ID alone and there is nowhere to read a name from.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            name ?: DEFAULT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            this.description = description ?: DEFAULT_CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jengine.guest"
        private const val NOTIFICATION_ID = 1

        private const val DEFAULT_CHANNEL_NAME = "Java runs"
        private const val DEFAULT_CHANNEL_DESCRIPTION = "A Java program is running in the background"

        /** `OK:STILL_RUNNING` is the one outcome the bridge spells with a colon. */
        private const val STILL_RUNNING_RESULT = "OK:STILL_RUNNING"

        /** Delay between announcing an outcome and killing the process. */
        private const val PROCESS_END_GRACE_MS = 300L

        /**
         * Notification extras the engine adds to a run intent. They live here
         * rather than in [GuestProtocol] because only this service reads them;
         * the protocol is shared with the front end, these are not.
         */
        internal const val EXTRA_NOTIFICATION_ENABLED = "notificationEnabled"
        internal const val EXTRA_CHANNEL_NAME = "notificationChannelName"
        internal const val EXTRA_CHANNEL_DESCRIPTION = "notificationChannelDescription"
    }
}

/** The run half of an intent, unpacked once so the worker reads no extras. */
private class GuestProcessRun(
    val mainClass: String,
    val args: Array<String>,
    val workDir: String,
    val logPath: String,
    val jreHome: String,
    val vmLibrary: String,
    val vmArgs: Array<String>,
    val environment: Array<String>,
    val label: String,
    val notification: NotificationOptions,
)

/** The notification choices for one run, as they crossed the intent boundary. */
private class NotificationOptions(
    val enabled: Boolean,
    val icon: Int,
    val title: String,
    val text: String,
    val stopLabel: String,
    val extrasChannelName: String?,
    val extrasChannelDescription: String?,
)

private fun notificationOf(intent: Intent, label: String): NotificationOptions = NotificationOptions(
    enabled = intent.getBooleanExtra(GuestJvmService.EXTRA_NOTIFICATION_ENABLED, true),
    icon = intent.getIntExtra(
        GuestProtocol.EXTRA_NOTIFICATION_ICON,
        android.R.drawable.stat_sys_download,
    ),
    title = intent.getStringExtra(GuestProtocol.EXTRA_NOTIFICATION_TITLE) ?: "$label is running",
    text = intent.getStringExtra(GuestProtocol.EXTRA_NOTIFICATION_TEXT) ?: "Tap to return to the app",
    stopLabel = intent.getStringExtra(GuestProtocol.EXTRA_NOTIFICATION_STOP) ?: "Stop",
    extrasChannelName = intent.getStringExtra(GuestJvmService.EXTRA_CHANNEL_NAME),
    extrasChannelDescription = intent.getStringExtra(GuestJvmService.EXTRA_CHANNEL_DESCRIPTION),
)

/**
 * Asks a bound guest process whether it holds a guest VM.
 *
 * Shared by the front end's poll loop and its adoption probe, which ask the same
 * question of the same binder. A binder whose process has gone throws rather
 * than answers, and "gone" is not "holding a guest".
 */
internal fun hostsGuest(binder: IBinder): Boolean {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        binder.transact(GuestProtocol.TRANSACTION_HOSTS_GUEST, data, reply, 0)
        reply.readInt() != 0
    } catch (e: android.os.RemoteException) {
        false
    } finally {
        data.recycle()
        reply.recycle()
    }
}
