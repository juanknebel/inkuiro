package dev.zero.inkchat.ui.common

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * Movement method that consumes a touch **only** when a link sits under the
 * finger, letting every other tap bubble up to the parent.
 *
 * The stock [LinkMovementMethod] extends ScrollingMovementMethod, which grabs
 * ACTION_DOWN unconditionally to support dragging the TextView's own content.
 * In this app that swallows the tap before it can reach
 * [dev.zero.inkchat.ui.chat.PagedScrollView], so tapping a message to turn the
 * page does nothing once messages fill the screen.
 */
object LinkOnlyMovementMethod : LinkMovementMethod() {

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return false

        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY

        val line = layout.getLineForVertical(y)
        // getOffsetForHorizontal snaps to the nearest character, so a tap in the
        // empty space beside a short line would otherwise "hit" its last link.
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false

        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val links = buffer.getSpans(offset, offset, ClickableSpan::class.java)
        if (links.isEmpty()) return false

        if (action == MotionEvent.ACTION_UP) links[0].onClick(widget)
        return true
    }
}
