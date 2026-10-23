# Karaoke

Karaoke takes the vocals out of the song that's playing so you can sing along.
The lyrics keep scrolling while it's on. Everything runs on the device, and the
vocal model ships inside the app, so karaoke works the first time and offline.

## Where it is

| Platform | Control |
|---|---|
| Android phone and tablet | Lyrics page › **mic** disc, next to Translate (portrait, landscape and the side panel) |
| Android TV | Now Playing › **mic** button, shown while the lyrics are |
| Desktop (macOS, Linux, Windows) | Player › song menu › **Sing along**, and the mic disc in the lyrics side panel |
| Wear OS | Not offered. The watch has no lyrics page. |

The disc wears a sing-along mic and lights up while karaoke is on. A tap
switches it. It shows a spinning ring while the song's vocal is still being
prepared, and the song keeps its voice until then.

**One song at a time.** Karaoke switches off whenever the track changes. A
repeat of the same song doesn't count as a change. The next song starts with
its voice, and the mic is a tap away.

**Vocal level.** Holding the mic grows the disc itself upward into a capsule
that shows how much of the vocal is taken out, from 0% (vocal untouched) to
100% (all of it). The same finger drags it up or down without lifting. Holding
while off switches karaoke on as well. The capsule stays for 2.5 s for another
tap or drag, then shrinks back into the disc. The level glides, so dragging it
mid-song doesn't zipper. Like the switch, it is not saved. The desktop song menu shows the level next to
**Sing along**, and TV has the switch only. Every control flips the same switch
(`shared/.../playback/Karaoke.kt`). The switch is **not saved**, so karaoke is
off again after a restart.

## How it works

A neural model does the work. Until its vocal is ready the song plays
untouched, so you hear one clean switch rather than a weak removal followed
by a second step. A spectral remover is kept only as a fallback: when no model
is available, or when the model's vocal hasn't arrived within 8 s.

### Neural separation

The model is **UVR-MDX-NET 9482** from Ultimate Vocal Remover's public release
(MIT), the same on every platform. It is a TFC-TDF U-Net that takes a song's
complex spectrogram and predicts the vocal's. It separates 5.8 s of audio in
about 2–3 s on a Snapdragon 8 Elite and about 1 s on an M1.

UVR's larger Voc_FT separates somewhat better (about 2 dB more vocal SDR on
published benchmarks), but it is slower than real time on a phone CPU: 6–10 s
per 5.9 s chunk on the Snapdragon 8 Elite. NNAPI and XNNPACK were no faster,
and the LiteRT GPU delegate only took half the graph.

The model ships in `app/src/main/assets/UVR_MDXNET_9482.onnx` (30 MB). The
desktop build packs the same assets folder, and TV shares it. ONNX Runtime wants
a file, so the model is copied out of the APK or jar the first time karaoke
runs (`KaraokeModel.kt`). That copy takes another 30 MB of app storage on
Android. Bundling it adds about 28 MB to each APK, since the weights barely
compress, and about the same to the desktop installer. ONNX Runtime was
already part of the app for Automix, so there is no new runtime.

The pipeline (`shared/.../playback/karaoke/`):

1. **Decode.** A worker thread decodes the playing song on its own, from the
   same bytes the player reads: the Android audio cache or local file, or a
   second FFmpeg decode on desktop. To get to a new position it seeks; it
   never decodes from the start.
2. **Separate the whole song.** The worker separates the song in 5.75 s
   segments (one model chunk of 5.9 s minus its context). It does the next
   30 s first, then, while karaoke is on, the rest of the song ahead, then
   anything behind. Each segment gets its own slot in a cache file: 16-bit
   vocal plus the mix it came from, about 85 MB for a 5-minute song, and none
   of it on the heap. It resamples to 44.1 kHz and back with the analyser's
   windowed-sinc resampler, and uses UVR's exact STFT (`MdxSeparator.kt`,
   matched against python-audio-separator to 57–64 dB). Adjacent segments
   crossfade over 1024 frames.
3. **Line decodes up with each other.** A seek lands on a container
   timestamp, so two of the worker's decodes can count frames a few samples
   apart. Every segment is read with room either side. Where it meets a
   neighbour from another decode, their overlap is cross-correlated and the new
   decode slid onto the old one's frames. Runs of segments sharing a frame
   count ("islands") merge when they meet. If two can't be reconciled, the one
   you aren't listening to is separated again.
