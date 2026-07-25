package dev.zero.inkchat.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

object Markdown {

    /**
     * Renders [markdown] into [view], keeping the message tappable for page
     * turns.
     *
     * Markwon installs a LinkMovementMethod, which grabs every touch and also
     * marks the TextView clickable — either one swallows the tap before it
     * reaches [dev.zero.inkchat.ui.chat.PagedScrollView]. Swapping in
     * [LinkOnlyMovementMethod] and clearing the flags keeps links working while
     * plain taps bubble up to the pager.
     */
    fun render(markwon: Markwon, view: TextView, markdown: String) {
        markwon.setMarkdown(view, markdown)
        view.movementMethod = LinkOnlyMovementMethod
        view.isClickable = false
        view.isLongClickable = false
    }

    /** Markwon configured for e-ink: everything black on white, monospaced code. */
    fun create(context: Context): Markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .codeBlockBackgroundColor(Color.WHITE)
                    .codeBackgroundColor(Color.WHITE)
                    .codeBlockTypeface(Typeface.MONOSPACE)
                    .codeTypeface(Typeface.MONOSPACE)
                    .codeTextColor(Color.BLACK)
                    .codeBlockTextColor(Color.BLACK)
                    .blockQuoteColor(Color.BLACK)
                    .listItemColor(Color.BLACK)
                    .linkColor(Color.BLACK)
                // TODO(polish): 1dp border around code blocks (custom span).
            }
        })
        .build()
}
