package fuck.system.hashcat

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Draws the Hashcat log without TextView selection, focus or nested ScrollView
 * behavior. Text layout is rebuilt only when the buffered log changes, while
 * touch scrolling changes only the canvas viewport.
 */
class HashcatLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs)
{
    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val scroller = OverScroller(context)
    private var text: CharSequence = ""
    private var textLayout: StaticLayout? = null
    private var lastTouchY = 0f
    private var dragging = false
    private var followTail = true

    /**
     * Replaces the buffered log text and keeps the current viewport stable.
     * When the viewport already follows the tail, it remains at the newest
     * output after the new layout is measured.
     */
    fun setLogText(value: CharSequence)
    {
        if (text == value)
        {
            return
        }

        val oldMaxScroll = maxScrollY()
        val oldScrollY = scrollY
        val wasFollowingTail = followTail || oldMaxScroll - oldScrollY <= bottomSlopPx()

        text = value
        rebuildLayout()

        if (wasFollowingTail)
        {
            followTail = true
            scrollTo(0, maxScrollY())
        }
        else
        {
            followTail = false
            scrollTo(0, oldScrollY.coerceAtMost(maxScrollY()))
        }

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
    {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()

        if (followTail)
        {
            scrollTo(0, maxScrollY())
        }
        else
        {
            scrollTo(0, scrollY.coerceAtMost(maxScrollY()))
        }
    }

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        textLayout?.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean
    {
        when (event.actionMasked)
        {
            MotionEvent.ACTION_DOWN ->
            {
                if (!scroller.isFinished)
                {
                    scroller.abortAnimation()
                }

                lastTouchY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE ->
            {
                val deltaY = lastTouchY - event.y

                if (abs(deltaY) >= 1f)
                {
                    dragging = true
                    scrollTo(0, (scrollY + deltaY.toInt()).coerceIn(0, maxScrollY()))
                    followTail = maxScrollY() - scrollY <= bottomSlopPx()
                    lastTouchY = event.y
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
            {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun computeScroll()
    {
        if (scroller.computeScrollOffset())
        {
            scrollTo(0, scroller.currY.coerceIn(0, maxScrollY()))
            followTail = maxScrollY() - scrollY <= bottomSlopPx()
            postInvalidateOnAnimation()
        }
    }

    private fun rebuildLayout()
    {
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)

        textLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun maxScrollY(): Int
    {
        val contentHeight = (textLayout?.height ?: 0) + paddingTop + paddingBottom
        return (contentHeight - height).coerceAtLeast(0)
    }

    private fun bottomSlopPx(): Int
    {
        return (BOTTOM_SLOP_DP * resources.displayMetrics.density).toInt()
    }

    private companion object
    {
        const val BOTTOM_SLOP_DP = 32
    }
}
