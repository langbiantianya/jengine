// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.langbiantianya.jengine.JvmOptions

/**
 * The VM arguments a run starts with: the platform's own, with the caller's
 * merged over them.
 *
 * Merging is by key rather than by appending, because two `-Xmx` values on one
 * command line are not a conflict a JVM reports — the last one silently wins —
 * and because a caller setting `os.name` should replace the engine's rather
 * than follow it. The order is then the order a JVM wants: the platform's
 * arguments first, then the caller's, then whatever the caller appended
 * verbatim.
 */
internal fun mergeJvmArgs(platformDefaults: List<String>, options: JvmOptions): List<String> {
    val heap = ArrayList<String>(2)
    options.initialHeapMb?.let { heap += "-Xms${it}M" }
    options.heapMb?.let { heap += "-Xmx${it}M" }

    val overridden = (heap + options.systemProperties.keys.map { "-D$it" } + options.extraArgs)
        .map { jvmArgKey(it) }
        .toSet()

    val merged = ArrayList<String>(
        platformDefaults.size + heap.size + options.systemProperties.size + options.extraArgs.size,
    )
    platformDefaults.filterTo(merged) { jvmArgKey(it) !in overridden }
    merged += heap
    options.systemProperties.forEach { (key, value) -> merged += "-D$key=$value" }
    merged += options.extraArgs
    return merged
}

/**
 * What an argument is about, for the purpose of one replacing another.
 *
 * `-Dkey=value` is about `key`, `-Xmx512M` is about the maximum heap, and
 * `-XX:+UseSerialGC` is about `UseSerialGC` — so a caller can turn the engine's
 * serializer collector off by asking for the opposite flag rather than by
 * adding a second one beside it. Anything else is its own key.
 */
internal fun jvmArgKey(arg: String): String = when {
    arg.startsWith("-XX:") -> {
        val body = arg.substring(4)
        val name = body.substringBefore('=')
        "-XX:" + name.removePrefix("+").removePrefix("-")
    }

    arg.startsWith("-Xmx") || arg.startsWith("-Xms") ||
        arg.startsWith("-Xss") || arg.startsWith("-Xmn") -> arg.take(4)

    arg.startsWith("-D") -> arg.substringBefore('=').substring(2).let { "-D$it" }

    else -> arg
}

/**
 * Environment entries as `KEY=VALUE`, which is how they cross the JNI boundary
 * on Android, and how a process environment is expressed on a desktop.
 *
 * A malformed entry — no `=` at all — is rejected here rather than silently
 * becoming a variable named after the whole string.
 */
internal fun environmentEntries(environment: Map<String, String>): List<String> =
    environment.map { (key, value) ->
        require(key.isNotBlank() && !key.contains('=')) {
            "environment variable names must be non-blank and free of '=': '$key'"
        }
        "$key=$value"
    }