4. **Line the sink up.** The sink hands over a capture of what it actually
   plays, and a thread of its own correlates it against the stored mix, with
   no decode and no waiting behind inference. The first time it uses 0.34 s
   and a ±100 ms search; this measures a codec's priming, which was 3480 frames
   on a test phone. After a seek it uses 85 ms and a ±256-frame search around
   the last offset.
5. **Subtract.** The sink's `KaraokeProcessor` subtracts the separated vocal
   from what it plays, sample for sample. Anything the model didn't call vocal
   passes through untouched.

### Spectral fallback

When no neural vocal can be had (for example when the song's bytes can't be
read), or the model's vocal has kept the stage waiting for more than 8 s, the
stage removes what both speakers play identically within 120 Hz–14 kHz. This
is per-bin left/right coherence over a 40 ms STFT. It crossfades with the
neural vocal over 20 ms.

### Where it sits

- **Android and Android TV**: one stage per ExoPlayer. It is the first stage of
  the Float32 `DspChain` in `PrecisionAudioSink`, which hands it each block's
  position in the track.
- **Desktop**: one stage per deck, on the decoder's own frames, before rate
  conversion, the Automix blend and the speed stretch.

## Switching, sync and cost

- **Seeking, once a part is separated.** A seek or lyric tap into separated
  audio only needs the sink lined up again: 85 ms of capture and a narrow
  search, so about 0.1–0.15 s, with the voice left whole meanwhile. Once the
  whole song is done (about 0.55× its length on a phone, e.g. ~2.5 min for a
  4.5-min song), that holds anywhere in it.
- **Seeking ahead of the worker.** The worker moves there at once, calls off
  an inference still running for the old position, and the new segment is
  ready one inference later (~3 s on a phone), with the preparing ring shown.

- **Instant from the lyrics page.** While the lyrics are on screen, karaoke
  gets ready even when it's off. The worker separates ahead, and the sink
  follows along (it reads the audio and changes nothing), so the vocal is
  separated and lined up before the mic is tapped. Switching on is then a
  crossfade of under 0.1 s. Switching on from elsewhere (the desktop song
  menu, TV), or without the lyrics open, takes about 3 s with the ring, the
  voice left whole meanwhile.

- **No interruption.** Switching on passes audio through while the stage's
  40 ms buffer fills, then crossfades over about 20 ms. Switching off
  crossfades back. You hear about 40 ms twice going in and skip about 40 ms
  coming out, under the crossfade.
- **Lyrics.** While karaoke is on, the audio runs about 40 ms behind the clock
  the lyrics follow. That is below the precision of word-synced lyrics.
- **Off costs nothing.** Once off, the sink stage returns before reading a
  sample (Android stays bit-exact), and the worker goes idle. A minute later it
  also releases the model's memory.
- **Cost while on.** One worker thread runs inference on 2–4 cores until the
  whole song is separated (about 0.55× its length on a phone, faster on
  desktop), then idles. The song takes up to ~85 MB in the cache for a
  5-minute song, deleted when the track changes and cleared at every launch.
  With the lyrics merely open, it only works 30 s ahead. On desktop the
  stream is read twice.

## Limitations

- **Separation isn't perfect.** Faint vocal residue and "ghosts" of the vocal's
  reverb can remain, mostly on dense, heavily processed mixes. Backing vocals
  count as vocals and are removed too.
- **A wait before the first segment.** It takes about 3 s when switching on
  without the lyrics open, or after a seek ahead of the worker, shown by the
  ring. The voice stays whole meanwhile.
- **The spectral fallback is weaker.** It only runs without a model or after
  an 8 s wait. Off-centre vocals stay, centred instruments thin out, and it
  can't touch a mono track.
- **Slow devices.** A device that can't separate faster than real time (some
  TV boxes, older phones) gets the neural vocal only for part of each track,
  and the fallback for the rest.
- **No local PCM, no karaoke.** When casting or playing on the watch, no
  samples pass through the app, so the disc dims and says so. Android
  passthrough/offload has the same limitation.
- **Android tracks not yet on disk** (for example a stream still downloading)
  are separated once their bytes arrive. Until then the fallback runs.
- **Multichannel.** Only the front left/right pair is processed. Other channels
  are delayed to stay in step and are otherwise untouched.
