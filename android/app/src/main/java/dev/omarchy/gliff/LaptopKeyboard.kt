package dev.omarchy.gliff

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/** US key events with previews; globe opens real system text input instead of guessing IME commits. */
class LaptopKeyboard(
    context: Context,private val ready: () -> Boolean,
    private val key: (Int,Boolean,String) -> Unit,private val release: () -> Unit,
    private val systemText: () -> Unit,private val changed: (Boolean) -> Unit
) : LinearLayout(context) {
    private val preferences = context.getSharedPreferences("controls",Context.MODE_PRIVATE)
    private var split = preferences.getBoolean("split_keyboard",false)
    private val modifiers = HashSet<Int>()
    private val modifierViews = HashMap<Int,MutableList<Button>>()
    private val letterViews = HashMap<Button,String>()
    private var fn = false
    private var preview: PopupWindow? = null
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    init { orientation = VERTICAL; isClickable = true; build() }
    fun name() = if (split) "Split" else "Standard"
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
    private fun background() = GradientDrawable().apply { setColor(0xff363638.toInt()); cornerRadius = dp(6).toFloat() }
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
    private fun button(label: String,code: Int): Button = Button(context).apply {
        text = label; textSize = 16f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0,0,0,0)
        setSingleLine(true); setAutoSizeTextTypeUniformWithConfiguration(8,16,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        setTextColor(Color.WHITE); background = background(); contentDescription = when(code) { -1 -> "System text input"; -2 -> "Function keys"; 57 -> "Space"; 14 -> "Backspace"; 28 -> "Enter"; else -> label }
        if (code in listOf(29,42,56,100,125,126)) modifierViews.getOrPut(code) { ArrayList() }.add(this)
        if (label.length == 1) letterViews[this] = label
        setOnClickListener { activate(code) }
        setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (ready()) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val location = IntArray(2); getLocationOnScreen(location)
                    val previewLabel = text
                    val tip = TextView(context).apply { text = previewLabel; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE) }
                    preview?.dismiss(); preview = PopupWindow(tip,maxOf(width,dp(48)),dp(58),false).apply {
                        isTouchable = false; isClippingEnabled = true
                        showAtLocation(rootView,Gravity.TOP or Gravity.LEFT,location[0],maxOf(0,location[1]-dp(62)))
                    }
                }
                MotionEvent.ACTION_UP -> { preview?.dismiss(); preview = null }
                MotionEvent.ACTION_CANCEL -> { preview?.dismiss(); preview = null }
            }
            false
        }
    }
    private fun updateLabels() {
        modifierViews.forEach { (code,views) -> views.forEach { it.alpha = if (code in modifiers) 1f else 0.7f; it.setTextColor(if (code in modifiers) 0xff83b8ff.toInt() else Color.WHITE) } }
        val shifted = mapOf("`" to "~","1" to "!","2" to "@","3" to "#","4" to "$","5" to "%","6" to "^","7" to "&","8" to "*","9" to "(","0" to ")","-" to "_","=" to "+","[" to "{","]" to "}","\\" to "|",";" to ":","'" to "\"","," to "<","." to ">","/" to "?")
        letterViews.forEach { (view,label) -> view.text = if (42 in modifiers) shifted[label] ?: label.uppercase() else label }
    }
    private fun build() {
        removeAllViews(); modifierViews.clear(); letterViews.clear(); modifiers.clear()
        fun row(keys: List<Pair<String,Int>>,cut: Int) {
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER; isClickable = true }
            keys.forEachIndexed { index,(label,code) ->
                if (split && index == cut) line.addView(View(context),LayoutParams(0,1,0.7f))
                line.addView(button(label,code),LayoutParams(0,-1,if(code == 14 || code == 28 || code == 42 || code == 15) 1.35f else 1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
            }
            addView(line,LayoutParams(-1,0,1f))
        }
        if (fn) row((1..10).map { "F$it" to (58+it) }+listOf("F11" to 87,"F12" to 88),6)
        row(listOf("`" to 41)+(1..9).map { it.toString() to (it+1) }+listOf("0" to 11,"-" to 12,"=" to 13,"⌫" to 14),7)
        row(listOf("Tab" to 15,"q" to 16,"w" to 17,"e" to 18,"r" to 19,"t" to 20,"y" to 21,"u" to 22,"i" to 23,"o" to 24,"p" to 25,"[" to 26,"]" to 27,"\\" to 43),6)
        row(listOf("Caps" to 58,"a" to 30,"s" to 31,"d" to 32,"f" to 33,"g" to 34,"h" to 35,"j" to 36,"k" to 37,"l" to 38,";" to 39,"'" to 40,"Enter" to 28),6)
        row(listOf("⇧" to 42,"z" to 44,"x" to 45,"c" to 46,"v" to 47,"b" to 48,"n" to 49,"m" to 50,"," to 51,"." to 52,"/" to 53,"⇧" to 42),6)
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER; isClickable = true }
        for ((label,code) in listOf("fn" to -2,"◎" to -1,"ctrl" to 29,"alt" to 56,"super" to 125,"space" to 57,"super" to 126,"alt" to 100)) {
            bottom.addView(button(label,code),LayoutParams(0,-1,if(code == 57) 2.8f else 1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
        }
        val arrows = LinearLayout(context).apply { orientation = VERTICAL }
        val up = LinearLayout(context).apply { gravity = Gravity.CENTER; addView(View(context),LayoutParams(0,1,1f)); addView(button("↑",103),LayoutParams(0,-1,1f)); addView(View(context),LayoutParams(0,1,1f)) }
        val down = LinearLayout(context).apply { addView(button("←",105),LayoutParams(0,-1,1f)); addView(button("↓",108),LayoutParams(0,-1,1f)); addView(button("→",106),LayoutParams(0,-1,1f)) }
        arrows.addView(up,LayoutParams(-1,0,1f)); arrows.addView(down,LayoutParams(-1,0,1f)); bottom.addView(arrows,LayoutParams(0,-1,2.8f))
        addView(bottom,LayoutParams(-1,0,1f))
        updateLabels()
    }
}
