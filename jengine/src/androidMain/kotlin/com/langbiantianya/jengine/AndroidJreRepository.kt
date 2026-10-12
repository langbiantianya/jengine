// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import android.os.Build
import com.langbiantianya.jengine.internal.DownloadProgress
import com.langbiantianya.jengine.internal.EngineLayout
import com.langbiantianya.jengine.internal.HttpDownloader
import com.langbiantianya.jengine.internal.androidAbiLayer
import com.langbiantianya.jengine.internal.deleteTree
import com.langbiantianya.jengine.internal.preparePojavHome
import com.langbiantianya.jengine.internal.replaceDirectory
import com.langbiantianya.jengine.internal.unpackPojavRuntime
import com.langbiantianya.jengine.internal.vmLibraryIn
import com.langbiantianya.jengine.internal.withFileLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

/** The four ABIs MojoLauncher publishes layers for, named as Android names them. */
private val FOUR_ABIS = listOf("arm", "arm64", "x86", "x86_64")

/** Where the rolling release of the Android OpenJDK port lives. */
private const val POJAV_BASE_URL =
    "https://github.com/MojoLauncher/android-openjdk-build-17-25/releases/download/rolling"

/**
 * The guest runtimes this engine can fetch, and the ABIs each one carries.
 *
 * One `jre<major>-pojav.zip` holds a `universal.tar.xz` plus one
 * `bin-<abi>.tar.xz` per architecture, so a single download serves the whole
 * device family; a run unpacks the universal layer and then the layer for the
 * device's own ABI. The ids — `jre17`, `jre21`, `jre25` — are the same ones the
 * desktop uses, so a run request reads the same on either platform.
 */
