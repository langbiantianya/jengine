// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine

import java.io.File

/**
 * What a desktop engine is made of: where it keeps everything, what a run falls
 * back to, and where runtimes come from.
 *
 * @property homeDir the directory the engine owns. Created on demand, never by
 *   the config itself, so that constructing a config — which a caller may do to
 *   read it back — has no side effects on disk.
 * @property defaultJvm the VM options every run starts from. A run request's
 *   own options are merged over these; see [JEngine.run].
 * @property defaultJreId the runtime a run without one of its own uses, such as
 *   `jre21`. Null asks for the newest installed runtime instead.
 * @property adoptiumApiBase root of the Adoptium release API. Overridable so a
 *   test or an internal mirror can answer in its place.
 * @property jreDownloadBase where the runtime archive itself is fetched from,
 *   when that is not the release host the Adoptium API names. Empty means "use
 *   the link the API returned". A non-empty value is the escape hatch for a
 *   network the release host cannot be reached from: the archive is then built
 *   as `{jreDownloadBase}/{major}/jre/{arch}/{os}/{name}`, the layout the
 *   Adoptium file mirrors publish, while the SHA-256 and size still come from
 *   the API response — so a mirror that serves different bytes is rejected
 *   rather than installed.
 */
public data class DesktopEngineConfig(
    val homeDir: File = File(System.getProperty("user.home") ?: ".", ".jengine"),
    val defaultJvm: JvmOptions = JvmOptions(),
    val defaultJreId: String? = null,
    val adoptiumApiBase: String = "https://api.adoptium.net",
    val jreDownloadBase: String = "",
)
