// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import kotlinx.coroutines.flow.Flow

/**
 * A runtime the engine can fetch, on this platform, for this device.
 *
 * The same ids name the same Java version on both platforms — `jre21` is a
 * Temurin 21 JRE on a desktop and the OpenJDK 21 port on a phone — so a run
 * request that names one reads the same wherever it is written.
 */
public data class JreSpec(
    /** Stable id, also the directory name a runtime is unpacked into. */
    public val id: String,
    public val majorVersion: Int,
    /** For a chooser: what to call it, and where it comes from. */
    public val displayName: String,
    /**
     * ABIs the published archive carries layers for. Empty where the archive is
     * not a per-ABI one, which is the case on a desktop.
     */
    public val abis: List<String> = emptyList(),
    /** Size of the download when the source can say, in bytes. */
    public val downloadSizeBytes: Long? = null,
)

/** A [JreSpec] that is unpacked and ready to run a JAR. */
public data class InstalledJre(
    public val spec: JreSpec,
    /** Absolute path of the runtime's home directory. */
    public val home: String,
    public val installedAtEpochMs: Long,
)

/**
 * Which runtime a run should use.
 *
 * A runtime the engine installed itself is [Managed]; one the caller already
 * has on disk is [Home] (a JRE or JDK directory) or, for a caller that only
 * knows the launcher it wants, [JavaExecutable].
 */
public sealed interface JreRef {
    /** A runtime [JreRepository] installed under this id, such as `jre21`. */
    public data class Managed(public val id: String) : JreRef

    /**
     * A runtime directory the caller supplies: `bin/java` on a desktop,
     * `lib/server/libjvm.so` on Android.
     */
    public data class Home(public val path: String) : JreRef

    /**
     * A `java` launcher the caller supplies. Desktop only — on Android the
     * runtime is loaded as a library rather than executed as a program, and
     * Android 10 forbids running an executable unpacked into app storage.
     */
    public data class JavaExecutable(public val path: String) : JreRef

    public companion object {
        public fun of(id: String): JreRef = Managed(id)
    }
}

/**
 * A [JreRef] checked against the disk, as the run itself will see it.
 *
 * [home] and [javaExecutable] are what the platform's launcher needs; the one
 * it does not use is null. Resolving is what turns "a JRE the caller pointed
 * at" into either a runnable runtime or an error naming what was missing.
 */
public data class ResolvedJre(
    public val ref: JreRef,
    public val home: String?,
    public val majorVersion: Int?,
    /** Desktop: the launcher to run. */
    public val javaExecutable: String? = null,
    /** Android: the VM library to load. */
    public val vmLibrary: String? = null,
    /** Set when the runtime was installed by the engine. */
    public val spec: JreSpec? = null,
)

/** Progress and outcome of a runtime install. */
public sealed interface JreInstallEvent {
    /** The install is starting; on a first install this precedes the transfer. */
    public data class Started(public val spec: JreSpec) : JreInstallEvent

    public data class Progress(
        public val bytesRead: Long,
        public val totalBytes: Long?,
    ) : JreInstallEvent {
        /** 0..1, or null while the server has not said how large the file is. */
        public val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { bytesRead.toFloat() / it }
    }

    /** The download is done and the archive is being unpacked. */
    public data class Unpacking(public val spec: JreSpec) : JreInstallEvent

    public data class Installed(public val jre: InstalledJre) : JreInstallEvent

    /** The runtime was already unpacked; nothing was fetched. */
    public data class AlreadyInstalled(public val jre: InstalledJre) : JreInstallEvent

    public data class Failed(
        public val message: String,
        public val cause: Throwable? = null,
    ) : JreInstallEvent
}

/**
 * The runtimes this platform can fetch, and the ones it has.
 *
 * Implementations are safe to call from any coroutine: installs are
 * serialised behind a lock in the engine's home directory, because two callers
 * asking for the same runtime — a settings screen and a run — would otherwise
 * share one archive file and one staging directory and delete each other's
 * half-finished work.
 */
public interface JreRepository {

    /**
     * What can be installed here, newest last.
     *
     * On a desktop this asks the vendor's release API, so it needs the network
     * and is the only call in this interface that does.
     */
    public suspend fun available(): List<JreSpec>

    /** The installed runtimes, newest last. */
    public suspend fun installed(): List<InstalledJre>

    public suspend fun isInstalled(specId: String): Boolean

    public suspend fun installedJre(specId: String): InstalledJre?

    /**
     * The runtime a run falls back to when its request names none: the newest
     * installed one where the platform has no preference, a fixed default where
     * it does. Null when nothing is installed and nothing is preferred.
     */
    public suspend fun defaultSpec(): JreSpec?

    /**
     * Installs [specId], reporting progress as it goes, and emits exactly one
     * terminal event: [JreInstallEvent.Installed], [JreInstallEvent.Failed], or
     * [JreInstallEvent.AlreadyInstalled] when it was already there.
     *
     * [force] re-fetches and re-unpacks a runtime that is already installed.
     */
    public fun install(specId: String, force: Boolean = false): Flow<JreInstallEvent>

    /**
     * [install] collected to completion, for callers that want the runtime
     * rather than the progress. Throws [JEngineException] on failure.
     */
    public suspend fun installNow(specId: String, force: Boolean = false): InstalledJre

    /** Deletes an installed runtime and everything under it. */
    public suspend fun remove(specId: String)

    /**
     * Checks [ref] against the disk.
     *
     * For a [JreRef.Managed] runtime that is not installed this throws
     * [JreNotInstalledException] rather than installing: the decision to fetch
     * a few hundred megabytes belongs to the caller, or to a run that was told
     * it may ([JarRunRequest.installJreIfMissing]).
     */
    public suspend fun resolve(ref: JreRef): ResolvedJre
}

/** Base of everything this library reports as a failure to the caller. */
public open class JEngineException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A runtime a run asked for is not on disk.
 *
 * [specId] is null where no runtime was named at all — the engine had nothing
 * installed and no default to fall back on — and the message then says how to
 * get one rather than echoing an id the caller never wrote.
 */
public class JreNotInstalledException(
    public val specId: String?,
    message: String = if (specId == null) {
        "no JRE is installed and none was named; install one with jres.install(\"jre21\"), " +
            "or name one with JreRef.Home"
    } else {
        "JRE '$specId' is not installed; install it with jres.install(\"$specId\")"
    },
) : JEngineException(message)

/** A JAR a run named is missing or unreadable. */
public class JarNotFoundException(
    public val jarPath: String,
    cause: Throwable? = null,
) : JEngineException("no JAR at $jarPath", cause)

/** A JAR declares no main class and the request named none. */
public class MainClassNotFoundException(
    public val jarPath: String,
) : JEngineException(
    "$jarPath declares no Main-Class; name the class to run in the request",
)

/** The engine is already running a JAR, and hosts one run at a time. */
public class RunAlreadyActiveException(
    public val activeRunId: String,
) : JEngineException(
    "run $activeRunId is still active; stop it, or wait for it, before starting another",
)

/** A [JreRef] this platform cannot run a JAR from. */
public class UnsupportedJreRefException(
    public val ref: JreRef,
    platform: String,
) : JEngineException("$ref is not something $platform can run a JAR from", null)

/** A [JreRef.Home] or [JreRef.JavaExecutable] that is not a runtime. */
public class InvalidJreHomeException(
    public val path: String,
    detail: String,
) : JEngineException("$path is not a usable JRE: $detail")
