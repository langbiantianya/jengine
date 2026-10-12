// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** How much of a download has arrived. */
internal data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
)

/**
 * Fetches a file over HTTP, twice as careful as it needs to be for a
 * download that a few hundred megabytes of runtime hang on.
 *
 * The transfer goes to a `.part` file beside the target and is moved over it
 * only after the size and, when the source published one, the SHA-256 match.
 * That is what makes an interrupted download harmless — the next attempt starts
 * from nothing rather than from a truncated archive that unpacks into a broken
 * runtime — and what makes a corrupted one impossible to install silently.
 *
 * `HttpURLConnection` rather than a newer HTTP client because this code is
 * compiled for Android too, where it is the only one that exists at the API
 * level the engine supports.
 */
internal class HttpDownloader(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    /**
     * Downloads [url] to [target], reporting progress as it goes.
     *
     * @param expectedSha256 the lowercase hex digest the source published, or
     *   null where it publishes none.
     * @param expectedSize the length the source published, or null.
     * @throws IOException on a failed transfer, a mismatch, or a server that
     *   redirected somewhere this client would not follow.
     */
    fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        expectedSize: Long? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        deleteTree(part)

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var reported = 0L
        try {
            val connection = open(url)
            try {
                val status = connection.responseCode
                if (status != HTTP_OK) {
                    throw IOException("HTTP $status downloading $url")
                }
                val total = contentLengthOf(connection)
                connection.inputStream.use { input ->
                    part.outputStream().buffered(BUFFER).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            onProgress(DownloadProgress(written, total))
                            reported = written
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (expectedSize != null && written != expectedSize) {
                throw IOException("$url is $written bytes, expected $expectedSize")
            }
            val actual = digest.digest().toHex()
            if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("$url has SHA-256 $actual, expected $expectedSha256")
            }
            if (written == 0L) throw IOException("$url returned no data")
            if (written != reported) onProgress(DownloadProgress(written, written))

            deleteTree(target)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                deleteTree(part)
            }
        } catch (e: Throwable) {
            deleteTree(part)
            throw e
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection()
        if (connection !is HttpURLConnection) throw IOException("not an HTTP URL: $url")
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        // A binary download must not be re-encoded in transit: HttpURLConnection
        // does not undo a content encoding, so a gzip'ed response would be saved
        // as gzip and unpacked as corruption.
        connection.setRequestProperty("Accept-Encoding", "identity")
        return connection
    }

    /**
     * The response's length in bytes, or null when the server does not say.
     *
     * Read from the header rather than through `getContentLengthLong`, which
     * arrived with API 24 — above the level this library supports.
     */
    private fun contentLengthOf(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private companion object {
        const val HTTP_OK = 200
        const val DEFAULT_USER_AGENT = "jengine/1.0"
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
