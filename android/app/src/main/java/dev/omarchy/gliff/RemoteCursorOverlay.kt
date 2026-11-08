package dev.omarchy.gliff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Server position with a standard pointer fallback when the host omits its image. */
class RemoteCursorOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var shapeId = -1
    private var hotX = 0
    private var hotY = 0
    private var imagePixelToLogical = 1.0
    private var positionId = -1
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var shown = false
    private var geometry: SessionState.Geometry? = null
    private val picture = RectF()
    private var lastDrawReason = ""
    private fun diagnostic(reason: String) {
        if(reason == lastDrawReason) return
        lastDrawReason = reason
        android.util.Log.i("GliffCursor","draw=$reason shape=${shapeId.toUInt()} position=${positionId.toUInt()} shown=$shown geometry=${geometry?.let { "${it.width}x${it.height}@${it.scale}" } ?: "none"} picture=${picture.width()}x${picture.height()}")
    }
    init { isClickable = false; isLongClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun reset() {
        bitmap = null; shapeId = -1; positionId = -1; shown = false; geometry = null; picture.setEmpty(); invalidate()
    }
    fun shape(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, logicalPerPixel: Double, argb: IntArray) {
        require(width in 1..1024 && height in 1..1024 && width.toLong()*height <= 1048576 && argb.size == width*height)
        require(hotspotX in 0 until width && hotspotY in 0 until height && logicalPerPixel.isFinite() && logicalPerPixel > 0)
        bitmap = Bitmap.createBitmap(argb,width,height,Bitmap.Config.ARGB_8888)
        shapeId = id; hotX = hotspotX; hotY = hotspotY; imagePixelToLogical = logicalPerPixel; invalidate()
    }
    fun position(id: Int, logicalX: Double, logicalY: Double, visible: Boolean) {
        require(logicalX.isFinite() && logicalY.isFinite())
        positionId = id; cursorX = logicalX; cursorY = logicalY; shown = visible; invalidate()
    }
    fun picture(geometry: SessionState.Geometry, left: Int, top: Int, width: Int, height: Int) {
        this.geometry = geometry; picture.set(left.toFloat(),top.toFloat(),(left+width).toFloat(),(top+height).toFloat()); invalidate()
    }
    override fun onTouchEvent(event: MotionEvent) = false
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap
        val g = geometry ?: run { diagnostic("no-geometry"); return }
        if (!shown) { diagnostic("hidden"); return }

        if(picture.isEmpty) { diagnostic("empty-picture"); return }
        val useImage = image != null && shapeId == positionId
        diagnostic(if (useImage) "drawing" else "fallback-no-matching-shape")
        val logicalWidth = g.width*1000.0/g.scale; val logicalHeight = g.height*1000.0/g.scale
        val sx = picture.width()/logicalWidth; val sy = picture.height()/logicalHeight
        val pixelX = imagePixelToLogical*sx; val pixelY = imagePixelToLogical*sy
        val left = picture.left+cursorX*sx-hotX*pixelX; val top = picture.top+cursorY*sy-hotY*pixelY
        val save = canvas.save(); canvas.clipRect(picture)
        if (useImage && image != null) {
            canvas.drawBitmap(image,null,RectF(left.toFloat(),top.toFloat(),(left+image.width*pixelX).toFloat(),(top+image.height*pixelY).toFloat()),paint)
        } else {
            // Position is authoritative; only the missing shape is substituted.
            canvas.translate((picture.left+cursorX*sx).toFloat(), (picture.top+cursorY*sy).toFloat())
            val size = 24f * resources.displayMetrics.density
            val path = Path().apply {
                moveTo(0f,0f); lineTo(0f,size); lineTo(size*.25f,size*.74f)
                lineTo(size*.43f,size*1.08f); lineTo(size*.6f,size)
                lineTo(size*.42f,size*.67f); lineTo(size*.78f,size*.67f); close()
            }
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; canvas.drawPath(path,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=resources.displayMetrics.density
            paint.strokeJoin=Paint.Join.ROUND; paint.color=Color.BLACK; canvas.drawPath(path,paint)
            paint.style=Paint.Style.FILL
        }
        canvas.restoreToCount(save)
    }
}
