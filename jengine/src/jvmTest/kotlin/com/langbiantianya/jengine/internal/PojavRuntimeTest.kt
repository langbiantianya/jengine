// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PojavRuntimeTest {

    private val root = tempDir("pojav")

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    // --- layer discovery -----------------------------------------------------

    @Test
    fun abiLayersListsOnlyTheAbiMembers() {
        val archive = pojavZip("one.zip", listOf("x86_64" to abiLayer("x86_64", "X86JVM")))
        assertEquals(listOf("x86_64"), pojavAbiLayersIn(archive))
    }

    @Test
    fun abiLayersKeepsTheArchiveOrder() {
        val archive = pojavZip(
            "two.zip",
            listOf(
                "arm64" to abiLayer("arm64", "ARM64JVM"),
                "x86_64" to abiLayer("x86_64", "X86JVM"),
            ),
        )
        assertEquals(listOf("arm64", "x86_64"), pojavAbiLayersIn(archive))
    }

    // --- unpacking -----------------------------------------------------------

    @Test
    fun unpackYieldsAbiLayerFilesAndTheAbiLayerWinsOverlap() {
        val archive = pojavZip("rt.zip", listOf("x86_64" to abiLayer("x86_64", "X86JVM")))
        val dest = File(root, "home")

        unpackPojavRuntime(archive, "x86_64", dest)

        assertContentEquals("X86JVM".toByteArray(), File(dest, "lib/server/libjvm.so").readBytes())
        assertTrue(File(dest, "lib/libfreetype.so.6").isFile)
        // universal-only file survived
        assertContentEquals("modules".toByteArray(), File(dest, "lib/modules").readBytes())
        // present in both layers: the ABI layer's bytes win
        assertContentEquals("x86_64-release".toByteArray(), File(dest, "release").readBytes())
        // the universal layer's symlink is materialised as a copy of its sibling
        assertContentEquals("GPLv2 text".toByteArray(), File(dest, "legal/LICENSE").readBytes())
        assertFalse(Files.isSymbolicLink(File(dest, "legal/LICENSE").toPath()))
    }

    @Test
    fun vmLibraryInFindsTheServerFlavouredJvm() {
        val archive = pojavZip("rt2.zip", listOf("x86_64" to abiLayer("x86_64", "JVM")))
        val dest = File(root, "home")
        unpackPojavRuntime(archive, "x86_64", dest)

        assertEquals(
            File(dest, "lib/server/libjvm.so").canonicalFile,
            vmLibraryIn(dest)!!.canonicalFile,
        )
    }

    @Test
    fun missingAbiLayerFailsNamingTheLayer() {
        val archive = pojavZip("rt3.zip", listOf("x86_64" to abiLayer("x86_64", "JVM")))
        val dest = File(root, "home")

        val failure = assertFailsWith<IOException> { unpackPojavRuntime(archive, "mips", dest) }

        assertTrue(failure.message!!.contains("mips"), failure.message)
    }

    // --- post-extraction fixes ----------------------------------------------

    @Test
    fun prepareRenamesVersionedFreetypeToTheUnversionedName() {
        val archive = pojavZip("rt4.zip", listOf("x86_64" to abiLayer("x86_64", "JVM")))
        val dest = File(root, "home")
        unpackPojavRuntime(archive, "x86_64", dest)
        val versioned = File(dest, "lib/libfreetype.so.6")
        val plain = File(dest, "lib/libfreetype.so")
        assertTrue(versioned.isFile)
        assertFalse(plain.exists())

        preparePojavHome(dest)

        assertTrue(plain.isFile)
        assertContentEquals("FREETYPE".toByteArray(), plain.readBytes())
        assertFalse(versioned.exists())
    }

    @Test
    fun prepareLeavesAnExistingUnversionedFreetypeAlone() {
        val dest = File(root, "home")
        File(dest, "lib/server").mkdirs()
        File(dest, "lib/server/libjvm.so").writeText("jvm") // vmLibraryIn must be non-null
        val versioned = File(dest, "lib/libfreetype.so.6").apply { writeText("VER1") }
        val plain = File(dest, "lib/libfreetype.so").apply { writeText("PLAI") }

        preparePojavHome(dest)

        assertEquals("PLAI", plain.readText())
        assertEquals("VER1", versioned.readText())
    }

    // --- ABI mapping ---------------------------------------------------------

    @Test
    fun androidAbiLayerMapsTheKnownAbis() {
        assertEquals("arm64", androidAbiLayer(listOf("arm64-v8a")))
        assertEquals("arm", androidAbiLayer(listOf("armeabi-v7a")))
        assertEquals("x86", androidAbiLayer(listOf("x86")))
        assertEquals("x86_64", androidAbiLayer(listOf("x86_64")))
    }

    @Test
    fun androidAbiLayerPassesAnUnknownAbiThrough() {
        assertEquals("riscv64", androidAbiLayer(listOf("riscv64")))
        // only the first, most preferred, ABI is consulted
        assertEquals("riscv64", androidAbiLayer(listOf("riscv64", "x86_64")))
    }

    @Test
    fun androidAbiLayerFallsBackForAnEmptyList() {
        assertEquals("x86_64", androidAbiLayer(emptyList()))
    }

    // --- helpers -------------------------------------------------------------

    private fun pojavZip(name: String, layers: List<Pair<String, ByteArray>>): File {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "universal.tar.xz" to universalLayer()
        for ((layer, bytes) in layers) entries += "bin-$layer.tar.xz" to bytes
        return File(root, name).apply { writeBytes(zipBytes(entries)) }
    }

    private fun universalLayer(): ByteArray = tar {
        file("release", "JAVA_VERSION=\"99\"".toByteArray())
        file("lib/modules", "modules".toByteArray())
        file("legal/GPLv2", "GPLv2 text".toByteArray())
        symlink("legal/LICENSE", "GPLv2")
    }.toXzBytes()

    private fun abiLayer(layer: String, jvm: String): ByteArray = tar {
        file("lib/server/libjvm.so", jvm.toByteArray())
        file("lib/libfreetype.so.6", "FREETYPE".toByteArray())
        // overlaps the universal layer: the ABI layer must win
        file("release", "$layer-release".toByteArray())
    }.toXzBytes()
}
