package com.lagradost.quicknovel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.text.Spanned
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver
import com.lagradost.quicknovel.receivers.BecomingNoisyReceiver
import com.lagradost.quicknovel.util.UIHelper.requestAudioFocus
import io.noties.markwon.Markwon
import org.jsoup.Jsoup
import java.util.Stack
import kotlin.collections.ArrayList

class TTSSession(val context: Context, event: (TTSHelper.TTSActionType) -> Boolean) {
    private val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    private val myNoisyAudioStreamReceiver = BecomingNoisyReceiver()
    private var mediaSession: MediaSessionCompat
    private var focusRequest: AudioFocusRequest? = null
    private val myAudioFocusListener =
        AudioManager.OnAudioFocusChangeListener {
            val pause =
                when (it) {
                    AudioManager.AUDIOFOCUS_GAIN -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> false
                    else -> true
                }
            if (pause) {
                event(TTSHelper.TTSActionType.Pause)
            }
        }
    private var isRegisterd = false

    fun register(context: Context?) {
        if (context == null || isRegisterd) return
        isRegisterd = true
        context.registerReceiver(myNoisyAudioStreamReceiver, intentFilter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.requestAudioFocus(focusRequest)
        }
    }

    fun unregister(context: Context?) {
        if (context == null || !isRegisterd) return
        isRegisterd = false
        context.unregisterReceiver(myNoisyAudioStreamReceiver)
    }

    init {
        mediaSession = TTSHelper.initMediaSession(context, event)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(myAudioFocusListener)
                build()
            }
        }
    }
}

fun generateId(type: Long, index: Int, start: Int, end: Int): Long {
    // 4b bits for type, max 16
    // 16b for chapter, max 65536
    // 22b for start, max 4 194 304
    // 22b for end, max 4 194 304

    val typeBits = type and ((1 shl 5) - 1)
    val indexBits = index.toLong() and ((1 shl 17) - 1)
    val startBits = start.toLong() and ((1 shl 23) - 1)
    val endBits = end.toLong() and ((1 shl 23) - 1)

    return typeBits or (indexBits shl 4) or (startBits shl 20) or (endBits shl 42)
}

data class TextSpan(
    val text: Spanned,
    val start: Int,
    val end: Int,
    override val index: Int,
    override var innerIndex: Int,
) : SpanDisplay() {
    override fun id(): Long {
        return generateId(0, index, start, end)
    }
}

abstract class SpanDisplay {
    val id by lazy { id() }

    abstract val index: Int
    abstract val innerIndex: Int

    protected abstract fun id(): Long
}

// uses the last text inner index
data class ChapterStartSpanned(
    override val index: Int,
    override val innerIndex: Int,
    val name: String
) : SpanDisplay() {
    override fun id(): Long {
        return generateId(1, index, 0, 0)
    }
}

data class LoadingSpanned(val url: String?, override val index: Int) : SpanDisplay() {
    override val innerIndex: Int = 0
    override fun id(): Long {
        return generateId(2, index, 0, 0)
    }
}

data class FailedSpanned(val reason: String, override val index: Int) : SpanDisplay() {
    override val innerIndex: Int = 0
    override fun id(): Long {
        return generateId(3, index, 0, 0)
    }
}

object TTSHelper {
    data class TTSLine(
        val speakOutMsg: String,
        val startIndex: Int,
        val endIndex: Int,
    )

    enum class TTSStatus {
        IsRunning,
        IsPaused,
        IsStopped,
    }

    enum class TTSActionType {
        Pause,
        Resume,
        Stop,
        Next,
    }


