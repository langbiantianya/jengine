// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Follows a file that is being appended to, emitting complete lines.
 *
 * This is how a run's output is read on Android, where the guest's stdout and
 * stderr are redirected to a file by file descriptor — the only way to also
 * catch what a native library the JAR loaded prints — and it is used on a
 * desktop as well, so that both platforms answer `output()` and `lines` from
 * the same place.
 *
 * The flow replays from the beginning of the file and then follows it, so a
 * collector that subscribes late sees everything. It ends when [isFinished]
 * says the writer is done and there is nothing left to read, flushing a last
 * line that was never terminated.
 *
 * Decoding happens per line rather than per read: a read can split a multi-byte
 * character, and a line cannot, because UTF-8 never contains a newline byte
 * inside a sequence.
 */
internal fun tailLines(
    file: File,
    pollMillis: Long = 100,
    isFinished: () -> Boolean,
): Flow<String> = flow {
    var offset = 0L
    var pending = ByteArray(0)

    while (true) {
        val length = file.length()
        if (length > offset) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    offset += read
                    pending = pending + buffer.copyOf(read)
                    var start = 0
                    for (index in pending.indices) {
                        if (pending[index] != NEWLINE) continue
                        emit(decodeLine(pending, start, index))
                        start = index + 1
                    }
                    if (start > 0) pending = pending.copyOfRange(start, pending.size)
                }
            }
            // Read again rather than polling: a writer that has just produced a
            // lot of output should be followed at once.
            continue
        }
        if (runCatching { isFinished() }.getOrDefault(false)) {
            if (pending.isNotEmpty()) emit(decodeLine(pending, 0, pending.size))
            return@flow
        }
        delay(pollMillis)
    }
}

/**
 * Decodes one line, dropping the carriage return a Windows-written line ends
 * with so that callers see the same text on every platform.
 */
private fun decodeLine(bytes: ByteArray, start: Int, end: Int): String {
    var last = end
    if (last > start && bytes[last - 1] == CARRIAGE_RETURN) last--
    return String(bytes, start, last - start, Charsets.UTF_8)
}

/** The same flow, numbered as a run's log: see `JarRun.lines`. */
internal fun tailLogLines(
    file: File,
    pollMillis: Long = 100,
    isFinished: () -> Boolean,
): Flow<Pair<Long, String>> = flow {
    var sequence = 0L
    tailLines(file, pollMillis, isFinished).collect { line ->
        emit(sequence to line)
        sequence++
    }
}

/** Reads a whole file as text, tolerating one that is not there yet. */
internal fun readWholeFile(file: File): String =
    runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")

private val NEWLINE = '\n'.code.toByte()
private val CARRIAGE_RETURN = '\r'.code.toByte()
