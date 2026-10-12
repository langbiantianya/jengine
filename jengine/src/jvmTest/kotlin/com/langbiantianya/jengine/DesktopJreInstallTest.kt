// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import com.langbiantianya.jengine.internal.JavaFixture
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Installing a published runtime, end to end, and running a JAR on it.
 *
 * The vendor's release host is not reachable from every network — and the
 * published digest is what makes using another one safe — so this serves the
 * archive from a local mirror and lets the engine fetch it from there while it
 * takes the SHA-256, the size and the platform facts from the live Adoptium
 * API. The install therefore fails if the bytes differ from the ones Adoptium
 * published, which is the property the mirror escape hatch rests on.
 *
 * Two things have to be provided for it to run, and it skips without either:
 * the archive itself, by path, and a reachable Adoptium API:
 *
 * ```
 * JENGINE_TEST_JRE_ARCHIVE=/path/to/OpenJDK21U-jre_x64_linux_hotspot_*.tar.gz \
 *     ./gradlew :jengine:jvmTest
 * ```
 */
class DesktopJreInstallTest {

    @Test
    fun installsAPublishedRuntimeFromAMirrorAndRunsAJarOnIt() = runBlocking {
        val archive = System.getenv("JENGINE_TEST_JRE_ARCHIVE")?.let(::File)?.takeIf { it.isFile }
        assumeTrue(
            "set JENGINE_TEST_JRE_ARCHIVE to a published Temurin JRE tarball to run this",
            archive != null,
        )
        assumeTrue("this test drives the linux/x64 archive", isLinuxX64())
        assumeTrue("the Adoptium API is unreachable", adoptiumApiAnswers())

        val mirror = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                // The archive under any path: the engine builds the mirror URL
                // from what the API said, and what this test is checking is that
                // the engine's own URL comes back with the published bytes.
                exchange.sendResponseHeaders(200, archive!!.length())
                exchange.responseBody.use { body ->
                    archive.inputStream().use { it.copyTo(body) }
                }
            }
            start()
        }

        val home = Files.createTempDirectory("jengine-install").toFile()
        val engine = jengine(
            DesktopEngineConfig(
                homeDir = home,
                adoptiumApiBase = ADOPTIUM_API,
                jreDownloadBase = "http://127.0.0.1:${mirror.address.port}",
            ),
        )
        try {
            assertTrue(engine.jres.available().any { it.id == "jre21" }, "Adoptium 21 is not on offer")

            val events = withTimeout(TIMEOUT_MILLIS) { engine.jres.install("jre21").toList() }
            val installed = events.filterIsInstance<JreInstallEvent.Installed>().singleOrNull()
            if (installed == null) {
                val failure = events.filterIsInstance<JreInstallEvent.Failed>().singleOrNull()
                throw AssertionError("install did not succeed: ${failure?.message ?: events}")
            }
            assertEquals(21, installed.jre.spec.majorVersion)
            assertTrue(
                events.filterIsInstance<JreInstallEvent.Progress>().isNotEmpty(),
                "the install reported no progress: $events",
            )

            // Idempotent: the second ask finds the runtime rather than fetching it.
            val again = engine.jres.install("jre21").toList()
            assertIs<JreInstallEvent.AlreadyInstalled>(again.single())

            val runtime = engine.jres.installedJre("jre21")
                ?: throw AssertionError("the runtime is not in installed()")
            val launcher = File(runtime.home, "bin/java")
            assertTrue(launcher.isFile, "no launcher at $launcher")
            assertTrue(launcher.canExecute(), "$launcher is not executable")

            // The unpacked tree is a real JVM, not merely the right shape.
            val version = ProcessBuilder(launcher.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val versionText = version.inputStream.bufferedReader().readText()
            assertEquals(0, version.waitFor(), "the extracted runtime refused to start:\n$versionText")
            assertTrue(versionText.contains("version"), "unexpected -version output:\n$versionText")

            // And the engine runs a JAR on it, which is the whole point.
            val run = engine.run(
                JarRunRequest(
                    jarPath = JavaFixture.jarWithMain(home).absolutePath,
                    args = listOf("print"),
                    jre = JreRef.Managed("jre21"),
                    installJreIfMissing = false,
                ),
            )
            val result = withTimeout(TIMEOUT_MILLIS) { run.result() }
            assertEquals(JarRunResult.Completed(0), result)
            assertTrue(run.output().contains("stdout:hello"), "log was:\n${run.output()}")
        } finally {
            engine.shutdown()
            mirror.stop(0)
            home.deleteRecursively()
        }
    }

    private fun isLinuxX64(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return os.contains("linux") && (arch == "amd64" || arch == "x86_64")
    }

    private fun adoptiumApiAnswers(): Boolean = runCatching {
        URL("$ADOPTIUM_API/v3/info/available_releases").openStream().use { it.readBytes() }
    }.isSuccess

    private companion object {
        const val ADOPTIUM_API = "https://api.adoptium.net"
        const val TIMEOUT_MILLIS = 300_000L
    }
}
