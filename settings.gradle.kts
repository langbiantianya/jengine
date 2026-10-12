// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenCentral()
        gradlePluginPortal()
        // Mirrors, tried last: this machine reaches Central directly, and a
        // mirror that answers first only adds a round trip when it does.
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // Where this library is published (see jitpack.yml), so a checkout can
        // consume a released version of itself. Filtered to that one group: an
        // unfiltered JitPack entry makes Gradle ask it about every dependency
        // Central cannot serve, and JitPack answers a lookup by starting a
        // build.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.langbiantianya(\\..*)?") }
        }
    }
}

rootProject.name = "jengine"
include(":jengine")
