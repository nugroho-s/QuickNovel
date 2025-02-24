package com.lagradost.quicknovel

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.session.MediaSessionCompat
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.text.getSpans
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.media.session.MediaButtonReceiver
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.Target
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.lagradost.quicknovel.BookDownloader2Helper.getQuickChapter
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.mapper
import com.lagradost.quicknovel.DataStore.removeKey
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.databinding.ReadBottomSettingsBinding
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.receivers.BecomingNoisyReceiver
import com.lagradost.quicknovel.services.TTSPauseService
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.TextAdapter
import com.lagradost.quicknovel.util.Apis.Companion.getApiFromNameNull
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog
import com.lagradost.quicknovel.util.UIHelper
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
import com.lagradost.quicknovel.util.UIHelper.popupMenu
import com.lagradost.quicknovel.util.UIHelper.requestAudioFocus
import com.lagradost.quicknovel.util.toDp
import com.lagradost.quicknovel.util.toPx
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.ImageSizeResolver
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.*
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt


const val OVERFLOW_NEXT_CHAPTER_DELTA = 600
const val OVERFLOW_NEXT_CHAPTER_SHOW_PROCENTAGE = 10
const val OVERFLOW_NEXT_CHAPTER_NEXT = 90
const val OVERFLOW_NEXT_CHAPTER_SAFESPACE = 20
const val TOGGLE_DISTANCE = 20f

const val TTS_CHANNEL_ID = "QuickNovelTTS"
const val TTS_NOTIFICATION_ID = 133742

const val DEBUGGING = false

fun clearTextViewOfSpans(tv: TextView) {
    val wordToSpan: Spannable = SpannableString(tv.text)
    val spans = wordToSpan.getSpans<android.text.Annotation>(0, tv.text.length)
    for (s in spans) {
        wordToSpan.removeSpan(s)
    }
    tv.setText(wordToSpan, TextView.BufferType.SPANNABLE)
}

