# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 lbty <langbiantianya@outlook.com>

# Rules a consuming app's R8 run has to apply, merged from the AAR.
#
# The bridge is reached through JNI, and JNI binds by symbol name: the class and
# its native method must keep the names the C file exports. R8 is otherwise free
# to rename both, and the failure is a run that dies with
# UnsatisfiedLinkError in the consumer's release build only.
-keepclasseswithmembers,allowoptimization class com.langbiantianya.jengine.internal.NativeBridge {
    native <methods>;
}

# The guest process is named by the manifest the AAR contributes, and the
# engine starts and binds it by class name.
-keep class com.langbiantianya.jengine.internal.GuestJvmService { *; }
