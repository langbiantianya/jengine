# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 lbty <langbiantianya@outlook.com>

cmake_minimum_required(VERSION 3.22.1)
project(jengine_jni C)

# The guest JVM bridge: it dlopens an OpenJDK runtime built for Android and asks
# it for a JavaVM through the JNI invocation API. One VM per process, started on
# the calling thread, which is why the run happens in a process of its own.
#
# The library is packaged into the AAR for every ABI the published runtimes
# cover; the runtime the engine installs follows the device's own ABI.
add_library(jengine_jni SHARED
        jni_bridge.c
        log_bridge.c)

target_compile_options(jengine_jni PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(jengine_jni ${log-lib} dl)
