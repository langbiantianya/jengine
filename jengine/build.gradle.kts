// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.langbiantianya.jengine"
version = "1.0.0"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            // The shared `java.*` code below is compiled for Android as well, so
            // it is held to the API level a phone actually has rather than to
            // the daemon's JDK.
            freeCompilerArgs.add("-Xjdk-release=11")
        }
        withSourcesJar(publish = true)
    }

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Everything here is JVM-family code — `java.io`, `java.net`, the JDK's
        // zip and tar readers — shared by the two targets. The Android and
        // desktop halves differ in how a JAR is launched and where its runtime
        // comes from, not in what an archive or a log file is.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("jvmMain").dependsOn(jvmCommonMain)

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        jvmCommonMain.dependencies {
            implementation(libs.tukaani.xz)
        }
        getByName("jvmMain").dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("jvmTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.langbiantianya.jengine"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23

        // R8 keeps a class whose name the native side looks up, and the engine's
        // own service, which the merged manifest names. Without this the release
        // build of a consuming app has a bridge it cannot find.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The published coordinates say what they are under. A POM with no licence
// block leaves every consumer to guess, and for a copyleft library a wrong
// guess is discovered late and expensively. This applies to every publication
// the multiplatform plugin creates — the JVM jar and the Android AAR alike.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            licenses {
                license {
                    name.set("GPL-3.0-only")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    distribution.set("repo")
                }
            }
        }
    }
}
