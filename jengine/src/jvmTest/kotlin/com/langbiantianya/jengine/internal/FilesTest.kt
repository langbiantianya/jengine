// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {

    private val root = tempDir("files")

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun safeFileNameReducesToOneSafeSegment() {
        assertEquals("x", safeFileName("../x"))
        assertEquals("b", safeFileName("a/b"))
        assertEquals("b", safeFileName("a\\b"))
        assertEquals(FALLBACK_JAR_NAME, safeFileName(".."))
        assertEquals(FALLBACK_JAR_NAME, safeFileName(""))
        assertEquals("hello_world.jar", safeFileName("hello world.jar"))
    }

    @Test
    fun safeFileNameNeverYieldsAnEmptyOrMultiSegmentName() {
        val names = listOf("../x", "a/b", "a\\b", "..", "", "   ", "@@@", "///", "..\\..\\x", "./.")
        for (name in names) {
            val safe = safeFileName(name)
            assertTrue(safe.isNotEmpty(), "reduced '$name' to nothing")
            assertFalse(safe.contains('/'), "'$name' kept a '/'")
            assertFalse(safe.contains('\\'), "'$name' kept a '\\'")
        }
    }

    @Test
    fun safeFileNameKeepsNonAsciiLetters() {
        val safe = safeFileName("ünïcode file.jar")
        assertTrue(safe.isNotEmpty())
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertTrue(safe.endsWith(".jar"))
    }

    @Test
    fun safeJarStemDropsAJarSuffix() {
        assertEquals("app", safeJarStem("app.jar"))
        assertEquals("hello_world", safeJarStem("hello world.jar"))
        assertEquals("evil", safeJarStem("../evil.jar"))
        assertEquals("weird.txt", safeJarStem("weird.txt"))
        assertEquals("APP", safeJarStem("APP.JAR"))
    }

    @Test
    fun writeTextAtomicallyOverwritesAndLeavesNoTemporary() {
        val target = File(root, "result.txt")

        writeTextAtomically(target, "first")
        assertEquals("first", target.readText())
        assertFalse(File(root, "result.txt.tmp").exists())

        writeTextAtomically(target, "second")
        assertEquals("second", target.readText())
        assertEquals(listOf("result.txt"), root.list()!!.toList())
    }

    @Test
    fun deleteTreeRemovesANestedTreeAndReportsMissingAsDone() {
        val tree = File(root, "tree")
        File(tree, "a/b").mkdirs()
        File(tree, "a/b/c.txt").writeText("c")
        File(tree, "top.txt").writeText("t")

        assertTrue(deleteTree(tree))
        assertFalse(tree.exists())

        assertTrue(deleteTree(File(root, "missing")))
        assertTrue(deleteTree(null))
    }

    @Test
    fun replaceDirectoryReplacesAPopulatedDestination() {
        val from = File(root, "staging")
        File(from, "sub").mkdirs()
        File(from, "a.txt").writeText("A")
        File(from, "sub/b.txt").writeText("B")

        val to = File(root, "home")
        to.mkdirs()
        File(to, "old.txt").writeText("OLD")

        replaceDirectory(from, to)

        assertEquals("A", File(to, "a.txt").readText())
        assertEquals("B", File(to, "sub/b.txt").readText())
        assertFalse(File(to, "old.txt").exists())
        assertFalse(from.exists())
    }

    @Test
    fun checkReadableNamesWhatIsMissing() {
        val missing = File(root, "absent")
        val failure = kotlin.test.assertFailsWith<java.io.IOException> { checkReadable(missing, "archive") }
        assertTrue(failure.message!!.contains("archive"))

        val present = File(root, "present").apply { writeText("x") }
        checkReadable(present, "archive") // does not throw
    }
}
