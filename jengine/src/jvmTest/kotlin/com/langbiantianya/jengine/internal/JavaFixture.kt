// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import java.io.File
import java.io.FileOutputStream
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * The JAR the desktop tests run, compiled from source the first time it is
 * asked for.
 *
 * From source rather than committed as bytes, because what it does is what the
 * tests are about: it prints what it can see of the JVM it landed in — its
 * properties, its environment, its working directory, its maximum heap — leaves
 * a non-daemon thread behind when asked to, and can end itself with
 * `System.exit(7)`.
 */
internal object JavaFixture {

    /** The JAR, with a `Main-Class` in its manifest. */
    fun jarWithMain(workDirectory: File): File = build(workDirectory, mainClass = "Probe")

    /** The same classes with no `Main-Class`, for the manifest-less case. */
    fun jarWithoutMain(workDirectory: File): File = build(workDirectory, mainClass = null)

    private fun build(workDirectory: File, mainClass: String?): File {
        val root = File(workDirectory, "fixture").apply { mkdirs() }
        val classes = File(root, "classes").apply { deleteRecursively(); mkdirs() }
        val source = File(root, "Probe.java").apply { writeText(SOURCE) }

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("no JDK compiler on this machine; these tests need a JDK, not a JRE")
        val exit = compiler.run(
            null, null, null,
            "--release", "17",
            "-d", classes.absolutePath,
            source.absolutePath,
        )
        check(exit == 0) { "javac refused the fixture:\n$SOURCE" }

        val jar = File(root, if (mainClass == null) "fixture-nomain.jar" else "fixture.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (mainClass != null) mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }
        JarOutputStream(FileOutputStream(jar), manifest).use { output ->
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                output.putNextEntry(JarEntry(file.relativeTo(classes).path))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return jar
    }

    private val SOURCE = """
        public final class Probe {
            public static void main(String[] args) throws Exception {
                String mode = args.length > 0 ? args[0] : "print";
                if (mode.equals("lingering")) {
                    // Returns from main with a non-daemon thread running, which is
                    // what a web application's main does: `java -jar` would stay up
                    // here, and so does this.
                    Thread thread = new Thread(() -> {
                        try { Thread.sleep(600000); } catch (InterruptedException ignored) { }
                    });
                    thread.setDaemon(false);
                    thread.start();
                    return;
                }
                System.out.println("stdout:hello");
                System.err.println("stderr:hello");
                System.out.println("prop=" + System.getProperty("jengine.test", "unset"));
                System.out.println("env=" + System.getenv().getOrDefault("JENGINE_TEST_ENV", "unset"));
                System.out.println("dir=" + System.getProperty("user.dir"));
                System.out.println("home=" + System.getProperty("user.home"));
                System.out.println("max=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
                StringBuilder rest = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (rest.length() > 0) rest.append(',');
                    rest.append(args[i]);
                }
                System.out.println("args=" + rest);
                System.out.flush();
                if (mode.equals("exit")) {
                    System.exit(7);
                }
            }
        }
    """.trimIndent()
}
