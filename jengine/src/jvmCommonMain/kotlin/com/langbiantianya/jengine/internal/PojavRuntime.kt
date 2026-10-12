// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The runtimes MojoLauncher publishes for Android, as this engine needs to see
 * them.
 *
 * `jre<major>-pojav.zip` is not an exploded JRE: it holds a `universal.tar.xz`
 * with the architecture-independent files — the class libraries, `conf/`, the
 * `release` stamp — and one `bin-<abi>.tar.xz` per supported architecture, each
 * carrying that ABI's `bin/` and its own shared libraries. A usable runtime is
 * the two layers unpacked on top of each other, with the ABI layer winning
 * where they overlap, because the ABI layer holds the `lib/server/libjvm.so`
 * the universal layer deliberately omits.
 *
 * The layer names are the Android ABI directories the build was made for
 * (`arm`, `arm64`, `x86`, `x86_64`), not OpenJDK target triples.
 */
internal val POJAV_ABI_LAYERS = listOf("arm", "arm64", "x86", "x86_64")

/**
 * The layer name matching a device's preferred ABI.
 *
 * `Build.SUPPORTED_ABIS` is ordered by preference, and the first entry is the
 * ABI Android installed this app for. An ABI the archives do not name — a
 * future one — is passed through unchanged, so the error the installer raises
 * is about the missing layer rather than about a name this function invented.
 */
internal fun androidAbiLayer(supportedAbis: List<String>): String = when (supportedAbis.firstOrNull()) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a" -> "arm"
    "x86" -> "x86"
    else -> supportedAbis.firstOrNull() ?: "x86_64"
}

/** The ABI layers an archive actually carries, in the order it lists them. */
internal fun pojavAbiLayersIn(archive: File): List<String> =
    ZipFile(archive).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("bin-") && it.endsWith(".tar.xz") }
            .map { it.removePrefix("bin-").removeSuffix(".tar.xz") }
            .toList()
    }

/**
 * Unpacks the universal layer, then [abiLayer], into [dest], which is created
 * and must be empty: overlapping files are overwritten by the ABI layer, and
 * unpacking into anything already populated would leave one run's runtime
 * mixed with another's.
 */
internal fun unpackPojavRuntime(archive: File, abiLayer: String, dest: File) {
    checkReadable(archive, "runtime archive")
    dest.mkdirs()
    ZipFile(archive).use { zip ->
        val universal = zip.getEntry("universal.tar.xz")
            ?: throw IOException("${archive.name} has no universal.tar.xz")
        zip.getInputStream(universal).use { input ->
            untarXz(input, dest)
        }

        val abi = zip.getEntry("bin-$abiLayer.tar.xz")
            ?: throw IOException("${archive.name} has no bin-$abiLayer.tar.xz")
        zip.getInputStream(abi).use { input ->
            untarXz(input, dest)
        }
    }
}

/**
 * The guest's VM library in an unpacked runtime, or null when there is none.
 *
 * HotSpot keeps it in a "flavor" directory, `server` for the builds these
 * archives carry and `client` for the ones that still publish it.
 */
internal fun vmLibraryIn(home: File): File? =
    sequenceOf("server", "client")
        .map { File(home, "lib/$it/libjvm.so") }
        .firstOrNull { it.isFile }
        ?: File(home, "lib/server/libjvm.so").takeIf { it.isFile }

/**
 * Post-extraction fixes the guest runtime needs before it will start.
 *
 * Mirrors MojoLauncher's `MultiRTUtils.postPrepare`: freetype is published with
 * a versioned SONAME that nothing else references, so the loader would not find
 * it under the name the AWT font manager asks for.
 */
internal fun preparePojavHome(home: File) {
    val libDir = vmLibraryIn(home)?.parentFile?.parentFile ?: return
    val versioned = File(libDir, "libfreetype.so.6")
    val plain = File(libDir, "libfreetype.so")
    if (versioned.isFile && (!plain.isFile || versioned.length() != plain.length())) {
        if (!versioned.renameTo(plain)) versioned.copyTo(plain, overwrite = true)
    }
}
