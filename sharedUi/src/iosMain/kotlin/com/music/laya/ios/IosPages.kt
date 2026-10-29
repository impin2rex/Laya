package com.music.laya.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.music.laya.sharedui.resources.listening_history
import com.music.laya.sharedui.resources.settings
import com.music.laya.ui.components.thumbnailBorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.laya.data.YtMusicRepository
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.music.laya.data.model.Account
import com.music.laya.data.model.SPOTIFY_MISSING_PREFIX
import com.music.laya.data.model.SPOTIFY_PENDING_PREFIX
import com.music.laya.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.laya.data.spotify.SpotifyImporter
import com.music.laya.data.spotify.SpotifyLibrary
import com.music.laya.data.spotify.SpotifyTrack
import com.music.laya.sharedui.resources.spotify_empty_tracks
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.getString
import com.music.laya.sharedui.resources.no_internet_connection
import com.music.laya.sharedui.resources.something_went_wrong
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import com.music.laya.sharedui.resources.Res
import com.music.laya.data.settings.AppSettings
import com.music.laya.sharedui.resources.connected
import com.music.laya.sharedui.resources.sign_out
import com.music.laya.sharedui.resources.account_profiles_help
import com.music.laya.sharedui.resources.brand_account
import com.music.laya.sharedui.resources.add_account
import com.music.laya.sharedui.resources.accounts
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.fillMaxWidth
import com.music.laya.sharedui.resources.spotify
import com.music.laya.sharedui.resources.prioritize_spotify_canvas
import com.music.laya.sharedui.resources.prioritize_spotify_canvas_subtitle
import com.music.laya.sharedui.resources.spotify_canvas_auto_hide
import com.music.laya.sharedui.resources.spotify_canvas_auto_hide_subtitle
import com.music.laya.sharedui.resources.spotify_connect_subtitle
import com.music.laya.sharedui.resources.spotify_disconnect
import com.music.laya.sharedui.resources.spotify_logo
import com.music.laya.sharedui.resources.tap_to_connect
import com.music.laya.ui.screens.AccountCard
import com.music.laya.ui.screens.DestructiveRow
import com.music.laya.ui.screens.SettingsGroup
import com.music.laya.ui.screens.SettingsRow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.DetailPage
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.ui.utils.guardSheetFromContentTouches
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** A pushed page: an album / artist / playlist, or a mood / genre's playlists. */
internal sealed interface IosPage {
    class Detail(val page: DetailPage) : IosPage
    class Mood(val item: MoodGenre, val shelves: UiState<List<HomeShelf>>) : IosPage
    data object Settings : IosPage
    data object Account : IosPage
    data object Spotify : IosPage
    data object Equalizer : IosPage
    data object Sources : IosPage
    data object ListenTogether : IosPage
    data object Replay : IosPage
    class History(val songs: UiState<List<Song>>) : IosPage
    class ShowAll(val shelf: HomeShelf) : IosPage
}

/** YouTube browse ids are prefixed by kind (Android's MainViewModel.browseTypeOf, minus the device-local kinds). */
private fun browseTypeOf(id: String, fallback: BrowseType) = when {
    id.startsWith("UC") -> BrowseType.ARTIST
    id.startsWith("MPREb") || id.startsWith("VLOLAK") || id.startsWith("OLAK") -> BrowseType.ALBUM
    id.startsWith("VL") || id.startsWith("PL") -> BrowseType.PLAYLIST
    else -> fallback
}

internal fun loadingPage(browseId: String, title: String, subtitle: String, thumb: String?, type: BrowseType) =
    DetailPage(browseId, title, subtitle, thumb, UiState.Loading, browseTypeOf(browseId, type))

/** Android's MainViewModel.friendly: a dropped connection reads "No internet connection", not NSURLError's dump. */
internal suspend fun friendly(e: Throwable): String = when {
    generateSequence(e) { it.cause }.any { it is DarwinHttpRequestException || it is HttpRequestTimeoutException } ->
        getString(Res.string.no_internet_connection)
    else -> e.message ?: getString(Res.string.something_went_wrong)
}

/**
 * Fills a [loadingPage] — the same two calls MainViewModel.openDetail makes.
 * ponytail: first page of tracks only (openDetail follows the continuation), no device-local or Spotify pages.
 */
