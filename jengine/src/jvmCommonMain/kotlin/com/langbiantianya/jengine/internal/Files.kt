// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Used where a caller-supplied name reduces to nothing usable. */
internal const val FALLBACK_JAR_NAME = "app.jar"

/**
 * An id that sorts by when it was made and does not collide.
 *
 * Named after the clock so a directory listing reads in the order things
 * happened, and suffixed with randomness so two ids made in the same
 * millisecond — which a test does — are still distinct.
 */
internal fun newRunId(nowMillis: Long = System.currentTimeMillis()): String =
    "$nowMillis-${UUID.randomUUID().toString().take(8)}"

/**
 * Reduces a name chosen by someone else to one path segment.
 *
 * A JAR's name can come from a document provider, a download, or a caller that
 * did not sanitise it, so it can carry `../` or a separator. Everything that is
 * not part of a file name goes, and a name that reduces to nothing — `.` and
 * `..` both do, once the leading dots are trimmed — falls back to a constant.
 */
internal fun safeFileName(name: String, fallback: String = FALLBACK_JAR_NAME): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = leaf.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .trim('.', '_')
    return cleaned.ifEmpty { fallback }
}

/** The same reduction for a JAR, keeping its extension where it has one. */
internal fun safeJarStem(name: String): String {
    val stripped = name.removeSuffix(".jar").removeSuffix(".JAR")
    return safeFileName(stripped.ifEmpty { name }, fallback = "app")
}

/**
 * Runs [block] holding an exclusive lock on [lockFile], across processes.
 *
 * Two callers asking for the same runtime — a settings screen in the app
 * process and a run in the guest process — would otherwise share one archive
 * file and one staging directory and delete each other's half-finished work.
 */
internal inline fun <T> withFileLock(lockFile: File, block: () -> T): T {
    lockFile.parentFile?.mkdirs()
    RandomAccessFile(lockFile, "rw").use { file ->
        val lock = file.channel.lock()
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}

/** Deletes a file or a whole tree, reporting whether anything is left behind. */
internal fun deleteTree(target: File?): Boolean {
    if (target == null || !target.exists()) return true
    if (target.isDirectory) {
        target.listFiles()?.forEach { deleteTree(it) }
    }
    return target.delete() || !target.exists()
}

/**
 * Makes [to] hold exactly what [from] holds, replacing whatever was there.
 *
 * A rename is one operation where the file system allows it — the same volume,
 * which the staging directory and the runtime home always are — and a copy is
 * what is left when it is not.
 */
internal fun replaceDirectory(from: File, to: File) {
    to.parentFile?.mkdirs()
    deleteTree(to)
    if (from.renameTo(to)) return
    from.copyRecursively(to, overwrite = true)
    deleteTree(from)
}

/** Reads a file whole, or returns null when it is not there yet. */
internal fun File.readTextOrNull(): String? =
    if (isFile) runCatching { readText() }.getOrNull() else null

/**
 * Writes [text] over [file] so that a reader sees either all of it or none of
 * it: the content goes to a temporary file beside the target, which is then
 * renamed over it.
 *
 * This is what a result file has to do. A reader polls it for the moment a run
 * ends, and a file that exists half-written would be read as a result.
 */
internal fun writeTextAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(text)
    deleteTree(file)
    if (!temporary.renameTo(file)) {
        file.writeText(text)
        deleteTree(temporary)
    }
}

/** Appends one line, creating the file and its parents when they are missing. */
internal fun appendLine(file: File, text: String) {
    file.parentFile?.mkdirs()
    file.appendText(text + "\n")
}

/** Fails with a readable message rather than a bare [IOException]. */
internal fun checkReadable(file: File, what: String) {
    if (!file.isFile) throw IOException("$what is not a file: $file")
    if (!file.canRead()) throw IOException("$what is not readable: $file")
}
