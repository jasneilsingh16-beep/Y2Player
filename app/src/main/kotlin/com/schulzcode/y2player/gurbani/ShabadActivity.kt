package com.schulzcode.y2player.gurbani

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.playback.LocalVolumeKeyPolicy
import com.schulzcode.y2player.playback.PlaybackService
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File
import java.util.concurrent.Executors

/**
 * The Shabad page for whatever is playing: the shabad of a kirtan recording, the
 * angs of a Sehaj Paath part, or the whole of a Nitnem bani. Sehaj Paath and banis
 * follow the recording as it plays; turning the wheel takes over, and the page
 * goes back to following a little after the wheel stops. When the track changes,
 * so does the page.
 *
 * Play, Next, Previous and the volume buttons still work here; Back returns.
 */
class ShabadActivity : Activity() {
    private lateinit var view: ShabadView
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var text: GurbaniText? = null
    private var binder: PlaybackService.LocalBinder? = null
    private var bound = false
    private var shownTrackId: Long? = null
    private var passage: GurbaniText.Passage? = null
    private var wheelAt = Long.MIN_VALUE / 2

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? PlaybackService.LocalBinder
            tick.run()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            val snapshot = binder?.snapshot()
            if (snapshot != null) {
                if (snapshot.currentTrackId != shownTrackId) load(snapshot.currentTrackId)
                val current = passage
                if (current != null && current.follows && SystemClock.uptimeMillis() - wheelAt > FOLLOW_AGAIN_MS) {
                    view.focusOn(PassageFollow.lineAt(current.lines, snapshot.positionMs, snapshot.durationMs))
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = ShabadView(this)
        setContentView(view)
        val root = Y2StoragePaths.roots.firstOrNull { it.id == CARD } ?: return view.showMessage(NO_CARD)
        text = GurbaniText(File(root.directory, GurbaniText.RELATIVE_PATH))
    }

    override fun onStart() {
        super.onStart()
        bound = runCatching {
            bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        binder = null
        super.onStop()
    }

    override fun onDestroy() {
        loader.execute { text?.close() }
        loader.shutdown()
        super.onDestroy()
    }

    private fun load(trackId: Long?) {
        shownTrackId = trackId
        passage = null
        val source = text
        if (trackId == null) return view.showMessage(NOTHING_PLAYING)
        if (source == null || !source.available) return view.showMessage(NO_TEXT_FILE)
        val repository = (application as Y2Application).container.libraryRepository
        loader.execute {
            val track = repository.findTrack(trackId)
            val found = track?.takeIf { it.volumeId == CARD }?.let { runCatching { source.passageFor(it.relativePath) }.getOrNull() }
            val english = if (found != null) runCatching { source.attribution() }.getOrDefault("") else ""
            handler.post {
                if (isFinishing || shownTrackId != trackId) return@post
                passage = found
                if (found == null) view.showMessage(NOT_MATCHED) else view.show(found, english)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val volume = LocalVolumeKeyPolicy.direction(code, event.scanCode, HardwareKeyGate.isLocalKeypad(event.deviceId))
            ?: when (code) {
                KeyEvent.KEYCODE_VOLUME_UP -> 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                else -> null
            }
        if (volume != null) {
            if (down) startService(Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_ADJUST_VOLUME)
                .putExtra(PlaybackService.EXTRA_VOLUME_DIRECTION, volume))
            return true
        }
        if (code == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(event)
        if (!HardwareKeyGate.accept(event, HardwareKeyGate.Source.ACTIVITY)) return true
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { wheelAt = SystemClock.uptimeMillis(); view.move(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { wheelAt = SystemClock.uptimeMillis(); view.move(1) }
            // Centre goes back to following the recording at once.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { wheelAt = Long.MIN_VALUE / 2; tick.run() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (event.repeatCount == 0) binder?.togglePlayback()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) binder?.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0) binder?.previous()
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) finish()
        }
        return true
    }

    private companion object {
        const val CARD = "sdcard"
        const val TICK_MS = 1_000L
        const val FOLLOW_AGAIN_MS = 15_000L
        const val NOTHING_PLAYING = "Nothing is playing. Start a shabad, a Sehaj Paath part or a bani, then open this page from Now Playing."
        const val NO_CARD = "The card is not mounted."
        const val NO_TEXT_FILE = "Gurbani is not on the card yet. On a computer, run tools/gurbani/build_y2_gurbani.py with the card in."
        const val NOT_MATCHED = "This recording has no shabad text yet: its title could not be matched with certainty. " +
            "It is listed in Y2Player/Gurbani/to-check.csv on the card."
    }
}
