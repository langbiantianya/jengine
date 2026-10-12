// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.langbiantianya.jengine.JreSpec
import java.io.File

/**
 * How this library's names for an operating system and a CPU architecture map
 * onto the ones the Adoptium API publishes under, and how a runtime on disk is
 * recognised.
 *
 * Everything here is a pure function of its argument: the `os.name` and
 * `os.arch` system properties are read by the caller, not here, so that the
 * mapping can be exercised for every platform from one test run rather than
 * only for the machine the test happens to be on.
 */
internal object AdoptiumPlatform {

    /**
     * The Adoptium OS name for a JVM `os.name`, or a failure naming the value.
     *
     * The tests are substring rather than equality because the property carries
     * more than the OS — `Windows 11`, `Mac OS X` — and `Mac OS X` contains
     * neither `linux` nor `win`, so the order of the branches never decides.
     */
    fun osOf(name: String): String {
        val lower = name.lowercase()
        return when {
            "win" in lower -> "windows"
            "mac" in lower || "darwin" in lower -> "mac"
            "linux" in lower -> "linux"
            "aix" in lower -> "aix"
            "sunos" in lower || "solaris" in lower -> "solaris"
            else -> throw IllegalArgumentException("unsupported operating system: '$name'")
        }
    }

    /** The Adoptium architecture name for a JVM `os.arch`, or a failure naming it. */
    fun archOf(arch: String): String = when (arch.trim().lowercase()) {
        "amd64", "x86_64" -> "x64"
        "x86", "i386", "i686" -> "x86"
        "aarch64", "arm64" -> "aarch64"
        "arm" -> "arm"
        "ppc64le" -> "ppc64le"
        "s390x" -> "s390x"
        "riscv64" -> "riscv64"
        else -> throw IllegalArgumentException("unsupported architecture: '$arch'")
    }

    /**
     * The launcher inside a runtime home, or null when there is none.
     *
     * Both names are checked on every platform: an archive unpacked on one OS
     * may be inspected from another — a Windows JRE under Wine, a Mac JRE
     * unpacked on the build host — and the caller wants the file, not a claim
     * about which OS it is on.
     */
    fun javaExecutableIn(home: File): File? =
        sequenceOf(File(home, "bin/java"), File(home, "bin/java.exe")).firstOrNull { it.isFile }

    /**
     * The major Java version a runtime home is stamped with, or null when its
     * `release` file is missing, has no `JAVA_VERSION`, or names a version that
     * does not start with a number.
     *
     * The JDK writes the whole version quoted (`JAVA_VERSION="21.0.12.1"`), and
     * the digits up to the first `.` are the feature release — 21, not 21.0.
     */
    fun majorVersionOf(home: File): Int? {
        val release = File(home, "release")
        if (!release.isFile) return null
        val text = runCatching { release.readText() }.getOrNull() ?: return null
        val line = text.lineSequence().firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        val value = line.substringAfter('=').trim().trim('"')
        return value.takeWhile { it.isDigit() }.toIntOrNull()
    }
}

/**
 * A runtime spec as this platform names its runtimes.
 *
 * The id is the directory name a runtime unpacks into, so it is derived from
 * the major version rather than chosen — the same `jre21` names the same Java
 * version however a caller got here.
 */
internal fun adoptiumJreSpec(
    id: String,
    majorVersion: Int,
    downloadSizeBytes: Long? = null,
): JreSpec = JreSpec(
    id = id,
    majorVersion = majorVersion,
    displayName = "Temurin $majorVersion (JRE)",
    downloadSizeBytes = downloadSizeBytes,
)

/** The `major` out of an id of the form `jre21`, or null when it is not one. */
internal fun majorVersionFromId(specId: String): Int? =
    JRE_ID.matchEntire(specId)?.groupValues?.get(1)?.toIntOrNull()

private val JRE_ID = Regex("jre(\\d+)")
