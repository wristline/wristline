package dev.wristline.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import androidx.core.content.ContextCompat

/** The number on the Now Bar icon's badge: [waiting] up to 9, then "9+"; null (no badge) at zero. */
internal fun ongoingBadgeText(waiting: Int): String? = when {
    waiting <= 0 -> null
    waiting <= 9 -> waiting.toString()
    else -> "9+"
}

/** Where [OngoingIcon] draws the badge on an icon [size] pixels wide: a disc at ([cx], [cy]) holding [label]. */
internal data class OngoingBadge(val label: String, val cx: Float, val cy: Float, val radius: Float, val textSize: Float)

/** The badge for [waiting] sessions ([ongoingBadgeText]), at the top right, about 40% of the icon; null at zero. */
internal fun ongoingBadge(size: Int, waiting: Int): OngoingBadge? = ongoingBadgeText(waiting)?.let { label ->
    val radius = size * 0.2f
    OngoingBadge(label, cx = size - radius, cy = radius, radius = radius, textSize = radius * if (label.length == 1) 1.5f else 1.15f)
}

/**
 * The running badge for [running] sessions, at the bottom left, the waiting badge's size
 * ([ongoingBadge]): a plain dot for one, the count ([ongoingBadgeText]) from two; null at zero.
 */
internal fun ongoingRunningBadge(size: Int, running: Int): OngoingBadge? = ongoingBadge(size, running)?.let {
    it.copy(label = if (running >= 2) it.label else "", cy = size - it.radius, cx = it.radius)
}

/**
 * How far [R.drawable.ic_ongoing]'s squircle reaches from the centre along each axis, in half-widths
 * of the drawable's bounds: the case's edge (216 of 256 units) scaled 1.18.
 */
private const val SQUIRCLE_EXTENT = 0.995625f

/** The rows [OngoingIcon] fills with the primary on an icon [size] pixels tall, from [top] down to [bottom]. */
internal data class OngoingFill(val top: Float, val bottom: Float)

/**
 * The fill for [progress] (0 to 1): from the squircle's bottom edge up to that fraction of its
 * height; null when there is nothing to fill.
 */
internal fun ongoingFill(size: Int, progress: Float): OngoingFill? {
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction == 0f) return null
    val half = size / 2f * SQUIRCLE_EXTENT
    val bottom = size / 2f + half
    return OngoingFill(top = bottom - 2 * half * fraction, bottom = bottom)
}

/**
 * The icon Wristline shows on the Now Bar: [R.drawable.ic_ongoing] (the white squircle) as a
 * bitmap, used by the monitoring card and, as the small icon, by the Live Updates (the Now Bar
 * draws a Live Update's small icon bitmap untinted). Only the monitoring card carries badges, so
 * that it tells the state with the Now Bar set to icons only: the waiting count ([ongoingBadge]) in
 * an attention-yellow disc (Status.Attention) and the running one ([ongoingRunningBadge]) in a
 * green one, outlined dark so they read on the white squircle, the count in black bold; greyed out
 * ([R.drawable.ic_ongoing_offline]) and without badges while offline. Only the Live Updates show
 * progress: the squircle filled with the app's primary from the bottom up ([ongoingFill]), under
 * the dial and the prompt ([R.drawable.ic_ongoing_dial]).
 */
internal object OngoingIcon {
    private const val SIZE_PX = 96
    private const val BADGE_OUTLINE_PX = 2f
    private const val BADGE_FILL = 0xFFFFD60A.toInt() // Status.Attention
    private const val RUNNING_FILL = 0xFF30D158.toInt()
    private const val BADGE_OUTLINE = 0xFF000000.toInt()
    private const val BADGE_TEXT = 0xFF000000.toInt()
    private const val FILL = 0xFF4FA8FF.toInt() // Notifier.COLOR

    /**
     * The icon, with a badge for [waiting] sessions and one for [running] sessions when there are
     * any, and filled to [progress] (0 to 1) when not null; grey and without badges when [offline].
     */
    fun bitmap(context: Context, waiting: Int = 0, progress: Float? = null, running: Int = 0, offline: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = if (offline) R.drawable.ic_ongoing_offline else R.drawable.ic_ongoing
        ContextCompat.getDrawable(context, drawable)!!.apply { setBounds(0, 0, SIZE_PX, SIZE_PX) }.draw(canvas)
        progress?.let { ongoingFill(SIZE_PX, it) }?.let { fill ->
            // SRC_ATOP paints only where the squircle is: clipped to it, edges antialiased alike.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            canvas.drawRect(0f, fill.top, SIZE_PX.toFloat(), fill.bottom, paint)
            ContextCompat.getDrawable(context, R.drawable.ic_ongoing_dial)!!.apply { setBounds(0, 0, SIZE_PX, SIZE_PX) }.draw(canvas)
        }
        if (!offline) {
            ongoingBadge(SIZE_PX, waiting)?.let { drawBadge(canvas, it, BADGE_FILL) }
            ongoingRunningBadge(SIZE_PX, running)?.let { drawBadge(canvas, it, RUNNING_FILL) }
        }
        return bitmap
    }

    private fun drawBadge(canvas: Canvas, badge: OngoingBadge, fill: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = BADGE_OUTLINE
        canvas.drawCircle(badge.cx, badge.cy, badge.radius, paint)
        paint.color = fill
        canvas.drawCircle(badge.cx, badge.cy, badge.radius - BADGE_OUTLINE_PX, paint)
        if (badge.label.isEmpty()) return
        paint.color = BADGE_TEXT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = badge.textSize
        val metrics = paint.fontMetrics
        canvas.drawText(badge.label, badge.cx, badge.cy - (metrics.ascent + metrics.descent) / 2, paint)
    }
}
