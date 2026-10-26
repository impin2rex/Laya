package com.music.laya.ui

import androidx.compose.ui.graphics.vector.ImageVector
import coil3.request.ImageRequest
import coil3.request.transformations
import com.music.laya.data.LocalGroups
import com.music.laya.data.canvas.AppleArtistArt
import com.music.laya.data.canvas.AppleArtistArtRepository
import com.music.laya.data.canvas.ArtistSignature
import com.music.laya.data.canvas.ArtistSignatureRepository
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasRepository
import com.music.laya.data.canvas.SignatureInkTransformation
import com.music.laya.ui.screens.deviceFolderIcon
import com.music.laya.ui.screens.DetailHost
import kotlinx.coroutines.flow.StateFlow

object AndroidDetailHost : DetailHost {
    override fun cachedArt(name: String): AppleArtistArt? = AppleArtistArtRepository.cached(name)
    override suspend fun artFor(name: String): AppleArtistArt? = AppleArtistArtRepository.artFor(name)
    override val signatureUpdates: StateFlow<Int> get() = ArtistSignatureRepository.updates
    override fun signatureBase(enabled: Boolean, url: String) = ArtistSignatureRepository.effectiveBaseUrl(enabled, url)
    override fun cachedSignature(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.cached(name, base)
    override suspend fun signatureFor(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.signatureFor(name, base)
    override fun ImageRequest.Builder.signatureInk() { transformations(SignatureInkTransformation) }
    override suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? = CanvasRepository.canvasForAlbum(album, artist)
    override fun isLocalArtist(browseId: String) = LocalGroups.isArtist(browseId)
    override fun folderIcon(browseId: String): ImageVector? = deviceFolderIcon(browseId)
}