internal suspend fun loadDetail(base: DetailPage): DetailPage {
    val fallback = base.thumbnailUrl
    fun List<Song>.withArt(art: String?) = if (art == null) this else map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = art) else it }
    return if (base.type == BrowseType.ARTIST) {
        YtMusicRepository.artistPage(base.browseId).fold({ p ->
            base.copy(
                songs = if (p.songs.isEmpty()) UiState.Error("No tracks here") else UiState.Success(p.songs.withArt(fallback ?: p.thumbnailUrl)),
                sections = p.sections,
                thumbnailUrl = p.thumbnailUrl ?: fallback,
                title = p.name ?: base.title,
                description = p.description,
                subscriberCountText = p.subscriberCountText,
                monthlyListenerCount = p.monthlyListenerCount,
            )
        }, { base.copy(songs = UiState.Error(friendly(it))) })
    } else {
        YtMusicRepository.browseSongs(base.browseId).fold({ p ->
            val header = p.header
            base.copy(
                songs = if (p.songs.isEmpty()) UiState.Error("No tracks here") else UiState.Success(p.songs.withArt(fallback ?: header?.thumbnailUrl)),
                suggestedSongs = p.suggested.withArt(fallback ?: header?.thumbnailUrl),
                continuation = p.continuation,
                description = p.description,
                title = if (base.title.isBlank()) header?.title ?: base.title else base.title,
                subtitle = if (header != null && header.subtitle.isNotBlank() &&
                    (base.type == BrowseType.ALBUM || base.subtitle.isBlank())) header.subtitle else base.subtitle,
                thumbnailUrl = fallback ?: header?.thumbnailUrl,
            )
        }, { base.copy(songs = UiState.Error(friendly(it))) })
    }
}

/**
 * A Spotify playlist as an ordinary playlist page (Android's MainViewModel.openSpotifyPage): rows go up as read, each
 * pending its YouTube Music match, then are swapped in place. [update] applies a change to the page and returns false
 * once the page is no longer open.
 */
internal suspend fun loadSpotifyPage(browseId: String, update: ((DetailPage) -> DetailPage) -> Boolean) = coroutineScope {
    val playlistId = browseId.removePrefix(SPOTIFY_PAGE_PREFIX)
    fun setSongs(songs: UiState<List<Song>>) = update { it.copy(songs = songs) }
    launch { runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull()?.let { cover -> update { it.copy(thumbnailUrl = cover) } } }
    fun SpotifyTrack.pending() = Song(
        videoId = SPOTIFY_PENDING_PREFIX + id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}" },
        albumName = album,
    )
    val tracks = runCatching { SpotifyLibrary.tracks(playlistId) { soFar -> setSongs(UiState.Success(soFar.map { it.pending() })) } }
        .getOrElse { setSongs(UiState.Error(friendly(it))); return@coroutineScope }
    if (tracks.isEmpty()) {
        setSongs(UiState.Error(getString(Res.string.spotify_empty_tracks)))
        return@coroutineScope
    }
    val gate = Semaphore(6)
    tracks.forEachIndexed { index, track ->
        launch {
            gate.withPermit {
                val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                val found = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                    ?: track.pending().let { it.copy(videoId = SPOTIFY_MISSING_PREFIX + track.id) }
                update { page ->
                    val list = (page.songs as? UiState.Success<List<Song>>)?.data
                    if (list != null && index < list.size) page.copy(songs = UiState.Success(list.toMutableList().also { it[index] = found })) else page
                }
            }
        }
    }
}

internal suspend fun loadMood(item: MoodGenre): UiState<List<HomeShelf>> =
    YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
        { if (it.isEmpty()) UiState.Error("Nothing to explore") else UiState.Success(it) },
        { UiState.Error(friendly(it)) },
    )

