// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

/**
 * What the guest JVM is started with.
 *
 * The engine adds whatever a JAR needs to run at all on this platform — a class
 * path, the runtime's own directories on Android, headless AWT — and these are
 * the knobs on top of that. Where a value here collides with one of the
 * engine's, the caller's wins: the arguments are merged by key, so
 * `systemProperties["os.name"]` replaces the engine's `Linux` rather than
 * appearing beside it, and a second `-Xmx` never reaches the VM.
 *
 * @property heapMb the guest's maximum heap, or null to size it the way the
 *   platform would: on Android a deliberately small 256 MB, which is what a
 *   phone can spare and more than a JAR that only prints needs; on a desktop
 *   the JVM's own default, so that a JAR behaves as it does under `java -jar`.
 * @property initialHeapMb the guest's initial heap, or null to leave it to the
 *   platform. Android has a default of its own here, 16 MB: reserving the whole
 *   heap up front is what a phone cannot afford.
 * @property systemProperties `-D` properties for the guest, in order.
 * @property extraArgs further VM arguments, in order, appended last — after
 *   the engine's and after everything derived from the fields above, so that an
 *   argument repeated here is the one a JVM sees last and therefore the one it
 *   uses. Arguments that would decide what runs rather than how — `-jar`, `-cp`,
 *   `-classpath`, `-m` — are rejected, because the engine decides that from the
 *   request.
 * @property environment environment variables for the guest process. On Android
 *   this is the process the guest VM lives in, which exists for this run alone.
 */
public data class JvmOptions(
    public val heapMb: Int? = null,
    public val initialHeapMb: Int? = null,
    public val systemProperties: Map<String, String> = emptyMap(),
    public val extraArgs: List<String> = emptyList(),
    public val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(heapMb == null || heapMb > 0) { "heapMb must be positive, was $heapMb" }
        require(initialHeapMb == null || initialHeapMb > 0) {
            "initialHeapMb must be positive, was $initialHeapMb"
        }
        require(initialHeapMb == null || heapMb == null || initialHeapMb <= heapMb) {
            "initialHeapMb ($initialHeapMb) is larger than heapMb ($heapMb)"
        }
        val deciding = extraArgs.firstOrNull { arg ->
            DECIDING_ARGS.any { arg == it || arg.startsWith("$it=") || arg.startsWith("--$it=") }
        }
        require(deciding == null) {
            "'$deciding' decides which class or archive runs; the request does that and " +
                "extraArgs only shapes the VM"
        }
        require(!extraArgs.any { it.isBlank() }) { "extraArgs must not contain a blank argument" }
    }

    public companion object {
        private val DECIDING_ARGS = listOf("-jar", "-cp", "-classpath", "-m", "-Djava.class.path")

        /** No options of its own: the engine's defaults, nothing more. */
        public val DEFAULT: JvmOptions = JvmOptions()

        /** Options for a guest that may want room, with [heapMb] reserved up front. */
        public fun of(heapMb: Int): JvmOptions = JvmOptions(heapMb = heapMb, initialHeapMb = heapMb)
    }
}
