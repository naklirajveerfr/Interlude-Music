/*
 * Adapted from ArchiveTune (github.com/rukamori/ArchiveTune), GPL-3.0.
 * © Rukamori — original notice retained per GPL-3.0 Sections 4 & 5.
 */

package com.metrolist.music.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

enum class CanvasSource {
    BETTER_LYRICS,
    TIDAL,
    ALL;

    fun accepts(provider: CanvasSource): Boolean = provider != ALL && (this == ALL || this == provider)

    companion object {
        fun fromPreference(value: String?): CanvasSource =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}

@Serializable
data class CanvasArtwork(
    val name: String? = null,
    val artist: String? = null,
    @SerialName("albumId") val albumId: String? = null,
    val albumName: String? = null,
    val static: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val videoUrl: String? = null,
    val videoUrlVertical: String? = null,
) {
    val animationUrl: String? get() = animated ?: videoUrl
    val verticalAnimationUrl: String? get() = animatedVertical ?: videoUrlVertical
}

/** True when the provider's result is really the requested song, not just a fuzzy search hit. */
fun CanvasArtwork.matchesSong(song: String, artist: String): Boolean {
    val wantedSong = song.toSongIdentity()
    val wantedArtist = artist.toPrimaryArtistIdentity()
    return wantedSong.isNotEmpty() &&
        wantedArtist.isNotEmpty() &&
        name?.toSongIdentity() == wantedSong &&
        this.artist?.toPrimaryArtistIdentity() == wantedArtist
}

private fun String.toSongIdentity(): String =
    replace(FeaturingRegex, "").replace(PresentationRegex, "").toIdentity()

private fun String.toPrimaryArtistIdentity(): String =
    split(ArtistSeparatorRegex, limit = 2).firstOrNull().orEmpty().toIdentity()

private fun String.toIdentity(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(CombiningMarkRegex, "")
        .lowercase(Locale.ROOT)
        .replace(SeparatorRegex, " ")
        .trim()

private val FeaturingRegex =
    Regex("""\s*[\[(](?:feat\.?|ft\.?|featuring|with)\s+[^\])]*[\])]""", RegexOption.IGNORE_CASE)
private val PresentationRegex =
    Regex(
        """\s*(?:[-–—:]\s*)?[\[(]?(?:official\s+)?(?:music\s+)?(?:lyric\s+video|video|audio|lyrics?|visualizer|visualiser)[\])]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
private val ArtistSeparatorRegex =
    Regex("""(?:\s*,\s*|\s*&\s*|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+)""", RegexOption.IGNORE_CASE)
private val CombiningMarkRegex = Regex("\\p{M}+")
private val SeparatorRegex = Regex("[^\\p{L}\\p{N}]+")
