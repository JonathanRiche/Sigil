package dev.omarchy.gliff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import kotlin.math.abs
import kotlin.math.hypot

/** Local lower-pane controls. Every background consumes touch; no IME composition is synthesized. */
class FlexControls(
    context: Context,
    private val ready: () -> Boolean,
    private val move: (Float,Float,Int) -> Unit,
    private val button: (Int,Boolean,String) -> Unit,
    private val axis: (Int,Double,Boolean) -> Unit,
    private val key: (Int,Boolean,String) -> Unit,
    private val release: () -> Unit,
    private val text: () -> Unit,
    private val layoutChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val source = TextView(context)
    private var manual = false
    private var keyboardSelected = false
    private val preferences = context.getSharedPreferences("controls",Context.MODE_PRIVATE)
    private var splitKeyboard = preferences.getBoolean("split_keyboard",false)
    private val tabs = LinearLayout(context)
    private val pad = Pad(context)
    private val padArea = LinearLayout(context)
    private val keyboard = ScrollView(context)
    private val keyboardRows = LinearLayout(context)
    private val modifiers = HashSet<Int>()
    private val modifierButtons = HashMap<Int,Button>()
    private val letterButtons = HashMap<Button,String>()
    private val drag: ToggleButton
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun action(label: String, run: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; setSingleLine(true); textSize = 12f
        setTextColor(0xffdddddd.toInt()); minimumWidth = 0; minWidth = 0; setPadding(dp(4),0,dp(4),0)
        setOnClickListener { run() }
    }
    init {
        orientation = VERTICAL; isClickable = true; setBackgroundColor(0xff141414.toInt()); setPadding(dp(8),dp(4),dp(8),dp(4))
        source.apply { textSize = 11f; setTextColor(0xff969696.toInt()); gravity = Gravity.CENTER_VERTICAL }
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.addView(source,LayoutParams(0,dp(48),1f))
        tabs.addView(action("Trackpad") { selectKeyboard(false) },LayoutParams(dp(88),dp(48)))
        tabs.addView(action("Keyboard") { selectKeyboard(true) },LayoutParams(dp(88),dp(48)))
        tabs.addView(action("Layout") { showKeyboardSettings(this) }.apply {
            contentDescription = "Keyboard layout settings"
            setOnClickListener { showKeyboardSettings(this) }
        },LayoutParams(dp(64),dp(48)))
        addView(tabs,LayoutParams(-1,dp(48)))
        padArea.orientation = VERTICAL; padArea.isClickable = true
        padArea.addView(pad,LayoutParams(-1,0,1f))
        val clicks = LinearLayout(context)
        clicks.addView(action("Left click") { click(272) },LayoutParams(0,dp(48),1f))
        drag = ToggleButton(context).apply {
            textOn = "Dragging"; textOff = "Drag"; isChecked = false; isAllCaps = false; textSize = 12f
            setOnClickListener {
                if (!ready()) isChecked = false
                button(272,isChecked,"flex-drag")
            }
        }
        clicks.addView(drag,LayoutParams(0,dp(48),1f))
        clicks.addView(action("Right click") { click(273) },LayoutParams(0,dp(48),1f))
        padArea.addView(clicks,LayoutParams(-1,dp(48)))
        addView(padArea,LayoutParams(-1,0,1f))
        keyboardRows.orientation = VERTICAL; keyboardRows.isClickable = true
        keyboard.isFillViewport = true; keyboard.isClickable = true; keyboard.addView(keyboardRows)
        keyboard.visibility = GONE
        addView(keyboard,LayoutParams(-1,0,1f))
        buildKeyboard()
    }
    fun setSource(manual: Boolean) {
        this.manual = manual
        source.text = (if (manual) "Manual Flex" else "Tabletop") + (if (keyboardSelected) " · US" else "")
    }
    fun keyboardLayoutName() = if (splitKeyboard) "Split" else "Standard"
    fun showKeyboardSettings(anchor: View) {
        android.widget.PopupMenu(context,anchor).apply {
            for ((label,split) in listOf("Standard keyboard" to false,"Split keyboard" to true)) {
                menu.add(label).apply {
                    isCheckable = true; isChecked = splitKeyboard == split
                    setOnMenuItemClickListener {
                        if (splitKeyboard != split) {
                            release(); splitKeyboard = split
                            preferences.edit().putBoolean("split_keyboard",split).apply()
                            buildKeyboard(); layoutChanged(split)
                        }
                        true
                    }
                }
            }
            show()
        }
    }
    fun resetInputs() {
        modifiers.clear(); updateModifiers(); drag.isChecked = false; pad.reset()
    }
    private fun selectKeyboard(show: Boolean) {
        release(); keyboardSelected = show; setSource(manual); padArea.visibility = if (show) GONE else VISIBLE; keyboard.visibility = if (show) VISIBLE else GONE
    }
    private fun click(code: Int) { if (ready()) { button(code,true,"flex-click"); button(code,false,"flex-click") } }
    private fun updateModifiers() {
        modifierButtons.forEach { (code,view) -> view.isSelected = code in modifiers; view.alpha = if (code in modifiers) 1f else 0.65f }
        letterButtons.forEach { (view,label) -> view.text = if (42 in modifiers) label.uppercase() else label }
    }
    private fun press(code: Int) {
        if (!ready()) return
        key(code,true,"flex-key:$code"); key(code,false,"flex-key:$code")
        if (42 in modifiers) { modifiers.remove(42); key(42,false,"flex-mod:42"); updateModifiers() }
    }
    private fun modifier(code: Int) {
        if (!ready()) return
        val down = if (code in modifiers) { modifiers.remove(code); false } else { modifiers.add(code); true }
        key(code,down,"flex-mod:$code"); updateModifiers()
    }
    private fun buildKeyboard() {
        keyboardRows.removeAllViews(); modifierButtons.clear(); letterButtons.clear(); modifiers.clear()
        var rowIndex = 0
        fun row(vararg keys: Pair<String,Int>) {
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER; isClickable = true }
            val splitAt = when (rowIndex++) { 0 -> 7; 1 -> 6; 2 -> 5; 3 -> 6; else -> 4 }
            keys.forEachIndexed { index,(label,code) ->
                if (splitKeyboard && index == splitAt) line.addView(View(context),LayoutParams(dp(32),1,1f))
                val view = action(label) { if (code in listOf(29,42,56,125)) modifier(code) else if (code == -1) text() else press(code) }
                view.contentDescription = when (code) { 57 -> "Space"; 14 -> "Backspace"; 28 -> "Enter"; -1 -> "Offer clipboard text"; else -> label }
                if (code in listOf(29,42,56,125)) modifierButtons[code] = view
                if (label.length == 1 && label[0].isLetter()) letterButtons[view] = label
                line.addView(view,LayoutParams(dp(if (code == 57) 144 else 48),dp(48)))
            }
            val scroll = HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(line) }
            keyboardRows.addView(scroll,LayoutParams(-1,dp(48)))
        }
        row("Esc" to 1,"1" to 2,"2" to 3,"3" to 4,"4" to 5,"5" to 6,"6" to 7,"7" to 8,"8" to 9,"9" to 10,"0" to 11,"-" to 12,"=" to 13,"⌫" to 14)
        row("Tab" to 15,"q" to 16,"w" to 17,"e" to 18,"r" to 19,"t" to 20,"y" to 21,"u" to 22,"i" to 23,"o" to 24,"p" to 25,"[" to 26,"]" to 27)
        row("a" to 30,"s" to 31,"d" to 32,"f" to 33,"g" to 34,"h" to 35,"j" to 36,"k" to 37,"l" to 38,";" to 39,"'" to 40,"Enter" to 28)
        row("Shift" to 42,"z" to 44,"x" to 45,"c" to 46,"v" to 47,"b" to 48,"n" to 49,"m" to 50,"," to 51,"." to 52,"/" to 53,"↑" to 103)
        row("Ctrl" to 29,"Alt" to 56,"Super" to 125,"Space" to 57,"←" to 105,"↓" to 108,"→" to 106,"Text" to -1)
        updateModifiers()
    }
    private inner class Pad(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff777777.toInt(); textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var lastX = 0f; private var lastY = 0f; private var distance = 0f
        private var down = 0L; private var multi = false; private var scrolling = false
        init { isClickable = true; contentDescription = "Remote trackpad: move, tap to click, two fingers to scroll, long tap for right click"; setBackgroundColor(0xff1b1b1b.toInt()) }
        fun reset() { multi = false; scrolling = false; distance = 0f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawText("Trackpad",width/2f,height/2f,paint)
            canvas.drawText("Tap to click · two fingers to scroll",width/2f,height/2f+dp(23),paint)
        }
        private fun center(e: MotionEvent): Pair<Float,Float> {
            var x = 0f; var y = 0f
            for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
            return x/e.pointerCount to y/e.pointerCount
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!ready()) { if (e.actionMasked == MotionEvent.ACTION_CANCEL) release(); return true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; distance = 0f; down = e.eventTime; multi = false }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true; scrolling = true
                    if (drag.isChecked) { drag.isChecked = false; button(272,false,"flex-drag") }
                    val c = center(e); lastX = c.first; lastY = c.second
                }
                MotionEvent.ACTION_MOVE -> {
                    val c = center(e); val dx = c.first-lastX; val dy = c.second-lastY
                    distance += hypot(dx.toDouble(),dy.toDouble()).toFloat()
                    if (e.pointerCount >= 2) {
                        if (abs(dy) > 0) axis(0,-dy.toDouble(),false)
                        if (abs(dx) > 0) axis(1,-dx.toDouble(),false)
                    } else if (!multi) move(dx,dy,width)
                    lastX = c.first; lastY = c.second
                }
                MotionEvent.ACTION_POINTER_UP -> { axis(0,0.0,true); axis(1,0.0,true); scrolling = false }
                MotionEvent.ACTION_UP -> {
                    if (scrolling) { axis(0,0.0,true); axis(1,0.0,true) }
                    if (!multi && distance <= slop && !drag.isChecked) click(if (e.eventTime-down >= 500) 273 else 272)
                    reset()
                }
                MotionEvent.ACTION_CANCEL -> { release(); reset() }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); click(272); return true }
    }
}