internal enum class PojavRelease(
    val id: String,
    val majorVersion: Int,
    val assetName: String,
    /** ABI layers the published archive actually contains. */
    val abis: List<String>,
) {
    jre17("jre17", 17, "jre17-pojav.zip", FOUR_ABIS),
    jre21("jre21", 21, "jre21-pojav.zip", FOUR_ABIS),

    /**
     * Java 25 is not published for 32-bit x86, so offering it on such a device
     * would fail only at unpack time. Filtering it out of the catalog is the
     * honest answer: the device cannot run it, so it does not exist here.
     */
    jre25("jre25", 25, "jre25-pojav.zip", listOf("arm", "arm64", "x86_64")),
    ;

    val assetUrl: String get() = "$POJAV_BASE_URL/$assetName"

    fun toSpec(): JreSpec =
        JreSpec(id = id, majorVersion = majorVersion, displayName = "OpenJDK $majorVersion (Android)", abis = abis)

    companion object {
        fun byId(id: String?): PojavRelease? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The Android [JreRepository]: a fixed catalog of OpenJDK ports, fetched from
 * one rolling release and unpacked into the engine's private runtime root.
 *
 * There is no release API to ask — the archives are published by name and
 * version — so the catalog is hard-coded and [available] only filters it by the
 * ABI the device can actually run.
 */
internal class AndroidJreRepository(
    private val layout: EngineLayout,
    private val defaultId: String = DEFAULT_SPEC_ID,
    private val downloader: HttpDownloader = HttpDownloader(),
) : JreRepository {

    override suspend fun available(): List<JreSpec> {
        val abi = deviceAbiLayer()
        return PojavRelease.entries.filter { abi in it.abis }.map { it.toSpec() }
    }

    override suspend fun installed(): List<InstalledJre> =
        PojavRelease.entries.mapNotNull { installedJreOf(it) }

    override suspend fun isInstalled(specId: String): Boolean =
        vmLibraryIn(layout.jreHome(specId)) != null

    override suspend fun installedJre(specId: String): InstalledJre? =
        PojavRelease.byId(specId)?.let { installedJreOf(it) }

    /**
     * The runtime a run falls back to: the configured default where this device
     * can run it, otherwise the newest release it can. Null only on a device no
     * published runtime is built for.
     */
    override suspend fun defaultSpec(): JreSpec? {
        val abi = deviceAbiLayer()
        val usable = PojavRelease.entries.filter { abi in it.abis }
        val chosen = usable.firstOrNull { it.id == defaultId } ?: usable.lastOrNull()
        return chosen?.toSpec()
    }

    override fun install(specId: String, force: Boolean): Flow<JreInstallEvent> = flow {
        val release = PojavRelease.byId(specId)
        if (release == null) {
            emit(JreInstallEvent.Failed("unknown runtime '$specId'"))
            return@flow
        }
        if (!force) {
            installedJreOf(release)?.let {
                emit(JreInstallEvent.AlreadyInstalled(it))
                return@flow
            }
        }

        val archive = layout.runtimeArchive(specId)
        try {
            // The lock spans the download as well as the unpack: two callers
            // asking for the same runtime would otherwise fetch a few hundred
            // megabytes twice and share one staging directory.
            withFileLock(layout.installLock(specId)) {
                // Checked again: whoever held the lock may have installed it.
                if (!force) {
                    installedJreOf(release)?.let {
                        emit(JreInstallEvent.AlreadyInstalled(it))
                        return@withFileLock
                    }
                }

                emit(JreInstallEvent.Started(release.toSpec()))
                downloadWithProgress(release, archive)
                emit(JreInstallEvent.Unpacking(release.toSpec()))

                val abi = deviceAbiLayer()
                val staging = layout.staging(specId)
                deleteTree(staging)
                try {
                    unpackPojavRuntime(archive, abi, staging)
                    preparePojavHome(staging)
                    if (vmLibraryIn(staging) == null) {
                        deleteTree(staging)
                        emit(JreInstallEvent.Failed("${release.assetName} has no libjvm.so for $abi"))
                        return@withFileLock
                    }
                    replaceDirectory(staging, layout.jreHome(specId))
                } finally {
                    deleteTree(staging)
                }
                val installed = installedJreOf(release)
                if (installed == null) {
                    emit(JreInstallEvent.Failed("the runtime at ${layout.jreHome(specId)} is not usable"))
                } else {
                    emit(JreInstallEvent.Installed(installed))
                }
                return@withFileLock
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(JreInstallEvent.Failed(e.message ?: "installing '$specId' failed", e))
        } finally {
            deleteTree(archive)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun installNow(specId: String, force: Boolean): InstalledJre {
        var installed: InstalledJre? = null
        var failure: JreInstallEvent.Failed? = null
        install(specId, force).collect { event ->
            when (event) {
                is JreInstallEvent.Installed -> installed = event.jre
                is JreInstallEvent.AlreadyInstalled -> installed = event.jre
                is JreInstallEvent.Failed -> failure = event
                else -> Unit
            }
        }
        return installed ?: throw JEngineException(
            failure?.message ?: "installing '$specId' produced no runtime",
            failure?.cause,
        )
    }

    override suspend fun remove(specId: String) {
        deleteTree(layout.jreHome(specId))
    }

    override suspend fun resolve(ref: JreRef): ResolvedJre = when (ref) {
        is JreRef.Managed -> {
            val release = PojavRelease.byId(ref.id)
            val home = layout.jreHome(ref.id)
            val vmLibrary = vmLibraryIn(home)
            if (release == null || vmLibrary == null) throw JreNotInstalledException(ref.id)
            ResolvedJre(
                ref = ref,
                home = home.absolutePath,
                majorVersion = release.majorVersion,
                vmLibrary = vmLibrary.absolutePath,
                spec = release.toSpec(),
            )
        }

        is JreRef.Home -> {
            val home = File(ref.path)
            val vmLibrary = vmLibraryIn(home)
                ?: throw InvalidJreHomeException(ref.path, "no libjvm.so under lib/")
            ResolvedJre(
                ref = ref,
                home = home.absolutePath,
                majorVersion = null,
                vmLibrary = vmLibrary.absolutePath,
            )
        }

        // The runtime is loaded as a library, not run as a program, and Android
        // 10 forbids executing a binary unpacked into app storage anyway.
        is JreRef.JavaExecutable -> throw UnsupportedJreRefException(ref, "Android")
    }

    private fun installedJreOf(release: PojavRelease): InstalledJre? {
        val home = layout.jreHome(release.id)
        if (vmLibraryIn(home) == null) return null
        return InstalledJre(
            spec = release.toSpec(),
            home = home.absolutePath,
            // The runtime home's mtime is what changes when a runtime is
            // replaced, which is the only freshness a caller can be told.
            installedAtEpochMs = home.lastModified(),
        )
    }

    /**
     * Downloads the archive, forwarding progress into the flow.
     *
     * The downloader reports progress through a plain callback on its own
     * thread, and [FlowCollector.emit] is a suspend function, so the two are
     * joined by a channel: the download runs as a child coroutine, the flow
     * drains it, and a download that fails closes the channel and its exception
     * surfaces from `join`.
     */
    private suspend fun FlowCollector<JreInstallEvent>.downloadWithProgress(
        release: PojavRelease,
        archive: File,
    ) {
        val progress = Channel<DownloadProgress>(Channel.UNLIMITED)
        coroutineScope {
            val job = launch(Dispatchers.IO) {
                try {
                    downloader.download(release.assetUrl, archive) { report ->
                        progress.trySend(report)
                    }
                } finally {
                    progress.close()
                }
            }
            for (report in progress) {
                emit(JreInstallEvent.Progress(report.bytesRead, report.totalBytes))
            }
            job.join()
        }
    }

    companion object {
        /** The runtime a run reaches for when it names none. */
        internal const val DEFAULT_SPEC_ID = "jre21"

        private fun deviceAbiLayer(): String = androidAbiLayer(Build.SUPPORTED_ABIS.toList())
    }
}
