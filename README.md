# jengine
[![](https://jitpack.io/v/langbiantianya/jengine.svg)](https://jitpack.io/#langbiantianya/jengine)   
A library that runs JAR files — on Android and on the desktop — from Kotlin
Multiplatform code, and gives the caller the run's log, its exit status and the
knobs to shape the JVM it runs in.

It exists because "run a JAR" means two different things on the two platforms.
On a desktop it is a child process and the JVM you already have; on Android
there is no `java` to execute, ART cannot consume a class file, and an app may
not run a binary it unpacked into its own storage — so the JVM has to be
*loaded* into the app's process through JNI, from a runtime built for Android.
`jengine` owns both paths and hides the difference behind one API.

| What a caller asks for | Android | Desktop JVM |
|---|---|---|
| Run a JAR, with arguments and an overridable main class | guest JVM in its own process, entered through JNI | `bin/java` as a child process |
| Read the run's output, line by line or whole | the guest's stdout/stderr redirected to the log file by descriptor | both pipes appended to the same log file |
| Know how it ended | exit status, and whether `main` returned while the guest kept serving | the process's exit status |
| Stop a run | ends the guest process (there is no other way) | `destroy()`, then `destroyForcibly()` |
| Shape the JVM: heap, `-D`, extra flags, environment | VM options passed to `JNI_CreateJavaVM` | the same arguments on the command line |
| Fetch a runtime | MojoLauncher's OpenJDK-for-Android archives, per ABI | Adoptium/Temurin JREs, for the host OS and architecture |
| Run on a runtime the caller already has | a JRE home with `lib/server/libjvm.so` | a JRE home, or just the `java` launcher |

Targets: `androidTarget()` (minSdk 23) and `jvm()` (class files 11). Nothing
else — a JAR needs a JVM, and a phone and a desktop are where those are.

## Install

Releases are published through [JitPack](https://jitpack.io), which builds them
from this repository's tags. A consumer needs one coordinate — the module — and
Gradle picks the variant that matches the target:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.langbiantianya(\\..*)?") }
        }
    }
}

// the module that uses it
dependencies {
    implementation("com.github.langbiantianya.jengine:jengine:1.0.0")
}
```

A Kotlin Multiplatform project resolves the right variant for each target
automatically: the AAR for an Android target, the JAR for a `jvm` one. Java-only
Android projects can depend on the same coordinate; a call site then has to pass
what Kotlin's default arguments would have filled in, as in
`AndroidEngineKt.jengine(context, new AndroidEngineConfig())`.

Either target on its own can be named instead: `…:jengine-android:1.0.0` is the
AAR and `…:jengine-jvm:1.0.0` the JAR. What *not* to use is the coordinate
JitPack prints on its own page, `com.github.langbiantianya:jengine:1.0.0`: it is
a module JitPack synthesises over every module in the build, so it depends on
the JAR and the AAR at once and a consumer of one platform fails on the other
platform's variants.

A checkout can also build the same artifacts for itself:
`./gradlew :jengine:publishToMavenLocal` writes them into `~/.m2/repository`
under `com.langbiantianya.jengine:jengine:1.0.0`, which is the coordinate to
depend on if you are working from a local build rather than a release.

## Android

```kotlin
import com.langbiantianya.jengine.*

val engine = jengine(context)                    // one per app is enough

val run = engine.run(JarRunRequest(jarPath = jar.absolutePath))

// Everything the JAR prints, as it prints it.
run.lines.collect { line -> console.append(line.text + "\n") }

