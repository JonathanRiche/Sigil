package dev.omarchy.gliff

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Button

/** Colours for the lower control deck: a graphite or aluminium keyboard surface. Local appearance only. */
class DeckTheme private constructor(val dark: Boolean) {
    private fun pick(night: Long,day: Long) = (if (dark) night else day).toInt()
    val deck = pick(0xff0c0c0e,0xff9c9b97)
    val keyTop = pick(0xff262629,0xfff6f5f1)
    val keyBottom = pick(0xff1c1c1f,0xffe7e6e1)
    val keyDown = pick(0xff141416,0xffd2d1cc)
    val keyEdge = pick(0xff000000,0xff7b7a76)
    val keyShine = pick(0x16ffffff,0xffffffff)
    val label = pick(0xfff1f1f3,0xff4a494e)
    val dim = pick(0xff9a9aa2,0xff6b6a6f)
    val rail = pick(0xff1a1a1d,0xffadaca7)
    val railDown = pick(0xff2c2c31,0xffc6c5c0)
    val railIcon = pick(0xffd4d4da,0xfffafaf8)
    val pad = pick(0xff131315,0xffe6e5e1)
    val padLine = pick(0x24ffffff,0x99ffffff)
    val hint = pick(0xff6c6c76,0xff84837e)
    val onDeck = pick(0xff8c8c96,0xfff2f1ed)
    val accent = 0xff2fc6c8.toInt()
    val accentDown = 0xff249fa1.toInt()
    val onAccent = 0xff04292a.toInt()
    val danger = 0xffff5a5f.toInt()
    companion object {
        const val AUTO = "auto"; const val DARK = "dark"; const val LIGHT = "light"
        fun mode(context: Context) = context.getSharedPreferences("controls",Context.MODE_PRIVATE).getString("deck_theme",AUTO) ?: AUTO
        fun of(context: Context): DeckTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO
            return DeckTheme(when (mode(context)) { DARK -> true; LIGHT -> false; else -> night })
        }
    }
}

/** Stroke glyphs on a 24 unit grid. Touch targets come from the surrounding control. */
class DeckIcon(kind: String,private val density: Float,tint: Int = 0xffaaaaaa.toInt()) : Drawable() {
    private val filled = kind in FILLED
    private val path: Path = androidx.core.graphics.PathParser.createPathFromPathData(PATHS[kind] ?: PATHS.getValue("plus"))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        strokeWidth = if (filled) 1.2f else 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    var color: Int
        get() = paint.color
        set(value) { if (paint.color != value) { paint.color = value; invalidateSelf() } }
    override fun getIntrinsicWidth() = (20*density).toInt()
    override fun getIntrinsicHeight() = (20*density).toInt()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        canvas.drawPath(path,paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        private val FILLED = setOf("play","previous","next")
        private const val SPEAKER = "M4 9.5h3l5-4v13l-5-4H4z "
        private val PATHS = mapOf(
            "plus" to "M12 5v14 M5 12h14",
            "settings" to "M4 7h9 M19 7h1 M4 17h1 M11 17h9 M16 4.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5z M8 14.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5z",
            "fullscreen" to "M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5",
            "keyboard" to "M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M6 10h.01 M10 10h.01 M14 10h.01 M18 10h.01 M7.5 14h9",
            "trackpad" to "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M2 14h20 M12 14v5",
            "mic" to "M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v4",
            "volume" to SPEAKER+"M15.5 9a4 4 0 0 1 0 6 M18 6.5a7.5 7.5 0 0 1 0 11",
            "volume_down" to SPEAKER+"M16 12h5",
            "volume_up" to SPEAKER+"M16 12h5 M18.5 9.5v5",
            "mute" to SPEAKER+"M16 9.5l5 5 M21 9.5l-5 5",
            "display" to "M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M8 20h8 M12 16v4",
            "overview" to "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z",
            "search" to "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14z M16 16l4.5 4.5",
            "previous" to "M19 6v12l-9-6z M6 6v12",
            "play" to "M8 5.5v13l10.5-6.5z",
            "next" to "M5 6v12l9-6z M18 6v12",
            "brightness_down" to "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6z M12 4.5v.5 M12 19v.5 M4.5 12H5 M19 12h.5 M6.7 6.7l.4.4 M16.9 16.9l.4.4 M6.7 17.3l.4-.4 M16.9 7.1l.4-.4",
            "brightness_up" to "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4",
            "backspace" to "M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z M12 9.5l5 5 M17 9.5l-5 5",
            "shift" to "M12 4l8 9h-4.5v6h-7v-6H4z",
            "return" to "M20 5v7a3 3 0 0 1-3 3H5 M9 11l-4 4l4 4",
            "globe" to "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18",
            "undo" to "M9 14l-5-5l5-5 M4 9h10a6 6 0 0 1 0 12h-3",
            "copy" to "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1",
            "paste" to "M8 4H6a1 1 0 0 0-1 1v15a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1h-2 M9 3h6v3H9z",
            "left" to "M14.5 6.5l-5.5 5.5l5.5 5.5",
            "right" to "M9.5 6.5l5.5 5.5l-5.5 5.5",
            "up" to "M6.5 14.5l5.5-5.5l5.5 5.5",
            "down" to "M6.5 9.5l5.5 5.5l5.5-5.5",
            "zoom" to "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14z M16 16l4.5 4.5 M11 8.5v5 M8.5 11h5",
            "appearance" to "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M12 3v18 M12 7.5h7.7 M12 12h9 M12 16.5h7.7",
            "close" to "M6.5 6.5l11 11 M17.5 6.5l-11 11",
            "sigil" to "M12 2.5l8 9.5l-8 9.5l-8-9.5z M12 8l3.4 4l-3.4 4l-3.4-4z"
        )
    }
}

