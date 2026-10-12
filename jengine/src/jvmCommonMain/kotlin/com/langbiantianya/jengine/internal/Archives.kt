// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.tukaani.xz.XZInputStream

/**
 * Extracts an archive into a directory, whatever this library fetches or is
 * pointed at.
 *
 * The vendors publish the same runtime three ways — a plain zip for Windows, a
 * gzip'ed tar for Linux and macOS, and MojoLauncher's zip of xz'ed tar layers
 * for Android — and a caller that has a runtime archive of its own should not
 * have to say which it is. The format is read from the first bytes, with the
 * file name only as a fallback: a URL that ends in `/normal/eclipse` is a
 * gzip'ed tar, and its name says nothing.
 *
 * @param stripComponents leading path segments to drop, which is how a tar
 *   that wraps everything in `jdk-21.0.5+11/` lands as the runtime home rather
 *   than as one directory inside it.
 */
internal fun extractArchive(archive: File, dest: File, stripComponents: Int = 0) {
    checkReadable(archive, "archive")
    when (formatOf(archive)) {
        ArchiveFormat.ZIP -> unzip(archive, dest, stripComponents)
        ArchiveFormat.TAR_GZ -> untarGzip(archive, dest, stripComponents)
        ArchiveFormat.TAR_XZ -> untarXz(archive, dest, stripComponents)
    }
}

internal enum class ArchiveFormat { ZIP, TAR_GZ, TAR_XZ }

/**
 * The archive's format, from its magic bytes where it has any.
 *
 * A zip starts `PK`, gzip `1f 8b`, and xz `fd 37 7a 58 5a 00` — the last of
 * which is what tells an xz'ed tar from anything else. The name is consulted
 * only when the bytes are inconclusive, which a very short or empty file can be.
 */
internal fun formatOf(archive: File): ArchiveFormat {
    val magic = ByteArray(6)
    val read = FileInputStream(archive).use { it.read(magic) }
    if (read >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) return ArchiveFormat.ZIP
    if (read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) return ArchiveFormat.TAR_GZ
    if (read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
        magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte()
    ) {
        return ArchiveFormat.TAR_XZ
    }
    val name = archive.name.lowercase()
    return when {
        name.endsWith(".zip") || name.endsWith(".jar") -> ArchiveFormat.ZIP
        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
        name.endsWith(".tar.xz") || name.endsWith(".txz") -> ArchiveFormat.TAR_XZ
        else -> throw IOException("unrecognised archive: ${archive.name}")
    }
}

/** An xz'ed tar inside an archive, such as one member of a runtime zip. */
internal fun untarXz(input: InputStream, dest: File, stripComponents: Int = 0) {
    untar(XZInputStream(BufferedInputStream(input, BUFFER)), dest, stripComponents)
}

/** An xz'ed tar on disk. */
internal fun untarXz(archive: File, dest: File, stripComponents: Int = 0) {
    FileInputStream(archive).use { input -> untarXz(input, dest, stripComponents) }
}

internal fun untarGzip(archive: File, dest: File, stripComponents: Int = 0) {
    FileInputStream(archive).use { input ->
        untar(GZIPInputStream(BufferedInputStream(input, BUFFER)), dest, stripComponents)
    }
}

/**
 * Unpacks a zip, entry by entry.
 *
 * `ZipFile` rather than `ZipInputStream` for the same reason the layers are
 * read whole: an archive whose entries are worth checking can be read twice,
 * and a stream cannot.
 */
internal fun unzip(archive: File, dest: File, stripComponents: Int = 0) {
    val root = dest.canonicalPath
    dest.mkdirs()
    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = stripLeadingComponents(entry.name, stripComponents)
            if (name.isEmpty()) continue
            val out = containedIn(dest, root, name)
            out.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it, BUFFER) } }
        }
    }
}

/**
 * Unpacks a tar stream: POSIX ustar headers with the long-name extension GNU
 * tar writes, regular files, directories and symlinks.
 *
 * A symlink is materialised as a copy of what it points at, once that exists —
 * the archives are unpacked in one pass, so the target may only appear later.
 * Copying rather than linking is what portability across Android, where
 * creating a symlink needs an API level the engine does not require, ends up
 * doing anyway; for a runtime the two are the same tree of bytes. A link whose
 * target never appears, or that points outside [dest], is skipped: it is
 * `legal/` boilerplate in every runtime published so far, and never something
 * the VM loads.
 *
 * The archive's execute bits are applied, because they are the difference
 * between a runtime whose `bin/java` runs and a directory of libraries that
 * cannot be started. Nothing else about the mode is copied: a read-only file
 * that a caller may later have to replace, on a platform where that is decided
 * by the parent directory anyway, is not worth the incident.
 */