// How it went.
when (val result = run.result()) {
    is JarRunResult.Completed -> log("exited with ${result.exitCode}")
    is JarRunResult.StillRunning -> log("main returned, the guest is still serving")
    is JarRunResult.Stopped -> log("stopped")
    is JarRunResult.Crashed -> log("the guest died: ${result.detail}")
    is JarRunResult.Failed -> log("could not run: ${result.message}")
}
```

A first run downloads the runtime it needs, which is a few hundred megabytes;
watch that separately to show progress, or pre-install it:

```kotlin
engine.jres.install("jre21").collect { event ->
    when (event) {
        is JreInstallEvent.Progress -> progress.value = event.fraction
        is JreInstallEvent.Installed -> log("ready at ${event.jre.home}")
        is JreInstallEvent.Failed -> log("install failed: ${event.message}")
        else -> Unit
    }
}
```

What the library puts in the app's manifest: `INTERNET` (the runtime download
and everything the JAR connects to), `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_SPECIAL_USE`, the guest service itself in a process of its
own (`:jengine`), and `allowNativeHeapPointerTagging="false"` — the guest JVM
does not survive pointer tagging. Those merge from the AAR; nothing has to be
declared by hand.

Worth knowing before shipping:

- **A 64-bit guest needs a 64-bit host process.** The runtime follows the
  device's primary ABI; on a 32-bit-only device the guest is 32-bit and its heap
  is correspondingly small. Ship every ABI you intend to run on.
- **Start a run while the app is in the foreground.** Android refuses a
  foreground service started from the background, and running the guest as a
  foreground service is what keeps a serving JAR serving while the user is
  elsewhere.
- **Ask for `POST_NOTIFICATIONS`** if you want the run's notification to be
  visible on Android 13 and up; the run itself does not need the permission.
- **`ACCESS_LOCAL_NETWORK`** (Android 17 / API 37 and up) is needed for a JAR
  that serves requests from other devices rather than only from the phone.
- **`System.exit` ends the guest process**, which is what a JVM is supposed to
  do; the exit status is recorded on the way out and reported as
  `Completed(exitCode)`. The next run gets a fresh process and so a fresh VM.
- **One run at a time per engine.** A JVM accumulates state that can be set
  once — `URL.setURLStreamHandlerFactory` is the one that trips a Spring Boot
  app — so the process is the unit of reuse, exactly as each `java -jar` is.
  A second `run` while one is active throws `RunAlreadyActiveException`.

## Desktop

```kotlin
val engine = jengine()                            // ~/.jengine

engine.jres.installNow("jre21")                   // or point at your own JRE:

val run = engine.run(
    JarRunRequest(
        jarPath = "/opt/app/server.jar",
        args = listOf("--port", "8080"),
        jre = JreRef.Home(System.getProperty("java.home")),
        jvm = JvmOptions(heapMb = 1024, systemProperties = mapOf("spring.profiles.active" to "prod")),
    ),
)

