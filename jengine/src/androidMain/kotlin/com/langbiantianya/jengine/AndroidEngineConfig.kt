// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import java.io.File

/**
 * What an Android engine is created with.
 *
 * Unlike [JarRunRequest], this holds no `Context`: a config that remembered one
 * would keep an Activity — or worse, a finished Activity — alive for as long as
 * the engine, so the factory takes the context and the config keeps only the
 * decisions. The two directories are nullable so that the defaults (which need
 * a context) can be filled in at creation time.
 *
 * @property homeDir the app-private root the engine owns: runtimes are
 *   unpacked and executed there, which Android 10 requires (binaries in shared
 *   storage cannot be executed). Defaults to `<filesDir>/jengine`.
 * @property workRoot where run directories and logs live. Defaults to
 *   `<external files dir>/jengine`, falling back to the internal one when there
 *   is no external storage — a JAR's files can then be reached over USB.
 * @property defaultJvm the VM options a run starts from; a request's own
 *   options are merged over these, so the request wins where they collide.
 * @property defaultJreId the runtime a run reaches for when it names none. Must
 *   be an id the device can actually run; see `JreRepository.defaultSpec`.
 * @property notification how the foreground-service notification looks. Android
 *   requires that notification for a service that outlives the app, so it is
 *   always posted; these are the choices about it that a caller has.
 */
public class AndroidEngineConfig(
    public val homeDir: File? = null,
    public val workRoot: File? = null,
    public val defaultJvm: JvmOptions = JvmOptions(),
    public val defaultJreId: String = AndroidJreRepository.DEFAULT_SPEC_ID,
    public val notification: NotificationOptions = NotificationOptions(),
)

/**
 * The foreground-service notification an Android run posts.
 *
 * A stateful difference from the desktop, where the same machinery exists but no
 * notification does: Android freezes a merely-backgrounded process, and a JAR
 * that serves would then have open sockets nothing accepts on. The service must
 * be foreground for the whole run, and a foreground service must post a
 * notification — so [enabled] cannot remove it, only ask for the plainest one
 * the platform allows.
 *
 * @property enabled whether to use the words below or a bare notification. The
 *   notification itself is not optional on Android; see the class comment.
 * @property smallIconRes the icon drawn in the status bar.
 * @property titleFormat passed to `String.format` with the run's label.
 * @property text the notification's second line.
 * @property stopActionLabel the label of the action that ends the run.
 * @property channelName the user-visible name of the notification channel.
 * @property channelDescription the channel's description in system settings.
 */
public data class NotificationOptions(
    public val enabled: Boolean = true,
    public val smallIconRes: Int = android.R.drawable.stat_sys_download,
    public val titleFormat: String = "%s is running",
    public val text: String = "Tap to return to the app",
    public val stopActionLabel: String = "Stop",
    public val channelName: String = "Java runs",
    public val channelDescription: String = "A Java program is running in the background",
)
