# Third-party licences

jengine itself is GPL-3.0-only — the full text is in `LICENSE`, and the SPDX
header at the top of every source file says so. This file lists the third-party
code it stands on and the licence each piece is under, so a consumer does not
have to resolve a dependency tree to find out.

Nothing third-party is bundled into what this library publishes. The JAR
contains only `com/langbiantianya/jengine`, the AAR's `classes.jar` the same, and
the native library links nothing but the platform's own `liblog`, `libdl`,
`libm` and `libc`. The tables below are therefore about code a consumer's build
resolves, and about tools the build here uses — not about code inside an
artifact.

Versions are the ones the build resolves today; the source of truth for any of
them is `gradle/libs.versions.toml` and the dependency report.

```bash
./gradlew :jengine:dependencies --configuration releaseRuntimeClasspath
```

## Runtime dependencies

These are what a consumer's build pulls in, and they are the only licences that
reach the published POM.

| Dependency | Version | Licence | Where it is used |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | `commonMain`, an `api` dependency: the flows the public API hands out |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` (+ `-core`) | 1.9.0 | Apache-2.0 | `jvmMain`: the Adoptium API's JSON |
| `org.tukaani:xz` | 1.10 | 0BSD | `jvmCommonMain`: the `xz` layer of the published Android runtimes |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10 | Apache-2.0 | transitive, every target |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | transitive, through coroutines |

The `kotlinx-coroutines-bom` and `kotlinx-serialization-bom` entries in the
report are version constraints, not code.

## Test-only dependencies

Resolved by `:jengine:jvmTest` and nothing else; they are not in the published
POM and not in any artifact.

| Dependency | Version | Licence |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-test`, `kotlin-test-junit` | 2.2.10 | Apache-2.0 |
| `junit:junit` | 4.13.2 | EPL-1.0 |
| `org.hamcrest:hamcrest-core` | 1.3 | BSD-3-Clause |

`hamcrest-core` 1.3 is the one entry that cannot be read off its POM: that POM
has no `<licenses>` block at all. The licence above is taken from the
`LICENSE.txt` inside the jar.

## Build-only tools

Not distributed in any form, listed because a build of this project downloads
them.

| Tool | Version | Licence |
|---|---|---|
| Android Gradle Plugin (`com.android.tools.build:gradle`) | 9.4.1 | Apache-2.0 |
| Kotlin Gradle plugin, including the serialization plugin | 2.2.10 | Apache-2.0 |
| Gradle (wrapper distribution) | 9.6.0 | Apache-2.0 |
| Android NDK | r28c | Apache-2.0 |
| CMake (from the NDK's prebuilt toolchain) | 3.22.1 | BSD-3-Clause |

The `jni.h` the bridge includes comes from the NDK's sysroot, not from a JDK:
it is the AOSP header, Apache-2.0, so it carries no Classpath Exception
question with it.

## Runtimes fetched at install time

Not part of this library and never redistributed by it: the engine downloads
these into the app's or the user's own storage when a JAR is run.

| Runtime | Source | Licence |
|---|---|---|
| OpenJDK builds for Android | `MojoLauncher/android-openjdk-build-17-25`, as `jre<major>-pojav.zip` | GPL-2.0 with the Classpath Exception |
| Temurin JREs for the desktop | [Adoptium API](https://api.adoptium.net) | GPL-2.0 with the Classpath Exception (some components under other licences) |

## Licence texts

- Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0>
- BSD-3-Clause — <https://opensource.org/license/bsd-3-clause>
- 0BSD — <https://opensource.org/license/0bsd>
- EPL-1.0 — <https://www.eclipse.org/legal/epl-v10.html>
- GPL-2.0 with the Classpath Exception — <https://openjdk.org/legal/gplv2+ce.html>
- GPL-3.0-only (this project) — `LICENSE`