run.lines.collect { println(it.text) }
val result = run.result()
```

A caller that knows the launcher rather than the home directory can name it
directly: `JreRef.JavaExecutable("/usr/lib/jvm/21/bin/java")`.

Runtimes come from the [Adoptium API](https://api.adoptium.net), which names a
Temurin build for the host OS and architecture; the download is verified against
the SHA-256 and size the API published before anything is unpacked, and it lands
in `<homeDir>/jre/<id>`. If the release host is unreachable from your network —
GitHub is, from some — point the download at a mirror:

```kotlin
jengine(DesktopEngineConfig(jreDownloadBase = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium"))
```

The digest still comes from the API, so a mirror cannot serve different bytes.
A runtime you already have needs none of this: `JreRef.Home` and
`JreRef.JavaExecutable` skip the download entirely, and
`JarRunRequest(installJreIfMissing = false)` turns a missing runtime into a
`JreNotInstalledException` instead of a download.

## The API

| Type | What it is for |
|---|---|
| `jengine(config)` | The entry point. One per app; it owns a home directory, the runtimes in it and at most one run. |
| `JEngine.run(request)` | Starts a run and answers as soon as the guest is being started, so output can be collected from the first line. |
| `JEngine.activeRun()` | The run in flight. On Android this also adopts a guest that outlived the app's process. |
| `JEngine.stop()` / `shutdown()` / `close()` | Ends the active run; `shutdown` also releases the engine. |
| `JarRun` | One run: `state`, `isRunning`, `lines`, `output()`, `logFile`, `result()`, `stop()`. |
| `JarRunRequest` | What to run, on what, with what. |
| `JvmOptions` | Heap, `-D` properties, extra arguments, environment; `heapMb`/`initialHeapMb` accept null to keep the platform's own sizing. |
| `JreRef` | Which runtime: `Managed(id)`, `Home(path)`, `JavaExecutable(path)` (desktop only). |
| `JreRepository` | `available()`, `installed()`, `install(id)` as a flow with progress, `installNow(id)`, `remove(id)`, `resolve(ref)`, `defaultSpec()`. |
| `JarRunResult` | `Completed(exitCode)`, `StillRunning`, `Stopped`, `Crashed`, `Failed`. |

Both platforms run the same JAR the same way: the manifest's `Main-Class` and
`Class-Path` are read on the host, so a JAR that needs its siblings works
whether or not it is run through `-jar`. The run's working directory defaults to
`<homeDir>/jars/<jar name>`, reused between runs so the data a JAR writes
survives a restart; on Android that is under the app's *external* files
directory when there is one, so its files can also be reached over USB. The log
of a run is `<homeDir>/logs/<runId>.log`, and the run's id names it, so a log on
disk is self-describing.

## Runtimes this library fetches

| Where | Ids | Source |
|---|---|---|
| Android | `jre17`, `jre21`, `jre25` | `MojoLauncher/android-openjdk-build-17-25` (rolling release), as `jre<major>-pojav.zip`: a `universal.tar.xz` layer plus one `bin-<abi>.tar.xz` per ABI, unpacked on top of each other |
| Desktop | `jre<feature>` per the Adoptium API | Temurin JREs, `.tar.gz` or `.zip` for the host OS and architecture |

The runtime a JAR needs is a class file version question: ≤ 61 for Java 17,
≤ 65 for Java 21, ≤ 69 for Java 25. On Android the releases published for the
device's own ABI are the ones offered, and `jre25` has no 32-bit x86 build. No
runtime is redistributed by this library: it fetches the vendor's archive into
the app's own storage at install time.

## Tests

```bash
./gradlew :jengine:jvmTest          # everything that needs no network
```

The suite covers the parts a JAR's life depends on: tar/zip/xz extraction
(including archives that try to escape the destination, by file entry and by
directory entry, and the symlinks the published runtimes are full of), the
argument merge that decides what the VM is started with, log tailing across
appends and read boundaries, the downloader against a real HTTP server
(progress, digest mismatch, redirects, no partial file left behind), the Android
runtime archive layout, and the desktop engine end to end — a fixture JAR
compiled on the spot, run through the public API on this machine's JVM, with its
output, exit status, environment, working directory and `stop()` asserted.

Installing a *published* runtime needs the network and an archive, so it skips
without both:

```bash
JENGINE_TEST_JRE_ARCHIVE=/path/to/OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz \
    ./gradlew :jengine:jvmTest
```

That test serves the archive from a local mirror while taking the digest from
the live Adoptium API, so it also proves the mirror path.

The Android half needs a device to be meaningful — the guest JVM cannot be
loaded on a desktop, because the runtime is built against Bionic. What runs
everywhere is `:jengine:assembleDebug`, which compiles the bridge for every ABI
and packages it with the merged manifest.

## Publishing

A release is a tag. JitPack checks the tag out, builds it from source on the
first request for it, and serves the result from then on:

```bash
git tag 1.0.0
git push origin 1.0.0
```

`jitpack.yml` describes the environment that build needs — the JDK that runs
Gradle; the Android platform, NDK and CMake the native bridge is compiled with
are already in the image. JitPack runs `./gradlew build publishToMavenLocal`
with the coordinates it will serve the build under, as `-Pgroup` and
`-Pversion`, and then serves what those installed — which is why
`jengine/build.gradle.kts` takes its group and version from those properties
rather than from a constant. Build status and log per version are on
<https://jitpack.io/#langbiantianya/jengine>.

The same thing locally:

```bash
./gradlew :jengine:publishToMavenLocal     # com.langbiantianya.jengine:jengine:1.0.0
```

That is the development coordinate, without JitPack's `com.github.` prefix; the
two are the same artifacts built from the same commits. `gradle.properties` sets
`android.builtInKotlin=false` and `android.newDsl=false` because AGP 9 refuses
the classic library plugin next to Kotlin Multiplatform, and this module ships
native code — the `.so` that loads the guest JVM — which only the classic
`externalNativeBuild` DSL knows how to build. Those are AGP's own compatibility
switches, not a workaround of this project's devising.

Two kinds of warning a build prints are expected rather than pending work, and
neither is suppressed because a real one should stay visible:

- AGP's deprecation notices about those two switches (they say the same thing
  the paragraph above does, and they are the signal for when AGP 10 removes the
  escape hatch).
- lint's notes about the API 34 foreground-service constant — a compile-time int
  that is inlined and guarded, and matched against the type the manifest
  declares — and about `allowNativeHeapPointerTagging`, which only exists from
  API 30 and is ignored below it.

## Licence

GPL-3.0, because this is a derived work: the Android half is the JVM machinery
of [runJar](https://github.com/langbiantianya/runJar), which is GPL-3.0, and the
guest-runtime recipe — the runtime's library path, the preload pass, the signal
reset, the freetype rename — comes from
[MojoLauncher](https://github.com/MojoLauncher) and PojavLauncher before it.
The runtimes this library downloads are not part of it and carry their own
licences: OpenJDK (GPLv2 with the Classpath Exception), fetched at install time.

`GPL-3.0-only` is the SPDX identifier at the top of every source file, and the
one the published POM declares. The third-party code this library and its build
depend on, with the licence of each piece, is listed in `THIRD-PARTY.md`.