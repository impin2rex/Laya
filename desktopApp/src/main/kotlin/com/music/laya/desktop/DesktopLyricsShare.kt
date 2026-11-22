package com.music.laya.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import com.music.laya.data.model.Song
import com.music.laya.playback.youtubeSeedFor
import com.music.laya.ui.share.ImageSharer
import com.music.laya.ui.share.VideoSharer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Where a shared lyrics card or video goes on a computer, which has no share sheet: Save writes it
 * into the user's Pictures (or Movies / Videos) folder under Laya; Share does the same, puts the
 * file on the clipboard — ready to paste into a chat or a browser upload — and shows it in the
 * file manager.
 */
internal object DesktopImageSharer : ImageSharer {
    override suspend fun share(image: ImageBitmap, fileName: String): Boolean =
        saveToPhotos(image, fileName.substringBeforeLast('.')).also { if (it) lastSaved?.let(::handOver) }

    override suspend fun saveToPhotos(image: ImageBitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(shareFolder("Pictures"), "${safe(name)}.png")
            check(ImageIO.write(image.toAwtImage(), "png", file)) { "no PNG writer" }
            lastSaved = file
        }.onFailure { DesktopTrackLog.log("lyrics share: image save failed: ${it.message}") }.isSuccess
    }

    @Volatile
    private var lastSaved: File? = null
}

/**
 * The share video through the FFmpeg the desktop already ships (bytedeco's build, which carries the
 * `ffmpeg` program next to its libraries): the frames piped in raw, H.264 out through OpenH264,
 * the song's audio cut from the stream the player has open and encoded to AAC.
 *
 * The audio comes from the stream the player has open for the song, or else one resolved here the
 * way the player resolves it. The video is silent only when that fails too ([lastEncodeSilent]).
 */
internal object DesktopVideoSharer : VideoSharer {
    override suspend fun encode(
        song: Song,
        width: Int,
        height: Int,
        fps: Int,
        frames: Int,
        segments: List<Pair<Long, Long>>,
        frame: suspend (index: Int, argb: IntArray) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val work = File(System.getProperty("java.io.tmpdir"), "laya-share").apply { mkdirs() }
        val out = File(work, "lyrics.mp4").apply { delete() }
        val pcm = File(work, "lyrics-audio.f32")
        val log = File(work, "lyrics-ffmpeg.log")
        val rate = try {
            decodeAudio(song, segments, pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DesktopTrackLog.log("lyrics share: no audio (${e.message})")
            null
        }
        silent = rate == null
        val program = runCatching { org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }
            .onFailure { DesktopTrackLog.log("lyrics share: ffmpeg unavailable: ${it.message}") }
            .getOrNull() ?: return@withContext null
        val command = ffmpegCommand(program, width, height, fps, rate?.let { pcm to it }, out)
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(log)
            .start()
        try {
            val argb = IntArray(width * height)
            val bytes = ByteBuffer.allocate(argb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            BufferedOutputStream(process.outputStream, 1 shl 20).use { sink ->
                for (index in 0 until frames) {
                    frame(index, argb)
                    // Little-endian ARGB ints are B, G, R, A in memory: FFmpeg's bgra.
                    bytes.clear()
                    bytes.asIntBuffer().put(argb)
                    sink.write(bytes.array())
                }
            }
            val code = process.waitFor()
            if (code != 0 || !out.isFile) {
                DesktopTrackLog.log("lyrics share: ffmpeg exited $code; see ${log.path}")
                return@withContext null
            }
            out.path
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            process.destroyForcibly()
            DesktopTrackLog.log("lyrics share: video export failed: ${e.message}")
            null
        } finally {
            pcm.delete()
        }
    }

    override suspend fun share(path: String): Boolean =
        save(path, File(path).nameWithoutExtension).also { if (it) lastSaved?.let(::handOver) }

    override suspend fun save(path: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val movies = if (File(System.getProperty("user.home"), "Movies").isDirectory) "Movies" else "Videos"
            lastSaved = File(path).copyTo(File(shareFolder(movies), "${safe(name)}.mp4"), overwrite = true)
        }.onFailure { DesktopTrackLog.log("lyrics share: video save failed: ${it.message}") }.isSuccess
    }

    @Volatile
    private var lastSaved: File? = null

    @Volatile
    private var silent = false

    override fun lastEncodeSilent() = silent

    /** A song the player has not opened this run: its YouTube stream, resolved as the player would. Null when that fails. */
    private suspend fun resolveStream(song: Song): DesktopStream? {
        val id = youtubeSeedFor(song) ?: return null
        return DesktopStreamClient.resolve(song.copy(videoId = id)).onFailure {
            if (it is CancellationException) throw it
            DesktopTrackLog.log("lyrics share: could not resolve audio for $id (${it.message})")
        }.getOrNull()
    }

    /**
     * [segments] of the playing [song], back to back, as interleaved float stereo in [into];
     * the sample rate, or null when the song is not the one the player has open.
     */
    private suspend fun decodeAudio(song: Song, segments: List<Pair<Long, Long>>, into: File): Int? {
        val stream = DesktopPlaybackEngine.active?.streamOf(song.videoId) ?: resolveStream(song) ?: return null
        val pcm = DesktopKaraokeStream.open(stream) ?: return null
        try {
            val rate = pcm.sampleRate
            var wrote = 0L
            BufferedOutputStream(into.outputStream()).use { sink ->
                for ((start, end) in segments) {
                    if (pcm.seek(start * rate / 1000) < 0) return null
                    var remaining = (end - start) * rate / 1000
                    while (remaining > 0) {
                        val (left, right) = pcm.next() ?: break
                        val take = minOf(left.size.toLong(), remaining).toInt()
                        val chunk = ByteBuffer.allocate(take * 8).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until take) chunk.putFloat(left[i]).putFloat(right[i])
                        sink.write(chunk.array())
                        remaining -= take
                        wrote += take
                    }
                }
            }
            return rate.takeIf { wrote > 0 }
        } finally {
            pcm.close()
        }
    }
}

/** The FFmpeg run for one share video: raw frames on stdin, the cut audio from a file when there is one. */
internal fun ffmpegCommand(
    program: String,
    width: Int,
    height: Int,
    fps: Int,
    /** The cut audio, interleaved little-endian float stereo, and its sample rate. */
    audio: Pair<File, Int>?,
    out: File,
): List<String> = buildList {
    addAll(listOf(program, "-hide_banner", "-loglevel", "error", "-y"))
    addAll(listOf("-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${width}x$height", "-r", "$fps", "-i", "pipe:0"))
    if (audio != null) {
        addAll(listOf("-f", "f32le", "-ar", "${audio.second}", "-ac", "2", "-i", audio.first.path))
    }
    // OpenH264 is the H.264 encoder bytedeco's LGPL build carries; 10 Mbit/s as on the phone.
    addAll(listOf("-c:v", "libopenh264", "-b:v", "10M", "-pix_fmt", "yuv420p"))
    if (audio != null) addAll(listOf("-c:a", "aac", "-b:a", "256k", "-shortest"))
    addAll(listOf("-movflags", "+faststart", out.path))
}

/** ~/[base]/Laya, made if missing. */
private fun shareFolder(base: String): File =
    File(File(System.getProperty("user.home"), base), "Laya").apply { mkdirs() }

private fun safe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

/** [file] onto the clipboard as a file, and shown in the file manager. */
private fun handOver(file: File) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(FileSelection(file), null)
    }
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(file)
        else if (desktop.isSupported(Desktop.Action.OPEN)) desktop.open(file.parentFile)
    }
}

private class FileSelection(private val file: File) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = listOf(file)
}
