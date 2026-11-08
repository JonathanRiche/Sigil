package dev.omarchy.gliff

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small chrome glyphs; touch targets are supplied by the surrounding 48dp buttons. */
class ChromeIcon(private val kind: String, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffaaaaaa.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = (20*density).toInt()
    override fun getIntrinsicHeight() = (20*density).toInt()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(x1: Float,y1: Float,x2: Float,y2: Float) = canvas.drawLine(x1,y1,x2,y2,paint)
        when (kind) {
            "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            "controls" -> {
                line(4f,6f,20f,6f); line(4f,12f,20f,12f); line(4f,18f,20f,18f)
                line(9f,3f,9f,9f); line(16f,9f,16f,15f); line(11f,15f,11f,21f)
            }
            else -> {
                line(14f,4f,20f,4f); line(20f,4f,20f,10f); line(20f,4f,13f,11f)
                line(4f,14f,4f,20f); line(4f,20f,10f,20f); line(4f,20f,11f,13f)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
