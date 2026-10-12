// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTailTest {

    private val root = tempDir("tail")

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun replaysAnExistingFileFromItsFirstLineForEveryCollector() = runTest {
        val file = File(root, "run.log").apply { writeText("one\ntwo\nthree\n") }

        assertEquals(listOf("one", "two", "three"), tailLines(file, pollMillis = 1) { true }.toList())
        assertEquals(listOf("one", "two", "three"), tailLines(file, pollMillis = 1) { true }.toList())
    }

    @Test
    fun emitsLinesAsTheyAreAppended() = runTest {
        val file = File(root, "live.log")

        val lines = withContext(Dispatchers.IO) {
            coroutineScope {
                val collected = async { tailLines(file, pollMillis = 5) { false }.take(3).toList() }
                delay(50)
                file.appendText("one\n")
                delay(50)
                file.appendText("two\n")
                delay(50)
                file.appendText("three\n")
                collected.await()
            }
        }

        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun aLineSplitAcrossAppendsIsEmittedOnceWhole() = runTest {
        val file = File(root, "split.log")

        val lines = withContext(Dispatchers.IO) {
            coroutineScope {
                val collected = async { tailLines(file, pollMillis = 5) { false }.take(1).toList() }
                delay(40)
                file.appendText("hel")
                delay(40)
                file.appendText("lo\n")
                delay(40)
                collected.await()
            }
        }

        assertEquals(listOf("hello"), lines)
    }

    @Test
    fun carriageReturnIsDropped() = runTest {
        val file = File(root, "windows.log").apply { writeText("windows\r\nunix\n") }
        assertEquals(listOf("windows", "unix"), tailLines(file, pollMillis = 1) { true }.toList())
    }

    @Test
    fun anUnterminatedLastLineIsFlushedWhenFinished() = runTest {
        val file = File(root, "open.log").apply { writeText("a\nb") }

        // `toList` returning is itself the assertion that the flow completes.
        val lines = tailLines(file, pollMillis = 1) { true }.toList()

        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun theFlowCompletesWhenIsFinishedTurnsTrue() = runTest {
        val file = File(root, "late-finish.log")
        val finished = AtomicBoolean(false)

        val lines = withContext(Dispatchers.IO) {
            coroutineScope {
                val collected = async { tailLines(file, pollMillis = 5) { finished.get() }.toList() }
                delay(40)
                file.appendText("tail-without-newline")
                delay(40)
                finished.set(true)
                collected.await()
            }
        }

        assertEquals(listOf("tail-without-newline"), lines)
    }

    @Test
    fun multibyteCharacterSplitAtTheReadBoundarySurvives() = runTest {
        val file = File(root, "big.log")
        val line = "a".repeat(65535) + "\u00e9" // 'é' is two UTF-8 bytes
        val content = (line + "\n").toByteArray(Charsets.UTF_8)
        assertTrue(content.size > BUFFER, "the read boundary must fall inside the character")

        // The first 64 KiB ends between the two bytes of 'é'.
        file.writeBytes(content.copyOfRange(0, BUFFER))
        file.appendBytes(content.copyOfRange(BUFFER, content.size))

        assertEquals(listOf(line), tailLines(file, pollMillis = 1) { true }.toList())
    }

    @Test
    fun aMissingFileCollectsNothingAndCompletes() = runTest {
        val file = File(root, "never.log")
        assertFalse(file.exists())

        assertEquals(emptyList<String>(), tailLines(file, pollMillis = 1) { true }.toList())
    }

    @Test
    fun aFileCreatedAfterCollectionStartsIsFollowed() = runTest {
        val file = File(root, "later.log")

        val lines = withContext(Dispatchers.IO) {
            coroutineScope {
                val collected = async { tailLines(file, pollMillis = 5) { false }.take(1).toList() }
                delay(40)
                file.appendText("created-late\n")
                delay(40)
                collected.await()
            }
        }

        assertEquals(listOf("created-late"), lines)
    }
}
