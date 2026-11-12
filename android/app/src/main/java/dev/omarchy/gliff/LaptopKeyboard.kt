package dev.omarchy.gliff

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/** US key events with previews; globe opens real system text input instead of guessing IME commits. */
class LaptopKeyboard(
    context: Context,private val ready: () -> Boolean,
    private val key: (Int,Boolean,String) -> Unit,private val release: () -> Unit,
    private val systemText: () -> Unit,private val changed: (Boolean) -> Unit,
    private var theme: DeckTheme
) : LinearLayout(context) {
    private val preferences = context.getSharedPreferences("controls",Context.MODE_PRIVATE)
    private var split = preferences.getBoolean("split_keyboard",false)
    private val modifiers = HashSet<Int>()
    private val modifierViews = HashMap<Int,MutableList<KeyView>>()
    private val letterViews = HashMap<KeyView,String>()
    private var fn = false
    private var preview: PopupWindow? = null
    private val gap get() = (3.5f*resources.displayMetrics.density).toInt()
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    init { orientation = VERTICAL; isClickable = true; build() }
    fun name() = if (split) "Split" else "Standard"
    fun setTheme(next: DeckTheme) { release(); theme = next; build() }
    fun resetInputs() { preview?.dismiss(); preview = null; modifiers.clear(); updateLabels() }
    fun showSettings(anchor: View) {
        PopupMenu(context,anchor).apply {
            for ((title,value) in listOf("Standard keyboard" to false,"Split keyboard" to true)) menu.add(title).apply {
                isCheckable = true; isChecked = split == value
                setOnMenuItemClickListener {
                    if (split != value) { release(); split = value; preferences.edit().putBoolean("split_keyboard",split).apply(); build(); changed(split) }
                    true
                }
            }
            show()
        }
    }
    private fun activate(code: Int) {
        if (!ready()) return
        when (code) {
            -2 -> { release(); fn = !fn; build() }
            -1 -> systemText()
            29,42,56,100,125,126 -> {
                val down = if (code in modifiers) { modifiers.remove(code); false } else { modifiers.add(code); true }
                key(code,down,"laptop-mod:$code"); updateLabels()
            }
            else -> {
                key(code,true,"laptop-key:$code"); key(code,false,"laptop-key:$code")
                if (42 in modifiers) { modifiers.remove(42); key(42,false,"laptop-mod:42"); updateLabels() }
            }
        }
    }
    private fun describe(label: String,code: Int) = when(code) {
        -1 -> "System text input"; -2 -> "Function keys"; 57 -> "Space"; 14 -> "Backspace"; 28 -> "Enter"; 42 -> "Shift"
        103 -> "Up"; 108 -> "Down"; 105 -> "Left"; 106 -> "Right"; else -> label
    }
    private fun button(label: String,code: Int,glyph: String? = null): KeyView = KeyView(context,theme,KeyView.KEY).apply {
        val character = label.length == 1 && glyph == null
        if (glyph != null) icon = DeckIcon(glyph,resources.displayMetrics.density) else this.label = label
        textSp = if (character) 18f else 11.5f; muted = !character
        contentDescription = describe(label,code)
        if (code == -2) lit = fn
        if (code in listOf(29,42,56,100,125,126)) modifierViews.getOrPut(code) { ArrayList() }.add(this)
        if (character) letterViews[this] = label
        setOnClickListener { activate(code) }
        setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (ready()) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (character) {
                        val location = IntArray(2); getLocationOnScreen(location)
                        val tip = TextView(context).apply {
                            text = (view as KeyView).label; textSize = 26f; gravity = Gravity.CENTER; setTextColor(theme.label)
                            background = GradientDrawable().apply { setColor(theme.keyTop); cornerRadius = dp(10).toFloat(); setStroke(maxOf(1,dp(1)/2),theme.keyShine) }
                            elevation = dp(6).toFloat()
                        }
                        preview?.dismiss(); preview = PopupWindow(tip,maxOf(width,dp(48)),dp(56),false).apply {
                            isTouchable = false; isClippingEnabled = true; elevation = dp(8).toFloat()
                            showAtLocation(rootView,Gravity.TOP or Gravity.LEFT,location[0]-(maxOf(width,dp(48))-view.width)/2,maxOf(0,location[1]-dp(60)))
                        }
                    }
                }
                MotionEvent.ACTION_UP -> { preview?.dismiss(); preview = null }
                MotionEvent.ACTION_CANCEL -> { preview?.dismiss(); preview = null }
            }
            false
        }
    }
    private fun updateLabels() {
        modifierViews.forEach { (code,views) -> views.forEach { it.lit = code in modifiers } }
        val shifted = mapOf("`" to "~","1" to "!","2" to "@","3" to "#","4" to "$","5" to "%","6" to "^","7" to "&","8" to "*","9" to "(","0" to ")","-" to "_","=" to "+","[" to "{","]" to "}","\\" to "|",";" to ":","'" to "\"","," to "<","." to ">","/" to "?")
        letterViews.forEach { (view,label) -> view.label = if (42 in modifiers) shifted[label] ?: label.uppercase() else label }
    }
    private data class Cap(val label: String,val code: Int,val weight: Float = 1f,val glyph: String? = null)
    private fun caps(vararg pairs: Pair<String,Int>) = pairs.map { Cap(it.first,it.second) }
    private fun build() {
        preview?.dismiss(); preview = null
        removeAllViews(); modifierViews.clear(); letterViews.clear(); modifiers.clear()
        fun margins(weight: Float) = LayoutParams(0,-1,weight).apply { setMargins(gap,gap,gap,gap) }
        fun row(keys: List<Cap>,cut: Int,height: Float = 1f): LinearLayout {
            val line = LinearLayout(context).apply { isClickable = true }
            keys.forEachIndexed { index,cap ->
                if (split && index == cut) line.addView(View(context),LayoutParams(0,1,0.7f))
                line.addView(button(cap.label,cap.code,cap.glyph),margins(cap.weight))
            }
            addView(line,LayoutParams(-1,0,height)); return line
        }
        if (fn) row((1..10).map { Cap("F$it",58+it) }+listOf(Cap("F11",87),Cap("F12",88)),6,0.8f)
        row(listOf(Cap("`",41))+(1..9).map { Cap(it.toString(),it+1) }+listOf(Cap("0",11),Cap("-",12),Cap("=",13),Cap("⌫",14,1.5f,"backspace")),7)
        row(listOf(Cap("tab",15,1.5f))+caps("q" to 16,"w" to 17,"e" to 18,"r" to 19,"t" to 20,"y" to 21,"u" to 22,"i" to 23,"o" to 24,"p" to 25,"[" to 26,"]" to 27,"\\" to 43),6)
        row(listOf(Cap("caps",58,1.8f))+caps("a" to 30,"s" to 31,"d" to 32,"f" to 33,"g" to 34,"h" to 35,"j" to 36,"k" to 37,"l" to 38,";" to 39,"'" to 40)+Cap("return",28,1.8f,"return"),6)
        row(listOf(Cap("shift",42,2.3f,"shift"))+caps("z" to 44,"x" to 45,"c" to 46,"v" to 47,"b" to 48,"n" to 49,"m" to 50,"," to 51,"." to 52,"/" to 53)+Cap("shift",42,2.3f,"shift"),6)
        val bottom = row(listOf(Cap("fn",-2),Cap("text",-1,1f,"globe"),Cap("ctrl",29),Cap("alt",56),Cap("super",125,1.2f),Cap("",57,5f),Cap("super",126,1.2f),Cap("alt",100)),6)
        // Inverted T: half-height left and right keys flank a stacked up and down pair.
        fun half(view: View,top: Boolean) = LinearLayout(context).apply {
            orientation = VERTICAL
            val params = LayoutParams(-1,0,1f).apply { setMargins(gap,if (top) gap else gap/2,gap,if (top) gap/2 else gap) }
            if (top) { addView(view,params); addView(View(context),LayoutParams(-1,0,1f)) } else { addView(View(context),LayoutParams(-1,0,1f)); addView(view,params) }
        }
        val middle = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(button("↑",103,"up"),LayoutParams(-1,0,1f).apply { setMargins(gap,gap,gap,gap/2) })
            addView(button("↓",108,"down"),LayoutParams(-1,0,1f).apply { setMargins(gap,gap/2,gap,gap) })
        }
        bottom.addView(half(button("←",105,"left"),false),LayoutParams(0,-1,1f))
        bottom.addView(middle,LayoutParams(0,-1,1f))
        bottom.addView(half(button("→",106,"right"),false),LayoutParams(0,-1,1f))
        updateLabels()
    }
}
