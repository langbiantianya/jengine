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

class ArchiveExtractionTest {

    private val root = tempDir("extract")
    private val dest get() = File(root, "dest")

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    // --- round trips, one per format -----------------------------------------

    @Test
    fun roundTripsTarXz() {
        extractAndAssert("tree.tar.xz", treeFixture().toXzBytes())
    }

    @Test
    fun roundTripsTarGz() {
        extractAndAssert("tree.tar.gz", treeFixture().toGzBytes())
    }

    @Test
    fun roundTripsZip() {
        val zip = zipBytes(
            listOf(
                "dir/" to ByteArray(0),
                "dir/nested.txt" to "nested bytes".toByteArray(),
                "empty.bin" to ByteArray(0),
                "top.txt" to "top".toByteArray(),
            ),
        )
        extractAndAssert("tree.zip", zip)
    }

    @Test
    fun extractArchiveIgnoresALyingName() {
        // An xz stream named `.zip`: only the magic bytes may decide.
        val archive = File(root, "runtime.zip").apply { writeBytes(treeFixture().toXzBytes()) }
        extractArchive(archive, dest)
        assertTree(dest)
    }

    // --- names ---------------------------------------------------------------

    @Test
    fun longNamesAndDotSlashNamesLandAtTheRightPath() {
        val longName = "dir/" + "n".repeat(150) + ".txt"
        val archive = File(root, "names.tar.xz").apply {
            writeBytes(
                tar {
                    directory("dir")
                    file(longName, "long".toByteArray())
                    file("./plain.txt", "plain".toByteArray())
                }.toXzBytes(),
            )
        }

        extractArchive(archive, dest)

        assertContentEquals("long".toByteArray(), File(dest, longName).readBytes())
        assertContentEquals("plain".toByteArray(), File(dest, "plain.txt").readBytes())
    }

    @Test
    fun stripComponentsUnwrapsTheTopDirectory() {
        val archive = File(root, "wrapped.tar.xz").apply {
            writeBytes(
                tar {
                    directory("jdk-21")
                    file("jdk-21/release", "JAVA_VERSION=21".toByteArray())
                    file("jdk-21/bin/java", "fake".toByteArray())
                }.toXzBytes(),
            )
        }

        extractArchive(archive, dest, stripComponents = 1)

        assertContentEquals("JAVA_VERSION=21".toByteArray(), File(dest, "release").readBytes())
        assertContentEquals("fake".toByteArray(), File(dest, "bin/java").readBytes())
        assertFalse(File(dest, "jdk-21").exists())
    }

    // --- traversal -----------------------------------------------------------

