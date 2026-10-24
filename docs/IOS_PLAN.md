# Laya for iOS — build plan

Goal: an `.ipa` for iPhone that matches the Android app feature-for-feature, built by
reusing the existing Kotlin Multiplatform code instead of writing a Swift app from scratch.

Status: **plan only**. Nothing below exists in this repo yet.

---

## 0. Where the code stands today

| Module | Lines | Reusable on iOS as-is? |
|---|---|---|
| `shared/commonMain` | ~5k | **Yes** — pure Kotlin models, no JVM imports |
| `shared/jvmSharedMain` | ~21k | **No** — InnerTube, lyrics, Discord, Spotify, karaoke, analysis. Depends on OkHttp, jsoup, `java.*`, ONNX Runtime JVM, InnerTubeX v0.7.0 (JVM build), NewPipe |
| `sharedUi/jvmSharedMain` | ~23k | **Mostly** — Compose Multiplatform code (player, lyrics, Home/Search/Library/Explore), but in a JVM-only source set |
| `sharedUi/commonMain` | 0 | — |
| `app` (Android) | ~105k | **No** — phone screens, Media3 playback service, downloads, auth WebViews, widgets, cast, USB/BT audio, SMB, DSD |
| `desktopApp` + `desktopUi` | ~70k | **As a blueprint** — playback without Media3 (FFmpeg decode → own DSP → sink) |
| `native/analyzer`, `app/src/main/cpp` | ~1–2k C++ | **Yes** — portable C++, compiles for iOS |

The core problem: almost every shared line lives in `jvmSharedMain`, which iOS (Kotlin/Native)
can't compile. Most of the work is moving that code into `commonMain` behind small
`expect/actual` seams, then writing the iOS-only parts.

---

## 1. Existing iOS ports we can borrow from

Two public forks of upstream **BitChord** (Laya's parent; package `com.music.bitchord`, ours is
`com.music.laya`) already attempted this. Both are GPL-3.0 like Laya, so code can be copied with
attribution. Both fork an **older** BitChord, so anything copied must be merged into Laya's newer
files (karaoke, reverb, system font, etc.), never pasted over them.

### A. `atlasoftimezz-coder/BitChord-iOS` — honest, partly proven on a real iPhone

- Separate `ios/` Gradle build. Android sources are **copied** into an iOS-only `commonMain`
  (~28k lines) and kept compiling with shims (`okhttp3.*` → NSURLSession, `android.content.*` →
  NSUserDefaults, `java.io.File` → NSFileManager, …) plus Python porting scripts.
- Uses **upstream InnerTubeX `v0.7.4`, which publishes iOS artifacts** — search and playback via
  InnerTubeX confirmed on an iPhone 15.
- **Swift audio engine (`ios/iosApp/iosApp/`)**, confirmed on device:
  - `AudioEngine.swift` (652 lines): two `AVQueuePlayer` decks, gapless next item, crossfade by
    riding deck volumes from the Kotlin `CrossfadeController`, audio session, interruptions,
    headphone unplug, lock screen / Control Center.
  - `ChunkedResourceLoader.swift`: bounded-range googlevideo fetcher (`AVAssetResourceLoader`)
    — needed because googlevideo throttles/403s long unranged reads.
  - `TransitionFilter.swift`: EQ + transition filter via `MTAudioProcessingTap`.
  - `Analysis/AnalysisBridge.swift`: C++ analyzer + Beat This! / open-unmix ONNX on iOS.
- **CI with no Mac**: `.github/workflows/ios.yml` — XcodeGen generates the Xcode project, ad-hoc
  `codesign --sign -`, zips an `.ipa` artifact; installed with Sideloadly + free Apple ID.