internal fun untar(input: InputStream, dest: File, stripComponents: Int = 0) {
    val root = dest.canonicalPath
    dest.mkdirs()
    val deferredLinks = ArrayList<Pair<File, String>>()

    TarReader(input).use { tar ->
        while (true) {
            val entry = tar.nextHeader() ?: break
            val name = stripLeadingComponents(entry.name, stripComponents)
            if (entry.type == TarEntry.TYPE_SYMLINK) {
                if (name.isEmpty()) continue
                // The target is normally in the header, and the payload is a
                // fallback for writers that put it there instead.
                val linkTarget = entry.linkName.ifBlank {
                    if (entry.size > 0) tar.readEntryText(entry.size) else ""
                }
                val link = containedIn(dest, root, name)
                deferredLinks += link to linkTarget
                continue
            }
            if (entry.type != TarEntry.TYPE_DIR && entry.type != TarEntry.TYPE_FILE) continue
            if (name.isEmpty()) continue
            // Both kinds are checked, not just the ones that write bytes: a
            // directory entry is a name the archive gets to choose too, and
            // `mkdirs` on a traversing one creates directories outside the
            // destination before a single file is ever written.
            val out = containedIn(dest, root, name)
            if (entry.type == TarEntry.TYPE_DIR) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { output -> tar.copyEntry(output, entry.size) }
            if (entry.mode and EXECUTE_BITS != 0) {
                out.setExecutable(true, false)
            }
        }
    }

    materialiseLinks(deferredLinks, dest, root)
}

/** The three execute bits of a POSIX mode, any one of which means "runnable". */
private const val EXECUTE_BITS = 0b001_001_001

/**
 * Turns the symlinks of an archive into copies, resolving the ones whose target
 * is itself a link.
 *
 * Repeated until a pass makes no progress, which is what a chain of links
 * needs; what is left is a link whose target is missing or outsided the
 * destination, and is skipped.
 */
private fun materialiseLinks(links: List<Pair<File, String>>, dest: File, root: String) {
    var pending = links
    while (pending.isNotEmpty()) {
        var progressed = false
        val stillPending = ArrayList<Pair<File, String>>(pending.size)
        for ((link, target) in pending) {
            val resolved = runCatching {
                val candidate = if (target.startsWith("/")) File(target) else File(link.parentFile, target)
                candidate.canonicalFile
            }.getOrNull()
            if (resolved == null || (resolved.path != root && !resolved.path.startsWith(root + File.separator))) {
                continue
            }
            if (!resolved.isFile) {
                stillPending += link to target
                continue
            }
            link.parentFile?.mkdirs()
            resolved.copyTo(link, overwrite = true)
            // A link to something runnable is itself runnable as far as a
            // caller is concerned; a copy made by `copyTo` would not be.
            if (resolved.canExecute()) link.setExecutable(true, false)
            progressed = true
        }
        if (!progressed) return
        pending = stillPending
    }
}

/**
 * Resolves [name] under [dest], or throws if it would land outside.
 *
 * The comparison is made on canonical paths, so a name that traverses with
 * `..`, or whose parent is a symlink, is caught rather than merely looking
 * harmless. [root] is [dest]'s already-resolved path, passed in so the caller
 * resolves it once for the whole archive.
 */
private fun containedIn(dest: File, root: String, name: String): File {
    val candidate = File(dest, name).canonicalFile
    if (candidate.path != root && !candidate.path.startsWith(root + File.separator)) {
        throw IOException("archive entry escapes the destination directory: $name")
    }
    return candidate
}

/**
 * Drops the first [count] path segments, so that an archive wrapped in a
 * versioned directory unpacks to what is inside it.
 */
internal fun stripLeadingComponents(name: String, count: Int): String {
    if (count <= 0) return name.removePrefix("./")
    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
    return parts.drop(count).joinToString("/")
}

/** Reads a tar entry's payload, which for a symlink is its target's name. */
private fun TarReader.readEntryText(size: Long): String {
    val buffer = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
    copyEntry(buffer, size)
    return buffer.toString("UTF-8").trimEnd('\u0000')
}

internal const val BUFFER = 64 * 1024
