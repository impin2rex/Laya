package com.music.laya.data.canvas

import androidx.compose.ui.graphics.Color
import com.music.laya.ui.theme.ArtworkKeyColors

/**
 * What Apple Music's artist page shows at the top: a hero photograph, usually
 * the artist's title logo drawn over it as a separate image, and for some
 * artists a looping video that stands in for the photograph.
 *
 * [logoUrl] is a transparent PNG, null for the many artists without one;
 * [logoAspect] is its width over its height, so the caller can size it without
 * waiting for the image to decode. [videoUrl] is an HLS playlist.
 *
 * [background] and [accent] are Apple's own colours for the photograph (as
 * ARGB), so the page can be tinted without decoding the image to find them.
 */
data class AppleArtistArt(
    val heroUrl: String,
    val logoUrl: String?,
    val logoAspect: Float,
    val videoUrl: String?,
    val background: Int,
    val accent: Int,
    /** The colour Apple fills the artist's Play button with; absent for some artists. */
    val keyColor: Int?,
)

/** Apple's colours for this artist, in the form the palette takes. */
fun AppleArtistArt.keyColors() = ArtworkKeyColors(Color(background), Color(accent))

data class ArtistSignature(
    val imageUrl: String,
    val aspect: Float,
    /** Lowercased asset format (`svg`, `png`, …); "" when unknown. */
    val format: String = "",
)
