// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import com.langbiantianya.jengine.internal.AdoptiumApi
import com.langbiantianya.jengine.internal.AdoptiumAsset
import com.langbiantianya.jengine.internal.AdoptiumPlatform
import com.langbiantianya.jengine.internal.EngineLayout
import com.langbiantianya.jengine.internal.HttpDownloader
import com.langbiantianya.jengine.internal.adoptiumJreSpec
import com.langbiantianya.jengine.internal.deleteTree
import com.langbiantianya.jengine.internal.extractArchive
import com.langbiantianya.jengine.internal.majorVersionFromId
import com.langbiantianya.jengine.internal.replaceDirectory
import com.langbiantianya.jengine.internal.withFileLock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * The Temurin runtimes a desktop can fetch, and the ones this engine has.
 *
 * Runtimes are ordinary directories under the engine's home, so anything on
 * disk that looks like a JRE is a runtime whether this engine installed it or
 * not: a caller can point a run at a JRE it already has, and [installed] only
 * reports the ones this engine unpacked.
 */
internal class DesktopJreRepository(
    private val config: DesktopEngineConfig,
    private val layout: EngineLayout,
    private val api: AdoptiumApi = AdoptiumApi(config.adoptiumApiBase),
    private val downloader: HttpDownloader = HttpDownloader(),
) : JreRepository {

    override suspend fun available(): List<JreSpec> =
        api.availableReleases().map { major -> adoptiumJreSpec(id = "jre$major", majorVersion = major) }

    override suspend fun installed(): List<InstalledJre> = withContext(Dispatchers.IO) {
        layout.jreRoot.listFiles()
            .orEmpty()
            // Staging directories and lock files share the root while an
            // install runs; `.`-prefixed names are never a runtime home.
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { home -> installedAt(home) }
            .sortedBy { it.spec.majorVersion }
    }

    override suspend fun isInstalled(specId: String): Boolean =
        AdoptiumPlatform.javaExecutableIn(layout.jreHome(specId)) != null

    override suspend fun installedJre(specId: String): InstalledJre? =
        installedAt(layout.jreHome(specId))

    override suspend fun defaultSpec(): JreSpec? = installed().lastOrNull()?.spec

    override fun install(specId: String, force: Boolean): Flow<JreInstallEvent> = channelFlow {
        val requested = majorVersionFromId(specId)
        if (requested == null) {
            send(
                JreInstallEvent.Failed(
                    "'$specId' is not a runtime id; expected the form jre<major>, such as jre21",
                ),
            )
            return@channelFlow
        }

        // One install per runtime at a time, across processes as well as
        // coroutines: two callers asking for the same runtime would otherwise
        // share one archive file and one staging directory.
        withFileLock(layout.installLock(specId)) {
            val present = installedAt(layout.jreHome(specId))
            if (present != null && !force) {
                send(JreInstallEvent.AlreadyInstalled(present))
                return@withFileLock
            }

            val os = AdoptiumPlatform.osOf(System.getProperty("os.name").orEmpty())
            val arch = AdoptiumPlatform.archOf(System.getProperty("os.arch").orEmpty())
            val archive = layout.runtimeArchive(specId)
            val staging = layout.staging(specId)
            try {
                val asset = api.latestAsset(requested, os, arch)
                send(JreInstallEvent.Started(asset.spec))

                val url = downloadUrlFor(asset, os, arch)
                withContext(Dispatchers.IO) {
                    downloader.download(
                        url = url,
                        target = archive,
                        expectedSha256 = asset.sha256,
                        expectedSize = asset.spec.downloadSizeBytes,
                    ) { progress ->
                        // The downloader runs synchronously on this dispatcher,
                        // so progress is handed to the flow's channel rather
                        // than emitted from inside the callback.
                        trySend(JreInstallEvent.Progress(progress.bytesRead, progress.totalBytes))
                    }
                }

                send(JreInstallEvent.Unpacking(asset.spec))
                withContext(Dispatchers.IO) {
                    deleteTree(staging)
                    // Temurin archives wrap everything in one directory named
                    // after the build; dropping it lands the runtime home in
                    // the staging root.
                    extractArchive(archive, staging, stripComponents = 1)
                    val home = runtimeHomeIn(staging)
                        ?: throw IOException(
                            "${asset.fileName.ifBlank { archive.name }} does not contain a " +
                                "runtime home (no bin/java under it)",
                        )
                    replaceDirectory(home, layout.jreHome(specId))
                }

                val installed = installedAt(layout.jreHome(specId))
                    ?: throw IOException("$specId unpacked but has no bin/java at ${layout.jreHome(specId)}")
                send(JreInstallEvent.Installed(installed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                send(JreInstallEvent.Failed("installing $specId failed: ${e.message}", e))
            } finally {
                deleteTree(archive)
                deleteTree(staging)
            }
        }
    }

    override suspend fun installNow(specId: String, force: Boolean): InstalledJre {
        var installed: InstalledJre? = null
        var failure: JEngineException? = null
        withContext(Dispatchers.IO) {
            install(specId, force).collect { event ->
                when (event) {
                    is JreInstallEvent.Installed -> installed = event.jre
                    is JreInstallEvent.AlreadyInstalled -> installed = event.jre
                    is JreInstallEvent.Failed -> failure = JEngineException(event.message, event.cause)
                    else -> Unit
                }
            }
        }
        return installed ?: throw (failure ?: JEngineException("installing $specId produced no runtime"))
    }

    override suspend fun remove(specId: String) = withContext(Dispatchers.IO) {
        if (!deleteTree(layout.jreHome(specId))) {
            throw IOException("could not remove JRE '$specId' at ${layout.jreHome(specId)}")
        }
    }

    override suspend fun resolve(ref: JreRef): ResolvedJre = when (ref) {
        is JreRef.Managed -> {
            val installed = installedJre(ref.id) ?: throw JreNotInstalledException(ref.id)
            val executable = AdoptiumPlatform.javaExecutableIn(File(installed.home))
                ?: throw JreNotInstalledException(ref.id)
            ResolvedJre(
                ref = ref,
                home = installed.home,
                majorVersion = installed.spec.majorVersion,
                javaExecutable = executable.absolutePath,
                spec = installed.spec,
            )
        }

        is JreRef.Home -> {
            val home = File(ref.path)
            val executable = AdoptiumPlatform.javaExecutableIn(home)
                ?: throw InvalidJreHomeException(ref.path, "no bin/java under it")
            ResolvedJre(
                ref = ref,
                home = home.absolutePath,
                majorVersion = AdoptiumPlatform.majorVersionOf(home),
                javaExecutable = executable.absolutePath,
            )
        }

        is JreRef.JavaExecutable -> {
            val executable = File(ref.path)
            if (!executable.isFile || !executable.canExecute()) {
                throw InvalidJreHomeException(ref.path, "not an executable file")
            }
            // A launcher outside a `bin` directory is still usable — the caller
            // may have renamed or moved it — so its home is unknown rather than
            // wrong.
            val bin = executable.parentFile
            val home = bin?.takeIf { it.name == "bin" }?.parentFile
            ResolvedJre(
                ref = ref,
                home = home?.absolutePath,
                majorVersion = home?.let { AdoptiumPlatform.majorVersionOf(it) },
                javaExecutable = executable.absolutePath,
            )
        }
    }

    /** The archive URL for an asset, overridden when a download mirror is set. */
    private fun downloadUrlFor(asset: AdoptiumAsset, os: String, arch: String): String {
        val base = config.jreDownloadBase.trim().trimEnd('/')
        if (base.isEmpty()) return asset.downloadUrl
        return "$base/${asset.spec.majorVersion}/jre/$arch/$os/${asset.fileName}"
    }

    /**
     * An installed runtime at [home], or null when there is no launcher there.
     *
     * The major version comes from the runtime's own `release` stamp where it
     * has one, and from the directory name otherwise, so a runtime unpacked by
     * an older engine build still reports the version it is.
     */
    private fun installedAt(home: File): InstalledJre? {
        if (AdoptiumPlatform.javaExecutableIn(home) == null) return null
        val major = AdoptiumPlatform.majorVersionOf(home)
            ?: majorVersionFromId(home.name)
            ?: 0
        return InstalledJre(
            spec = adoptiumJreSpec(id = home.name, majorVersion = major),
            home = home.absolutePath,
            installedAtEpochMs = home.lastModified(),
        )
    }

    /** The runtime home inside an unpacked staging directory, or null. */
    private fun runtimeHomeIn(staging: File): File? {
        if (AdoptiumPlatform.javaExecutableIn(staging) != null) return staging
        val child = staging.listFiles()?.filter { it.isDirectory }?.singleOrNull() ?: return null
        return child.takeIf { AdoptiumPlatform.javaExecutableIn(it) != null }
    }
}