- Gaps: real Android UI not ported (sources parked, out of build); scrobbling, Discord, canvas,
  module sources, PoToken, Listen Together still todo. Quality capped at AAC ~130 kbps (AVPlayer
  can't play WebM/Opus).
- Weakness for us: the copy + shims approach **forks the code** — every Android change must be
  re-ported by hand.

### B. `Spvteam/bitchord-ipados` — right structure, unverified claims

- Same layout as Laya: moved `shared` + `sharedUi` from `jvmSharedMain` into **`commonMain`**
  in place (shared ~16k, sharedUi ~21k), so Android/desktop/iOS compile the same files. This is
  the approach this plan wants.
- Vendors InnerTubeX as a KMP module with its own iOS cipher loader (likely unnecessary now that
  upstream v0.7.4 supports iOS).
- iOS actuals worth reviewing: `IOSCipher.kt` (JavaScriptCore), `IOSPoTokenProvider.kt`
  (headless WKWebView BotGuard), `IOSKeychainAuthStore.kt` (Keychain + `SAPISIDHASH`),
  `IOSSandboxStorage.kt`, `IOSChunkedStreamLoader.kt`, `IOSNowPlayingManager.kt`, ONNX
  `BeatTracker.ios.kt` / `VocalTracker.ios.kt`, `ShareSheet.kt`, `PlatformHaptics.kt`.
- **Caution**: one squashed commit, AI-generated, and the "12/12 done, 284 tests" tracker does
  not hold up. `IOSAudioEngine.kt` only stores the URL and never decodes or schedules audio; the
  app actually plays through a plain `AVPlayer` inside a custom 2.2k-line `BitChordIosApp.kt`
  (not the real Laya UI). Treat every file as a draft to review, not as working code.

### What we take from each

| Need | Take from | How |
|---|---|---|
| `commonMain` migration of `shared` / `sharedUi` | **B** as a reference diff | Re-do the move on Laya's files; use B's diffs to speed up the JVM→common rewrites |
| InnerTubeX on iOS | **A** | Bump to upstream `v0.7.4+` (multiplatform). Drop NewPipe on iOS |
| Audio engine, chunked loader, EQ tap, analysis bridge | **A** | Copy the Swift files, rename package, wire to Laya's `CrossfadeController` / processors |
| CI + unsigned `.ipa` + sideload | **A** | Copy `ios.yml` + `project.yml` (XcodeGen) |
| Keychain, PoToken, sandbox storage, share sheet, haptics | **B** | Review, test on device, keep what works |
| Real phone UI | neither | Our own work (Phase 4) |
| Shims (`okhttp3`, `android.*`) | **no** | Can't live in a shared `commonMain` next to real OkHttp on JVM; convert to Ktor instead |

---

## 2. Dependency swaps

| JVM / Android dependency | iOS-capable replacement |
|---|---|
| OkHttp (`ktor-client-okhttp`, raw `okhttp3.*` in ~30 places) | Ktor everywhere; `ktor-client-darwin` engine on iOS |
| jsoup | `com.fleeksoft.ksoup:ksoup` (A uses `0.2.6`) |
| `java.util.Locale`, `SimpleDateFormat`, `Normalizer` | `expect` helpers → `NSLocale` / `NSDateFormatter` / NSString normalization |
| `ConcurrentHashMap`, `Atomic*` | `kotlinx.atomicfu` or Mutex-guarded maps |
| `java.io.File`, `ByteBuffer` | `okio` |
| `MessageDigest`, `Base64`, `UUID` | `kotlin.io.encoding.Base64`, `kotlin.uuid.Uuid`, `org.kotlincrypto.hash` |
| InnerTubeX `v0.7.0` (JVM) + NewPipe | InnerTubeX `v0.7.4+` multiplatform; NewPipe stays JVM-only fallback |
| Rhino / QuickJS / GraalJS | JavaScriptCore, only if InnerTubeX's iOS path still needs a host JS engine |
| PoToken WebView | Offscreen `WKWebView` (see B's `IOSPoTokenProvider.kt`) |
| ONNX Runtime (JVM/Android) | `onnxruntime-objc` / `onnxruntime-c` (CoreML EP) behind `expect class` |
| `SharedPreferences` / `EncryptedPrefs` (20 files) | `multiplatform-settings` (NSUserDefaults) + Keychain |
| Media3 ExoPlayer + MediaSession | A's Swift `AVQueuePlayer` engine; `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| androidx navigation-compose | `org.jetbrains.androidx.navigation:navigation-compose` |
| Coil 3 (`coil-network-okhttp`) | Coil 3 + `coil-network-ktor3` on iOS |
| Haze, Material3, Compose resources | Already multiplatform — add iOS targets |
| zxing (QR) | `qrose` or CoreImage `CIQRCodeGenerator` |
| compose-richtext | iOS artifact if available, else `multiplatform-markdown-renderer` |
| smbj (SMB) | `libsmb2` via cinterop |
| Google Cast SDK (Android) | Google Cast iOS SDK; AirPlay via `AVRoutePickerView` |

---

## 3. Prerequisites & distribution

1. **No Mac required to build.** Like repo A, builds run on GitHub Actions `macos-15`
   (XcodeGen + `xcodebuild`). A local Xcode (this machine only has Command Line Tools) is only
   needed for debugging with breakpoints / Instruments.
2. A real iPhone. Background audio, lock screen, AirPlay and ONNX performance can't be judged on
   the simulator.
3. Signing: a **free Apple ID + Sideloadly/AltStore** works (installs expire after 7 days).
   $99/yr Apple Developer account only if we want App Groups for the widget, longer installs, or
   ad-hoc builds for testers.
4. **App Store is not an option** — YouTube streaming/downloading violates Guidelines
   5.2.2/5.2.3. Ship an unsigned `.ipa` on GitHub Releases for sideloading (plus optional AltStore
   source JSON).

---

## 4. Phases

### Phase 1 — iOS targets, CI, "hello" on a phone

1. Add `iosArm64()` + `iosSimulatorArm64()` to `shared` and `sharedUi`; `sharedUi` exports a
   static framework `LayaKit`.
2. Copy A's `ios/iosApp/project.yml` + `iOSApp.swift` into `iosApp/`, rename bundle id to
   `com.music.laya`, host `MainViewController()` (Compose) from SwiftUI.
3. Copy A's `.github/workflows/ios.yml`, adjust paths (our root Gradle build, not `ios/`), output
   `Laya.ipa` artifact.
4. `settings.gradle.kts` already skips Android modules without an SDK — make sure the macOS CI
   runner builds only what iOS needs.

**Done when:** CI produces `Laya.ipa`, it sideloads, and shows a Compose screen.

### Phase 2 — Move shared logic into `commonMain` (use B as the reference diff)

Leaf-first, small batches, `./gradlew :shared:jvmTest` after each:

1. Utilities + `expect` seams (`Platform.kt`): locale, normalizer, clock, file root, `HttpClient`
   factory (OkHttp engine on JVM, Darwin on iOS).
2. Bump InnerTubeX to `v0.7.4+` multiplatform; move `data/innertube/*` (`Innertube`,
   `InnertubeParser`, `PlayerClient`, `StreamResolver`, `YtMusicRepository`,
   `SongInfoRepository`). NewPipe fallback stays in `jvmSharedMain`.
3. Queue / playback models, `ContextRegistry`, `TransitionFilter`, `Karaoke` models.
4. Lyrics (`data/lyrics/*`, ~30 providers): OkHttp → Ktor, jsoup → ksoup.
5. Discord gateway (`com.my.kizzy.*`), Spotify, Canvas, scrobbling, presence, WebDAV,
   listen-together protocol.
6. Karaoke / Automix analysis: pure Kotlin → common; ONNX behind `expect class OnnxSession`.

**Done when:** `./gradlew :shared:compileKotlinIosArm64` passes and Android + desktop are unchanged.

### Phase 3 — Playback on iOS (copy A's engine)

1. Copy `AudioEngine.swift`, `ChunkedResourceLoader.swift`, `TransitionFilter.swift` into
   `iosApp/`. Rename `Shared`/bitchord types to Laya's; expose the Kotlin `AudioEngine`
   interface they implement from `shared/iosMain`.
2. Drive them from Laya's queue (`QueueCoordinator`-equivalent logic in common), autoplay,
   shuffle, history, sleep timer, last-played.
3. Gapless (pre-resolved next item) and crossfade 0–12s via Laya's `CrossfadeController`.
4. Background audio (`UIBackgroundModes: audio`), lock screen, Control Center, AirPods,
   interruptions — already in A's engine; verify.
5. Per-network quality via `NWPathMonitor`; speed 0.5–2.0×, skip silence.
6. **Effects through `MTAudioProcessingTap`** (A's pattern): EQ (replaces Android's system EQ),
   loudness, Laya's **reverb** (`docs/REVERB.md`) and spatial processors — port the DSP to C++
   (`native/`) or call Kotlin from the tap; measure CPU on device.
7. Stats for nerds from `AVPlayerItem` access log / track format descriptions.

Known ceiling: AVPlayer can't decode WebM/Opus, so YouTube tops out at AAC ~130 kbps (itag 140;
256 kbps itag 141 with Premium cookies). See Phase 8 for the Opus upgrade.

**Done when:** 30+ minutes of locked-screen playback with gapless, crossfade, EQ and reverb.

### Phase 4 — Real Laya UI on iOS

Neither reference repo did this; it's the largest single chunk.

1. Move `sharedUi/jvmSharedMain` → `sharedUi/commonMain` (B shows the rewrites). `Haptics`,
   `StatusBarIcons`, `PlayerPlatformUi`, share sheet become `expect`/`actual` (B has drafts).
2. Move the Android phone screens from `app/.../ui` into `sharedUi/commonMain` so iOS and Android
   run the same UI: `navigation`, `screens/*`, `components/*`, `theme/*`, `onboarding`,
   `replay`, `player/*`. Android-only calls (`Context`, `Intent`, `R.string`, `BackHandler`,
   permission launchers, `Toast`) go behind a `PlatformActions` interface.
3. Strings: `res/values*/strings.xml` → `composeResources/values*/strings.xml`.
4. Multiplatform navigation-compose; safe-area insets, swipe-back, keyboard insets.

**Done when:** every Android screen is reachable on iOS and Android still ships from the same code.

### Phase 5 — Accounts & integrations

| Feature | iOS implementation |
|---|---|
| Google / YT Music sign-in | WKWebView with Safari UA, cookies from `WKHTTPCookieStore` (A's `YtMusicLoginScreen.ios.kt`) |
| Session storage | Keychain + `SAPISIDHASH` (A's `SecureStore.ios.kt`, B's `IOSKeychainAuthStore.kt`) |
| PoToken | B's `IOSPoTokenProvider.kt` — review and test |
| Spotify sign-in, Canvas auth | WKWebView / `ASWebAuthenticationSession` |
| Discord login + RPC | WKWebView token capture; shared gateway code. Socket only alive while audio plays |
| Last.fm / ListenBrainz | Shared Ktor code |
| Listen Together | Shared protocol + `backend/` server |
| Multiple accounts / brand channels | Shared `AccountSessions` logic |
| Settings (20 SharedPreferences files) | `multiplatform-settings`, same keys per file |
| App update checker | Off on iOS |

### Phase 6 — Library, downloads, sources

1. Downloads: background `URLSession`; port `Downloader`, `OfflineDash`, `OfflineHls`,
   taggers (`FlacTagger`, `Mp4Tagger`, `WebmTagger`, `Mp4Boxes`, `Mp4Sidx`) to common on okio.
   A's P5 (queue, 3 workers, `.lrc` sidecar, offline fallback) is a working reference.
2. Local music: Files-app import with security-scoped bookmarks (A's `LocalFilePicker.swift`)
   + `MPMediaQuery` for non-DRM Music-app songs.
3. Hi-Res module sources / add-ons (`data/sources/*`), JioSaavn — FLAC/ALAC play in AVPlayer.
4. SMB (libsmb2), WebDAV (Ktor).

### Phase 7 — On-device AI: Karaoke & Automix

1. A already runs the C++ analyzer + Beat This! + open-unmix ONNX on iOS
   (`AnalysisBridge.swift`) — copy, then add Laya's `UVR_MDXNET_9482.onnx` karaoke separator.
2. Karaoke output: separated stems played through the tap / a second deck.
3. Gate by device RAM and thermal state; "karaoke unavailable" fallback.

### Phase 8 — Quality & platform extras

| Item | iOS approach |
|---|---|
| Opus ~160 kbps (beat AAC ~130) | WebM demuxer + AudioToolbox Opus decoder (or FFmpeg XCFramework from `native/ffmpeg/build.sh`), fed via `AVSampleBufferAudioRenderer` or an `AVAudioEngine` source node |
| DSD | `app/src/main/cpp/dsd` compiled for iOS, decimated to PCM |
| Media widget | WidgetKit + App Group (needs paid account) |
| Chromecast / AirPlay | Google Cast iOS SDK; `AVRoutePickerView` |
| USB DAC / BT codec routing | Not controllable on iOS — show `AVAudioSession.currentRoute` read-only |
| Animated canvas | `AVPlayerLooper` via `UIKitView` (B has a draft) |
| Deep links | URL scheme / Share Extension |
| Wear OS, Android TV | Out of scope (watchOS/tvOS later) |

### Phase 9 — Release

1. CI from Phase 1 builds `Laya.ipa` on tags; upload to the GitHub release next to the APK and
   desktop builds (`docs/DESKTOP_RELEASES.md`).
2. Version from `app/build.gradle.kts` → `CFBundleShortVersionString` / `CFBundleVersion`.
3. Info.plist: `UIBackgroundModes` (audio), `NSLocalNetworkUsageDescription` +
   `NSBonjourServices` (Cast), `NSAppleMusicUsageDescription`, `UIFileSharingEnabled`,
   `LSSupportsOpeningDocumentsInPlace`.
4. Optional AltStore source JSON for update notifications.
5. Add BitChord-iOS / bitchord-ipados attribution to `ADDITIONAL.md` for copied files.

---

## 5. Parity checklist

- [ ] Search, browse, play; Hi-Res FLAC via module sources, YT fallback
- [ ] Gapless + crossfade 0–12s; Automix beat-match
- [ ] Offline downloads with tags; airplane-mode playback
- [ ] Local library (Files + Music app), SMB, WebDAV
- [ ] Background 30+ min, lock screen, Control Center, AirPods, call interruption
- [ ] Apple-style + word-synced lyrics, every provider, translation, offset
- [ ] Animated canvas; artwork theming; Haze frosted bars
- [ ] Google sign-in; Spotify playlists & Liked Songs; Discord RPC; Last.fm; ListenBrainz
- [ ] Listen Together with an Android peer
- [ ] Per-network quality; speed; skip silence; sleep timer
- [ ] EQ; reverb presets; karaoke live
- [ ] Stats for nerds
- [ ] Widget; Cast; AirPlay
- [ ] No Android/desktop regressions: `./gradlew :shared:jvmTest :sharedUi:jvmTest :app:assembleRelease`

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| Code copied from A/B is written against older BitChord | Merge file by file into Laya's versions; never overwrite |
| B's code looks complete but isn't (e.g. silent audio engine) | Treat B as a draft; every borrowed file needs an on-device check |
| `commonMain` move regresses Android/desktop | Small batches, JVM tests each time, Android releases continue from `development` |
| AAC ~130 kbps ceiling on YouTube | Phase 8 Opus decoder; FLAC module sources unaffected |
| Kotlin/Native too slow for DSP in the audio tap | Port reverb/EQ hot loops to C++ or vDSP |
| Karaoke model memory / heat | RAM gate, CoreML EP, graceful disable |
| Background sockets suspended | Discord / Listen Together only while playing; reconnect on foreground |

---

## 7. Order of work

Phase 1 → 2 → 3 gives a first usable `.ipa` (browse + background playback). Then 4 (UI parity),
5, 6, 7, 8, and 9 for release. Phases 2 and 4 can overlap once the `expect` seams exist.
