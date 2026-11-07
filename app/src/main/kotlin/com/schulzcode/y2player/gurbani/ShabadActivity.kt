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
 * A shabad found only probably is marked as not confirmed. Centre then offers to
 * confirm it, to try the next suggestion, or to say it has none; the choice is
 * kept on the card (overrides.tsv), where the computer's script reads it too.
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
    private var shownPath: String? = null
    private var markedNone = false
    private var menuActions: List<() -> Unit> = emptyList()
    /** Where the English comes from, as the card's file says. */
    private var english = ""
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
        shownPath = null
        markedNone = false
        val source = text
        if (trackId == null) return view.showMessage(NOTHING_PLAYING)
        if (source == null || !source.available) return view.showMessage(NO_TEXT_FILE)
        val repository = (application as Y2Application).container.libraryRepository
        loader.execute {
            val path = repository.findTrack(trackId)?.takeIf { it.volumeId == CARD }?.relativePath
            val result = path?.let { runCatching { source.lookup(it) }.getOrNull() } ?: GurbaniText.Lookup.Unknown
            val attribution = if (result is GurbaniText.Lookup.Found) runCatching { source.attribution() }.getOrDefault("") else ""
            handler.post {
                if (isFinishing || shownTrackId != trackId) return@post
                shownPath = path
                english = attribution
                show(result, english)
            }
        }
    }

    private fun show(result: GurbaniText.Lookup, english: String) {
        when (result) {
            is GurbaniText.Lookup.Found -> { passage = result.passage; markedNone = false; view.show(result.passage, english) }
            GurbaniText.Lookup.MarkedNone -> { passage = null; markedNone = true; view.showMessage(MARKED_NONE) }
            GurbaniText.Lookup.Unknown -> { passage = null; markedNone = false; view.showMessage(NOT_MATCHED) }
        }
    }

    /** Centre on a shabad: settle which shabad it is. */
    private fun openChoices() {
        val source = text ?: return
        val path = shownPath ?: return
        val current = passage
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (current != null && current.kind == GurbaniText.Kind.SHABAD) {
            if (!current.confident) {
                items += "Yes, this is the shabad"
                actions += { decide(path, current.lineId, CONFIRMED) }
            }
            if (!current.confident && current.candidates.size > 1) {
                val next = (current.candidate + 1) % current.candidates.size
                items += "Try suggestion ${next + 1} of ${current.candidates.size}"
                actions += {
                    loader.execute {
                        val other = runCatching { source.nextCandidate(current) }.getOrNull()
                        handler.post { if (other != null && shownPath == path) { passage = other; view.show(other, english) } }
                    }
                }
            }
            items += if (current.confident) "Wrong shabad: show no text" else "None of these: show no text"
            actions += { decide(path, null, MARKED_NONE_SAVED) }
        } else if (markedNone) {
            items += "Show the best guess again"
            actions += { decide(path, GurbaniText.BACK_TO_GUESS, null) }
        } else return
        menuActions = actions
        view.openMenu(items)
    }

    /** Keeps the choice on the card, then shows what it means. */
    private fun decide(path: String, lineId: String?, note: String?) {
        val source = text ?: return
        loader.execute {
            val saved = source.remember(path, lineId)
            val result = runCatching { source.lookup(path) }.getOrDefault(GurbaniText.Lookup.Unknown)
            val attribution = runCatching { source.attribution() }.getOrDefault("")
            handler.post {
                if (shownPath != path) return@post
                english = attribution
                show(result, english)
                if (!saved) view.showMessage(NOT_SAVED)
                else note?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show() }
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
        if (view.menu != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> view.moveMenu(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> view.moveMenu(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                    val action = menuActions.getOrNull(view.menuFocus)
                    view.closeMenu()
                    action?.invoke()
                }
                KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) view.closeMenu()
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { wheelAt = SystemClock.uptimeMillis(); view.move(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { wheelAt = SystemClock.uptimeMillis(); view.move(1) }
            // Centre settles a shabad, or goes back to following a paath at once.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                if (passage?.follows == true) { wheelAt = Long.MIN_VALUE / 2; tick.run() } else openChoices()
            }
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
        const val NOT_MATCHED = "This recording has no shabad text: its title does not match any line of " +
            "Sri Guru Granth Sahib Ji closely enough, or it is not from it."
        const val MARKED_NONE = "You marked this recording as having no shabad. Centre shows the best guess again."
        const val MARKED_NONE_SAVED = "Kept: no shabad for this recording"
        const val CONFIRMED = "Kept: this is the shabad"
        const val NOT_SAVED = "The choice could not be saved: is the card writable?"
    }
}
