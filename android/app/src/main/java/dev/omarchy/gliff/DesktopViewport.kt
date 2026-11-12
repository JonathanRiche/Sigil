package dev.omarchy.gliff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.*
import android.widget.FrameLayout
import kotlin.math.abs

/** Persistent video/cursor parent. Zoom is a local transform and never changes host modes. */
class DesktopViewport(
    context: Context, surface: SurfaceView, private val cursor: RemoteCursorOverlay,
    private val ready: () -> Boolean,
    private val tap: (Float,Float) -> Unit,
    private val cancelInput: () -> Unit,
    private val workspace: (Int) -> Boolean
) : FrameLayout(context) {
    private val picture = FrameLayout(context)
    private val feedback = Feedback(context)
    private var geometry: SessionState.Geometry? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var multi = false
    private var three = false
    private var threeX = 0f
    private var threeY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var endX = 0f
    private var endY = 0f
    private var animator: ValueAnimator? = null
    private var slide = 0f
    private var slideAnimator: ValueAnimator? = null
    private fun dp(n: Int) = n*resources.displayMetrics.density
    private val scaleDetector = ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom((zoom*detector.scaleFactor).coerceIn(1f,3f),detector.focusX,detector.focusY,false); return true
        }
    })
    private val taps = GestureDetector(context,object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
            if (ready() && !multi) {
                tap(e.x,e.y)
                feedback.pulse(picture.left+picture.width/2f+panX+(e.x-picture.width/2f)*zoom,picture.top+picture.height/2f+panY+(e.y-picture.height/2f)*zoom)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            return true
        }
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
            if (ready()) setZoom(if (zoom > 1.1f) 1f else 2f,e.x,e.y,true)
            return true
        }
    })
    init {
        clipChildren = true; clipToPadding = true; setBackgroundColor(android.graphics.Color.BLACK)
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View,outline: android.graphics.Outline) { outline.setRoundRect(0,0,view.width,view.height,dp(18)) } }
        clipToOutline = true
        picture.clipChildren = false; picture.clipToPadding = false
        picture.addView(surface,LayoutParams(-1,-1)); picture.addView(cursor,LayoutParams(-1,-1))
        addView(picture,LayoutParams(-1,-1)); addView(feedback,LayoutParams(-1,-1))
        surface.setOnTouchListener { _,event -> handle(event) }
    }
    fun fit(geometry: SessionState.Geometry) {
        this.geometry = geometry
        if (width <= 0 || height <= 0) return
        val fit = minOf(width.toFloat()/geometry.viewWidth,height.toFloat()/geometry.viewHeight)
        val w = (geometry.viewWidth*fit).toInt(); val h = (geometry.viewHeight*fit).toInt()
        val left = (width-w)/2; val top = (height-h)/2
        cursor.picture(geometry,0,0,w,h)
        val old = picture.layoutParams as LayoutParams
        if (old.width != w || old.height != h || old.leftMargin != left || old.topMargin != top) picture.layoutParams = LayoutParams(w,h).apply { leftMargin = left; topMargin = top }
        applyTransform()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); geometry?.let { fit(it) } }
    fun transform(factor: Float,dx: Float,dy: Float) {
        if (!ready()) return
        setZoom((zoom*factor).coerceIn(1f,3f),picture.width/2f,picture.height/2f,false)
        panX += dx; panY += dy; applyTransform()
    }
    /** Step the local zoom from a deck button: fit, 1.6x, 2.4x, then back to fit. */
    fun toggleZoom() { if (ready()) setZoom(if (zoom < 1.3f) 1.6f else if (zoom < 2f) 2.4f else 1f,picture.width/2f,picture.height/2f,true) }
    fun reset() { animator?.cancel(); slideAnimator?.cancel(); slide = 0f; zoom = 1f; panX = 0f; panY = 0f; applyTransform() }
    private fun applyTransform() {
        val maxX = maxOf(0f,(picture.width*zoom-width)/2); val maxY = maxOf(0f,(picture.height*zoom-height)/2)
        panX = panX.coerceIn(-maxX,maxX); panY = panY.coerceIn(-maxY,maxY)
        picture.scaleX = zoom; picture.scaleY = zoom; picture.translationX = panX+slide; picture.translationY = panY
    }
    private fun setZoom(target: Float,focusX: Float,focusY: Float,smooth: Boolean) {
        animator?.cancel()
        val initial = zoom; val startX = panX; val startY = panY
        val endPanX = if (target == 1f) 0f else panX+(focusX-picture.width/2f)*(zoom-target)
        val endPanY = if (target == 1f) 0f else panY+(focusY-picture.height/2f)*(zoom-target)
        if (!smooth) { zoom = target; panX = endPanX; panY = endPanY; applyTransform(); return }
        animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 220
            addUpdateListener { val f = it.animatedValue as Float; zoom = initial+(target-initial)*f; panX = startX+(endPanX-startX)*f; panY = startY+(endPanY-startY)*f; applyTransform() }
            start()
        }
    }
    private fun centroid(e: android.view.MotionEvent): Pair<Float,Float> {
        var x = 0f; var y = 0f
        for (i in 0 until e.pointerCount) { x += e.getRawX(i); y += e.getRawY(i) }
        return x/e.pointerCount to y/e.pointerCount
    }
    private fun handle(e: android.view.MotionEvent): Boolean {
        if (!ready()) return true
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { multi = false; three = false }
        if (e.pointerCount >= 2) { if (!multi) { cancelInput(); val cancel = MotionEvent.obtain(e); cancel.action = MotionEvent.ACTION_CANCEL; taps.onTouchEvent(cancel); cancel.recycle() }; multi = true }
        if (e.pointerCount >= 3 && !three) { three = true; val c = centroid(e); threeX = c.first; threeY = c.second; endX = threeX; endY = threeY }
        if (three) {
            val c = centroid(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE) { endX = c.first; endY = c.second }
            if (e.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP && e.pointerCount == 3) {
                if (abs(endX-threeX) > dp(70) && abs(endX-threeX) > abs(endY-threeY)*1.5f) {
                    val direction = if (endX < threeX) 1 else -1
                    if(workspace(direction)) {
                        slideAnimator?.cancel()
                        slideAnimator = ValueAnimator.ofFloat(0f,direction*dp(24),0f).apply { duration = 230; addUpdateListener { slide = it.animatedValue as Float; applyTransform() }; start() }
                    }
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(e)
        if (multi) {
            val c = centroid(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) { lastX = c.first; lastY = c.second }
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && zoom > 1f) { panX += c.first-lastX; panY += c.second-lastY; applyTransform() }
            lastX = c.first; lastY = c.second
            return true
        }
        taps.onTouchEvent(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) cancelInput()
        return true
    }
    private inner class Feedback(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(1).coerceAtLeast(1f) }
        private var cx = 0f; private var cy = 0f; private var progress = 1f
        init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun pulse(x: Float,y: Float) { cx = x; cy = y; ValueAnimator.ofFloat(0f,1f).apply { duration = 280; addUpdateListener { progress = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDraw(canvas: Canvas) { if (progress < 1f) { paint.alpha = ((1f-progress)*220).toInt(); canvas.drawCircle(cx,cy,dp(9)+dp(18)*progress,paint) } }
    }
}