/** Long-press menu. Like needs sign-in. The Download row carries a download's whole life, as Android's DownloadRow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosSongMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    /** Null hides the row (signed out). */
    liked: Boolean? = null,
    onToggleLike: () -> Unit = {},
    /** Null hides the row (signed out). */
    disliked: Boolean? = null,
    onToggleDislike: () -> Unit = {},
    onAddToPlaylist: (() -> Unit)? = null,
    onSongInfo: (() -> Unit)? = null,
    /** Player-only rows, like Android's showSleepTimer / onCopyLog; null hides them. */
    sleepStatus: String? = null,
    onSleepTimer: (() -> Unit)? = null,
    onCopyLog: (() -> Unit)? = null,
    /** Player-only, one at a time like Android: back to YouTube's audio, or the way out of that. Null hides the row. */
    onRevertToOriginal: (() -> Unit)? = null,
    onUpgradeQuality: (() -> Unit)? = null,
    /** Greys the row out while the lookup runs; the player's seekbar label says "Upgrading quality". */
    upgradeInProgress: Boolean = false,
) {
    @Composable
    fun Action(icon: ImageVector, label: String, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable { onDismiss(); onClick() },
    )
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The sheet grabs a touch landing right after a scroll ends (see guardSheetFromContentTouches): the menu jumped.
        // Capped like Android's TintedSheet: a full-height sheet fights its own scrolling list and jumps up and down.
        val maxHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
            androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() * 0.85f
        }
        Column(
            Modifier.guardSheetFromContentTouches(sheetState, partialHeight = true).heightIn(max = maxHeight)
                .navigationBarsPadding().verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(song.title, maxLines = 1) },
                supportingContent = { Text(song.artist, maxLines = 1) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            onRevertToOriginal?.let { Action(Icons.AutoMirrored.Rounded.Undo, "Revert to original", it) }
            onUpgradeQuality?.let {
                ListItem(
                    headlineContent = { Text("Upgrade quality") },
                    leadingContent = { Icon(Icons.Rounded.HighQuality, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = !upgradeInProgress) { onDismiss(); it() },
                )
            }
            liked?.let { Action(if (it) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (it) "Remove from liked" else "Like", onToggleLike) }
            disliked?.let { Action(if (it) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt, if (it) "Undo dislike" else "Dislike", onToggleDislike) }
            onAddToPlaylist?.let { Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", it) }
            DownloadRow(song)
            Action(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", onPlayNext)
            Action(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", onAddToQueue)
            song.albumId?.let { id -> Action(Icons.Rounded.Album, "Open album") { onOpenAlbum(id) } }
            song.artistId?.let { id -> Action(Icons.Rounded.Person, "Open artist") { onOpenArtist(id) } }
            onSongInfo?.let { Action(Icons.Rounded.Info, "Song info", it) }
            onSleepTimer?.let { Action(Icons.Rounded.Bedtime, sleepStatus?.let { s -> "Sleep timer · $s" } ?: "Sleep timer", it) }
            Action(Icons.Rounded.Share, "Share") { shareText("https://music.youtube.com/watch?v=${song.videoId}") }
            onCopyLog?.let { Action(Icons.Rounded.BugReport, "Copy log", it) }
        }
    }
}

/** Start, watch, cancel, retry and delete one song's download in a single row that keeps the sheet open. */
@Composable
private fun DownloadRow(song: Song) {
    val id = song.videoId
    val active by IosDownloads.active.collectAsState()
    val saved by IosDownloads.savedIds.collectAsState()
    DisposableEffect(id) { onDispose { IosDownloads.dismissFailure(id) } }
    @Composable
    fun Row(icon: ImageVector, label: String, value: String?, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null, tint = tint ?: androidx.compose.material3.LocalContentColor.current) },
        trailingContent = value?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
    when (val state = active[id]) {
        IosDownloadState.Queued -> Row(Icons.Rounded.Downloading, "Queued", "Cancel") { IosDownloads.cancel(id) }
        is IosDownloadState.Running -> Row(Icons.Rounded.Downloading, "Downloading", if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null) { IosDownloads.cancel(id) }
        is IosDownloadState.Failed -> Row(Icons.Rounded.ErrorOutline, state.reason, "Retry", MaterialTheme.colorScheme.error) { IosDownloads.enqueue(song) }
        null -> if (id in saved) Row(Icons.Rounded.DownloadDone, "Saved to Downloads", "Delete") { IosDownloads.delete(id) }
        else Row(Icons.Rounded.Download, "Download", null) { IosDownloads.enqueue(song) }
    }
}

/** The system share sheet, presented from whatever view controller is on top. */
internal fun shareText(text: String) = presentShare(listOf(text))

/** [shareText] for anything the system can send: strings, file URLs (pictures). */
internal fun presentShare(items: List<Any>) {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    while (true) top = top.presentedViewController ?: break
    top.presentViewController(UIActivityViewController(items, null), true, null)
}

/** Who is signed in, with the way out, and the Spotify connection. ponytail: no channel switcher (single account). */
@Composable
internal fun IosAccountPage(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSpotify: () -> Unit,
    onSpotifyDisconnect: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spotifyConnected = AppSettings.spotifySpdcToken.collectAsState().value.isNotBlank()
    val autoHide by AppSettings.spotifyCanvasAutoHide.collectAsState()
    val prioritize by AppSettings.prioritizeSpotifyCanvas.collectAsState()
    @Composable
    fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) = SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(contentPadding)) {
        if (!signedIn) {
            SettingsGroup(topSpacing = 0.dp) {
                AccountCard(signedIn = false, account = account, onSignIn = onSignIn, grouped = true)
            }
        } else {
            IosAccountsGroup(onAddAccount = onSignIn, onRemoveActive = onSignOut)
        }
        SettingsGroup(header = stringResource(Res.string.spotify), footer = stringResource(Res.string.spotify_connect_subtitle)) {
            SettingsRow(
                iconPainter = painterResource(Res.drawable.spotify_logo),
                title = stringResource(Res.string.spotify),
                subtitle = stringResource(if (spotifyConnected) Res.string.connected else Res.string.tap_to_connect),
                onClick = onSpotify,
            )
            if (spotifyConnected) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                Toggle(stringResource(Res.string.spotify_canvas_auto_hide), stringResource(Res.string.spotify_canvas_auto_hide_subtitle), autoHide, AppSettings::setSpotifyCanvasAutoHide)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                Toggle(stringResource(Res.string.prioritize_spotify_canvas), stringResource(Res.string.prioritize_spotify_canvas_subtitle), prioritize, AppSettings::setPrioritizeSpotifyCanvas)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                DestructiveRow(label = stringResource(Res.string.spotify_disconnect), onClick = onSpotifyDisconnect)
            }
        }
    }
}