    @Test
    fun fileEntryThatTraversesIsRefusedAndWritesNothing() {
        val archive = File(root, "file-traversal.tar.xz").apply {
            writeBytes(
                tar {
                    file("../evil.txt", "evil".toByteArray())
                    file("good.txt", "good".toByteArray())
                }.toXzBytes(),
            )
        }

        val failure = assertFailsWith<IOException> { extractArchive(archive, dest) }

        assertTrue(failure.message!!.contains("escapes"), failure.message)
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(dest, "good.txt").exists())
        assertTrue(dest.listFiles()!!.isEmpty())
    }

    @Test
    fun directoryEntryThatTraversesIsRefusedAndCreatesNothing() {
        // A guard around the branch that writes bytes only would pass this and
        // still let `mkdirs` create `root/escape` before the file is refused.
        val archive = File(root, "dir-traversal.tar.xz").apply {
            writeBytes(
                tar {
                    directory("../escape")
                    file("good.txt", "good".toByteArray())
                }.toXzBytes(),
            )
        }

        val failure = assertFailsWith<IOException> { extractArchive(archive, dest) }

        assertTrue(failure.message!!.contains("escapes"), failure.message)
        assertFalse(File(root, "escape").exists())
        assertFalse(File(dest, "good.txt").exists())
    }

    @Test
    fun absoluteNameIsResolvedInsideTheDestination() {
        // On the JVM `File(dest, "/tmp/evil.txt")` is `dest + "/tmp/evil.txt"`,
        // so an absolute entry name is contained, not an escape, and the
        // reader writes it under the destination rather than refusing it.
        val archive = File(root, "absolute.tar.xz").apply {
            writeBytes(tar { file("/tmp/evil.txt", "abs".toByteArray()) }.toXzBytes())
        }

        extractArchive(archive, dest)

        assertContentEquals("abs".toByteArray(), File(dest, "tmp/evil.txt").readBytes())
    }

    // --- symlinks ------------------------------------------------------------

    @Test
    fun symlinkToASiblingIsMaterialisedWhereverItAppears() {
        val archive = File(root, "links.tar.xz").apply {
            writeBytes(
                tar {
                    symlink("before.txt", "target.txt") // target not seen yet
                    file("target.txt", "payload".toByteArray())
                    symlink("after.txt", "target.txt") // target already seen
                }.toXzBytes(),
            )
        }

        extractArchive(archive, dest)

        assertContentEquals("payload".toByteArray(), File(dest, "before.txt").readBytes())
        assertContentEquals("payload".toByteArray(), File(dest, "after.txt").readBytes())
        assertFalse(Files.isSymbolicLink(File(dest, "before.txt").toPath()))
        assertFalse(Files.isSymbolicLink(File(dest, "after.txt").toPath()))
    }

    @Test
    fun symlinkChainResolves() {
        val archive = File(root, "chain.tar.xz").apply {
            writeBytes(
                tar {
                    file("real.txt", "chain".toByteArray())
                    symlink("mid.txt", "real.txt")
                    symlink("top.txt", "mid.txt")
                }.toXzBytes(),
            )
        }

        extractArchive(archive, dest)

        assertContentEquals("chain".toByteArray(), File(dest, "top.txt").readBytes())
    }

    @Test
    fun symlinkOutsideTheDestinationIsSkipped() {
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        val archive = File(root, "escape-link.tar.xz").apply {
            writeBytes(
                tar {
                    file("present.txt", "here".toByteArray())
                    symlink("escape.txt", "../outside.txt")
                }.toXzBytes(),
            )
        }

        extractArchive(archive, dest)

        assertTrue(File(dest, "present.txt").isFile)
        assertFalse(File(dest, "escape.txt").exists())
        assertEquals("outside", outside.readText())
    }

    // --- format sniffing -----------------------------------------------------

    @Test
    fun formatOfSniffsXzUnderAZipName() {
        val archive = File(root, "lying.zip").apply { writeBytes(treeFixture().toXzBytes()) }
        assertEquals(ArchiveFormat.TAR_XZ, formatOf(archive))
    }

    @Test
    fun formatOfRejectsAnEmptyFileWithAnUnknownName() {
        val archive = File(root, "mystery.bin").apply { writeBytes(ByteArray(0)) }
        assertFailsWith<IOException> { formatOf(archive) }
    }

    @Test
    fun formatOfFallsBackToTheNameForAnEmptyKnownName() {
        val archive = File(root, "empty.zip").apply { writeBytes(ByteArray(0)) }
        assertEquals(ArchiveFormat.ZIP, formatOf(archive))
    }

    // --- helpers -------------------------------------------------------------

    private fun treeFixture(): TarFixture = tar {
        directory("dir")
        file("dir/nested.txt", "nested bytes".toByteArray())
        file("empty.bin", ByteArray(0))
        file("top.txt", "top".toByteArray())
    }

    private fun extractAndAssert(name: String, bytes: ByteArray) {
        val archive = File(root, name).apply { writeBytes(bytes) }
        extractArchive(archive, dest)
        assertTree(dest)
    }

    private fun assertTree(directory: File) {
        assertContentEquals("top".toByteArray(), File(directory, "top.txt").readBytes())
        assertContentEquals("nested bytes".toByteArray(), File(directory, "dir/nested.txt").readBytes())
        assertTrue(File(directory, "dir").isDirectory)
        assertTrue(File(directory, "empty.bin").isFile)
        assertEquals(0L, File(directory, "empty.bin").length())
    }
}
