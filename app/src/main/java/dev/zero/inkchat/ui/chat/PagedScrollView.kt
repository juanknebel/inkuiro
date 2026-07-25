package dev.zero.inkchat.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ScrollView
import dev.zero.inkchat.ui.eink.EinkRefresh

/**
 * Reading surface for the chat. Supports finger dragging plus discrete page
 * jumps (~90% of the visible height) via [pageUp]/[pageDown] or a tap on the
 * top or bottom third.
 *
 * Two concessions to e-ink: fling is disabled (inertia keeps repainting the
 * screen after the finger lifts, which smears badly and overshoots), and a
 * drag runs in the fast waveform, restoring quality plus a full refresh once
 * the finger lifts.
 */
class PagedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                when {
                    e.y < height / 3f -> pageUp()
                    e.y > height * 2f / 3f -> pageDown()
                }
                return true
            }
        },
    )

    /** scrollY when the current gesture started, to tell a drag from a tap. */
    private var scrollYAtTouchDown = 0
    private var draggingInFastMode = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> scrollYAtTouchDown = scrollY

            // Only once the content actually moves — a tap wiggles a few pixels
            // and must not pay for a full refresh on release.
            MotionEvent.ACTION_MOVE -> if (!draggingInFastMode && scrollY != scrollYAtTouchDown) {
                draggingInFastMode = true
                EinkRefresh.applyFastMode(this)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingInFastMode) {
                draggingInFastMode = false
                EinkRefresh.applyQualityMode(this)
                EinkRefresh.fullRefresh(this)
            }
        }
        return super.onTouchEvent(ev)
    }

    /** No inertia: a fling would keep repainting long after the finger lifts. */
    override fun fling(velocityY: Int) = Unit

    /** Manual page turns since the last full refresh. */
    private var turnsSinceFullRefresh = 0

    fun pageUp() {
        jumpTo(scrollY - pageJump())
        countTurn()
    }

    fun pageDown() {
        jumpTo(scrollY + pageJump())
        countTurn()
    }

    /** Not counted as a page turn: used by streaming to follow the text. */
    fun toBottom() = jumpTo(maxScroll())

    // Full refresh every N page turns to clean accumulated ghosting.
    // TODO(device): tune N based on real feel on the Palma.
    private fun countTurn() {
        if (++turnsSinceFullRefresh >= FULL_REFRESH_EVERY_TURNS) {
            turnsSinceFullRefresh = 0
            EinkRefresh.fullRefresh(this)
        }
    }

    private fun pageJump(): Int = (height * 0.9f).toInt().coerceAtLeast(1)

    private fun maxScroll(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.height + paddingTop + paddingBottom - height).coerceAtLeast(0)
    }

    private fun jumpTo(y: Int) {
        scrollTo(0, y.coerceIn(0, maxScroll()))
    }

    companion object {
        private const val FULL_REFRESH_EVERY_TURNS = 5
    }
}
