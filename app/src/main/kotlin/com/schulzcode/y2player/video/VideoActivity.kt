package com.schulzcode.y2player.video

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.playback.LocalVolumeKeyPolicy
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.ui.Y2Palette
import com.schulzcode.y2player.util.TimeFormat
import java.io.File

/**
 * Full-screen video playback. Browsing happens in Y2Player's own lists; this
 * screen only plays, and Back returns to the list it was opened from.
 *
 * Android's MediaPlayer hands H.264 to the MT6582's hardware decoder, so video
 * never goes through the FFmpeg music engine. MainActivity pauses music first.
 *
 *   Center / Play    play or pause
 *   Wheel            volume
 *   Left / Right     back 10 s / forward 30 s; hold to keep seeking
 *   Hold Center      subtitles on or off
 *   Next / Previous  next or previous video in the folder
 *   Back             stop; the position is remembered
 */
class VideoActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var resumeStore: VideoResumeStore

    private lateinit var videoView: VideoView
    private lateinit var subtitleView: TextView
    private lateinit var osd: VideoOsdView

    private var playlist: List<File> = emptyList()
    private var current: File? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var subtitlesAvailable = false
    private var subtitlesOn = true
    private var focusHeld = false

    /**
     * The screen turning off releases VideoView's surface; when it comes back the
     * video prepares again and must wait at the same spot rather than play.
     */
    private var stayPausedOnPrepare = false

    private var volumeMode = VolumeMode.SYSTEM
    private var appVolumeLevel = VolumeCurve.STEPS

    // VideoView reports the old position until a seek lands, so repeated presses
    // accumulate onto a pending target.
    private var seekTarget = -1L
    private val clearSeekTarget = Runnable { seekTarget = -1L }
    private var seekRepeats = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (videoView.isPlaying) pauseVideo()
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (osd.visibility == View.VISIBLE) {
                updateOsd()
                handler.postDelayed(this, 500)
            }
        }
    }
    private val hideOsd = Runnable { if (videoView.isPlaying) osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = runCatching { (application as Y2Application).container.preferences.snapshot() }.getOrNull()
        val palette = Y2Palette.of(preferences?.lightTheme ?: false)
        volumeMode = preferences?.volumeMode ?: VolumeMode.SYSTEM
        appVolumeLevel = preferences?.volumeLevel ?: VolumeCurve.STEPS
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        resumeStore = VideoResumeStore(this)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoView = VideoView(this)
        root.addView(videoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        val density = resources.displayMetrics.density
        subtitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
            val side = (16 * density).toInt()
            setPadding(side, 0, side, (10 * density).toInt())
        }
        root.addView(subtitleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        osd = VideoOsdView(this, palette)
        root.addView(osd, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView.setOnPreparedListener(::onPrepared)
        videoView.setOnCompletionListener { onCompleted() }
        videoView.setOnErrorListener { _, what, extra ->
            onPlaybackError(what, extra)
            true
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        playlist = intent.getStringArrayListExtra(EXTRA_PLAYLIST).orEmpty().map(::File)
        if (path == null) {
            finish()
            return
        }
        play(File(path))
    }

    // ---- playback ------------------------------------------------------------

    private fun play(file: File) {
        current = file
        prepared = false
        player = null
        subtitlesAvailable = false
        subtitleView.text = ""
        seekTarget = -1L
        stayPausedOnPrepare = false
        requestFocus()
        osd.setTitle(VideoLibrary.displayTitle(file.name))
        osd.setMessage("Loading…")
        showOsd(sticky = true)
        videoView.setVideoPath(file.absolutePath)
    }

    private fun onPrepared(mediaPlayer: MediaPlayer) {
        player = mediaPlayer
        prepared = true
        applyAppVolume()
        attachSubtitles(mediaPlayer)
        val file = current ?: return
        val start = VideoResumePolicy.startPosition(resumeStore.get(file.absolutePath), mediaPlayer.duration.toLong())
        if (start > 0) {
            videoView.seekTo(start.toInt())
            osd.setMessage("Resumed at ${TimeFormat.duration(start)} · Previous starts over")
        } else {
            osd.setMessage(null)
        }
        if (stayPausedOnPrepare) {
            stayPausedOnPrepare = false
            showOsd(sticky = true)
            return
        }
        videoView.start()
        showOsd()
    }

    private fun attachSubtitles(mediaPlayer: MediaPlayer) {
        val srt = current?.let(VideoLibrary::subtitleFor) ?: return
        runCatching {
            mediaPlayer.addTimedTextSource(srt.absolutePath, MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP)
            val index = mediaPlayer.trackInfo.indexOfLast {
                it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
            }
            if (index >= 0) {
                mediaPlayer.selectTrack(index)
                mediaPlayer.setOnTimedTextListener { _, text ->
                    subtitleView.text = if (subtitlesOn) text?.text.orEmpty() else ""
                }
                subtitlesAvailable = true
            }
        }
    }

    private fun togglePlayPause() {
        stayPausedOnPrepare = false
        if (!prepared) return
        if (videoView.isPlaying) {
            pauseVideo()
        } else {
            requestFocus()
            videoView.start()
            showOsd()
        }
    }

    private fun pauseVideo() {
        videoView.pause()
        saveResume()
        showOsd(sticky = true)
    }

    private fun seekBy(deltaMs: Long) {
        if (!prepared) return
        val duration = videoView.duration.toLong().coerceAtLeast(0)
        val base = if (seekTarget >= 0) seekTarget else videoView.currentPosition.toLong()
        seekTo((base + deltaMs).coerceIn(0L, (duration - 1_000).coerceAtLeast(0)))
    }

    private fun seekTo(target: Long) {
        seekTarget = target
        handler.removeCallbacks(clearSeekTarget)
        handler.postDelayed(clearSeekTarget, 1_500)
        videoView.seekTo(target.toInt())
        osd.setMessage(null)
        showOsd()
    }

    private fun neighbour(step: Int): File? {
        val index = playlist.indexOfFirst { it.absolutePath == current?.absolutePath }
        return if (index < 0) null else playlist.getOrNull(index + step)
    }

    private fun skip(step: Int) {
        val next = neighbour(step)
        if (next == null) {
            if (step < 0 && prepared) seekTo(0)
            return
        }
        saveResume()
        videoView.stopPlayback()
        play(next)
    }

    private fun onCompleted() {
        current?.let { resumeStore.clear(it.absolutePath) }
        val next = neighbour(1)
        if (next != null) {
            videoView.stopPlayback()
            play(next)
            osd.setMessage("Up next")
        } else {
            prepared = false
            finish()
        }
    }

    private fun onPlaybackError(what: Int, extra: Int) {
        val file = current
        prepared = false
        runCatching {
            (application as Y2Application).container.logger.warn(
                "Video", "playback error what=$what extra=$extra file=${file?.name}"
            )
        }
        Toast.makeText(this, "Can't play this file. Re-encode it as H.264 MP4.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveResume() {
        val file = current ?: return
        if (!prepared) return
        resumeStore.save(file.absolutePath, videoView.currentPosition.toLong(), videoView.duration.toLong())
    }

    // ---- volume ----------------------------------------------------------------

    /**
     * Follows Y2Player's volume mode. In Perceptual mode the system stream sits
     * at maximum and loudness is an app-side gain, so video must apply the same
     * gain or it would play at full volume.
     */
    private fun adjustVolume(direction: Int) {
        if (volumeMode == VolumeMode.PERCEPTUAL) {
            appVolumeLevel = VolumeCurve.adjustLevel(appVolumeLevel, direction)
            applyAppVolume()
            osd.setVolume(VolumeCurve.percentForLevel(appVolumeLevel))
        } else {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            osd.setVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
        }
        showOsd()
    }

    private fun applyAppVolume() {
        val gain = if (volumeMode == VolumeMode.PERCEPTUAL) VolumeCurve.gainForLevel(appVolumeLevel) else 1f
        runCatching { player?.setVolume(gain, gain) }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (focusHeld) return
        focusHeld = audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (!focusHeld) return
        audioManager.abandonAudioFocus(focusListener)
        focusHeld = false
    }

    // ---- on-screen display -----------------------------------------------------

    private fun showOsd(sticky: Boolean = false) {
        updateOsd()
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        handler.removeCallbacks(hideOsd)
        if (!sticky) handler.postDelayed(hideOsd, OSD_TIMEOUT_MS)
    }

    private fun updateOsd() {
        if (!prepared) {
            osd.setProgress(0, 0, playing = false)
            return
        }
        val position = if (seekTarget >= 0) seekTarget else videoView.currentPosition.toLong()
        osd.setProgress(position, videoView.duration.toLong(), videoView.isPlaying)
    }

    // ---- input -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val remappedVolume = LocalVolumeKeyPolicy.direction(
            code, event.scanCode, HardwareKeyGate.isLocalKeypad(event.deviceId)
        )
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || remappedVolume != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                adjustVolume(remappedVolume ?: if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(event)
        if (!HardwareKeyGate.accept(event, HardwareKeyGate.Source.ACTIVITY)) return true

        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) adjustVolume(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) adjustVolume(1)

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Holding toggles subtitles; a short press acts on release.
                if (down && event.repeatCount == LONG_PRESS_REPEATS) toggleSubtitles()
                if (up && !event.isCanceled && event.eventTime - event.downTime < LONG_PRESS_MS) togglePlayPause()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (down && event.repeatCount == 0) togglePlayPause()

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                seekKey(event, SHORT_SEEK_BACK_MS, HELD_SEEK_BACK_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                seekKey(event, SHORT_SEEK_FORWARD_MS, HELD_SEEK_FORWARD_MS)

            KeyEvent.KEYCODE_MEDIA_NEXT -> if (down && event.repeatCount == 0) skip(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (down && event.repeatCount == 0) {
                if (osd.hasResumeMessage() && prepared) seekTo(0) else skip(-1)
            }

            KeyEvent.KEYCODE_BACK -> if (down && event.repeatCount == 0) finish()
            KeyEvent.KEYCODE_MENU -> if (down) showOsd()
        }
        return true
    }

    private fun seekKey(event: KeyEvent, shortStep: Long, heldStep: Long) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                seekRepeats = 0
            } else {
                seekRepeats++
                if (seekRepeats % HELD_SEEK_EVERY_REPEATS == 1) seekBy(heldStep)
            }
            KeyEvent.ACTION_UP -> {
                if (seekRepeats == 0 && !event.isCanceled) seekBy(shortStep)
                seekRepeats = 0
            }
        }
    }

    private fun toggleSubtitles() {
        if (!subtitlesAvailable) {
            osd.setMessage("No subtitles for this video")
        } else {
            subtitlesOn = !subtitlesOn
            if (!subtitlesOn) subtitleView.text = ""
            osd.setMessage(if (subtitlesOn) "Subtitles on" else "Subtitles off")
        }
        showOsd()
    }

    // ---- lifecycle -------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        foreground = this
    }

    /** A transport key from a headset or Bluetooth remote. */
    private fun onRemoteMediaKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!videoView.isPlaying) togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (videoView.isPlaying) pauseVideo()
            KeyEvent.KEYCODE_MEDIA_NEXT -> skip(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skip(-1)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(SHORT_SEEK_FORWARD_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(SHORT_SEEK_BACK_MS)
        }
    }

    override fun onPause() {
        if (foreground === this) foreground = null
        // Screen off, or another app in front: stop where we are.
        stayPausedOnPrepare = true
        if (prepared && videoView.isPlaying) videoView.pause()
        saveResume()
        showOsd(sticky = true)
        super.onPause()
    }

    override fun finish() {
        saveResume()
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { videoView.stopPlayback() }
        abandonFocus()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val OSD_TIMEOUT_MS = 3_000L
        private const val SHORT_SEEK_BACK_MS = -10_000L
        private const val SHORT_SEEK_FORWARD_MS = 30_000L
        private const val HELD_SEEK_BACK_MS = -30_000L
        private const val HELD_SEEK_FORWARD_MS = 60_000L
        private const val HELD_SEEK_EVERY_REPEATS = 4
        private const val LONG_PRESS_MS = 600L
        private const val LONG_PRESS_REPEATS = 12

        @Volatile private var foreground: VideoActivity? = null

        /**
         * Called by Y2Player's media-button receiver. Returns true when a video is
         * in front, in which case music must not react. Keypad events are already
         * delivered to the activity directly and are only swallowed here.
         */
        fun consumeMediaKey(event: KeyEvent, fromLocalKeypad: Boolean): Boolean {
            val activity = foreground ?: return false
            if (!fromLocalKeypad && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val code = event.keyCode
                activity.runOnUiThread { if (!activity.isFinishing) activity.onRemoteMediaKey(code) }
            }
            return true
        }

        fun intent(context: Context, path: String, folderVideos: List<String>): Intent =
            Intent(context, VideoActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putStringArrayListExtra(EXTRA_PLAYLIST, ArrayList(folderVideos))
    }
}
