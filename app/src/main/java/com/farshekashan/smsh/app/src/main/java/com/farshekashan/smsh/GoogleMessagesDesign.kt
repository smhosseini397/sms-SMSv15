package com.farshekashan.smsh

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object GoogleMessagesDesign {

    private val blue = Color.rgb(26, 115, 232)
    private val lightBackground = Color.rgb(248, 249, 250)
    private val lightSurface = Color.WHITE
    private val lightText = Color.rgb(32, 33, 36)
    private val lightMuted = Color.rgb(95, 99, 104)
    private val lightField = Color.rgb(241, 243, 244)
    private val lightDivider = Color.rgb(232, 234, 237)

    private val darkBackground = Color.rgb(18, 19, 22)
    private val darkSurface = Color.rgb(32, 33, 36)
    private val darkText = Color.rgb(232, 234, 237)
    private val darkMuted = Color.rgb(189, 193, 198)
    private val darkField = Color.rgb(48, 49, 52)
    private val darkDivider = Color.rgb(60, 64, 67)

    var darkMode = false

    val background: Int
        get() = if (darkMode) darkBackground else lightBackground

    val surface: Int
        get() = if (darkMode) darkSurface else lightSurface

    val textColor: Int
        get() = if (darkMode) darkText else lightText

    val mutedColor: Int
        get() = if (darkMode) darkMuted else lightMuted

    val fieldColor: Int
        get() = if (darkMode) darkField else lightField

    val dividerColor: Int
        get() = if (darkMode) darkDivider else lightDivider

    val primaryColor: Int
        get() = blue

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()

            if (strokeColor != Color.TRANSPARENT) {
                setStroke(1, strokeColor)
            }
        }
    }

    fun text(
        context: Context,
        value: String,
        size: Float = 16f,
        color: Int = textColor,
        bold: Boolean = false
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }

            includeFontPadding = true
        }
    }

    fun topBar(
        context: Context,
        title: String,
        subtitle: String? = null,
        onSearch: (() -> Unit)? = null,
        onMenu: (() -> Unit)? = null
    ): LinearLayout {

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)

            setPadding(
                dp(context, 16),
                dp(context, 8),
                dp(context, 8),
                dp(context, 8)
            )

            minimumHeight = dp(context, 64)
        }

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = text(
            context,
            title,
            21f,
            textColor,
            true
        )

        titleContainer.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val sub = text(
                context,
                subtitle,
                12f,
                mutedColor
            )

            titleContainer.addView(sub)
        }

        bar.addView(
            titleContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        if (onSearch != null) {
            bar.addView(
                iconButton(context, "⌕", onSearch)
            )
        }

        if (onMenu != null) {
            bar.addView(
                iconButton(context, "⋮", onMenu)
            )
        }

        return bar
    }

    fun iconButton(
        context: Context,
        symbol: String,
        action: () -> Unit
    ): TextView {

        return TextView(context).apply {
            text = symbol
            textSize = 27f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true

            setOnClickListener {
                action()
            }

            layoutParams = LinearLayout.LayoutParams(
                dp(context, 48),
                dp(context, 48)
            )
        }
    }

    fun searchBar(
        context: Context,
        hint: String = "جست‌وجو در پیام‌ها",
        onTextChanged: ((String) -> Unit)? = null
    ): EditText {

        return EditText(context).apply {

            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)

            this.hint = hint

            singleLine = true

            setPadding(
                dp(context, 18),
                dp(context, 4),
                dp(context, 18),
                dp(context, 4)
            )

            background = rounded(
                fieldColor,
                dp(context, 32)
            )

            if (onTextChanged != null) {
                addTextChangedListener(
                    object : android.text.TextWatcher {

                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            onTextChanged(s?.toString() ?: "")
                        }

                        override fun afterTextChanged(
                            s: android.text.Editable?
                        ) {}
                    }
                )
            }
        }
    }

    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(dividerColor)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            )
        }
    }

    fun avatar(
        context: Context,
        name: String,
        size: Int = 48
    ): TextView {

        val colors = intArrayOf(
            0xFF1A73E8.toInt(),
            0xFF188038.toInt(),
            0xFF9334E6.toInt(),
            0xFFE8710A.toInt(),
            0xFF00897B.toInt(),
            0xFFD93025.toInt()
        )

        val color = colors[
            (name.hashCode().absoluteValue % colors.size)
        ]

        val firstLetter = name.trim().firstOrNull()?.toString() ?: "?"

        return TextView(context).apply {
            text = firstLetter
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }

            layoutParams = LinearLayout.LayoutParams(
                dp(context, size),
                dp(context, size)
            )
        }
    }

    fun bottomNavigation(
        context: Context,
        selected: String,
        onInbox: () -> Unit,
        onStarred: () -> Unit,
        onArchived: () -> Unit
    ): LinearLayout {

        val navigation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(surface)

            setPadding(
                dp(context, 8),
                dp(context, 8),
                dp(context, 8),
                dp(context, 8)
            )
        }

        fun item(
            icon: String,
            label: String,
            key: String,
            action: () -> Unit
        ): LinearLayout {

            val active = selected == key

            return LinearLayout(context).apply {

                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true

                val iconView = TextView(context).apply {
                    text = icon
                    textSize = 22f
                    gravity = Gravity.CENTER

                    setTextColor(
                        if (active) blue else mutedColor
                    )

                    background = rounded(
                        if (active) {
                            if (darkMode)
                                Color.rgb(39, 57, 82)
                            else
                                Color.rgb(232, 240, 254)
                        } else {
                            Color.TRANSPARENT
                        },
                        dp(context, 24)
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        dp(context, 64),
                        dp(context, 32)
                    )
                }

                val labelView = text(
                    context,
                    label,
                    12f,
                    if (active) blue else mutedColor,
                    active
                )

                labelView.gravity = Gravity.CENTER

                addView(iconView)
                addView(labelView)

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )

                setOnClickListener {
                    action()
                }
            }
        }

        navigation.addView(
            item("▤", "پیام‌ها", "inbox", onInbox)
        )

        navigation.addView(
            item("★", "ستاره‌دار", "starred", onStarred)
        )

        navigation.addView(
            item("▣", "بایگانی", "archived", onArchived)
        )

        return navigation
    }

    private val Int.absoluteValue: Int
        get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
}
