/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val description: String,
    val releaseDate: String,
    val assets: List<ReleaseAsset>
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val architecture: String,
    val variant: String // "foss" or "gms"
)

object Updater {
    private val client = HttpClient()
    var lastCheckTime = -1L
        private set

    private var cachedReleaseInfo: ReleaseInfo? = null

    private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 60 * 1000L // 2 hours
    private const val LATEST_URL =
        "https://api.github.com/repos/naklirajveerfr/Interlude-Music/releases/latest"
    private const val RELEASES_URL =
        "https://api.github.com/repos/naklirajveerfr/Interlude-Music/releases?per_page=50"
    const val DOWNLOAD_URL = "https://github.com/naklirajveerfr/Interlude-Music/releases/"

    private val versionRegex = Regex("""v?\d+(\.\d+)*""")

    /**
     * Compares two version strings.
     * Returns: 1 if v1 > v2, -1 if v1 < v2, 0 if equal
     */
    fun compareVersions(v1: String, v2: String): Int {
        val v1Parts = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val v2Parts = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val part1 = v1Parts.getOrNull(i) ?: 0
            val part2 = v2Parts.getOrNull(i) ?: 0
            when {
                part1 > part2 -> return 1
                part1 < part2 -> return -1
            }
        }
        return 0
    }

    /**
     * Checks if the latest version is newer than the current version.
     * Returns true if an update is available (latestVersion > currentVersion)
     */
    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
        return compareVersions(latestVersion, currentVersion) > 0
    }

    /**
     * Returns the version from a release tag, or null if it isn't a plain version number.
     */
    internal fun parseVersion(text: String): String? =
        text.trim().takeIf { versionRegex.matches(it) }?.removePrefix("v")

    /**
     * Fetch the latest version from the latest GitHub release tag
     */
    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedReleaseInfo != null && !forceRefresh) {
                    return@runCatching cachedReleaseInfo!!
                }

                val release = JSONObject(
                    client.get(LATEST_URL) {
                        header("Accept", "application/vnd.github+json")
                    }.bodyAsText()
                )
                val version = parseVersion(release.optString("tag_name"))
                    ?: throw IllegalStateException("Latest GitHub release has no valid version tag")

                val assetsJson = release.optJSONArray("assets") ?: JSONArray()
                val assets = (0 until assetsJson.length())
                    .map { assetsJson.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    .map {
                        val name = it.getString("name")
                        ReleaseAsset(
                            name = name,
                            downloadUrl = it.getString("browser_download_url"),
                            size = it.optLong("size"),
                            architecture = "universal",
                            variant = when {
                                name.contains("Cast", ignoreCase = true) -> "gms"
                                name.contains("izzy", ignoreCase = true) -> "izzy"
                                else -> "foss"
                            }
                        )
                    }

                val releaseInfo = ReleaseInfo(
                    tagName = version,
                    versionName = version,
                    description = release.optString("body"),
                    releaseDate = release.optString("published_at"),
                    assets = assets
                )

                cachedReleaseInfo = releaseInfo
                lastCheckTime = System.currentTimeMillis()
                releaseInfo
            }
        }

    private var cachedReleases: List<ReleaseInfo>? = null

    /**
     * Fetch the release history from the GitHub releases of Interlude Music.
     */
    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                cachedReleases?.takeIf { !forceRefresh }?.let { return@runCatching it }

                val body = client.get(RELEASES_URL) {
                    header("Accept", "application/vnd.github+json")
                }.bodyAsText()
                val array = JSONArray(body)
                val releases = (0 until array.length())
                    .map { array.getJSONObject(it) }
                    .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                    .map {
                        val tag = it.getString("tag_name")
                        ReleaseInfo(
                            tagName = tag,
                            versionName = tag.removePrefix("v"),
                            description = it.optString("body"),
                            releaseDate = it.optString("published_at"),
                            assets = emptyList()
                        )
                    }
                    .sortedWith { a, b -> compareVersions(b.tagName, a.tagName) }

                cachedReleases = releases
                releases
            }
        }

    /**
     * The Metrolist KMP upgrade prompt is not used.
     */
    suspend fun getLatestKmpRelease(): Result<ReleaseInfo?> = Result.success(null)

    /**
     * The APK of the release that matches the installed variant (foss or gms)
     */
    fun getApkAssetForCurrentVariant(releaseInfo: ReleaseInfo): ReleaseAsset? {
        val variant = if (BuildConfig.CAST_AVAILABLE) "gms" else "foss"
        return releaseInfo.assets.firstOrNull { it.variant == variant }
    }

    /**
     * Direct APK link for the installed variant, or the releases page if the release has none
     */
    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String =
        getApkAssetForCurrentVariant(releaseInfo)?.downloadUrl ?: DOWNLOAD_URL

    /**
     * Check if update is needed (respects 2-hour cache)
     */
    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Check if we should fetch (2 hour interval)
                val shouldFetch = forceRefresh ||
                    (System.currentTimeMillis() - lastCheckTime) > CHECK_INTERVAL_MILLIS

                if (!shouldFetch && cachedReleaseInfo != null) {
                    val hasUpdate = isUpdateAvailable(
                        BuildConfig.BASE_VERSION_NAME,
                        cachedReleaseInfo!!.versionName
                    )
                    return@runCatching cachedReleaseInfo!! to hasUpdate
                }

                val result = getLatestRelease(forceRefresh = true)
                if (result.isSuccess) {
                    val releaseInfo = result.getOrThrow()
                    val hasUpdate = isUpdateAvailable(
                        BuildConfig.BASE_VERSION_NAME,
                        releaseInfo.versionName
                    )
                    releaseInfo to hasUpdate
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error")
                }
            }
        }

    /**
     * Get the latest release info (cached)
     */
    fun getCachedLatestRelease(): ReleaseInfo? = cachedReleaseInfo
}
