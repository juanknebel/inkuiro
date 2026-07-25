package dev.zero.inkchat.ui.common

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import dev.zero.inkchat.R

/** E-ink single-line text prompt: title + prefilled EditText + Save/Cancel. */
object PromptDialog {

    fun show(
        context: Context,
        title: String,
        initialText: String,
        onConfirm: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val input = EditText(context)
        input.setText(initialText)
        input.setTextColor(context.getColor(R.color.ink_black))
        input.setBackgroundResource(R.drawable.bg_input)
        input.setPadding(dp(12), dp(12), dp(12), dp(12))
        val wrapper = FrameLayout(context)
        wrapper.setPadding(dp(16), dp(8), dp(16), 0)
        wrapper.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        AlertDialog.Builder(context, R.style.Theme_InkChat_Dialog)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