fun setHighLightedText(tv: TextView?, start: Int, end: Int): Boolean {
    if (tv == null) return false
    try {
        val wordToSpan: Spannable = SpannableString(tv.text)
        val spans = wordToSpan.getSpans<android.text.Annotation>(0, tv.text.length)
        for (s in spans) {
            wordToSpan.removeSpan(s)
        }

        wordToSpan.setSpan(
            android.text.Annotation("", "rounded"),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tv.setText(wordToSpan, TextView.BufferType.SPANNABLE)

        return true
    } catch (e: Exception) {
        return false
    }
}

enum class TTSActionType {
    Pause,
    Resume,
    Stop,
}

class ReadActivity : AppCompatActivity(), ColorPickerDialogListener {
    companion object {
        var markwon: Markwon? = null
        lateinit var readActivity: ReadActivity
        lateinit var images: ArrayList<ImageView>

        var defFont: Typeface? = null
        fun getAllFonts(): Array<File>? {
            val path = "/system/fonts"
            val file = File(path)
            return file.listFiles()
        }

        fun parseSpan(unsegmented: Spanned): List<Spanned> {
            val spans: ArrayList<Spanned> = ArrayList()

            //get locations of '/n'
            val loc = getNewLineLocations(unsegmented)
            loc.push(unsegmented.length)

            //divides up a span by each new line character position in loc
            while (!loc.isEmpty()) {
                val end = loc.pop()
                val start = if (loc.isEmpty()) 0 else loc.peek()
                spans.add(0, unsegmented.subSequence(start, end) as Spanned)
            }
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
    }

    var isFromEpub = true
    lateinit var book: Book
    lateinit var quickdata: QuickStreamData

    private fun getBookSize(): Int {
        return if (isFromEpub) book.tableOfContents.tocReferences.size else quickdata.data.size
    }

    private fun getBookTitle(): String {
        return if (isFromEpub) book.title else quickdata.meta.name
    }

    private suspend fun getBookBitmap(): Bitmap? {
        if (bookCover == null) {
            var byteArray: ByteArray? = null

            if (isFromEpub) {
                if (book.coverImage != null && book.coverImage.data != null)
                    byteArray = book.coverImage.data
            } else {
                val poster = quickdata.poster
                if (poster != null) {
                    try {
                        byteArray = app.get(poster).okhttpResponse.body.bytes()
                    } catch (e: Exception) {
                        println("BITMAP ERROR: $e")
                    }
                }
            }

            if (byteArray != null)
                bookCover = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        }
        return bookCover
    }

    private fun getChapterName(index: Int): String {
        return if (isFromEpub) book.tableOfContents.tocReferences?.get(index)?.title
            ?: "Chapter ${index + 1}" else quickdata.data[index].name
    }

    private suspend fun Context.getChapterData(index: Int, forceReload: Boolean = false): String? {
        println("getChapterData $index")
        val text =
            (if (isFromEpub) book.tableOfContents.tocReferences[index].resource.reader.readText() else {
                main {
                    binding.loadingText.text = quickdata.data[index].url
                }

                getQuickChapter(
                    quickdata.meta,
                    quickdata.data[index],
                    index,
                    forceReload
                )?.html ?: return null
            })
        val document = Jsoup.parse(text)

        // REMOVE USELESS STUFF THAT WONT BE USED IN A NORMAL TXT
        document.select("style").remove()
        document.select("script").remove()

        for (a in document.allElements) {
            if (a != null && a.hasText() &&
                (a.text() == chapterName || (a.tagName() == "h3" && a.text()
                    .startsWith("Chapter ${index + 1}")))
            ) { // IDK, SOME MIGHT PREFER THIS SETTING??
                a.remove() // THIS REMOVES THE TITLE
                break
            }
        }

        return document.html()
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
    }


    private fun TextView.setFont(file: File?) {
        if (file == null) {
            this.typeface = defFont
        } else {
            this.typeface = Typeface.createFromFile(file)
        }
    }

    private fun setReadTextFont(file: File?, nameCallback: ((String) -> Unit)? = null) {
        if (defFont == null) defFont = binding.readText.typeface
        setKey(EPUB_FONT, file?.name ?: "")
        binding.readText.setFont(file)
        binding.readTitleText.setFont(file)
        binding.readTitleText.setTypeface(binding.readText.typeface, Typeface.BOLD)
        nameCallback?.invoke(UIHelper.parseFontFileName(file?.name))
    }

    private fun showFonts(nameCallback: (String) -> Unit) {
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(R.layout.font_bottom_sheet)
        val res = bottomSheetDialog.findViewById<ListView>(R.id.sort_click)!!

        val fonts = getAllFonts() ?: return
        val items = fonts.toMutableList() as ArrayList<File?>
        items.add(0, null)

        val currentName = getKey(EPUB_FONT) ?: ""
        val sotringIndex = items.indexOfFirst { it?.name ?: "" == currentName }

        /* val arrayAdapter = ArrayAdapter<String>(this, R.layout.sort_bottom_single_choice)
         arrayAdapter.addAll(sortingMethods.toMutableList())
         res.choiceMode = AbsListView.CHOICE_MODE_SINGLE
         res.adapter = arrayAdapter
         res.setItemChecked(sotringIndex, true)*/
        val adapter = FontAdapter(this, sotringIndex, items)

        res.adapter = adapter
        res.setOnItemClickListener { _, _, which, _ ->
            setReadTextFont(items[which], nameCallback)
            stopTTS()
            loadTextLines()
            globalTTSLines.clear()
            bottomSheetDialog.dismiss()
        }
        bottomSheetDialog.show()
    }

    fun callOnPause(): Boolean {
        if (!isTTSPaused) {
            isTTSPaused = true
            return true
        }
        return false
    }

    fun callOnPlay(): Boolean {
        if (isTTSPaused) {
            isTTSPaused = false
            return true
        }
        return false
    }

    fun callOnStop(): Boolean {
        if (isTTSRunning) {
            stopTTS()
            return true
        }
        return false
    }

    fun callOnNext(): Boolean {
        if (isTTSRunning) {
            nextTTSLine()
            return true
        } else if (isTTSPaused) {
            isTTSRunning = true
            nextTTSLine()
            return true
        }
        return false
    }

    private val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    private val myNoisyAudioStreamReceiver = BecomingNoisyReceiver()

    private var tts: TextToSpeech? = null

    private var bookCover: Bitmap? = null

    override fun onColorSelected(dialog: Int, color: Int) {
        when (dialog) {
            0 -> setBackgroundColor(color)
            1 -> setTextColor(color)
        }
    }

    override fun onDialogDismissed(dialog: Int) {
        updateImages()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasRunningTTS = isTTSRunning
        stopTTS()
        globalTTSLines.clear()

        if (isHidden) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
        binding.readText.post {
            loadTextLines()
            if (wasRunningTTS) {
                startTTS(readFromIndex)
            }
        }
    }

    private fun getSpeakIdFromIndex(id: Int, startIndex: Int, endIndex: Int): String {
        return "$speakId:$startIndex:$endIndex" //TODO FIX
    }

    private fun getSpeakIdFromLine(id: Int, line: TTSLine): String {
        return getSpeakIdFromIndex(id, line.startIndex, line.endIndex)
    }

    // USING Queue system because it is faster by about 0.2s
    private var currentTTSQueue: String? = null
    private fun speakOut(
        ttsLine: TTSLine,
        ttsLineQueue: TTSLine?,
    ) {
        canSpeak = false
        if (ttsLine.speakOutMsg.isEmpty() || ttsLine.speakOutMsg.isBlank()) {
            showToast("No data")
            return
        }
        if (tts != null) {
            if (currentTTSQueue != ttsLine.speakOutMsg) {
                speakId++
                tts!!.speak(
                    ttsLine.speakOutMsg,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    getSpeakIdFromLine(speakId, ttsLine)
                )
            }
            if (ttsLineQueue != null) {
                tts!!.speak(
                    ttsLineQueue.speakOutMsg,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    getSpeakIdFromLine(speakId + 1, ttsLineQueue)
                )
                currentTTSQueue = ttsLineQueue.speakOutMsg
            }
        }
    }

    private fun getCurrentTTSLineScroll(): Int? {
        if (ttsStatus == TTSStatus.IsRunning || ttsStatus == TTSStatus.IsPaused) {
            try {
                if (readFromIndex >= 0 && readFromIndex < globalTTSLines.size) {
                    val line = globalTTSLines[readFromIndex]
                    val textLine = getMinMax(line.startIndex, line.endIndex)
                    if (textLine != null) {
                        return textLine.max + getLineOffset() - binding.readToolbarHolder.height + binding.readerLinContainer.paddingTop //dimensionFromAttribute(R.attr.actionBarSize))
                    }
                }
            } catch (e: Exception) {
                //IDK SMTH HAPPEND
            }
        }
        return null
    }

    public override fun onDestroy() {
        val scroll = getCurrentTTSLineScroll()
        if (scroll != null) {
            setKey(
                EPUB_CURRENT_POSITION_SCROLL,
                getBookTitle(), scroll
            )
        }

        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            cancel(TTS_NOTIFICATION_ID)
        }

        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        mMediaSessionCompat.release()

        super.onDestroy()
    }

    lateinit var path: String

    var canSpeak = true
    private var speakId = 0

    enum class TTSStatus {
        IsRunning,
        IsPaused,
        IsStopped,
    }

    private var _ttsStatus = TTSStatus.IsStopped

    private val myAudioFocusListener =
        AudioManager.OnAudioFocusChangeListener {
            val pause =
                when (it) {
                    AudioManager.AUDIOFOCUS_GAIN -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> false
                    else -> true
                }
            if (pause && isTTSRunning) {
                isTTSPaused = true
            }
        }
    var focusRequest: AudioFocusRequest? = null

    private fun initTTSSession() {
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

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Text To Speech"//getString(R.string.channel_name)
            val descriptionText =
                "The TTS notification channel" //getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(TTS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    var ttsStatus: TTSStatus
        get() = _ttsStatus
        set(value) {
            _ttsStatus = value
            if (value == TTSStatus.IsRunning) {
                //   mediaSession?.isActive = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requestAudioFocus(focusRequest)
                }
                try {
                    registerReceiver(myNoisyAudioStreamReceiver, intentFilter)
                } catch (e: Exception) {
                    println(e)
                }
            } else if (value == TTSStatus.IsStopped) {
                // mediaSession?.isActive = false
                try {
                    unregisterReceiver(myNoisyAudioStreamReceiver)
                } catch (e: Exception) {
                    println(e)
                }
            }

            if (value == TTSStatus.IsStopped) {
                with(NotificationManagerCompat.from(this)) {
                    // notificationId is a unique int for each notification that you must define
                    cancel(TTS_NOTIFICATION_ID)
                }
            } else {
                main {
                    val builder = NotificationCompat.Builder(this, TTS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_baseline_volume_up_24) //TODO NICE ICON
                        .setContentTitle(getBookTitle())
                        .setContentText(chapterName)

                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .setOngoing(true)

                    val icon = withContext(Dispatchers.IO) { getBookBitmap() }
                    if (icon != null) builder.setLargeIcon(icon)

                    builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle())
                    // .setMediaSession(mediaSession?.sessionToken))

                    val actionTypes: MutableList<TTSActionType> = ArrayList()

                    if (value == TTSStatus.IsPaused) {
                        actionTypes.add(TTSActionType.Resume)
                    } else if (value == TTSStatus.IsRunning) {
                        actionTypes.add(TTSActionType.Pause)
                    }
                    actionTypes.add(TTSActionType.Stop)

                    for ((index, i) in actionTypes.withIndex()) {
                        val resultIntent = Intent(this, TTSPauseService::class.java)
                        resultIntent.putExtra("id", i.ordinal)

                        val pending: PendingIntent = PendingIntent.getService(
                            this, 3337 + index,
                            resultIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                PendingIntent.FLAG_MUTABLE else 0
                        )

                        builder.addAction(
                            NotificationCompat.Action(
                                when (i) {
                                    TTSActionType.Resume -> R.drawable.ic_baseline_play_arrow_24
                                    TTSActionType.Pause -> R.drawable.ic_baseline_pause_24
                                    TTSActionType.Stop -> R.drawable.ic_baseline_stop_24
                                }, when (i) {
                                    TTSActionType.Resume -> "Resume"
                                    TTSActionType.Pause -> "Pause"
                                    TTSActionType.Stop -> "Stop"
                                }, pending
                            )
                        )
                    }

                    with(NotificationManagerCompat.from(this)) {
                        // notificationId is a unique int for each notification that you must define
                        notify(TTS_NOTIFICATION_ID, builder.build())
                    }
                }
            }

            binding.readerBottomViewTts.isVisible = isTTSRunning && !isHidden
            binding.readerBottomView.isVisible =!isTTSRunning && !isHidden
            binding.ttsActionPausePlay.setImageResource(
                when (value) {
                    TTSStatus.IsPaused -> R.drawable.ic_baseline_play_arrow_24
                    TTSStatus.IsRunning -> R.drawable.ic_baseline_pause_24
                    else -> { // IDK SHOULD BE AN INVALID STATE
                        R.drawable.ic_baseline_play_arrow_24
                    }
                }
            )
        }

    private var isTTSRunning: Boolean
        get() = ttsStatus != TTSStatus.IsStopped
        set(running) {
            ttsStatus = if (running) TTSStatus.IsRunning else TTSStatus.IsStopped
        }

    var isTTSPaused: Boolean
        get() = ttsStatus == TTSStatus.IsPaused
        set(paused) {
            ttsStatus = if (paused) TTSStatus.IsPaused else TTSStatus.IsRunning
            if (paused) {
                readFromIndex--
                interruptTTS()
            } else {
                playDummySound() // FUCK ANDROID
            }
        }

    private var lockTTS = true
    private val lockTTSOnPaused = false
    private var scrollWithVol = true
    var minScroll: Int? = 0
    var maxScroll: Int? = 0

    var isHidden = true

    private fun hideSystemUI() {
        isHidden = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.readerContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        fun lowerBottomNav(v: View) {
            v.translationY = 0f
            ObjectAnimator.ofFloat(v, "translationY", v.height.toFloat()).apply {
                duration = 200
                start()
            }.doOnEnd {
                v.visibility = View.GONE
            }
        }

        lowerBottomNav(binding.readerBottomView)
        lowerBottomNav(binding.readerBottomViewTts)

        binding.readToolbarHolder.translationY = 0f
        ObjectAnimator.ofFloat(
            binding.readToolbarHolder,
            "translationY",
            -binding.readToolbarHolder.height.toFloat()
        ).apply {
            duration = 200
            start()
        }.doOnEnd {
            binding.readToolbarHolder.visibility = View.GONE
        }
    }

    // Shows the system bars by removing all the flags
// except for the ones that make the content appear under the system bars.
    private fun showSystemUI() {
        isHidden = false
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(
            window,
            binding.readerContainer
        ).show(WindowInsetsCompat.Type.systemBars())

        binding.readToolbarHolder.visibility = View.VISIBLE

        binding.readerBottomView.isGone = isTTSRunning
        binding.readerBottomViewTts.isVisible = isTTSRunning

        fun higherBottomNavView(v: View) {
            v.translationY = v.height.toFloat()
            ObjectAnimator.ofFloat(v, "translationY", 0f).apply {
                duration = 200
                start()
            }
        }

        higherBottomNavView(binding.readerBottomView)
        higherBottomNavView(binding.readerBottomViewTts)

        binding.readToolbarHolder.translationY = -binding.readToolbarHolder.height.toFloat()

        ObjectAnimator.ofFloat(binding.readToolbarHolder, "translationY", 0f).apply {
            duration = 200
            start()
        }
    }

    private fun Context.updateTimeText() {
        val string = if (this.updateTwelveHourTime()) "hh:mm a" else "HH:mm"

        val currentTime: String = SimpleDateFormat(string, Locale.getDefault()).format(Date())

        binding.readTime.text = currentTime
        binding.readTime.postDelayed({ -> updateTimeText() }, 1000)

    }


    private var hasTriedToFillNextChapter = false
    private fun fillNextChapter(): Boolean {
        if (hasTriedToFillNextChapter || isFromEpub) {
            return false
        }
        hasTriedToFillNextChapter = true

        try {
            val elements =
                Jsoup.parse(currentHtmlText).allElements.filterNotNull()

            for (element in elements) {
                val href = element.attr("href") ?: continue

                val text =
                    element.ownText().replace(Regex("[\\[\\]().,|{}<>]"), "").trim()
                if (text.equals("next", true) || text.equals(
                        "next chapter",
                        true
                    ) || text.equals("next part", true)
                ) {
                    val name = RedditProvider.getName(href) ?: "Next"
                    quickdata.data.add(ChapterData(name, href, null, null))
                    chapterTitles.add(getChapterName(maxChapter))
                    maxChapter += 1
                    return true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    private fun loadNextChapter(): Boolean {
        return if (currentChapter >= maxChapter - 1) {
            if (fillNextChapter()) {
                loadNextChapter()
            } else {
                showToast("No more chapters", Toast.LENGTH_SHORT)
                false
            }
        } else {
            ioSafe {
                loadChapter(currentChapter + 1, true)
                binding.readScroll.smoothScrollTo(0, 0)
            }

            true
        }
    }

    private fun loadPrevChapter(): Boolean {
        return if (currentChapter <= 0) {
            false
            //Toast.makeText(this, "No more chapters", Toast.LENGTH_SHORT).show()
        } else {
            ioSafe {
                loadChapter(currentChapter - 1, false)
            }
            true
        }
    }

    private fun View.fixLine(offset: Int) {
        // this.setPadding(0, 200, 0, 0)
        val layoutParams =
            this.layoutParams as FrameLayout.LayoutParams// FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,offset)
        layoutParams.setMargins(0, offset, 0, 0)
        this.layoutParams = layoutParams
    }

    private fun getLineOffset(): Int {
        return binding.readTitleText.height + binding.readText.paddingTop
    }

    var lastChange: TextLine? = null

    private fun createTempBottomPadding(size: Int) {
        val parms = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, size)
        parms.gravity = Gravity.BOTTOM

        binding.readTempBottomMargin.visibility = View.VISIBLE
        binding.readTempBottomMargin.layoutParams = parms
    }

    private fun changeLine(line: TextLine) {//, moveToTextBottom: Boolean) {
        val offset = getLineOffset()

        binding.readScroll.let {
            it.scrollTo(0, line.topPosition + offset)
            for (tLine in textLines!!) {
                if (tLine.bottomPosition + offset > mainScrollY + it.height) {
                    val size =
                        (mainScrollY + it.height) - (tLine.topPosition + offset) + binding.readOverlay.height
                    createTempBottomPadding(size)
                    if (DEBUGGING) {
                        binding.readTempBottomMargin.setBackgroundResource(R.color.colorPrimary)
                        binding.lineTopExtra.fixLine(tLine.topPosition + offset)
                    }
                    break
                }
            }
        }

        /*if (DEBUGGING) {
            line_top.visibility = View.VISIBLE
            line_bottom.visibility = View.VISIBLE
            line_top_extra.visibility = View.VISIBLE

            line_top.fixLine(line.topPosition + offset)
            line_bottom.fixLine(line.bottomPosition + offset)

            if (lastChange != null) {
                setHighLightedText(read_text, lastChange!!.startIndex, lastChange!!.endIndex)
            } else {
                setHighLightedText(read_text, line.startIndex, line.endIndex)
            }
            lastChange = line
        }*/
    }

    override fun onBackPressed() {
        super.onBackPressed()
        kill()
    }

    private fun kill() {
        with(NotificationManagerCompat.from(this)) { // KILLS NOTIFICATION
            cancel(TTS_NOTIFICATION_ID)
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            kill()
            return true
        }
        if (scrollWithVol && isHidden && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            val offset = getLineOffset()
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (isTTSRunning) {
                        nextTTSLine()
                        return true
                    }

                    if (binding.readScroll.scrollY >= getScrollRange()) {
                        loadNextChapter()
                        return true
                    }
                    for (t in textLines!!) {
                        if (t.bottomPosition + offset > mainScrollY + binding.readScroll.height) {
                            val str = try {
                                binding.readText.text.substring(t.startIndex, t.endIndex) ?: "valid"
                            } catch (e: Exception) {
                                "valid"
                            }
                            if (str.isBlank()) { // skips black areas
                                continue
                            }
                            changeLine(t)
                            binding.readScroll.fling(0)
                            return true
                        }
                    }
                    loadNextChapter()
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (isTTSRunning) {
                        prevTTSLine()
                        return true
                    }

                    if (binding.readScroll.scrollY <= 0) {
                        loadPrevChapter()
                        return true
                    }
                    for ((index, textLine) in textLines!!.withIndex()) {
                        if (textLine.topPosition + offset >= mainScrollY) { // finds current top
                            if (index == 0) {
                                loadPrevChapter()
                                return true
                            }
                            for (returnIndex in index downTo 0) {
                                val returnLine = textLines!![returnIndex]
                                if (textLine.bottomPosition - returnLine.topPosition > binding.readScroll.height) {
                                    changeLine(returnLine)
                                    binding.readScroll.fling(0)
                                    return true
                                }
                            }
                        }
                    }
                    loadPrevChapter()
                    return true
                }
            }
        }
        return false
    }

    data class TextLine(
        val layout: Layout,
        val lineIndex: Int
    ) {
        /*

                        lay.getLineStart(i),
                        lay.getLineEnd(i),
                        lay.getLineTop(i),
                        lay.getLineBottom(i),
         */
        val startIndex: Int get() = layout.getLineStart(lineIndex)
        val endIndex: Int get() = layout.getLineEnd(lineIndex)
        val topPosition: Int get() = layout.getLineTop(lineIndex)
        val bottomPosition: Int get() = layout.getLineBottom(lineIndex)
    }

    private lateinit var chapterTitles: ArrayList<String>
    private var maxChapter: Int = 0

    private var currentChapter = 0
    private var textLines: ArrayList<TextLine>? = null
    private var mainScrollY = 0
    private var scrollYOverflow = 0f

    private var startY: Float? = null
    private var scrollStartY: Float = 0f
    private var scrollStartX: Float = 0f
    private var scrollDistance: Float = 0f

    private var overflowDown: Boolean = true
    private var chapterName: String? = null

    @SuppressLint("SetTextI18n")
    fun updateChapterName(scrollX: Int) {
        if (binding.readScroll.height == 0) {
            binding.readChapterName.text = chapterName!!
            return
        }
        val height = maxOf(1, getScrollRange())
        val chaptersTotal = ceil(height.toDouble() / binding.readScroll.height).toInt()
        val currentChapter = binding.readScroll.scrollY * chaptersTotal / height
        binding.readChapterName.text =
            "${chapterName!!} (${currentChapter + 1}/${chaptersTotal + 1})"
    }

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

    private var currentText = ""
    private var currentHtmlText = ""
    private suspend fun Context.loadChapter(
        chapterIndex: Int,
        scrollToTop: Boolean,
        scrollToRemember: Boolean = false,
        forceReload: Boolean = false
    ) {
        println("loadChapter = $chapterIndex")
        if (maxChapter == 0) return
        if (chapterIndex > maxChapter - 1) {
            if (isFromEpub) {
                loadChapter(maxChapter - 1, scrollToTop, scrollToRemember, forceReload)
                return
            } else {
                for (i in maxChapter - 1 until chapterIndex) {
                    hasTriedToFillNextChapter = false
                    currentHtmlText = getChapterData(i, false) ?: break
                    if (!fillNextChapter()) {
                        break
                    }
                }
                loadChapter(maxChapter - 1, scrollToTop, scrollToRemember, forceReload)
                return
            }
        }

        main {
            setKey(EPUB_CURRENT_POSITION, getBookTitle(), chapterIndex)
            val txt = if (isFromEpub) {
                getChapterData(chapterIndex, forceReload)
            } else {
                binding.readLoading.isVisible = true
                binding.readNormalLayout.alpha = 0f
                withContext(Dispatchers.IO) {
                    getChapterData(chapterIndex, forceReload)
                }
            }

            if (!isFromEpub)
                fadeInText()

            if (txt == null) {
                showToast("Error loading chapter", Toast.LENGTH_SHORT)
                if (!isFromEpub && !forceReload) {
                    loadChapter(chapterIndex, scrollToTop, scrollToRemember, true)
                }
                return@main // TODO FIX REAL INTERACT BUTTON
            }

            fun scroll() {
                if (scrollToRemember) {
                    val scrollToY = getKey<Int>(EPUB_CURRENT_POSITION_SCROLL, getBookTitle(), null)
                    if (scrollToY != null) {
                        binding.readScroll.apply {
                            scrollTo(0, scrollToY)
                            fling(0)
                        }
                        return
                    }
                }

                val scrollToY = if (scrollToTop) 0 else getScrollRange()
                binding.readScroll.apply {
                    scrollTo(0, scrollToY)
                    fling(0)
                }
                updateChapterName(scrollToY)
            }

            binding.readText.alpha = 0f

            chapterName = getChapterName(chapterIndex)

            currentChapter = chapterIndex
            hasTriedToFillNextChapter = false

            binding.readToolbar.apply {
                title = getBookTitle()
                subtitle = chapterName

            }
            binding.readTitleText.text = chapterName

            updateChapterName(0)
            markwon =
                markwon ?: Markwon.builder(readActivity) // automatically create Glide instance
                    //.usePlugin(GlideImagesPlugin.create(context)) // use supplied Glide instance
                    //.usePlugin(GlideImagesPlugin.create(Glide.with(context))) // if you need more control
                    .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(false) })
                    .usePlugin(GlideImagesPlugin.create(object : GlideImagesPlugin.GlideStore {
                        @NonNull
                        override fun load(@NonNull drawable: AsyncDrawable): RequestBuilder<Drawable> {
                            return try {
                                var newUrl = drawable.destination.substringAfter("&url=")
                                if (!isFromEpub) {
                                    getApiFromNameNull(quickdata.meta.apiName)?.fixUrlNull(newUrl)
                                        ?.let {
                                            newUrl = it
                                        }
                                }

                                val url =
                                    if (newUrl.length > 8) { // we assume that it is not a stub url by length > 8
                                        URLDecoder.decode(newUrl)
                                    } else {
                                        drawable.destination
                                    }
                                /* TODO https://github.com/bumptech/glide/tree/master/integration/okhttp3/src/main/java/com/bumptech/glide/integration/okhttp3
                                Glide.get(readActivity).apply {
                                    registry.replace(GlideUrl::class.java, InputStream::class.java, com.bumptech.glide OkHttpUrlLoader.Factory(OkHttpClient()
                                        .newBuilder()
                                        .ignoreAllSSLErrors()
                                        .build()))
                                }*/
                                Glide.with(readActivity)
                                    .load(GlideUrl(url) { mapOf("user-agent" to USER_AGENT) })
                            } catch (e: Exception) {
                                logError(e)
                                Glide.with(readActivity)
                                    .load(R.drawable.books_emoji) // might crash :)
                            }
                        }

                        override fun cancel(target: Target<*>) {
                            try {
                                Glide.with(readActivity).clear(target)
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                    }))
                    .usePlugin(object :
                        AbstractMarkwonPlugin() {
                        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                            builder.imageSizeResolver(object : ImageSizeResolver() {
                                override fun resolveImageSize(drawable: AsyncDrawable): Rect {
                                    //val imageSize = drawable.imageSize
                                    return drawable.result.bounds
                                }
                            })
                        }
                    })
                    .usePlugin(SoftBreakAddsNewLinePlugin.create())
                    .build()

            /*val index = txt.indexOf("<body>")
            markwon?.apply {
                // Spannable
                val renderResult = render(parse(
                    txt.replaceAfterIndex( // because markwon is fucked we have to replace newlines with breaklines and becausse I dont want 3 br on top I start after body
                        "\n",
                        "<br>",
                        startIndex = index + 7
                    )//.replaceFirst(Regex("""[\\s*<br>\\s*\\n*]*"""), "")
                ))
                val result = parseSpan(renderResult)

                val textAdapter = TextAdapter()
                binding.realText.layoutManager = GridLayoutManager(binding.realText.context, 1)
                binding.realText.adapter = textAdapter


                textAdapter.submitList(result)//renderResult.getSpans<Any>().toMutableList())
                /*(spanbuilder as? SpannableStringBuilder)?.let { builder ->
                    val spans = builder.getSpans<CharSequence>()
                    println("SPANs: $spans")
                    binding.readText.setText(spanbuilder)
                    for (span in spans) {
                        span.toSpannable()

                    }
                }

                println(spanbuilder)*/
            }*/

            /*markwon?.setMarkdown(
                binding.readText,
                txt.replaceAfterIndex( // because markwon is fucked we have to replace newlines with breaklines and becausse I dont want 3 br on top I start after body
                    "\n",
                    "<br>",
                    startIndex = index + 7
                )//.replaceFirst(Regex("""[\\s*<br>\\s*\\n*]*"""), "")
            ) ?:*/ run {
                val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(
                        txt,
                        Html.FROM_HTML_MODE_LEGACY,
                        null,
                        null
                    ) as Spannable
                } else {
                    Html.fromHtml(txt, null, null) as Spannable
                }

                binding.readText.text = spanned
            }


            //println("TEXT:" + document.html())
            //read_text?.text = spanned
            currentText = binding.readText.text.toString()
            currentHtmlText = txt
            binding.readText.post {
                loadTextLines()
                scroll()
                binding.readText.alpha = 1f

                globalTTSLines.clear()
                interruptTTS()
                if (isTTSRunning || isTTSPaused) { // or Paused because it will fuck up otherwise
                    startTTS(true)
                }
            }
        }
    }

    private fun loadTextLines() {
        val lines = ArrayList<TextLine>()

        val lay = binding.readText.layout ?: return
        for (i in 0..lay.lineCount) {
            try {
                lines.add(
                    TextLine(
                        lay,
                       // lay.getLineStart(i),
                        //lay.getLineEnd(i),
                        //lay.getLineTop(i),
                        //lay.getLineBottom(i),
                        i,
                    )
                )
            } catch (e: Exception) {
                println("EX: $e")
            }
        }

        textLines = lines
    }

    private fun interruptTTS() {
        currentTTSQueue = null
        if (tts != null) {
            tts!!.stop()
        }
        canSpeak = true
    }

    private fun nextTTSLine() {
        //readFromIndex++
        interruptTTS()
    }

    private fun prevTTSLine() {
        readFromIndex -= 2
        interruptTTS()
    }

    fun stopTTS() {
        runOnUiThread {
            val scroll = getCurrentTTSLineScroll()
            if (scroll != null) {
                binding.readScroll.scrollTo(0, scroll)
            }
            clearTextViewOfSpans(binding.readText)
            interruptTTS()
            ttsStatus = TTSStatus.IsStopped
        }
    }

    data class TTSLine(
        val speakOutMsg: String,
        val startIndex: Int,
        val endIndex: Int,
    )

    var globalTTSLines = ArrayList<TTSLine>()

    private fun prepareTTS(text: String, callback: (Boolean) -> Unit) {
        val job = Job()
        val uiScope = CoroutineScope(Dispatchers.Main + job)
        uiScope.launch {
            // CLEAN TEXT IS JUST TO MAKE SURE THAT THE TTS SPEAKER DOES NOT SPEAK WRONG, MUST BE SAME LENGTH
            val cleanText = text
                .replace("\\.([A-z])".toRegex(), ",$1")//\.([A-z]) \.([^-\s])
                .replace("([0-9])([.:])([0-9])".toRegex(), "$1,$3") // GOOD FOR DECIMALS
                .replace(
                    "([ \"“‘'])(Dr|Mr|Mrs)\\. ([A-Z])".toRegex(),
                    "$1$2, $3"
                ) // Doctor or Mister

            val ttsLines = ArrayList<TTSLine>()

            var index = 0
            while (true) {
                if (index >= text.length) {
                    globalTTSLines = ttsLines
                    callback.invoke(true)
                    return@launch
                }

                val invalidStartChars =
                    arrayOf(
                        ' ', '.', ',', '\n', '\"',
                        '\'', '’', '‘', '“', '”', '«', '»', '「', '」', '…'
                    )
                while (invalidStartChars.contains(text[index])) {
                    index++
                    if (index >= text.length) {
                        globalTTSLines = ttsLines
                        callback.invoke(true)
                        return@launch
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
                    globalTTSLines = ttsLines
                    callback.invoke(true)
                    return@launch
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
                        if (textLines == null)
                            return@launch
                    }
                } catch (e: Exception) {
                    println(e)
                    return@launch
                }
                index = endIndex + 1
            }
        }
    }

    data class ScrollLine(val min: Int, val max: Int)

    private fun getMinMax(startIndex: Int, endIndex: Int): ScrollLine? {
        if (textLines == null || textLines?.size == 0) {
            loadTextLines()
        }
        val text = textLines ?: return null

        var max: Int? = null
        var min: Int? = null
        for (t in text) {
            if (t.endIndex > startIndex && max == null) {
                max = t.topPosition
            }
            if (t.endIndex > endIndex && min == null) {
                min = t.bottomPosition
            }
            if (max != null && min != null) return ScrollLine(min, max)
        }
        return null
    }

    private fun isValidSpeakOutMsg(msg: String): Boolean {
        return msg.isNotEmpty() && msg.isNotBlank() && msg.contains("[A-z0-9]".toRegex())
    }

    private var readFromIndex = 0
    private var currentTTSRangeStartIndex = 0
    private var currentTTSRangeEndIndex = 0
    private fun runTTS(index: Int? = null) {
        isTTSRunning = true

        playDummySound() // FUCK ANDROID

        val job = Job()
        val uiScope = CoroutineScope(Dispatchers.Main + job)
        uiScope.launch {
            while (tts == null) {
                if (!isTTSRunning) return@launch
                delay(50)
            }
            if (index != null) {
                readFromIndex = index
            } else {
                val offset = getLineOffset()

                val topPadding = binding.readerLinContainer.paddingTop
                val height = binding.readToolbarHolder.height

                for ((startIndex, line) in globalTTSLines.withIndex()) {
                    if (binding.readScroll.scrollY <= (getMinMax(
                            line.startIndex,
                            line.endIndex
                        )?.max
                            ?: 0) + offset - height + topPadding
                    ) {
                        readFromIndex = startIndex
                        break
                    }
                }
            }

            while (true) {
                try {
                    if (!isTTSRunning) return@launch
                    while (isTTSPaused) {
                        delay(50)
                    }
                    if (!isTTSRunning) return@launch
                    if (globalTTSLines.size == 0) return@launch
                    if (readFromIndex < 0) {
                        if (!loadPrevChapter()) {
                            stopTTS()
                        }
                        return@launch
                    } else if (readFromIndex >= globalTTSLines.size) {
                        if (!loadNextChapter()) {
                            stopTTS()
                        }
                        return@launch
                    }

                    val line = globalTTSLines[readFromIndex]
                    val nextLine =
                        if (readFromIndex + 1 >= globalTTSLines.size) null else globalTTSLines[readFromIndex + 1]

                    currentTTSRangeStartIndex = line.startIndex
                    currentTTSRangeEndIndex = line.endIndex

                    val textLine = getMinMax(line.startIndex, line.endIndex)
                    minScroll = textLine?.min
                    maxScroll = textLine?.max
                    checkTTSRange(binding.readScroll.scrollY, true)


                    if (isValidSpeakOutMsg(line.speakOutMsg)) {
                        setHighLightedText(binding.readText, line.startIndex, line.endIndex)

                        speakOut(line, nextLine)
                    }

                    while (!canSpeak) {
                        delay(10)
                        if (!isTTSRunning) return@launch
                    }

                    readFromIndex++
                } catch (e: Exception) {
                    println(e)
                    return@launch
                }
            }
        }
    }

    private fun startTTS(fromStart: Boolean = false) {
        startTTS(if (fromStart) 0 else null)
    }

    private fun startTTS(fromIndex: Int?) {
        if (globalTTSLines.size <= 0) {
            prepareTTS(currentText) {
                if (it) {
                    runTTS(fromIndex)
                } else {
                    showToast("Error parsing text", Toast.LENGTH_SHORT)
                }
            }
        } else {
            runTTS(fromIndex)
        }
    }

    private fun checkTTSRange(scrollY: Int, scrollToTop: Boolean = false) {
        try {
            if (!lockTTSOnPaused && isTTSPaused) return
            val min = minScroll
            val max = maxScroll
            if (min == null || max == null) return
            val offset = getLineOffset()

            if (lockTTS && isTTSRunning) {
                binding.readScroll.apply {
                    if (height + scrollY - offset - 0 <= min) { // FOR WHEN THE TEXT IS ON THE BOTTOM OF THE SCREEN
                        if (scrollToTop) {
                            scrollTo(0, max + offset)
                        } else {
                            scrollTo(0, min - height + offset + 0)
                        }
                        fling(0) // FIX WACK INCONSISTENCY, RESETS VELOCITY
                    } else if (scrollY - offset >= max) { // WHEN TEXT IS ON TOP
                        scrollTo(0, max + offset)
                        fling(0) // FIX WACK INCONSISTENCY, RESETS VELOCITY
                    }
                }
            }
        } catch (e: Exception) {
            println("WHAT THE FUCK HAPPENED HERE? : $e")
        }
    }

    override fun onResume() {
        super.onResume()

        this.window?.navigationBarColor =
            this.colorFromAttribute(R.attr.primaryGrayBackground)

        main {
            if (binding.readScroll == null) return@main
            if (!lockTTSOnPaused && isTTSPaused) return@main
            if (!lockTTS || !isTTSRunning) return@main

            val textLine = getMinMax(currentTTSRangeStartIndex, currentTTSRangeEndIndex)
            minScroll = textLine?.min
            maxScroll = textLine?.max
            checkTTSRange(binding.readScroll.scrollY, true)
        }
        hideSystemUI()
    }

    private fun selectChapter() {
        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this)
        //builderSingle.setIcon(R.drawable.ic_launcher)
        builderSingle.setTitle(chapterTitles[currentChapter]) //  "Select Chapter"

        val arrayAdapter = ArrayAdapter<String>(this, R.layout.chapter_select_dialog)

        arrayAdapter.addAll(chapterTitles)

        builderSingle.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }

        builderSingle.setAdapter(arrayAdapter) { _, which ->
            ioSafe {
                loadChapter(which, true)
            }
        }

        val dialog = builderSingle.create()
        dialog.show()

        dialog.listView.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        dialog.listView.setSelection(currentChapter)
        dialog.listView.setItemChecked(currentChapter, true)
    }

    private fun getScrollRange(): Int {
        var scrollRange = 0
        binding.readScroll.apply {
            if (childCount > 0) {
                val child: View = getChildAt(0)
                scrollRange = max(
                    0,
                    child.height - (height - paddingBottom - paddingTop)
                )
            }
        }

        return scrollRange
    }

    private var orientationType: Int = OrientationType.DEFAULT.prefValue

    private lateinit var mMediaSessionCompat: MediaSessionCompat
    private val mMediaSessionCallback: MediaSessionCompat.Callback =
        object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val keyEvent =
                    mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as KeyEvent?
                if (keyEvent != null) {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) { // NO DOUBLE SKIP
                        val consumed = when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> callOnPause()
                            KeyEvent.KEYCODE_MEDIA_PLAY -> callOnPlay()
                            KeyEvent.KEYCODE_MEDIA_STOP -> callOnStop()
                            KeyEvent.KEYCODE_MEDIA_NEXT -> callOnNext()
                            else -> false
                        }
                        if (consumed) return true
                    }
                }

                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        }

    // FUCK ANDROID WITH ALL MY HEART
    // SEE https://stackoverflow.com/questions/45960265/android-o-oreo-8-and-higher-media-buttons-issue WHY
    private fun playDummySound() {
        val mMediaPlayer: MediaPlayer = MediaPlayer.create(this, R.raw.dummy_sound_500ms)
        mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
        mMediaPlayer.start()
    }

    private fun Context.initMediaSession() {
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mMediaSessionCompat = MediaSessionCompat(this, "TTS", mediaButtonReceiver, null)
        mMediaSessionCompat.setCallback(mMediaSessionCallback)
        mMediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
    }

    fun Context.setTextFontSize(size: Int) {
        setKey(EPUB_TEXT_SIZE, size)
        binding.readText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        binding.readTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat() + 2f)
    }

    private fun Context.getTextFontSize(): Int {
        return getKey(EPUB_TEXT_SIZE, 14)!!
    }

    private fun Context.getScrollWithVol(): Boolean {
        scrollWithVol = getKey(EPUB_SCROLL_VOL, true)!!
        return scrollWithVol
    }

    private fun Context.setScrollWithVol(scroll: Boolean) {
        scrollWithVol = scroll
        setKey(EPUB_SCROLL_VOL, scroll)
    }


    private fun Context.getLockTTS(): Boolean {
        lockTTS = getKey(EPUB_TTS_LOCK, true)!!
        return lockTTS
    }

    private fun Context.setLockTTS(scroll: Boolean) {
        lockTTS = scroll
        setKey(EPUB_TTS_LOCK, scroll)
    }

    private fun Context.setBackgroundColor(color: Int) {
        binding.readerContainer.setBackgroundColor(color)
        binding.readTempBottomMargin.setBackgroundColor(color)
        setKey(EPUB_BG_COLOR, color)
    }

    private fun Context.setTextColor(color: Int) {
        binding.apply {
            readText.setTextColor(color)
            readBattery.setTextColor(color)
            readTime.setTextColor(color)
            readTitleText.setTextColor(color)
        }

        setKey(EPUB_TEXT_COLOR, color)
    }

    private fun Context.updateHasBattery(status: Boolean? = null): Boolean {
        val set = if (status != null) {
            setKey(EPUB_HAS_BATTERY, status)
            status
        } else {
            getKey(EPUB_HAS_BATTERY, true)!!
        }
        binding.readBattery.isVisible = set

        return set
    }

    private fun Context.updateKeepScreen(status: Boolean? = null): Boolean {
        val set = if (status != null) {
            setKey(EPUB_KEEP_SCREEN_ACTIVE, status)
            status
        } else {
            getKey(EPUB_KEEP_SCREEN_ACTIVE, true)!!
        }
        if (set) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        return set
    }

    private fun Context.updateTwelveHourTime(status: Boolean? = null): Boolean {
        return if (status != null) {
            this.setKey(EPUB_TWELVE_HOUR_TIME, status)
            status
        } else {
            this.getKey(EPUB_TWELVE_HOUR_TIME, false)!!
        }
    }


    private fun Context.updateHasTime(status: Boolean? = null): Boolean {
        val set = if (status != null) {
            setKey(EPUB_HAS_TIME, status)
            status
        } else {
            getKey(EPUB_HAS_TIME, true)!!
        }
        binding.readTime.isVisible = set
        return set
    }

    private fun Context.getTextColor(): Int {
        val color = getKey(EPUB_TEXT_COLOR, ContextCompat.getColor(this, R.color.readerTextColor))!!

        binding.apply {
            readText.setTextColor(color)
            readBattery.setTextColor(color)
            readTime.setTextColor(color)
            readTitleText.setTextColor(color)
        }
        return color
    }

    /** In DP **/
    private fun Context.getTextPadding(): Int {
        return getKey(EPUB_TEXT_PADDING, 20)!!
    }

    /** In DP **/
    private fun Context.getTextPaddingTop(): Int {
        return getKey(EPUB_TEXT_PADDING_TOP, 0)!!
    }

    /** In DP **/
    private fun Context.setTextPaddingTop(padding: Int) {
        setKey(EPUB_TEXT_PADDING_TOP, padding)
        binding.readerLinContainer.apply {
            setPadding(
                paddingLeft,
                padding.toPx,
                paddingRight,
                0,//padding.toPx,
            )
        }
    }

    /** In DP **/
    private fun Context.setTextPadding(padding: Int) {
        setKey(EPUB_TEXT_PADDING, padding)
        binding.readText.apply {
            setPadding(
                padding.toPx,
                paddingTop,
                padding.toPx,
                paddingBottom
            )
        }
    }

    private fun Context.getBackgroundColor(): Int {
        val color = getKey(EPUB_BG_COLOR, ContextCompat.getColor(this, R.color.readerBackground))!!
        setBackgroundColor(color)
        return color
    }

    private fun updateImages() {
        val bgColors = resources.getIntArray(R.array.readerBgColors)
        val textColors = resources.getIntArray(R.array.readerTextColors)
        val color = getBackgroundColor()
        val colorPrimary = colorFromAttribute(R.attr.colorPrimary)
        val colorPrim = ColorStateList.valueOf(colorPrimary)
        val colorTrans = ColorStateList.valueOf(Color.TRANSPARENT)
        var foundCurrentColor = false
        val fullAlpha = 200
        val fadedAlpha = 50

        for ((index, img) in images.withIndex()) {
            if (index == bgColors.size) { // CUSTOM COLOR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                    img.foreground = ContextCompat.getDrawable(
                        this,
                        if (foundCurrentColor) R.drawable.ic_baseline_add_24 else R.drawable.ic_baseline_check_24
                    )
                }
                img.imageAlpha = if (foundCurrentColor) fadedAlpha else fullAlpha
                img.backgroundTintList =
                    ColorStateList.valueOf(if (foundCurrentColor) Color.parseColor("#161616") else color)
                continue
            }

            if ((color == bgColors[index] && getTextColor() == textColors[index])) {
                foundCurrentColor = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                }
                img.imageAlpha = fullAlpha
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorTrans
                }
                img.imageAlpha = fadedAlpha
            }
        }
    }

    private fun fadeInText() {
        binding.readLoading.visibility = View.GONE
        binding.readNormalLayout.alpha = 0.01f

        ObjectAnimator.ofFloat(binding.readNormalLayout, "alpha", 1f).apply {
            duration = 300
            start()
        }
    }

    var latestTTSSpeakOutId = Int.MIN_VALUE
    var ttsDefaultVoice: Voice? = null

    private fun requireTTS(callback: (TextToSpeech) -> Unit) {
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Locale.getDefault()
                    // tts!!.availableLanguages
                    // tts!!.setVoice(Voice.QUALITY_VERY_HIGH)


                    val voiceName = getKey<String>(EPUB_VOICE)
                    val langName = getKey<String>(EPUB_LANG)

                    val result = tts!!.setLanguage(
                        tts!!.availableLanguages.firstOrNull { it.displayName == langName }
                            ?: Locale.US)
                    tts!!.voice =
                        tts!!.voices.firstOrNull { it.name == voiceName } ?: tts!!.defaultVoice

                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        showToast("This Language is not supported")
                    } else {
                        tts!!.setOnUtteranceProgressListener(object :
                            UtteranceProgressListener() {
                            //MIGHT BE INTERESTING https://stackoverflow.com/questions/44461533/android-o-new-texttospeech-onrangestart-callback
                            override fun onDone(utteranceId: String) {
                                canSpeak = true
                                //  println("ENDMS: " + System.currentTimeMillis())
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                canSpeak = true
                            }

                            override fun onError(utteranceId: String) {
                                canSpeak = true
                            }

                            override fun onStart(utteranceId: String) {
                                val highlightResult =
                                    Regex("([0-9]*):([0-9]*):([0-9]*)").matchEntire(utteranceId)
//                                println("AAAAAAAAA:$highlightResult on $utteranceId")
                                if (highlightResult == null || (highlightResult.groupValues.size < 4)) return
                                try {
                                    latestTTSSpeakOutId =
                                        highlightResult.groupValues[1].toIntOrNull() ?: return
                                    val startIndex =
                                        highlightResult.groupValues[2].toIntOrNull() ?: return
                                    val endIndex =
                                        highlightResult.groupValues[3].toIntOrNull() ?: return
                                    runOnUiThread {
                                        setHighLightedText(binding.readText, startIndex, endIndex)
                                    }
                                } catch (e: Exception) {
                                    logError(e)
                                }
                            }
                        })
                        callback(tts!!)
                        //readTTSClick()
                    }
                } else {
                    val errorMSG = when (status) {
                        TextToSpeech.ERROR -> "ERROR"
                        TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
                        TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
                        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
                        TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
                        TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
                        TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
                        else -> status.toString()
                    }

                    showToast("Initialization Failed! Error $errorMSG")
                    tts = null
                }
            }
        } else {
            callback(tts!!)
        }
    }

    val defaultTTSLanguage = Locale.US
    lateinit var binding: ReadMainBinding

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)

        super.onCreate(savedInstanceState)

        val mBatInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("SetTextI18n")
            override fun onReceive(ctxt: Context?, intent: Intent) {
                val batteryPct: Float = intent.let { intent ->
                    val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level * 100 / scale.toFloat()
                }
                binding.readBattery.text = "${batteryPct.toInt()}%"
            }
        }
        IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            this.registerReceiver(mBatInfoReceiver, ifilter)
        }

        val data = intent.data

        if (data == null) {
            kill()
            return
        }

        // THIS WAY YOU CAN OPEN FROM FILE OR FROM APP
        val input = contentResolver.openInputStream(data)
        if (input == null) {
            kill()
            return
        }

        isFromEpub = intent.type == "application/epub+zip"

        initMediaSession()
        binding = ReadMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        //setContentView(R.layout.read_main)
        setTextFontSize(getTextFontSize())
        setTextPadding(getTextPadding())
        setTextPaddingTop(getTextPaddingTop())
        initTTSSession()
        getLockTTS()
        getScrollWithVol()
        getBackgroundColor()
        getTextColor()
        updateHasTime()
        updateTwelveHourTime()
        updateHasBattery()
        updateKeepScreen()

        val fonts = getAllFonts()
        if (fonts == null) {
            setReadTextFont(null)
        } else {
            val index = fonts.map { it.name }.indexOf(getKey(EPUB_FONT) ?: "")
            setReadTextFont(if (index > 0) fonts[index] else null, null)
        }


        createNotificationChannel()
        binding.readTitleText.minHeight = binding.readToolbar.height

        fixPaddingStatusbar(binding.readToolbar)

        //<editor-fold desc="Screen Rotation">
        fun setRot(org: OrientationType) {
            orientationType = org.prefValue
            requestedOrientation = org.flag
            binding.readActionRotate.setImageResource(org.iconRes)
        }

        binding.readActionRotate.apply {
            setOnClickListener {
                popupMenu(
                    items = OrientationType.values().map { it.prefValue to it.stringRes },
                    selectedItemId = orientationType
                    //   ?: preferences.defaultOrientationType(),
                ) {
                    val org = OrientationType.fromSpinner(itemId)
                    setKey(EPUB_LOCK_ROTATION, itemId)
                    setRot(org)
                }
            }
        }


        val colorPrimary =
            colorFromAttribute(R.attr.colorPrimary)//   getColor(R.color.colorPrimary)

        binding.readActionSettings.setOnClickListener {
            val bottomSheetDialog = BottomSheetDialog(this)

            val binding = ReadBottomSettingsBinding.inflate(layoutInflater, null, false)
            bottomSheetDialog.setContentView(binding.root)

            binding.hardResetStream.apply {
                isGone = isFromEpub
                setOnClickListener {
                    ioSafe {
                        loadChapter(
                            currentChapter,
                            scrollToTop = false,
                            scrollToRemember = true,
                            forceReload = true
                        )
                    }
                }
            }


            binding.readLanguage.setOnClickListener { view ->
                view?.context?.let { ctx ->
                    requireTTS { tts ->
                        val languages = mutableListOf<Locale?>(null).apply {
                            addAll(tts.availableLanguages?.filterNotNull() ?: emptySet())
                        }
                        ctx.showBottomDialog(
                            languages.map {
                                it?.displayName ?: ctx.getString(R.string.default_text)
                            },
                            languages.indexOf(tts.voice?.locale),
                            ctx.getString(R.string.tts_locale), false, {}
                        ) { index ->
                            stopTTS()
                            val lang = languages[index] ?: defaultTTSLanguage
                            setKey(EPUB_LANG, lang.displayName)
                            tts.language = lang
                        }
                    }
                }
            }

            binding.readVoice.setOnClickListener { view ->
                view?.context?.let { ctx ->
                    requireTTS { tts ->
                        val matchAgainst = tts.voice.locale.language
                        val voices = mutableListOf<Voice?>(null).apply {
                            addAll(tts.voices.filter { it != null && it.locale.language == matchAgainst })
                        }

                        ctx.showBottomDialog(
                            voices.map { it?.name ?: ctx.getString(R.string.default_text) },
                            voices.indexOf(tts.voice),
                            ctx.getString(R.string.tts_locale), false, {}
                        ) { index ->
                            stopTTS()
                            setKey(EPUB_VOICE, voices[index]?.name)
                            tts.voice = voices[index] ?: tts.defaultVoice
                        }
                    }
                }
            }

            //val root = bottomSheetDialog.findViewById<LinearLayout>(R.id.read_settings_root)!!
            val horizontalColors =
                bottomSheetDialog.findViewById<LinearLayout>(R.id.read_settings_colors)!!

            binding.readShowFonts.apply {
                text = UIHelper.parseFontFileName(getKey(EPUB_FONT))
                setOnClickListener {
                    showFonts {
                        text = it
                    }
                }
            }

            binding.readSettingsScrollVol.apply {
                isChecked = scrollWithVol
                setOnCheckedChangeListener { _, checked ->
                    setScrollWithVol(checked)
                }
            }

            binding.readSettingsLockTts.apply {
                isChecked = lockTTS
                setOnCheckedChangeListener { _, checked ->
                    setLockTTS(checked)
                }
            }

            binding.readSettingsTwelveHourTime.apply {
                isChecked = updateTwelveHourTime()
                setOnCheckedChangeListener { _, checked ->
                    updateTwelveHourTime(checked)
                }
            }

            binding.readSettingsShowTime.apply {
                isChecked = updateHasTime()
                setOnCheckedChangeListener { _, checked ->
                    updateHasTime(checked)
                }
            }

            binding.readSettingsShowBattery.apply {
                isChecked = updateHasBattery()
                setOnCheckedChangeListener { _, checked ->
                    updateHasBattery(checked)
                }
            }

            binding.readSettingsKeepScreenActive.apply {
                isChecked = updateKeepScreen()
                setOnCheckedChangeListener { _, checked ->
                    updateKeepScreen(checked)
                }
            }

            val bgColors = resources.getIntArray(R.array.readerBgColors)
            val textColors = resources.getIntArray(R.array.readerTextColors)

            images = ArrayList()

            for ((index, backgroundColor) in bgColors.withIndex()) {
                val textColor = textColors[index]

                val imageHolder = layoutInflater.inflate(
                    R.layout.color_round_checkmark,
                    null
                ) //color_round_checkmark
                val image = imageHolder.findViewById<ImageView>(R.id.image1)
                image.backgroundTintList = ColorStateList.valueOf(backgroundColor)
                image.setOnClickListener {
                    setBackgroundColor(backgroundColor)
                    setTextColor(textColor)
                    updateImages()
                }
                images.add(image)
                horizontalColors.addView(imageHolder)
                //  image.backgroundTintList = ColorStateList.valueOf(c)// ContextCompat.getColorStateList(this, c)
            }

            val imageHolder = layoutInflater.inflate(R.layout.color_round_checkmark, null)
            val image = imageHolder.findViewById<ImageView>(R.id.image1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                image.foreground = ContextCompat.getDrawable(this, R.drawable.ic_baseline_add_24)
            }
            image.setOnClickListener {
                val builder: AlertDialog.Builder = AlertDialog.Builder(this)
                builder.setTitle(getString(R.string.reading_color))

                val colorAdapter = ArrayAdapter<String>(this, R.layout.chapter_select_dialog)
                val array = arrayListOf(
                    getString(R.string.background_color),
                    getString(R.string.text_color)
                )
                colorAdapter.addAll(array)

                builder.setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    updateImages()
                }

                builder.setAdapter(colorAdapter) { _, which ->
                    ColorPickerDialog.newBuilder()
                        .setDialogId(which)
                        .setColor(
                            when (which) {
                                0 -> getBackgroundColor()
                                1 -> getTextColor()
                                else -> 0
                            }
                        )
                        .show(this)
                }

                builder.show()
                updateImages()
            }

            images.add(image)
            horizontalColors.addView(imageHolder)
            updateImages()

            var updateAllTextOnDismiss = false
            val offsetSize = 10
            binding.readSettingsTextSize.apply {
                max = 20
                progress = getTextFontSize() - offsetSize
                setOnSeekBarChangeListener(object :
                    SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        setTextFontSize(progress + offsetSize)
                        stopTTS()

                        updateAllTextOnDismiss = true
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            binding.readSettingsTextPadding.apply {
                max = 50
                progress = getTextPadding()
                setOnSeekBarChangeListener(object :
                    SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        setTextPadding(progress)
                        stopTTS()

                        updateAllTextOnDismiss = true
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            binding.readSettingsTextPaddingTop.apply {
                max = 50
                progress = getTextPaddingTop()
                setOnSeekBarChangeListener(object :
                    SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        setTextPaddingTop(progress)
                        stopTTS()

                        updateAllTextOnDismiss = true
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            binding.readSettingsTextPaddingTextTop.setOnClickListener {
                it.popupMenu(
                    items = listOf(Pair(1, R.string.reset_value)),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        it.context?.removeKey(EPUB_TEXT_PADDING_TOP)
                        binding.readSettingsTextPaddingTop.progress = getTextPaddingTop()
                    }
                }
            }


            binding.readSettingsTextPaddingText.apply {
                setOnClickListener {
                    it.popupMenu(
                        items = listOf(Pair(1, R.string.reset_value)),
                        selectedItemId = null
                    ) {
                        if (itemId == 1) {
                            it.context?.removeKey(EPUB_TEXT_PADDING)
                            binding.readSettingsTextPadding.progress = getTextPadding()
                        }
                    }
                }
            }

            binding.readSettingsTextSizeText.setOnClickListener {
                it.popupMenu(items = listOf(Pair(1, R.string.reset_value)), selectedItemId = null) {
                    if (itemId == 1) {
                        it.context?.removeKey(EPUB_TEXT_SIZE)
                        binding.readSettingsTextSize.progress = getTextFontSize() - offsetSize
                    }
                }
            }


            binding.readSettingsTextFontText.setOnClickListener {
                it.popupMenu(items = listOf(Pair(1, R.string.reset_value)), selectedItemId = null) {
                    if (itemId == 1) {
                        setReadTextFont(null) { fileName ->
                            binding.readShowFonts.text = fileName
                        }
                        stopTTS()
                        updateAllTextOnDismiss = true
                    }
                }
            }


            bottomSheetDialog.setOnDismissListener {
                if (updateAllTextOnDismiss) {
                    loadTextLines()
                    globalTTSLines.clear()
                }
            }
            bottomSheetDialog.show()
        }

        setRot(
            OrientationType.fromSpinner(
                getKey(
                    EPUB_LOCK_ROTATION,
                    OrientationType.DEFAULT.prefValue
                )
            )
        )
        //</editor-fold>
        binding.apply {


            readActionChapters.setOnClickListener {
                selectChapter()
            }
            ttsActionStop.setOnClickListener {
                stopTTS()
            }
            ttsActionPausePlay.setOnClickListener {
                when (ttsStatus) {
                    TTSStatus.IsRunning -> isTTSPaused = true
                    TTSStatus.IsPaused -> isTTSPaused = false
                    else -> {
                        // DO NOTHING
                    }
                }
            }
            ttsActionForward.setOnClickListener {
                nextTTSLine()
            }
            ttsActionBack.setOnClickListener {
                prevTTSLine()
            }
            readActionTts.setOnClickListener {
                /* fun readTTSClick() {
                     when (ttsStatus) {
                         TTSStatus.IsStopped -> startTTS()
                         TTSStatus.IsRunning -> stopTTS()
                         TTSStatus.IsPaused -> isTTSPaused = false
                     }
                 }*/

                // DON'T INIT TTS UNTIL IT IS NECESSARY
                requireTTS {
                    startTTS()
                }
            }

            hideSystemUI()

            readToolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            readToolbar.setNavigationOnClickListener {
                kill() // KILLS ACTIVITY
            }
            readOverflowProgress.max = OVERFLOW_NEXT_CHAPTER_DELTA
        }




        readActivity = this


        fixPaddingStatusbar(binding.readTopmargin)

        //window.navigationBarColor =
        //    colorFromAttribute(R.attr.grayBackground) //getColor(R.color.readerHightlightedMetaInfo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.readScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                checkTTSRange(scrollY)
                binding.readTempBottomMargin.visibility = View.GONE

                setKey(EPUB_CURRENT_POSITION_SCROLL, getBookTitle(), scrollY)

                mainScrollY = scrollY
                updateChapterName(scrollY)
            }
        }

        fun toggleShow() {
            if (isHidden) {
                showSystemUI()
            } else {
                hideSystemUI()
            }
        }

        val touchListener = View.OnTouchListener { view, event ->
            val height = getScrollRange()
            if (view != null && view == binding.readerLinContainer && event.action == MotionEvent.ACTION_DOWN) {
                toggleShow()
                return@OnTouchListener true
            }
            if (event == null) return@OnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (mainScrollY >= height) {
                        overflowDown = true
                        startY = event.y
                    } else if (mainScrollY == 0) {
                        overflowDown = false
                        startY = event.y
                    }

                    scrollStartY = event.y
                    scrollStartX = event.x
                    scrollDistance = 0f
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = scrollStartX - event.x
                    val deltaY = scrollStartY - event.y
                    scrollDistance += abs(deltaX) + abs(deltaY)
                    scrollStartY = event.y
                    scrollStartX = event.x

                    fun deltaShow() {
                        if (scrollYOverflow * 100 / OVERFLOW_NEXT_CHAPTER_DELTA > OVERFLOW_NEXT_CHAPTER_SHOW_PROCENTAGE) {
                            /*read_overflow_progress.visibility = View.VISIBLE
                                    read_overflow_progress.progress =
                                        minOf(scrollYOverflow.toInt(), OVERFLOW_NEXT_CHAPTER_DELTA)*/

                            binding.readText.translationY = (if (overflowDown) -1f else 1f) * sqrt(
                                minOf(
                                    scrollYOverflow,
                                    OVERFLOW_NEXT_CHAPTER_DELTA.toFloat()
                                )
                            ) * 4 // *4 is the amount the page moves when you overload it

                        }
                    }

                    if (!overflowDown && (mainScrollY <= OVERFLOW_NEXT_CHAPTER_SAFESPACE.toDp || scrollYOverflow > OVERFLOW_NEXT_CHAPTER_SHOW_PROCENTAGE) && startY != null && currentChapter > 0) {
                        scrollYOverflow = maxOf(0f, event.y - startY!!)
                        deltaShow()
                    } else if (overflowDown && (mainScrollY >= height - OVERFLOW_NEXT_CHAPTER_SAFESPACE.toDp || scrollYOverflow > OVERFLOW_NEXT_CHAPTER_SHOW_PROCENTAGE) && startY != null) { // && currentChapter < maxChapter
                        scrollYOverflow = maxOf(0f, startY!! - event.y)
                        deltaShow()
                    } else {
                        binding.readOverflowProgress.visibility = View.GONE
                        binding.readText.translationY = 0f
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (scrollDistance < TOGGLE_DISTANCE) {
                        toggleShow()
                    }

                    binding.readOverflowProgress.visibility = View.GONE
                    binding.readText.translationY = 0f
                    startY = null

                    if (100 * scrollYOverflow / OVERFLOW_NEXT_CHAPTER_DELTA >= OVERFLOW_NEXT_CHAPTER_NEXT) {
                        if (mainScrollY >= height && overflowDown) {
                            loadNextChapter()
                        } else if (mainScrollY == 0 && !overflowDown) {
                            loadPrevChapter()
                        }
                    }
                    scrollYOverflow = 0f
                }
            }
            false
        }

        binding.apply {
            readScroll.setOnTouchListener(touchListener)
            readerLinContainer.setOnTouchListener(touchListener)
            readNormalLayout.setOnTouchListener(touchListener)
            readText.setOnTouchListener(touchListener)
        }


//        read_overlay.setOnClickListener {
//            selectChapter()
//        }

        main { // THIS IS USED FOR INSTANT LOAD

            binding.readLoading.postDelayed({
                if (!this::chapterTitles.isInitialized) {
                    binding.readLoading.isVisible = true
                }
            }, 200) // I DON'T WANT TO SHOW THIS IN THE BEGINNING, IN CASE IF SMALL LOAD TIME

            withContext(Dispatchers.IO) {
                if (isFromEpub) {
                    val epubReader = EpubReader()
                    book = epubReader.readEpub(input)
                } else {
                    quickdata = mapper.readValue(input.reader().readText())
                }
            }

            if (!isFromEpub && quickdata.data.isEmpty()) {
                showToast(R.string.no_chapters_found, Toast.LENGTH_SHORT)
                kill()
                return@main
            }

            maxChapter = getBookSize()

            chapterTitles = ArrayList()
            for (i in 0 until maxChapter) {
                chapterTitles.add(getChapterName(i))
            }
            if (chapterTitles.isEmpty()) {
                finish()
            }
            loadChapter(
                maxOf(getKey(EPUB_CURRENT_POSITION, getBookTitle()) ?: 0, 0),
                //minOf(
                //    maxChapter - 1
                // ), // CRASH FIX IF YOU SOMEHOW TRY TO LOAD ANOTHER EPUB WITH THE SAME NAME
                scrollToTop = true,
                scrollToRemember = true
            )
            updateTimeText()

            fadeInText()
        }
    }
}