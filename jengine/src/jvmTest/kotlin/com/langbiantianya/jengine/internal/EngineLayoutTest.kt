// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineLayoutTest {

    private val root = tempDir("layout")
    private val layout = EngineLayout(File(root, "runtime"), File(root, "work"))

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun runtimesLogsAndWorkingDirectoriesSitWhereDocumented() {
        assertEquals(File(File(root, "runtime"), "jre"), layout.jreRoot)
        assertEquals(File(layout.jreRoot, "jre21"), layout.jreHome("jre21"))
        assertEquals(File(File(root, "work"), "logs"), layout.logsDir)
        assertEquals(File(layout.logsDir, "run-1.log"), layout.logFile("run-1"))
        assertEquals(File(File(root, "work"), "jars"), layout.jarsDir)
        assertEquals(File(layout.jarsDir, "hello_world"), layout.workDir("hello world.jar"))
    }

    @Test
    fun runHomeIsUnderTheJreRootAndDoesNotEscapeIt() {
        val home = layout.jreHome("../evil")
        assertEquals(layout.jreRoot, home.parentFile)
    }

    @Test
    fun distinctLabelsGetDistinctWorkingDirectories() {
        val labels = listOf("app.jar", "hello world.jar", "hello-world.jar", "run_two.JAR")
        val directories = labels.map { layout.workDir(it).canonicalPath }
        assertEquals(labels.size, directories.toSet().size)
    }

    @Test
    fun ensureDirectoriesCreatesTheRoots() {
        layout.ensureDirectories()
        assertTrue(layout.jreRoot.isDirectory)
        assertTrue(layout.logsDir.isDirectory)
        assertTrue(layout.jarsDir.isDirectory)
    }
}
