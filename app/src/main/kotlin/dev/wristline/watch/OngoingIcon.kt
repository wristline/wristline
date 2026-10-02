package dev.wristline.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
 * The icon Wristline shows on the Now Bar: [R.drawable.ic_ongoing] (the white squircle) as a
 * bitmap, used by the monitoring card and, as the small icon, by the Live Updates (the Now Bar
 * draws a Live Update's small icon bitmap untinted). Only the monitoring card carries a badge
 * ([ongoingBadge]): an attention-yellow disc (Status.Attention), outlined dark so it reads on the
 * white squircle, holding the count in black bold.
 */
internal object OngoingIcon {
    private const val SIZE_PX = 96
    private const val BADGE_OUTLINE_PX = 2f
    private const val BADGE_FILL = 0xFFFFD60A.toInt() // Status.Attention
    private const val BADGE_OUTLINE = 0xFF000000.toInt()
    private const val BADGE_TEXT = 0xFF000000.toInt()

    /** The icon, with a badge for [waiting] sessions when there are any. */
    fun bitmap(context: Context, waiting: Int = 0): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(context, R.drawable.ic_ongoing)!!.apply { setBounds(0, 0, SIZE_PX, SIZE_PX) }.draw(canvas)
        ongoingBadge(SIZE_PX, waiting)?.let { badge ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = BADGE_OUTLINE
            canvas.drawCircle(badge.cx, badge.cy, badge.radius, paint)
            paint.color = BADGE_FILL
            canvas.drawCircle(badge.cx, badge.cy, badge.radius - BADGE_OUTLINE_PX, paint)
            paint.color = BADGE_TEXT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = badge.textSize
            val metrics = paint.fontMetrics
            canvas.drawText(badge.label, badge.cx, badge.cy - (metrics.ascent + metrics.descent) / 2, paint)
        }
        return bitmap
    }
}
