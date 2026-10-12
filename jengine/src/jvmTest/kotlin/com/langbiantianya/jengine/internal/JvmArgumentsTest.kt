// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.langbiantianya.jengine.JvmOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmArgumentsTest {

    // --- merging -------------------------------------------------------------

    @Test
    fun callerPropertyReplacesThePlatformDefaultOfTheSameKey() {
        val merged = mergeJvmArgs(
            listOf("-Dos.name=Linux", "-Dfile.encoding=UTF-8"),
            JvmOptions(systemProperties = mapOf("os.name" to "Windows")),
        )

        assertEquals(listOf("-Dfile.encoding=UTF-8", "-Dos.name=Windows"), merged)
        assertEquals(1, merged.count { jvmArgKey(it) == "-Dos.name" })
    }

    @Test
    fun heapOptionsReplaceThePlatformHeapDefaults() {
        val merged = mergeJvmArgs(
            listOf("-Xms16M", "-Xmx256M"),
            JvmOptions(heapMb = 512, initialHeapMb = 64),
        )

        assertEquals(listOf("-Xms64M", "-Xmx512M"), merged)
    }

    @Test
    fun oppositeXxFlagReplacesThePlatformFlag() {
        val merged = mergeJvmArgs(
            listOf("-XX:+UseSerialGC", "-XX:+UseG1GC"),
            JvmOptions(extraArgs = listOf("-XX:-UseSerialGC")),
        )

        // The same key is replaced, an unrelated `-XX:` flag survives.
        assertEquals(listOf("-XX:+UseG1GC", "-XX:-UseSerialGC"), merged)
    }

    @Test
    fun orderIsDefaultsThenHeapThenPropertiesThenExtraArgs() {
        val merged = mergeJvmArgs(
            listOf("-server", "-Dos.arch=x86_64", "-Xmx4096M", "-XX:+UseSerialGC"),
            JvmOptions(
                heapMb = 512,
                initialHeapMb = 256,
                systemProperties = linkedMapOf("a" to "1", "b" to "2"),
                extraArgs = listOf("-XX:+UseZGC"),
            ),
        )

        assertEquals(
            listOf(
                "-server",
                "-Dos.arch=x86_64",
                "-XX:+UseSerialGC",
                "-Xms256M",
                "-Xmx512M",
                "-Da=1",
                "-Db=2",
                "-XX:+UseZGC",
            ),
            merged,
        )
    }

    // --- JvmOptions validation ----------------------------------------------

    @Test
    fun initialHeapAboveMaximumIsRejected() {
        assertFailsWith<IllegalArgumentException> { JvmOptions(heapMb = 256, initialHeapMb = 512) }
    }

    @Test
    fun nonPositiveHeapIsRejected() {
        assertFailsWith<IllegalArgumentException> { JvmOptions(heapMb = 0) }
        assertFailsWith<IllegalArgumentException> { JvmOptions(heapMb = -1) }
        assertFailsWith<IllegalArgumentException> { JvmOptions(initialHeapMb = 0) }
    }

    @Test
    fun blankExtraArgumentIsRejected() {
        assertFailsWith<IllegalArgumentException> { JvmOptions(extraArgs = listOf("-Xmx1G", "  ")) }
        assertFailsWith<IllegalArgumentException> { JvmOptions(extraArgs = listOf("")) }
    }

    @Test
    fun decidingArgumentsAreRejected() {
        for (arg in listOf("-jar", "-cp", "-classpath", "-m")) {
            assertFailsWith<IllegalArgumentException>("expected '$arg' to be rejected") {
                JvmOptions(extraArgs = listOf(arg))
            }
        }
        assertFailsWith<IllegalArgumentException> { JvmOptions(extraArgs = listOf("-cp=/tmp/a.jar")) }
        assertFailsWith<IllegalArgumentException> { JvmOptions(extraArgs = listOf("-m=mod")) }
    }

    @Test
    fun shapingArgumentsAreAccepted() {
        assertEquals(
            listOf("-XX:+UseZGC"),
            JvmOptions(extraArgs = listOf("-XX:+UseZGC")).extraArgs,
        )
        assertEquals(JvmOptions(heapMb = 256, initialHeapMb = 256), JvmOptions.of(256))
    }

    // --- environment ---------------------------------------------------------

    @Test
    fun environmentEntriesFormatValidPairs() {
        assertEquals(listOf("A=1", "B=2"), environmentEntries(linkedMapOf("A" to "1", "B" to "2")))
    }

    @Test
    fun environmentEntriesRejectAMalformedKey() {
        assertFailsWith<IllegalArgumentException> { environmentEntries(mapOf("A=B" to "value")) }
        assertFailsWith<IllegalArgumentException> { environmentEntries(mapOf("" to "value")) }
        assertFailsWith<IllegalArgumentException> { environmentEntries(mapOf("   " to "value")) }
    }

    @Test
    fun keyOfAnArgument() {
        assertContains(jvmArgKey("-Dos.name=Linux"), "os.name")
        assertEquals("-Xmx", jvmArgKey("-Xmx512M"))
        assertEquals("-XX:UseSerialGC", jvmArgKey("-XX:-UseSerialGC"))
        assertEquals("-server", jvmArgKey("-server"))
    }
}
