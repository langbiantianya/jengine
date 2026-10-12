// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/*
 * A hand-written POSIX ustar writer, because the entries worth feeding the
 * extraction code — a name that traverses out of the archive, a symlink whose
 * target escapes it, a name past the 100-byte header field — are exactly the
 * ones `tar` refuses to produce. The reader pads every block to
 * [TAR_BLOCK_SIZE] and stops at two zero blocks, so this must too.
 */

/** Builds a small archive: `tar { file("a", bytes); directory("d"); symlink("l", "a") }`. */
internal fun tar(block: TarFixtureBuilder.() -> Unit): TarFixture {
    val builder = TarFixtureBuilder()
    builder.block()
    return TarFixture(builder.finish())
}

internal class TarFixtureBuilder {

    private val out = ByteArrayOutputStream()

    fun file(name: String, bytes: ByteArray) = entry(name, bytes, TYPE_FILE, "")

    fun file(name: String, text: String) = file(name, text.toByteArray(Charsets.UTF_8))

    fun directory(name: String) = entry(name.trimEnd('/') + "/", EMPTY, TYPE_DIR, "")

    fun symlink(name: String, target: String) = entry(name, EMPTY, TYPE_SYMLINK, target)

    private fun entry(name: String, bytes: ByteArray, type: Byte, linkname: String) {
        if (name.toByteArray(Charsets.UTF_8).size > NAME_FIELD) writeLongName(name)
        writeHeader(name, bytes.size.toLong(), type, linkname)
        out.write(bytes)
        padToBlock(bytes.size)
    }

    private fun writeLongName(name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8) + 0.toByte()
        writeHeader(LONG_LINK_NAME, nameBytes.size.toLong(), TYPE_LONGNAME, "")
        out.write(nameBytes)
        padToBlock(nameBytes.size)
    }

    private fun padToBlock(size: Int) {
        val padding = (TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE
        if (padding > 0) out.write(ByteArray(padding))
    }

    private fun writeHeader(name: String, size: Long, type: Byte, linkname: String) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        putString(header, 0, NAME_FIELD, name)
        putOctal(header, 100, 8, 420L) // 0644
        putOctal(header, 108, 8, 0L)
        putOctal(header, 116, 8, 0L)
        putOctal(header, 124, 12, size)
        putOctal(header, 136, 12, 0L)
        header[156] = type
        putString(header, 157, LINK_FIELD, linkname)
        putString(header, 257, 6, "ustar")
        header[262] = 0
        putString(header, 263, 2, "00")
        putString(header, 265, 32, "tester")
        putString(header, 297, 32, "tester")
        putOctal(header, 329, 8, 0L)
        putOctal(header, 337, 8, 0L)

        for (i in 148 until 156) header[i] = SPACE
        val checksum = "%06o".format(header.sumOf { it.toInt() and 0xFF }) + "\u0000 "
        putString(header, 148, 8, checksum)
        out.write(header)
    }

    internal fun finish(): ByteArray {
        val payload = out.toByteArray()
        val archive = ByteArray(payload.size + TAR_BLOCK_SIZE * 2)
        System.arraycopy(payload, 0, archive, 0, payload.size)
        return archive
    }

    private fun putString(header: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length))
    }

    private fun putOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        require(text.length <= length - 1) { "value $value does not fit in $length octal bytes" }
        putString(header, offset, text.length, text)
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val NAME_FIELD = 100
        const val LINK_FIELD = 100
        const val LONG_LINK_NAME = "././@LongLink"
        const val TYPE_FILE = '0'.code.toByte()
        const val TYPE_DIR = '5'.code.toByte()
        const val TYPE_SYMLINK = '2'.code.toByte()
        const val TYPE_LONGNAME = 'L'.code.toByte()
        const val SPACE = ' '.code.toByte()
    }
}

internal class TarFixture(private val bytes: ByteArray) {

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun toXzBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun toGzBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}

/** A zip in the order the entries are listed, which is what `ZipFile` reads back. */
internal fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** A fresh empty directory under the JVM temp directory. */
internal fun tempDir(prefix: String): File =
    Files.createTempDirectory("jengine-$prefix-").toFile()