    fun initMediaSession(context: Context, event: (TTSActionType) -> Boolean): MediaSessionCompat {
        val mediaButtonReceiver = ComponentName(context, MediaButtonReceiver::class.java)
        return MediaSessionCompat(context, "TTS", mediaButtonReceiver, null).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        val keyEvent =
                            mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as KeyEvent?

                        if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) { // NO DOUBLE SKIP
                            return when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> event(TTSActionType.Pause)
                                KeyEvent.KEYCODE_MEDIA_PLAY -> event(TTSActionType.Resume)
                                KeyEvent.KEYCODE_MEDIA_STOP -> event(TTSActionType.Stop)
                                KeyEvent.KEYCODE_MEDIA_NEXT -> event(TTSActionType.Next)
                                else -> false
                            }
                        }

                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                }
            )
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }

    lateinit var markwon: Markwon

    fun String.replaceAfterIndex(
        oldValue: String,
        newValue: String,
        ignoreCase: Boolean = false,
        startIndex: Int = 0
    ): String {
        run {
            var occurrenceIndex: Int = indexOf(oldValue, startIndex, ignoreCase)
            // FAST PATH: no match
            if (occurrenceIndex < 0) return this

            val oldValueLength = oldValue.length
            val searchStep = oldValueLength.coerceAtLeast(1)
            val newLengthHint = length - oldValueLength + newValue.length
            if (newLengthHint < 0) throw OutOfMemoryError()
            val stringBuilder = StringBuilder(newLengthHint)

            var i = 0
            do {
                stringBuilder.append(this, i, occurrenceIndex).append(newValue)
                i = occurrenceIndex + oldValueLength
                if (occurrenceIndex >= length) break
                occurrenceIndex = indexOf(oldValue, occurrenceIndex + searchStep, ignoreCase)
            } while (occurrenceIndex > 0)
            return stringBuilder.append(this, i, length).toString()
        }
    }

    private fun parseSpan(unsegmented: Spanned, index: Int): List<TextSpan> {
        val spans: ArrayList<TextSpan> = ArrayList()

        var currentOffset = 0
        var innerIndex = 0
        var nextIndex = unsegmented.indexOf('\n')

        while (nextIndex != -1) {
            // don't include duplicate newlines
            if (currentOffset != nextIndex) {
                spans.add(
                    TextSpan(
                        unsegmented.subSequence(currentOffset, nextIndex) as Spanned,
                        currentOffset,
                        nextIndex,
                        index,
                        innerIndex
                    )
                )
                innerIndex++
            }

            currentOffset = nextIndex + 1

            nextIndex = unsegmented.indexOf('\n', currentOffset)
        }

        if (currentOffset != unsegmented.length)
            spans.add(
                TextSpan(
                    unsegmented.subSequence(currentOffset, unsegmented.length) as Spanned,
                    currentOffset,
                    unsegmented.length,
                    index,
                    innerIndex
                )
            )

        return spans
    }

    private fun getNewLineLocations(unsegmented: Spanned): Stack<Int> {
        val loc = Stack<Int>()
        val string = unsegmented.toString()


        var next = string.indexOf('\n')
        while (next > 0) {
            //avoid chains of newline characters
            next = if (string[next - 1] != '\n') {
                loc.push(next)
                string.indexOf('\n', loc.peek() + 1)
            } else {
                string.indexOf('\n', next + 1)
            }
            if (next >= string.length) next = -1
        }
        return loc
    }

    fun preParseHtml(text: String): String {
        val document = Jsoup.parse(text)

        // REMOVE USELESS STUFF THAT WONT BE USED IN A NORMAL TXT
        document.select("style").remove()
        document.select("script").remove()

        val html = document.html()
            .replace("<tr>", "<div style=\"text-align: center\">")
            .replace("</tr>", "</div>")
            .replace("<td>", "")
            .replace("</td>", " ")
            //.replace("\n\n", "\n") // REMOVES EMPTY SPACE
            .replace("...", "…") // MAKES EASIER TO WORK WITH
            .replace(
                "<p>.*<strong>Translator:.*?Editor:.*>".toRegex(),
                ""
            ) // FUCK THIS, LEGIT IN EVERY CHAPTER
            .replace(
                "<.*?Translator:.*?Editor:.*?>".toRegex(),
                ""
            )
        return text
    }

    fun parseTextToSpans(html: String, markwon: Markwon, index: Int): List<TextSpan> {

        //val index = html.indexOf("<body>")

        val renderResult = markwon.render(
            markwon.parse(
                html
                /*.replaceAfterIndex( // because markwon is fucked we have to replace newlines with breaklines and becausse I dont want 3 br on top I start after body
                "\n",
                "<br>",
                startIndex = index + 7
            )*/
            )
        )
        return parseSpan(renderResult, index = index)
    }

    private fun isValidSpeakOutMsg(msg: String): Boolean {
        return msg.isNotEmpty() && msg.isNotBlank() && msg.contains("[A-z0-9]".toRegex())
    }

    fun ttsParseText(text: String): ArrayList<TTSLine> {
        val cleanText = text
            .replace("\\.([A-z])".toRegex(), ",$1")//\.([A-z]) \.([^-\s])
            .replace("([0-9])([.:])([0-9])".toRegex(), "$1,$3") // GOOD FOR DECIMALS
            .replace(
                "([ \"“‘'])(Dr|Mr|Mrs)\\. ([A-Z])".toRegex(),
                "$1$2, $3"
            )

        assert(cleanText.length == text.length) {
            "TTS requires same length"
        }

        val ttsLines = ArrayList<TTSLine>()

        var index = 0
        while (true) {
            if (index >= text.length) {
                break
            }

            val invalidStartChars =
                arrayOf(
                    ' ', '.', ',', '\n', '\"',
                    '\'', '’', '‘', '“', '”', '«', '»', '「', '」', '…'
                )
            while (invalidStartChars.contains(text[index])) {
                index++
                if (index >= text.length) {
                    break
                }
            }

            var endIndex = Int.MAX_VALUE
            for (a in arrayOf(".", "\n", ";", "?", ":")) {
                val indexEnd = cleanText.indexOf(a, index)

                if (indexEnd == -1) continue

                if (indexEnd < endIndex) {
                    endIndex = indexEnd + 1
                }
            }


            if (endIndex > text.length) {
                endIndex = text.length
            }
            if (index >= text.length) {
                break
            }

            val invalidEndChars =
                arrayOf('\n')
            while (true) {
                var containsInvalidEndChar = false
                for (a in invalidEndChars) {
                    if (endIndex <= 0 || endIndex > text.length) break
                    if (text[endIndex - 1] == a) {
                        containsInvalidEndChar = true
                        endIndex--
                    }
                }
                if (!containsInvalidEndChar) {
                    break
                }
            }

            try {
                // THIS PART IF FOR THE SPEAK PART, REMOVING STUFF THAT IS WACK
                val message = text.substring(index, endIndex)
                var msg = message//Regex("\\p{L}").replace(message,"")
                val invalidChars =
                    arrayOf(
                        "-",
                        "<",
                        ">",
                        "_",
                        "^",
                        "«",
                        "»",
                        "「",
                        "」",
                        "—",
                        "–",
                        "¿",
                        "*",
                        "~"
                    ) // "\'", //Don't ect
                for (c in invalidChars) {
                    msg = msg.replace(c, " ")
                }
                msg = msg.replace("...", " ")

                /*.replace("…", ",")*/

                if (msg
                        .replace("\n", "")
                        .replace("\t", "")
                        .replace(".", "").isNotEmpty()
                ) {
                    if (isValidSpeakOutMsg(msg)) {
                        ttsLines.add(TTSLine(msg, index, endIndex))
                    }
                }
            } catch (t: Throwable) {
                break
            }
            index = endIndex + 1
        }

        return ttsLines
    }
}