/** A sculpted keycap or a flat rail capsule, depending on kind. */
class KeyDrawable(private val theme: DeckTheme,private val density: Float,private val kind: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = false
    private var gradient: LinearGradient? = null
    private var gradientHeight = -1f
    private var gradientTint = 0
    val raised get() = kind == KeyView.KEY || kind == KeyView.ACCENT
    val depth get() = if (raised) 2f*density else 0f
    var radius = 10f*density
    var lit = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var fill = theme.accent
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now; invalidateSelf(); return true
    }
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        // Keycaps stay rounded rectangles on short rows; rail capsules may round fully.
        val r = if (kind == KeyView.KEY) minOf(radius,b.height()*0.28f,b.width()/2f) else minOf(radius,b.height()/2f,b.width()/2f)
        if (kind == KeyView.RAIL) {
            paint.shader = null; paint.style = Paint.Style.FILL
            paint.color = if (pressed) theme.railDown else if (lit) theme.railDown else theme.rail
            rect.set(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())
            canvas.drawRoundRect(rect,r,r,paint)
            return
        }
        val travel = if (pressed) depth*0.6f else 0f
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = if (kind == KeyView.ACCENT) darken(fill) else theme.keyEdge
        rect.set(b.left.toFloat(),b.top+depth,b.right.toFloat(),b.bottom.toFloat())
        canvas.drawRoundRect(rect,r,r,paint)
        rect.set(b.left.toFloat(),b.top+travel,b.right.toFloat(),b.bottom-depth+travel)
        if (pressed) paint.color = if (kind == KeyView.ACCENT) darken(fill) else theme.keyDown
        else {
            if (gradient == null || gradientHeight != rect.height() || gradientTint != fill) {
                gradientHeight = rect.height(); gradientTint = fill
                gradient = if (kind == KeyView.ACCENT) LinearGradient(0f,0f,0f,gradientHeight,lighten(fill),fill,Shader.TileMode.CLAMP)
                else LinearGradient(0f,0f,0f,gradientHeight,theme.keyTop,theme.keyBottom,Shader.TileMode.CLAMP)
            }
            val save = canvas.save(); canvas.translate(0f,rect.top)
            paint.shader = gradient
            canvas.drawRoundRect(rect.left,0f,rect.right,rect.height(),r,r,paint)
            paint.shader = null; canvas.restoreToCount(save)
        }
        if (pressed) canvas.drawRoundRect(rect,r,r,paint)
        paint.style = Paint.Style.STROKE
        if (lit) { paint.strokeWidth = 1.5f*density; paint.color = theme.accent }
        else { paint.strokeWidth = maxOf(1f,density*0.5f); paint.color = if (kind == KeyView.ACCENT) 0x33ffffff else theme.keyShine }
        val inset = paint.strokeWidth/2f
        rect.inset(inset,inset)
        if (lit || !pressed) canvas.drawRoundRect(rect,r-inset,r-inset,paint)
    }
    private fun darken(color: Int) = android.graphics.Color.rgb((android.graphics.Color.red(color)*0.72f).toInt(),(android.graphics.Color.green(color)*0.72f).toInt(),(android.graphics.Color.blue(color)*0.72f).toInt())
    private fun lighten(color: Int) = android.graphics.Color.rgb(minOf(255,android.graphics.Color.red(color)+26),minOf(255,android.graphics.Color.green(color)+26),minOf(255,android.graphics.Color.blue(color)+26))
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** One deck control: a label, a glyph, or both. Reported to accessibility as a button. */
class KeyView(context: Context,private val theme: DeckTheme,private val kind: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val face = KeyDrawable(theme,density,kind)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(if (kind == KEY) "sans-serif" else "sans-serif-medium",Typeface.NORMAL) }
    var label = ""
        set(value) { if (field != value) { field = value; invalidate() } }
    var icon: DeckIcon? = null
        set(value) { field = value; invalidate() }
    var textSp = 16f
        set(value) { field = value; invalidate() }
    /** Draw the content rotated a quarter turn, for tall capsules in a side rail. */
    var vertical = false
        set(value) { field = value; invalidate() }
    var muted = false
        set(value) { field = value; invalidate() }
    var lit: Boolean
        get() = face.lit
        set(value) { face.lit = value; invalidate() }
    var fill: Int
        get() = face.fill
        set(value) { face.fill = value; invalidate() }
    var radiusDp: Float
        get() = face.radius/density
        set(value) { face.radius = value*density; invalidate() }
    init { background = face; isClickable = true; isFocusable = true }
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val color = when {
            kind == ACCENT -> if (face.fill == theme.accent) theme.onAccent else 0xffffffff.toInt()
            face.lit -> if (kind == RAIL) theme.label else theme.accent
            kind == RAIL -> theme.railIcon
            muted -> theme.dim
            else -> theme.label
        }
        val alpha = if (isEnabled) 255 else 96
        val travel = if (isPressed && face.raised) face.depth*0.6f else 0f
        val cx = width/2f; val cy = (height-face.depth)/2f+travel
        val length = if (vertical) height.toFloat() else width.toFloat()
        val glyph = icon
        val glyphSize = if (glyph == null) 0f else minOf(20f*density,minOf(width,height)-6f*density)
        val gap = if (glyph != null && label.isNotEmpty()) 6f*density else 0f
        var size = textSp*resources.displayMetrics.scaledDensity
        paint.textSize = size
        var textWidth = if (label.isEmpty()) 0f else paint.measureText(label)
        val room = length-8f*density-glyphSize-gap
        if (textWidth > room && textWidth > 0f) {
            size = maxOf(7f*density,size*room/textWidth); paint.textSize = size; textWidth = paint.measureText(label)
        }
        val total = glyphSize+gap+textWidth
        val baseline = -(paint.ascent()+paint.descent())/2f
        paint.color = color; paint.alpha = alpha
        glyph?.let { it.color = color; it.alpha = alpha }
        if (vertical) {
            // Glyph stays upright at the bottom; only the label turns to read upward.
            val bottom = cy+total/2f
            glyph?.let { it.setBounds((cx-glyphSize/2f).toInt(),(bottom-glyphSize).toInt(),(cx+glyphSize/2f).toInt(),bottom.toInt()); it.draw(canvas) }
            if (label.isNotEmpty()) {
                val middle = bottom-glyphSize-gap-textWidth/2f
                val save = canvas.save(); canvas.rotate(-90f,cx,middle)
                canvas.drawText(label,cx-textWidth/2f,middle+baseline,paint)
                canvas.restoreToCount(save)
            }
        } else {
            var x = cx-total/2f
            glyph?.let { it.setBounds(x.toInt(),(cy-glyphSize/2f).toInt(),(x+glyphSize).toInt(),(cy+glyphSize/2f).toInt()); it.draw(canvas); x += glyphSize+gap }
            if (label.isNotEmpty()) canvas.drawText(label,x,cy+baseline,paint)
        }
    }
    companion object { const val KEY = 0; const val RAIL = 1; const val ACCENT = 2 }
}
