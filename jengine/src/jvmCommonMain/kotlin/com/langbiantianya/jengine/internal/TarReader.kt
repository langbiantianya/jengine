// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** One entry of a tar archive. */
internal class TarEntry(
    val name: String,
    val size: Long,
    val type: Byte,
    /**
     * The entry's permission bits, of which only the execute ones are used: a
     * runtime unpacked from an archive whose `bin/` files were left unwritable
     * by the file system's default would be a directory of libraries that
     * cannot be run.
     */
    val mode: Int = 0,
    /**
     * Where a symlink points, relative to the directory holding it. It is in
     * the header rather than in the payload — a symlink entry has no payload —
     * which is why the reader has to surface it.
     */
    val linkName: String = "",
) {
    companion object {
        const val TYPE_FILE: Byte = '0'.code.toByte()
        const val TYPE_DIR: Byte = '5'.code.toByte()

        /** POSIX symlink entry; its payload is empty and its target is [linkName]. */
        const val TYPE_SYMLINK: Byte = '2'.code.toByte()
    }
}

/**
 * The tar block every header and every payload is padded to. Exposed because a
 * caller that writes tar — the tests that build the archives this reader is fed
 * — has to pad to the same number to produce a stream the reader accepts.
 */
internal const val TAR_BLOCK_SIZE = 512

/**
 * A streaming reader for the subset of tar that the JDK runtime archives use:
 * POSIX ustar headers, regular files and directories, the symlinks the
 * published runtimes are full of, and the long-name extension GNU tar writes
 * for paths beyond the 100-byte header field.
 *
 * The JDK build writes with GNU tar, so long-name entries are expected and are
 * folded into the name of the entry that follows. Entries of any other type are
 * still returned, with their payload left for the caller to consume, so that a
 * type this reader does not understand cannot desynchronise the stream.
 *
 * A symlink's target is in the header rather than in its payload — a link entry
 * carries no payload — so it is surfaced as [TarEntry.linkName] and the caller
 * decides what to do with it. `legal/` links pointing at sibling license files
 * are most of what the published runtimes contain; the extractor materialises
 * them as copies, because nothing the VM loads is a link.
 */
internal class TarReader(private val input: InputStream) : Closeable {

    private val header = ByteArray(BLOCK_SIZE)
    private var remainingInEntry = 0L
    private var paddingOfEntry = 0L
    private var pendingLongName: String? = null

    override fun close() = input.close()

    /** Advances to the next file or directory entry, or null at end of archive. */
    fun nextHeader(): TarEntry? {
        skipRestOfEntry()
        while (true) {
            if (!readFully(header)) return null
            if (header.all { it == 0.toByte() }) {
                // Two zero blocks terminate the archive.
                if (!readFully(header)) return null
                if (header.all { it == 0.toByte() }) return null
            }
            val name = readString(0, 100)
            val mode = readOctal(100, 8).toInt()
            val sizeField = readOctal(124, 12)
            val type = header[156]
            val linkName = readString(157, 100)
            val prefix = readString(345, 155)

            if (type == TYPE_LONGNAME) {
                // The long-name payload is itself a file entry: consume exactly
                // sizeField bytes (plus block padding) so the following header
                // lands on a block boundary.
                val nameBytes = readEntryBytes(sizeField)
                pendingLongName = nameBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                continue
            }

            val fullName = when {
                pendingLongName != null -> pendingLongName!!
                prefix.isNotEmpty() && prefix != "./" -> "$prefix/$name"
                name.startsWith("./") -> name.removePrefix("./")
                else -> name
            }
            pendingLongName = null
            if (fullName.isEmpty()) continue

            remainingInEntry = sizeField
            paddingOfEntry = (BLOCK_SIZE - (sizeField % BLOCK_SIZE)) % BLOCK_SIZE
            return TarEntry(fullName, sizeField, type, mode, linkName)
        }
    }

    /** Copies exactly [size] bytes of the current entry's payload. */
    fun copyEntry(output: OutputStream, size: Long) {
        var left = size
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw java.io.IOException("truncated tar entry")
            output.write(buffer, 0, read)
            left -= read
            remainingInEntry -= read
        }
    }

    /** Reads and consumes the current entry's payload, plus its block padding. */
    private fun readEntryBytes(size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        copyEntry(out, size)
        remainingInEntry = 0
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        if (padding > 0) {
            var left = padding
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) break
                    left--
                } else {
                    left -= skipped
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Consumes whatever is left of the current entry — the caller may have read
     * only part of the payload, or none of it — followed by the block padding
     * that every payload is followed by.
     */
    private fun skipRestOfEntry() {
        var left = remainingInEntry + paddingOfEntry
        remainingInEntry = 0
        paddingOfEntry = 0
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun readFully(target: ByteArray): Boolean {
        var filled = 0
        while (filled < target.size) {
            val read = input.read(target, filled, target.size - filled)
            if (read < 0) return false
            filled += read
        }
        return true
    }

    private fun readString(offset: Int, length: Int): String {
        var end = offset + length
        if (end > header.size) end = header.size
        var i = offset
        while (i < end && header[i] != 0.toByte()) i++
        val chars = CharArray(i - offset)
        for (k in chars.indices) chars[k] = (header[offset + k].toInt() and 0xFF).toChar()
        return String(chars)
    }

    private fun readOctal(offset: Int, length: Int): Long {
        val raw = readString(offset, length).trim()
        if (raw.isEmpty()) return 0
        // GNU base-256 encoding for values that do not fit in octal.
        if (header[offset] == 0x80.toByte()) {
            var value = 0L
            for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            return value
        }
        return raw.toLongOrNull(8) ?: 0
    }

    private companion object {
        const val BLOCK_SIZE = TAR_BLOCK_SIZE
        const val TYPE_LONGNAME: Byte = 'L'.code.toByte()
    }
}
