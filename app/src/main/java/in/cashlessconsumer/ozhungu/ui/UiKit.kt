package `in`.cashlessconsumer.ozhungu.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small view factories for the Files-by-Google-style screens. Framework widgets only. */
object UiKit {

    val PAGE_BG: Int = 0xFFF6F8FB.toInt()
    val CARD_BG: Int = Color.WHITE
    val PRIMARY: Int = 0xFF1A73E8.toInt()
    val PRIMARY_DIM: Int = 0xFFD2E3FC.toInt()
    val INK: Int = 0xFF202124.toInt()
    val DIM: Int = 0xFF5F6368.toInt()
    val LINE: Int = 0xFFE8EAED.toInt()
    val OK: Int = 0xFF188038.toInt()
    val BAD: Int = 0xFFD93025.toInt()

    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun dp(c: Context, v: Float): Int =
        (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun card(c: Context, radiusDp: Float = 16f, bg: Int = CARD_BG): GradientDrawable =
        GradientDrawable().apply { setColor(bg); cornerRadius = dp(c, radiusDp).toFloat() }

    fun roundBg(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    fun label(c: Context, text: String, size: Float, color: Int = INK, bold: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun primaryButton(c: Context, text: String, onClick: (() -> Unit)? = null): Button =
        Button(c).apply {
            this.text = text
            isAllCaps = false
            setTextSize(15f)
            setTextColor(Color.WHITE)
            background = card(c, 24f, PRIMARY)
            stateListAnimator = null
            setPadding(dp(c, 20f), dp(c, 10f), dp(c, 20f), dp(c, 10f))
            if (onClick != null) setOnClickListener { onClick() }
        }

    fun ghostButton(c: Context, text: String, onClick: (() -> Unit)? = null): Button =
        Button(c).apply {
            this.text = text
            isAllCaps = false
            setTextSize(15f)
            setTextColor(PRIMARY)
            background = card(c, 24f).apply {
                setStroke(dp(c, 1f), PRIMARY_DIM)
                setColor(CARD_BG)
            }
            stateListAnimator = null
            setPadding(dp(c, 20f), dp(c, 10f), dp(c, 20f), dp(c, 10f))
            if (onClick != null) setOnClickListener { onClick() }
        }

    fun chip(c: Context, text: String, bg: Int = PRIMARY_DIM, fg: Int = 0xFF174EA6.toInt()): TextView =
        TextView(c).apply {
            this.text = text
            textSize = 12f
            setTextColor(fg)
            background = card(c, 14f, bg)
            setPadding(dp(c, 12f), dp(c, 5f), dp(c, 12f), dp(c, 5f))
        }

    /** Coloured circle with an emoji glyph — category tiles and file-row icons. */
    fun circle(c: Context, emoji: String, bg: Int, sizeDp: Int): TextView =
        TextView(c).apply {
            text = emoji
            textSize = 16f
            gravity = Gravity.CENTER
            background = roundBg(bg, dp(c, sizeDp / 2f).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)).apply {
                setMargins(0, 0, dp(c, 12f), 0)
            }
        }

    /** One recent-file row: circle icon + two-line text. */
    fun fileRow(c: Context, emoji: String, bg: Int, title: String, sub: String): LinearLayout {
        val name = label(c, title, 14f)
        val detail = label(c, sub, 12f, DIM)
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 16f), dp(c, 10f), dp(c, 16f), dp(c, 10f))
            addView(circle(c, emoji, bg, 38))
            addView(col)
        }
    }

    /** Small rounded count pill for the right edge of a row. */
    fun badge(c: Context, text: String): TextView =
        TextView(c).apply {
            this.text = text
            textSize = 12f
            setTextColor(DIM)
            background = card(c, 12f, PAGE_BG)
            setPadding(dp(c, 8f), dp(c, 2f), dp(c, 8f), dp(c, 2f))
        }

    /** 1234 → "1.2 KB" for summary lines. */
    fun humanBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        return (if (v >= 100) v.toInt().toString() else String.format("%.1f", v)) + " " + units[u]
    }

    fun cardShell(c: Context, child: View, padDp: Float = 16f): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = card(c)
            setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
            addView(child)
        }

    fun divider(c: Context): View =
        View(c).apply {
            setBackgroundColor(LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f))
        }
}
