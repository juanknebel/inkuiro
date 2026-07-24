package dev.zero.inkchat.ui.common

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** E-ink list item: bold title + smaller metadata below. */
object TwoLine {

    fun of(title: String, subtitle: String): CharSequence =
        SpannableStringBuilder().apply {
            append(title)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            val subtitleStart = length
            append(subtitle)
            setSpan(
                RelativeSizeSpan(0.8f),
                subtitleStart,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
}
