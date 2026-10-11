package com.gumm.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

object Theme {
    var bg = Color.parseColor("#0F1115")
    var card = Color.parseColor("#1A1D24")
    var input = Color.parseColor("#262A33")
    var accent = Color.parseColor("#5B8DEF")
    var danger = Color.parseColor("#E5584F")
    var text = Color.parseColor("#E8EAF0")
    var muted = Color.parseColor("#9AA1B0")
    var success = Color.parseColor("#4CAF7A")
    var warn = Color.parseColor("#E8A33D")
    var line = Color.parseColor("#2A2E38")
    var trackOff = Color.parseColor("#3A3F4B")

    fun apply(light: Boolean) {
        if (light) {
            bg = Color.parseColor("#F3F4F8")
            card = Color.parseColor("#FFFFFF")
            input = Color.parseColor("#E4E7EE")
            accent = Color.parseColor("#3F6FD8")
            text = Color.parseColor("#1B1E25")
            muted = Color.parseColor("#5F6675")
            line = Color.parseColor("#DADDE5")
            trackOff = Color.parseColor("#C3C8D3")
        } else {
            bg = Color.parseColor("#0F1115")
            card = Color.parseColor("#1A1D24")
            input = Color.parseColor("#262A33")
            accent = Color.parseColor("#5B8DEF")
            text = Color.parseColor("#E8EAF0")
            muted = Color.parseColor("#9AA1B0")
            line = Color.parseColor("#2A2E38")
            trackOff = Color.parseColor("#3A3F4B")
        }
    }
}

fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun tv(ctx: Context, text: String, size: Float = 15f, color: Int = Theme.text, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun pill(ctx: Context, text: String, color: Int = Theme.accent, onClick: () -> Unit): Button =
    Button(ctx).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (color == Theme.input) Theme.text else Color.WHITE)
        background = rounded(color, dp(ctx, 10).toFloat())
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

fun field(ctx: Context, hint: String, value: String, onChange: (String) -> Unit): EditText =
    EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(Theme.muted)
        setTextColor(Theme.text)
        setSingleLine(true)
        background = rounded(Theme.input, dp(ctx, 8).toFloat())
        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
    }

fun styleSwitch(sw: Switch): Switch {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Theme.muted))
    sw.trackTintList = ColorStateList(states, intArrayOf(Theme.accent, Theme.trackOff))
    return sw
}

fun toggle(ctx: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
    styleSwitch(Switch(ctx)).apply {
        this.text = text
        setTextColor(Theme.text)
        isChecked = checked
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun card(ctx: Context): LinearLayout = vbox(ctx).apply {
    background = rounded(Theme.card, dp(ctx, 14).toFloat())
    setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, 12) }
}

fun lp(ctx: Context, top: Int = 0, left: Int = 0, weight: Float = 0f, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
        topMargin = dp(ctx, top)
        leftMargin = dp(ctx, left)
    }

fun fullWidth(ctx: Context, top: Int = 0): LinearLayout.LayoutParams =
    lp(ctx, top = top, width = ViewGroup.LayoutParams.MATCH_PARENT)

fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun chip(ctx: Context, text: String, color: Int): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(withAlpha(color, 40), dp(ctx, 10).toFloat())
        setPadding(dp(ctx, 9), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
    }

fun primary(ctx: Context, text: String, color: Int = Theme.accent, onClick: () -> Unit): Button =
    Button(ctx).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (color == Theme.input) Theme.text else Color.WHITE)
        background = rounded(color, dp(ctx, 12).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

fun section(ctx: Context, title: String): TextView =
    tv(ctx, title.uppercase(), 12f, Theme.accent, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(ctx, 2), dp(ctx, 20), 0, dp(ctx, 2))
    }

fun toggleRow(ctx: Context, title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
    hbox(ctx).apply {
        val col = vbox(ctx)
        col.addView(tv(ctx, title, 15f))
        if (desc != null) col.addView(tv(ctx, desc, 12f, Theme.muted))
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = styleSwitch(Switch(ctx))
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, value -> onChange(value) }
        addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(ctx, 12)
        })
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        setOnClickListener { sw.toggle() }
    }

fun infoRow(ctx: Context, label: String, value: String): LinearLayout =
    hbox(ctx).apply {
        addView(tv(ctx, label, 13f, Theme.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = tv(ctx, value, 13f, Theme.text)
        v.gravity = Gravity.END
        addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 12) })
        setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
    }

fun divider(ctx: Context): View =
    View(ctx).apply { setBackgroundColor(Theme.line) }

fun dividerParams(ctx: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
        topMargin = dp(ctx, 4)
        bottomMargin = dp(ctx, 4)
    }
