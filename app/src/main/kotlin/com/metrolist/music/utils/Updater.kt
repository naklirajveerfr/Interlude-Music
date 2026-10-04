/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/naklirajveerfr/Interlude-Music/refs/heads/main/vrsn.txt"
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
     * Returns the version from the text of vrsn.txt, or null if it isn't a plain version number.
     */
    internal fun parseVersion(text: String): String? =
        text.trim().takeIf { versionRegex.matches(it) }?.removePrefix("v")

    /**
     * Fetch the latest version from vrsn.txt in the Interlude Music repo
     */
    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedReleaseInfo != null && !forceRefresh) {
                    return@runCatching cachedReleaseInfo!!
                }

                val version = parseVersion(client.get(VERSION_URL).bodyAsText())
                    ?: throw IllegalStateException("vrsn.txt does not contain a valid version")

                val releaseInfo = ReleaseInfo(
                    tagName = version,
                    versionName = version,
                    description = "",
                    releaseDate = "",
                    assets = emptyList()
                )

                cachedReleaseInfo = releaseInfo
                lastCheckTime = System.currentTimeMillis()
                releaseInfo
            }
        }

    /**
     * Interlude Music has no release history to list.
     */
    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        Result.success(emptyList())

    /**
     * The Metrolist KMP upgrade prompt is not used.
     */
    suspend fun getLatestKmpRelease(): Result<ReleaseInfo?> = Result.success(null)

    /**
     * Get the page where the new version can be downloaded
     */
    @Suppress("UNUSED_PARAMETER")
    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String = DOWNLOAD_URL

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
