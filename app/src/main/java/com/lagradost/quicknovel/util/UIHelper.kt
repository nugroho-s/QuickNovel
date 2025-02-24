package com.lagradost.quicknovel.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.text.Spanned
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.text.HtmlCompat
import androidx.core.text.toSpanned
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import jp.wasabeef.glide.transformations.BlurTransformation
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import kotlin.math.roundToInt


val Int.toPx: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
val Float.toPx: Float get() = (this * Resources.getSystem().displayMetrics.density)
val Int.toDp: Int get() = (this / Resources.getSystem().displayMetrics.density).toInt()
val Float.toDp: Float get() = (this / Resources.getSystem().displayMetrics.density)

object UIHelper {
    fun String?.html(): Spanned {
        return getHtmlText(this ?: return "".toSpanned())
    }

    private fun getHtmlText(text: String): Spanned {
        return try {
            // I have no idea if this can throw any error, but I dont want to try
            HtmlCompat.fromHtml(
                text, HtmlCompat.FROM_HTML_MODE_LEGACY
            )
        } catch (e: Exception) {
            logError(e)
            text.toSpanned()
        }
    }

    fun humanReadableByteCountSI(bytes: Int): String {
        if (-1000 < bytes && bytes < 1000) {
            return "$bytes"
        }
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        var currentBytes = bytes
        while (currentBytes <= -999950 || currentBytes >= 999950) {
            currentBytes /= 1000
            ci.next()
        }
        return String.format("%.1f%c", currentBytes / 1000.0, ci.current()).replace(',', '.')
    }
    fun Dialog?.dismissSafe(activity: Activity?) {
        if (this?.isShowing == true && activity?.isFinishing == false) {
            this.dismiss()
        }
    }
    fun FragmentActivity.popCurrentPage() {
        val currentFragment = supportFragmentManager.fragments.lastOrNull {
            it.isVisible
        } ?: return

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.enter_anim,
                R.anim.exit_anim,
                R.anim.pop_enter,
                R.anim.pop_exit
            )
            .remove(currentFragment)
            .commitAllowingStateLoss()
    }

    fun Context.dimensionFromAttribute(attribute: Int): Int {
        val attributes = obtainStyledAttributes(intArrayOf(attribute))
        val dimension = attributes.getDimensionPixelSize(0, 0)
        attributes.recycle()
        return dimension
    }

    fun Context.colorFromAttribute(attribute: Int): Int {
        val attributes = obtainStyledAttributes(intArrayOf(attribute))
        val color = attributes.getColor(0, 0)
        attributes.recycle()
        return color
    }

    fun ImageView?.setImage(
        url: String?,
        referer: String? = null,
        headers: Map<String, String>? = null,
        @DrawableRes
        errorImageDrawable: Int? = null,
        blur: Boolean = false,
        skipCache: Boolean = true,
        fade: Boolean = true
    ): Boolean {
        if (this == null || url.isNullOrBlank()) return false
        val allHeaders =
            (headers ?: emptyMap()) + (referer?.let { mapOf("referer" to referer) } ?: emptyMap())

        // Using the normal GlideUrl(url) { allHeaders } will refresh the image
        // causing flashing when downloading novels, hence why this is used instead
        val glideHeaders = LazyHeaders.Builder().apply {
            allHeaders.forEach {
                addHeader(it.key, it.value)
            }
        }.build()
        val glideUrl = GlideUrl(url, glideHeaders)

        return try {
            val builder = Glide.with(this)
                .load(glideUrl)
                .let {
                    if (fade)
                        it.transition(
                            DrawableTransitionOptions.withCrossFade()
                        ) else it
                }.let {
                    if (blur)
                        it.apply(bitmapTransform(BlurTransformation(100, 3)))
                    else
                        it
                }
                .skipMemoryCache(skipCache)
                .diskCacheStrategy(DiskCacheStrategy.ALL)

            val res = if (errorImageDrawable != null)
                builder.error(errorImageDrawable).into(this)
            else
                builder.into(this)
            res.clearOnDetach()

            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    fun Activity.getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun Activity.fixPaddingStatusbar(v: View) {
        v.setPadding(
            v.paddingLeft,
            v.paddingTop + getStatusBarHeight(),
            v.paddingRight,
            v.paddingBottom
        )
    }

    fun Context.requestAudioFocus(focusRequest: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(focusRequest)
        } else {
            val audioManager: AudioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    @ColorInt
    fun Context.getResourceColor(@AttrRes resource: Int, alphaFactor: Float = 1f): Int {
        val typedArray = obtainStyledAttributes(intArrayOf(resource))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()

        if (alphaFactor < 1f) {
            val alpha = (color.alpha * alphaFactor).roundToInt()
            return Color.argb(alpha, color.red, color.green, color.blue)
        }

        return color
    }

    fun parseFontFileName(name: String?): String {
        return (if (name.isNullOrEmpty()) "Default" else name)
            .replace('-', ' ')
            .replace(".ttf", "")
            .replace(".ttc", "")
            .replace(".otf", "")
            .replace(".otc", "")
    }

    /**
     * Shows a popup menu on top of this view.
     *
     * @param menuRes menu items to inflate the menu with.
     * @param initMenu function to execute when the menu after is inflated.
     * @param onMenuItemClick function to execute when a menu item is clicked.
     */
    inline fun View.popupMenu(
        @MenuRes menuRes: Int,
        noinline initMenu: (Menu.() -> Unit)? = null,
        noinline onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val popup = PopupMenu(context, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)
        popup.menuInflater.inflate(menuRes, popup.menu)

        if (initMenu != null) {
            popup.menu.initMenu()
        }
        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }


    /**
     * Shows a popup menu on top of this view.
     *
     * @param items menu item names to inflate the menu with. List of itemId to stringRes pairs.
     * @param selectedItemId optionally show a checkmark beside an item with this itemId.
     * @param onMenuItemClick function to execute when a menu item is clicked.
     */
    @SuppressLint("RestrictedApi")
    inline fun View.popupMenu(
        items: List<Pair<Int, Int>>,
        selectedItemId: Int? = null,
        noinline onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val ctw = ContextThemeWrapper(context, R.style.PopupMenu)
        val popup = PopupMenu(ctw, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)

        items.forEach { (id, stringRes) ->
            popup.menu.add(0, id, 0, stringRes)
        }

        if (selectedItemId != null) {
            (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

            val emptyIcon = ContextCompat.getDrawable(context, R.drawable.ic_blank_24)
            popup.menu.forEach { item ->
                item.icon = when (item.itemId) {
                    selectedItemId -> ContextCompat.getDrawable(context, R.drawable.ic_check_24)
                        ?.mutate()?.apply {
                            setTint(context.getResourceColor(android.R.attr.textColorPrimary))
                        }
                    else -> emptyIcon
                }
            }
        }

        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }

    @SuppressLint("RestrictedApi")
    inline fun View.popupMenu(
        items: List<Triple<Int, Int, Int>>,
        noinline onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val ctw = ContextThemeWrapper(context, R.style.PopupMenu)
        val popup = PopupMenu(ctw, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)

        items.forEach { (id, icon, stringRes) ->
            popup.menu.add(0, id, 0, stringRes).setIcon(icon)
        }

        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }

    fun Fragment.hideKeyboard() {
        view.let {
            if (it != null) {
                activity?.hideKeyboard(it)
            }
        }
    }

    fun Context.hideKeyboard(view: View) {
        val inputMethodManager =
            getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
}