// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import com.langbiantianya.jengine.internal.JavaFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * The desktop half end to end, on a real JVM: a real JAR, started by the
 * engine's own runner, on the JVM running these tests.
 *
 * Nothing here is faked — the fixture is compiled from Java source by this
 * JDK, packed into a JAR, and run through [JEngine.run] with a runtime the
 * caller names, which is also the answer for a caller that already has a JRE:
 * no install, no download. These run in a second or two each, so they are the
 * tests that run everywhere; the published-runtime install is
 * `DesktopJreInstallTest`, which needs a fixture of its own.
 */
class DesktopEngineRunTest {

    private val hostHome: String get() = System.getProperty("java.home")

    @Test
    fun runsAJarAndCollectsItsOutput() = runBlocking {
        withEngine { engine ->
            val run = engine.run(runRequest("print"))

            assertEquals(JarRunResult.Completed(0), withTimeout(TIMEOUT_MILLIS) { run.result() })

            assertEquals(RunState.FINISHED, run.state.value)
            val text = run.output()
            assertTrue(text.contains("stdout:hello"), "log was:\n$text")
            assertTrue(text.contains("stderr:hello"), "log was:\n$text")
            assertTrue(File(run.logFile).isFile, "no log file at ${run.logFile}")
        }
    }

    @Test
    fun aRunIsOverOnlyWhenItsProcessIs() = runBlocking {
        withEngine { engine ->
            val run = engine.run(runRequest("lingering"))
            waitUntil { run.state.value == RunState.RUNNING }
            delay(750)

            assertEquals(RunState.RUNNING, run.state.value)
            assertTrue(run.isRunning)

            val result = withTimeout(TIMEOUT_MILLIS) { run.stop() }
            assertTrue(result is JarRunResult.Stopped, "stopping a live run reported $result")
            assertEquals(RunState.FINISHED, run.state.value)
        }
    }

    @Test
    fun reportsTheExitStatusTheJarChose() = runBlocking {
        withEngine { engine ->
            val run = engine.run(runRequest("exit"))

            assertEquals(JarRunResult.Completed(7), withTimeout(TIMEOUT_MILLIS) { run.result() })
        }
    }

    @Test
    fun passesJvmOptionsAndEnvironmentToTheGuest() = runBlocking {
        withEngine { engine ->
            val run = engine.run(
                runRequest("print").copy(
                    jvm = JvmOptions(
                        heapMb = 192,
                        initialHeapMb = 32,
                        systemProperties = mapOf("jengine.test" to "from-the-caller"),
                        environment = mapOf("JENGINE_TEST_ENV" to "from-the-caller"),
                    ),
                ),
            )

            withTimeout(TIMEOUT_MILLIS) { run.result() }
            val text = run.output()
            assertTrue(text.contains("prop=from-the-caller"), "log was:\n$text")
            assertTrue(text.contains("env=from-the-caller"), "log was:\n$text")
            assertTrue(text.contains("max=192"), "the guest's heap was not 192 MB; log was:\n$text")
        }
    }

    @Test
    fun passesArgumentsThrough() = runBlocking {
        withEngine { engine ->
            val run = engine.run(runRequest("print", "a b", "c"))

            withTimeout(TIMEOUT_MILLIS) { run.result() }
            assertTrue(run.output().contains("args=a b,c"), "log was:\n${run.output()}")
        }
    }

    @Test
    fun runsInADirectoryOfItsOwnNamedAfterTheJar() = runBlocking {
        withEngine { engine ->
            val jar = fixtureJar
            val run = engine.run(runRequest("print"))

            withTimeout(TIMEOUT_MILLIS) { run.result() }
            val expected = File(engine.homeDir, "jars/${jar.name.removeSuffix(".jar")}")
            assertTrue(expected.isDirectory, "no working directory at $expected")
            assertTrue(run.output().contains("dir=${expected.absolutePath}"), "log was:\n${run.output()}")
        }
    }

    @Test
    fun everyCollectorSeesTheWholeLog() = runBlocking {
        withEngine { engine ->
            val run = engine.run(runRequest("print"))
            withTimeout(TIMEOUT_MILLIS) { run.result() }

            val first = run.lines.toList()
            val second = run.lines.toList()

            assertEquals(first.map { it.text }, second.map { it.text }, "the log does not replay the same way twice")
            assertEquals(first.indices.map { it.toLong() }, first.map { it.seq })
            assertTrue(first.any { it.text.contains("stdout:hello") }, "lines were: $first")
        }
    }

