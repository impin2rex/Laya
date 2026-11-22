<div align="center">

<br/>
<br/>

<img src="Logo.png" alt="Laya app icon" width="200" />

# Laya

### Lossless Music Streaming & Download App — Hi-Res FLAC, Word-Synced Lyrics, Every Device

**Laya** is a free, open-source, ad-free **lossless music streaming and download app** and **YouTube Music client**. It streams and downloads **Hi-Res lossless audio (FLAC / ALAC, 24-bit)** from YouTube Music, Tidal and Qobuz, plays your Spotify playlists, shows **word-synced lyrics**, and runs on **Android, iPhone, Windows, macOS, Linux, Android TV and Wear OS** from a single Kotlin Multiplatform codebase.

<br/>

[![Latest release](https://img.shields.io/github/v/release/impin2rex/Laya?style=for-the-badge&labelColor=0d1117)](https://github.com/impin2rex/Laya/releases)
[![License](https://img.shields.io/github/license/impin2rex/Laya?style=for-the-badge&labelColor=0d1117)](https://github.com/impin2rex/Laya/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/impin2rex/Laya/total?style=for-the-badge&labelColor=0d1117)](https://github.com/impin2rex/Laya/releases)

<br/>

[**Screenshots**](#screenshots) · [**Features**](#features) · [**Download**](#download) · [**iPhone**](#install-on-iphone-with-sidestore) · [**FAQ**](#faq) · [**Disclaimer**](#disclaimer)

<br/>


</div>

> [!IMPORTANT]
> Laya is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<div align="center">

<img src="Banner.png" alt="Laya — free open-source lossless music streaming and download app" width="100%" />

<h1><a id="screenshots"></a>Screenshots</h1>

<table>
  <tr>
    <td width="33%" align="center" valign="top">
      <img src="assets/screenshots/now-playing-lyrics.jpg" alt="Laya now playing screen with Apple-style word-synced lyrics and Hi-Res Lossless playback badge" width="260" />
      <br/><sub><b>Now playing</b> — word-synced lyrics, Hi-Res Lossless badge, artwork-driven theming</sub>
    </td>
    <td width="33%" align="center" valign="top">
      <img src="assets/screenshots/artist-page.jpg" alt="Laya artist page showing animated artist canvas, top songs and recent singles" width="260" />
      <br/><sub><b>Artist page</b> — animated canvas, top songs, latest releases</sub>
    </td>
    <td width="33%" align="center" valign="top">
      <img src="assets/screenshots/explore-moods.jpg" alt="Laya explore screen with mood and moment playlists and frosted glass mini player" width="260" />
      <br/><sub><b>Explore</b> — moods &amp; moments, frosted-glass mini player</sub>
    </td>
  </tr>
</table>

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- **Search, browse and play** anything available on YouTube Music.
- **Recognize music** — name the song playing around you and play it on Laya, with a history of what you've found. Start it from the Quick Settings tile (Android), Control Center (iOS), the app icon's long-press menu, your Wear OS watch, or the app on any platform.
- **Hi-Res lossless audio** — FLAC/ALAC from a configured module source, with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable 0–12s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** integration.
- **Background playback** via a proper foreground media session.
- **Apple-like lyrics animation** — credit to [binimum](https://github.com/binimum/am-lyrics).

#### Connectivity & Accounts
- **Sign in with your Google account** for personalized content.
- **Spotify integration** — connect your account to play your playlists and Liked Songs.
- **Discord Rich Presence** — in-app login, live track/artist/album and progress.
- **Scrobbling** to Last.fm and ListenBrainz.
- **Pluggable sources** — add, edit, test and health-check module sources.

    </td>
    <td width="50%" valign="top">

#### Experience
- **Animated album canvas** — motion artwork on the now-playing screen.
- **Word-synced lyrics** — word/syllable-level highlighting from multiple sources.
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).
- **Built-in Tidal & Qobuz lossless** — no addon needed; a one-time verification on the Sources screen. Protocol ported from [Mornye](https://github.com/mornyeapp/Mornye) and [SpotiFLAC-Extension](https://github.com/mornyeapp/Mornye-Extension) (LGPL-3.0). iPhone has Qobuz only: Tidal streams as segmented DASH, which the iOS player can't play.
- **Dynamic, artwork-driven theming** — Material palette extracted from album art.
- **Frosted-glass UI** — Telegram-style translucent bars via Haze, Material 3 theming.

#### Controls & Tweaks
- **Per-network audio quality** — separate quality ceilings for Wi-Fi and mobile data.
- **Playback speed control** (0.5×–2.0×) and **skip silence**.
- **Sleep timer** — fixed presets or "stop after this track".
- **System equalizer** integration.
- **Karaoke** — sing along with the vocals taken out by an on-device AI separator (UVR MDX-Net), live from the lyrics page. See [docs/KARAOKE.md](docs/KARAOKE.md).
- **Reverb** — Room, Hall, Plate and Cathedral presets with adjustable decay, pre-delay, damping, mix and width, plus a quick toggle in the player. See [docs/REVERB.md](docs/REVERB.md).
- **Stats for nerds** — codec, bit depth, sample rate, and more on the now-playing screen.

    </td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="download"></a>Download</h1>

<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/android/cozy.svg" alt="Download for Android" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://api.jm26.net/badge?label=&icon=f17b&message=Android%20TV&color=000000&labelcolor=000000&fontcolor=white&autofontcolor=false&scale=20" alt="Android TV" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Wear%20OS-%234285F4.svg?style=for-the-badge&logo=wear-os&logoColor=white" alt="Available on Wear%20OS" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/windows/cozy.svg" alt="Download for Windows" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/macos/cozy.svg" alt="Download for macOS" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://api.jm26.net/badge?label=&icon=f17c&message=Linux&color=000000&labelcolor=000000&fontcolor=white&autofontcolor=false&scale=20" alt="Linux" height="55"/></a>
<a href="https://github.com/impin2rex/Laya/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/ios/cozy.svg" alt="Download for iPhone" height="55"/></a>

Grab the latest build for your device from the [Releases](https://github.com/impin2rex/Laya/releases) page.

| Platform | File | Notes |
|---|---|---|
| Android | `Laya-<version>-android-<abi>.apk` | Sideloading requires enabling "Install unknown apps" for whichever app you download it with. |
| Android TV | `Laya-<version>-android-tv-<abi>.apk` | Sideload with `adb install` or a file manager on the TV. |
| Wear OS | `Laya-<version>-wear-os.apk` | Install over ADB / Wi-Fi debugging. |
| Windows | `.exe`, `.msi`, portable `.zip` | Run the `.exe` installer, no admin rights needed. The `.zip` runs from any folder. |
| macOS | `.dmg`, `.pkg` | `arm64` for Apple Silicon, `x64` for Intel. If macOS blocks it, run `xattr -cr /Applications/Laya.app`. |
| Linux | `.AppImage`, `.deb`, `.rpm` | AppImage: `chmod +x` and run. Debian/Ubuntu: `sudo apt install ./Laya-*.deb`. Fedora: `sudo dnf install ./Laya-*.rpm`. |
| iPhone | `Laya-<version>-ios.ipa` | Unsigned, install with [SideStore](#install-on-iphone-with-sidestore). Runs on iPad in iPhone compatibility mode. |

</div>

---

<div align="center">

<h1><a id="install-on-iphone-with-sidestore"></a>Install on iPhone with SideStore</h1>

</div>

The iOS build is an unsigned `.ipa`, so it has to be signed with your own Apple ID. [SideStore](https://sidestore.io) does this on the device and refreshes the app by itself, no computer needed after setup.

1. **Set up SideStore** once by following the official guide at [docs.sidestore.io](https://docs.sidestore.io). It needs a computer for the first install, a free Apple ID, and a local VPN (LocalDevVPN / StosVPN) that SideStore uses to refresh apps.
2. **Enable Developer Mode** on the device: Settings → Privacy & Security → Developer Mode → On, then restart.
3. **Download the IPA** from [Releases](https://github.com/impin2rex/Laya/releases) straight onto the iPhone (`Laya-<version>-ios.ipa`).
4. In SideStore open **My Apps**, tap **+**, and pick the downloaded IPA. Sign in with your Apple ID if asked and wait for the install to finish.
5. Turn the VPN on and open **Laya** from the home screen. If iOS says the developer is untrusted, go to Settings → General → VPN & Device Management and trust your Apple ID.

**Good to know**
- A free Apple ID signs apps for **7 days**. SideStore refreshes Laya in the background while the VPN is on; you can also refresh by hand in My Apps.
- A free account allows 3 active sideloaded apps and 10 App IDs per week. Laya plus its widgets use 2 App IDs.
- To update, install the newer IPA over the old one. Your data stays.
- Laya has iPhone widgets (iOS 17+). On iPad it runs as a scaled iPhone app.

---

<div align="center">

<h1><a id="faq"></a>Frequently Asked Questions</h1>

</div>

**What is Laya?**
Laya is a free, open-source, ad-free music player and YouTube Music client that streams and downloads lossless audio. It is built with Kotlin Multiplatform and Compose Multiplatform, and ships for Android, iPhone, Windows, macOS, Linux, Android TV and Wear OS from one codebase.

**Does Laya support lossless and Hi-Res audio?**
Yes. Laya plays Hi-Res lossless FLAC and ALAC from built-in Tidal and Qobuz support (a one-time verification on the Sources screen) and from pluggable module sources, with YouTube Music as the fallback stream. The now-playing screen reports the live codec, bit depth and sample rate.

**Can I download music for offline listening?**
Yes. Tracks download with embedded metadata and artwork for offline playback, alongside a local music library that indexes files already on the device.

**Is Laya free, and does it have ads?**
Laya is free software under the GPLv3, with no ads, no trackers and no subscription. Source code and releases are on GitHub.

**How do I install Laya on an iPhone?**
The iOS build is an unsigned `.ipa` that you sign with your own Apple ID using SideStore. See [Install on iPhone with SideStore](#install-on-iphone-with-sidestore).

**Do I need a Google, Spotify or Tidal account?**
No account is required to search and play. Signing in with Google personalizes recommendations, Spotify brings over your playlists and Liked Songs, and Tidal or Qobuz verification unlocks their lossless streams.

**Does Laya show synced lyrics?**
Yes — word and syllable-level synced lyrics with an Apple Music–style animation, pulled from LRCLIB, Musixmatch, Genius, lrc.red and several other providers, with translation and romanization.

**Which devices does Laya run on?**
Android phones and tablets, iPhone and iPad (iPhone compatibility mode), Windows, macOS (Apple Silicon and Intel), Linux, Android TV and Wear OS watches.

**Is Laya affiliated with YouTube Music or Spotify?**
No. Laya is an independent third-party client and is not affiliated with, endorsed by or connected to Google, YouTube, Spotify, Tidal, Qobuz or Deezer.

---

<div align="center">

<h1><a id="disclaimer"></a>Disclaimer & Legal Notice</h1>

Laya is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

* **No Media Hosting:** Laya does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public, public-facing, or user-authenticated APIs.
* **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
* **No Ad-Blocking Guarantee:** While Laya focuses on providing a clean listening environment, it does not guarantee permanent bypasses or modifications to commercial third-party platform conditions.
* **Copyleft:** Laya is free software under the GPLv3. The license does not let anyone forbid others from selling or redistributing copies, but any distribution must come with the Corresponding Source under the same license.

</div>

---

<div align="center">

<h1><a id="license"></a>License</h1>

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

Laya is a modified version of [BitChord](https://github.com/kushagrasinghx/BitChord), also licensed under GPLv3.

</div>