/**
 * Every Google login and its Personal / Brand profiles, Android's AccountProfileSelector as a
 * settings group: tap one to act as it, add another login, or remove the one in use.
 */
@Composable
private fun IosAccountsGroup(onAddAccount: () -> Unit, onRemoveActive: () -> Unit) {
    val accounts by IosAuth.accounts.collectAsState()
    val activeAccountId by IosAuth.activeAccountId.collectAsState()
    SettingsGroup(
        header = stringResource(Res.string.accounts),
        footer = stringResource(Res.string.account_profiles_help),
        topSpacing = 0.dp,
    ) {
        accounts.forEachIndexed { index, login ->
            if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            login.profiles.forEachIndexed { profileIndex, profile ->
                if (profileIndex > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                IosProfileRow(
                    profile = profile,
                    login = login,
                    active = login.id == activeAccountId && profile.id == login.activeProfile?.id,
                    onClick = { IosAuth.select(login.id, profile.id) },
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        SettingsRow(icon = Icons.Rounded.Add, title = stringResource(Res.string.add_account), onClick = onAddAccount)
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        DestructiveRow(label = stringResource(Res.string.sign_out), onClick = onRemoveActive)
    }
}

@Composable
private fun IosProfileRow(profile: IosProfile, login: IosAccount, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = Modifier.size(40.dp).clip(CircleShape).thumbnailBorder(CircleShape)
        if (profile.avatar != null) {
            AsyncImage(profile.avatar, null, avatar, contentScale = ContentScale.Crop)
        } else {
            Box(avatar.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                Icon(Icons.Rounded.Person, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (profile.brand) stringResource(Res.string.brand_account) else login.email.ifBlank { profile.handle },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Android's top-right pill: the account photo (a person glyph when signed out), with History beside it on Library. */
@Composable
internal fun IosTopPill(account: Account?, onHistory: (() -> Unit)?, onAccount: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onHistory != null) IconButton(onClick = onHistory) {
            Icon(Icons.Rounded.History, stringResource(Res.string.listening_history))
        }
        IconButton(onClick = onAccount) {
            val avatar = Modifier.size(28.dp).clip(CircleShape).thumbnailBorder(CircleShape)
            val photo = account?.thumbnailUrl
            if (photo != null) {
                AsyncImage(photo, stringResource(Res.string.settings), avatar, contentScale = ContentScale.Crop)
            } else {
                Box(avatar.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    Icon(Icons.Rounded.Person, stringResource(Res.string.settings), Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
