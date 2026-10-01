package dev.wristline.watch.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.wristline.watch.R
import java.util.Locale

/** Korean when the text has any Hangul (syllables or jamo), otherwise US English. */
internal fun ttsLocale(text: String): Locale {
    val hangul = text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }
    return if (hangul) Locale.KOREAN else Locale.US
}

/**
 * Reads an answer aloud with the system text-to-speech engine. The engine is bound on the first
 * [speak] (its init is asynchronous, so the text waits for it) and released by [release]. A missing
 * engine or voice is reported with a toast. [speaking] drives the play/stop icon.
 */
internal class Reader(private val context: Context) {
    var speaking by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun toggle(text: String) {
        if (speaking) stop() else speak(text)
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            pending = text
            speaking = true
            // The init callback comes on an arbitrary thread; the state is touched on the main one.
            tts = TextToSpeech(context) { status -> main.post { onInit(status) } }
            return
        }
        if (!ready) {
            pending = text
            speaking = true
            return
        }
        start(engine, text)
    }

    fun stop() {
        pending = null
        tts?.stop()
        speaking = false
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            engine.shutdown()
            tts = null
            speaking = false
            pending = null
            Toast.makeText(context, R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    main.post { speaking = false }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { speaking = false }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { speaking = false }
                }
            },
        )
        val text = pending ?: return
        pending = null
        start(engine, text)
    }

    private fun start(engine: TextToSpeech, text: String) {
        val locale = ttsLocale(text)
        val language = engine.setLanguage(locale)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            speaking = false
            Toast.makeText(context, context.getString(R.string.tts_no_language, locale.getDisplayLanguage()), Toast.LENGTH_SHORT).show()
            return
        }
        val spoken = text.take(TextToSpeech.getMaxSpeechInputLength())
        speaking = engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
        if (!speaking) Toast.makeText(context, R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val UTTERANCE_ID = "ask"
    }
}

/**
 * Whether the watch's screen is on, as MainActivity's screen-off receiver last heard: some watches
 * keep the activity resumed while the screen is off or dozing.
 */
internal val LocalScreenOn = compositionLocalOf { true }

/**
 * A [Reader] for this screen. It stops when the activity stops (another app or the watch face in
 * front, or this screen covered by another activity) or the screen turns off: Android 17 silences
 * audio from an app that is not visible or whose screen is off, and nobody is reading along then.
 * It lets the engine go when the screen leaves.
 */
@Composable
internal fun rememberReader(): Reader {
    val context = LocalContext.current.applicationContext
    val reader = remember { Reader(context) }
    DisposableEffect(reader) {
        onDispose { reader.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reader.stop() }
    val screenOn = LocalScreenOn.current
    LaunchedEffect(screenOn) { if (!screenOn) reader.stop() }
    return reader
}
