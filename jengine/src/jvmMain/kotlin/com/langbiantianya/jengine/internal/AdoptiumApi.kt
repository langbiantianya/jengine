// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 lbty <langbiantianya@outlook.com>

package com.langbiantianya.jengine.internal

import com.langbiantianya.jengine.JreSpec
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The Adoptium release API, as this engine needs to see it.
 *
 * Only two calls are made: the list of releases that exist, and the latest JRE
 * asset for one release on one platform. A JDK release is a set of files with a
 * checksum each, so the API is consulted once per install rather than per
 * runtime, and the archive itself — a few hundred megabytes — is fetched by
 * [HttpDownloader] with the checksum the API published.
 *
 * `HttpURLConnection` rather than a newer client for the same reason the
 * downloader uses it, and the JSON is read with `ignoreUnknownKeys` because
 * Adoptium adds fields between releases and an engine should not stop working
 * because a response grew a key it does not read.
 */
internal open class AdoptiumApi(
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
) {

    /**
     * The Java major versions Adoptium publishes a JRE for, oldest first.
     *
     * This is the one call in the repository that needs the network, and a
     * failure is allowed to propagate: `available()` is the call a chooser
     * makes because it wants to know what can be fetched, so a caller that
     * cannot reach the API has to hear about it.
     */
    open suspend fun availableReleases(): List<Int> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v3/info/available_releases"
        val response = json.decodeFromString<AvailableReleases>(get(url))
        response.releases.sorted()
    }

    /**
     * The latest JRE asset for [majorVersion] on [os]/[arch], as the API
     * describes it, or a failure naming the release and platform when it
     * publishes none.
     */
    open suspend fun latestAsset(majorVersion: Int, os: String, arch: String): AdoptiumAsset =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/v3/assets/latest/$majorVersion/hotspot" +
                "?architecture=$arch&image_type=jre&os=$os&vendor=eclipse"
            val assets = json.decodeFromString<List<AssetResponse>>(get(url))
            val asset = assets.firstOrNull()
                ?: throw IOException("Adoptium publishes no JRE $majorVersion for $os/$arch")
            val published = asset.binary.pkg
            val major = asset.version.major.takeIf { it > 0 } ?: majorVersion
            AdoptiumAsset(
                spec = adoptiumJreSpec(
                    id = "jre$major",
                    majorVersion = major,
                    downloadSizeBytes = published.size.takeIf { it > 0 },
                ),
                downloadUrl = published.link,
                fileName = published.name,
                sha256 = published.checksum.takeIf { it.isNotBlank() },
            )
        }

    /** Fetches [url] and returns its body, or throws with the HTTP status. */
    private fun get(url: String): String {
        val connection = URL(url).openConnection()
        if (connection !is HttpURLConnection) throw IOException("not an HTTP URL: $url")
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val status = connection.responseCode
            if (status != HTTP_OK) throw IOException("HTTP $status fetching $url")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val USER_AGENT = "jengine/1.0"
    }
}

/** A runtime asset the API described, ready to be downloaded and unpacked. */
internal data class AdoptiumAsset(
    val spec: JreSpec,
    val downloadUrl: String,
    val fileName: String,
    val sha256: String?,
)

@Serializable
private data class AvailableReleases(
    @SerialName("available_releases") val releases: List<Int> = emptyList(),
)

/**
 * One entry of `/v3/assets/latest/{major}/hotspot`.
 *
 * Only the parts an install uses are modelled: the archive's link, name, size
 * and checksum, and the release it belongs to. Everything else — the other
 * assets, the source and scm metadata — is ignored by the parser.
 */
@Serializable
private data class AssetResponse(
    val binary: Binary = Binary(),
    val version: Version = Version(),
) {
    @Serializable
    data class Binary(
        @SerialName("package") val pkg: Package = Package(),
    )

    @Serializable
    data class Package(
        val link: String = "",
        val name: String = "",
        val size: Long = 0,
        val checksum: String = "",
    )

    @Serializable
    data class Version(val major: Int = 0)
}
