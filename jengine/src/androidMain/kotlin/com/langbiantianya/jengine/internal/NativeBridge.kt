// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

/**
 * Binding for the native bridge in `src/androidMain/cpp/jni_bridge.c`.
 *
 * The native side owns one guest JavaVM per process: OpenJDK permits a single
 * VM per process and its shutdown is a no-op on the Android port, so a run is
 * "invoke main and collect what it printed", not "start and stop a VM". The
 * class and this method keep exactly these names because JNI binds by symbol
 * name; `consumer-rules.pro` is what stops R8 from renaming them.
 */
internal object NativeBridge {

    /**
     * Ensures the guest VM exists, then invokes `mainClass.main(appArgs)` on it.
     *
     * Everything the guest writes to stdout and stderr is redirected to
     * [outPath] — appending — before the VM is touched, so the caller can tail
     * the file; the VM also writes its exit status to `"$outPath.exit"` when a
     * JAR calls `System.exit`.
     *
     * [environment] entries are `KEY=VALUE`, applied before the VM is created.
     * [nativeLibDir] is the app's own native library directory, which goes on
     * the guest's linker search path alongside the runtime's. [workDir] becomes
     * the process's working directory — and so the VM's `user.dir`, which is
     * what relative paths in the JAR resolve against — and must already exist.
     *
     * @return `OK` when main returned and left nothing running,
     *   `OK:STILL_RUNNING` when it returned but the guest is still serving, and
     *   `ERROR <message>` when the run could not be made to work.
     */
    external fun nativeBootstrap(
        jvmPath: String,
        javaHome: String,
        nativeLibDir: String,
        vmArgs: Array<String>,
        appArgs: Array<String>,
        mainClass: String,
        workDir: String,
        outPath: String,
        environment: Array<String>,
    ): String

    fun load() {
        System.loadLibrary("jengine_jni")
    }
}
