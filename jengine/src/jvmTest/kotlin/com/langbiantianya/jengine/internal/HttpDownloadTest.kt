// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A JVM-only test: the downloader is exercised against a real `HttpServer`
 * bound to a loopback port, so no external network is needed.
 */
class HttpDownloadTest {

    private val root = tempDir("http")
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var base: String

    private val body = "the quick brown fox jumps over the lazy dog\n".repeat(40).toByteArray()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/ok") { exchange -> respond(exchange, 200, body) }
        server.createContext("/badsha") { exchange -> respond(exchange, 200, body) }
        server.createContext("/notfound") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$base/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/nolength") { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked: no Content-Length
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
        root.deleteRecursively()
    }

    @Test
    fun downloadsWithLengthAndDigestReportingMonotonicProgress() {
        val target = File(root, "out.bin")
        val progress = mutableListOf<DownloadProgress>()

        HttpDownloader().download(
            "$base/ok",
            target,
            expectedSha256 = sha256Hex(body),
            expectedSize = body.size.toLong(),
            onProgress = { progress += it },
        )

        assertContentEquals(body, target.readBytes())
        assertFalse(File(root, "out.bin.part").exists())
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next.bytesRead >= previous.bytesRead })
        assertEquals(body.size.toLong(), progress.last().bytesRead)
        assertEquals(body.size.toLong(), progress.last().totalBytes)
        assertTrue(progress.all { it.totalBytes == body.size.toLong() })
    }

    @Test
    fun wrongDigestFailsNamingBothDigestsAndLeavesNoFile() {
        val target = File(root, "out.bin")
        val actual = sha256Hex(body)
        val expected = "0".repeat(64)

        val failure = assertFailsWith<IOException> {
            HttpDownloader().download("$base/badsha", target, expectedSha256 = expected)
        }

        assertTrue(failure.message!!.contains(actual), failure.message)
        assertTrue(failure.message!!.contains(expected), failure.message)
        assertFalse(target.exists())
        assertFalse(File(root, "out.bin.part").exists())
    }

    @Test
    fun wrongSizeFailsNamingBothSizesAndLeavesNoFile() {
        val target = File(root, "out.bin")
        val expected = body.size.toLong() + 7

        val failure = assertFailsWith<IOException> {
            HttpDownloader().download("$base/ok", target, expectedSize = expected)
        }

        assertTrue(failure.message!!.contains(expected.toString()), failure.message)
        assertTrue(failure.message!!.contains(body.size.toString()), failure.message)
        assertFalse(target.exists())
        assertFalse(File(root, "out.bin.part").exists())
    }

    @Test
    fun notFoundFailsNamingTheStatus() {
        val target = File(root, "out.bin")

        val failure = assertFailsWith<IOException> {
            HttpDownloader().download("$base/notfound", target)
        }

        assertTrue(failure.message!!.contains("404"), failure.message)
        assertFalse(target.exists())
    }

    @Test
    fun aNonHttpUrlFails() {
        val target = File(root, "out.bin")

        val failure = assertFailsWith<IOException> {
            HttpDownloader().download("file:///tmp/definitely-not-a-url", target)
        }

        assertTrue(failure.message!!.contains("not an HTTP URL"), failure.message)
        assertFalse(target.exists())
    }

    @Test
    fun aRedirectIsFollowed() {
        val target = File(root, "out.bin")

        HttpDownloader().download("$base/redirect", target)

        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun aResponseWithoutContentLengthStillDownloads() {
        val target = File(root, "out.bin")
        val progress = mutableListOf<DownloadProgress>()

        HttpDownloader().download("$base/nolength", target, onProgress = { progress += it })

        assertContentEquals(body, target.readBytes())
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it.totalBytes == null })
        assertEquals(body.size.toLong(), progress.last().bytesRead)
    }

    private fun respond(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digits = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }
}