    @Test
    fun runsOnTheJreTheCallerNames() = runBlocking {
        withEngine { engine ->
            assumeTrue("no JRE at $hostHome", File(hostHome, "bin/java").isFile)

            val run = engine.run(
                JarRunRequest(
                    jarPath = fixtureJar.absolutePath,
                    args = listOf("print"),
                    jre = JreRef.Home(hostHome),
                    installJreIfMissing = false,
                ),
            )

            assertEquals(JarRunResult.Completed(0), withTimeout(TIMEOUT_MILLIS) { run.result() })
        }
    }

    @Test
    fun runsOnAnExplicitJavaLauncher() = runBlocking {
        withEngine { engine ->
            val launcher = File(File(hostHome, "bin"), "java")
            assumeTrue("no launcher at $launcher", launcher.isFile)

            val run = engine.run(
                JarRunRequest(
                    jarPath = fixtureJar.absolutePath,
                    args = listOf("print"),
                    jre = JreRef.JavaExecutable(launcher.absolutePath),
                    installJreIfMissing = false,
                ),
            )

            assertEquals(JarRunResult.Completed(0), withTimeout(TIMEOUT_MILLIS) { run.result() })
        }
    }

    @Test
    fun refusesWhatItCannotRun() = runBlocking {
        withEngine { engine ->
            assertFailsWith<JarNotFoundException> {
                engine.run(JarRunRequest(jarPath = File(engine.homeDir, "absent.jar").absolutePath))
            }
            assertFailsWith<MainClassNotFoundException> {
                engine.run(JarRunRequest(jarPath = JavaFixture.jarWithoutMain(tempDirectory()).absolutePath))
            }
            assertFailsWith<JreNotInstalledException> {
                engine.run(
                    JarRunRequest(
                        jarPath = fixtureJar.absolutePath,
                        jre = JreRef.Managed("jre-does-not-exist"),
                        installJreIfMissing = false,
                    ),
                )
            }
        }
    }

    @Test
    fun hostsOneRunAtATimeAndFreesTheNext() = runBlocking {
        withEngine { engine ->
            val request = JarRunRequest(
                jarPath = fixtureJar.absolutePath,
                args = listOf("lingering"),
                jre = JreRef.Home(hostHome),
                installJreIfMissing = false,
            )
            val active = engine.run(request)
            waitUntil { active.state.value == RunState.RUNNING }
            assertFailsWith<RunAlreadyActiveException> { engine.run(request) }

            withTimeout(TIMEOUT_MILLIS) { active.stop() }
            assertEquals(null, engine.activeRun())

            val next = engine.run(request.copy(args = listOf("print")))
            assertEquals(JarRunResult.Completed(0), withTimeout(TIMEOUT_MILLIS) { next.result() })
            assertTrue(next.id != active.id, "two runs shared the id ${active.id}")
        }
    }

    // ---------------------------------------------------------------------

    /**
     * A request that runs the fixture on this machine's own JVM.
     *
     * Naming the runtime is the honest thing for a test to do — a desktop
     * engine has no runtime of its own until one is installed, and installing
     * one is the subject of `DesktopJreInstallTest` — and it is also the API a
     * caller that already has a JRE uses.
     */
    private fun runRequest(vararg args: String): JarRunRequest = JarRunRequest(
        jarPath = fixtureJar.absolutePath,
        args = args.toList(),
        jre = JreRef.Home(hostHome),
        installJreIfMissing = false,
    )

    private suspend fun withEngine(block: suspend (JEngine) -> Unit) {
        val home = tempDirectory()
        val engine = jengine(DesktopEngineConfig(homeDir = home))
        try {
            block(engine)
        } finally {
            engine.shutdown()
            home.deleteRecursively()
        }
    }

    private fun tempDirectory(): File = Files.createTempDirectory("jengine-test").toFile()

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MILLIS) {
            while (!condition()) delay(25)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L

        /**
         * Built once for the whole test JVM. Compiling it is a second of work,
         * and every test runs the same JAR anyway; the run directory it works
         * in is what differs, and that belongs to the engine, not the fixture.
         */
        val fixtureJar: File by lazy {
            JavaFixture.jarWithMain(File(System.getProperty("java.io.tmpdir")))
        }
    }
}
