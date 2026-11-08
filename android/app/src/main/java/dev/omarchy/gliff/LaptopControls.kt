package dev.omarchy.gliff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.hypot

/** Stationary lower toolbar with real remote actions and local trackpad/keyboard/dictation modes. */
class LaptopControls(
    context: Context,private val ready: () -> Boolean,
    private val move: (Float,Float,Int) -> Unit,
    private val button: (Int,Boolean,String) -> Unit,
    private val axis: (Int,Double,Boolean) -> Unit,
    private val key: (Int,Boolean,String) -> Unit,
    private val release: () -> Unit,private val text: () -> Unit,
    private val layoutChanged: (Boolean) -> Unit,
    private val remote: (Int,String) -> Boolean,
    private val refresh: () -> Unit,private val displays: () -> Unit,
    private val connection: () -> Unit,
    private val startVoice: (String) -> Unit,private val cancelVoice: () -> Unit,
    private val viewport: (Float,Float,Float) -> Unit,
    private val notice: (String) -> Unit
) : LinearLayout(context) {
    private val toolbar = LinearLayout(context)
    private val body = FrameLayout(context)
    private val padArea = FrameLayout(context)
    private val keyboardArea = LinearLayout(context)
    private val dock = HorizontalScrollView(context)
    private val dockApps = LinearLayout(context)
    private val keyboardDock = FrameLayout(context)
    private val shortcuts = LinearLayout(context)
    private val keyboard: LaptopKeyboard
    private val battery = TextView(context)
    private val source = TextView(context)
    private val keyboardTab: Button
    private val trackpadTab: Button
    private val bottomMode: Button
    private val floating: Button
    private val pad = Pad(context)
    private val dictation = FrameLayout(context)
    private val transcript = TextView(context)
    private val speechStatus = TextView(context)
    private val insert: Button
    private var textDraft = ""
    private val textHistory = ArrayDeque<String>()
    private var listening = false
    private var keyboardSelected = false
    private var tabletop = false
    private var manual = false
    private var desktop: RemoteDesktopState? = null
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun shape(color: Int,radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun action(label: String,description: String = label,run: () -> Unit) = Button(context).apply {
        text = label; contentDescription = description; isAllCaps = false; setSingleLine(true); textSize = 12f
        minWidth = 0; minimumWidth = 0; setPadding(dp(8),0,dp(8),0); setTextColor(0xffdddddd.toInt()); background = shape(0xff272729.toInt(),8)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); run() }
    }
    init {
        orientation = VERTICAL; isClickable = true; setBackgroundColor(0xff19191b.toInt())
        toolbar.gravity = Gravity.CENTER_VERTICAL; toolbar.setPadding(dp(6),0,dp(6),0); toolbar.setBackgroundColor(0xff202022.toInt())
        toolbar.addView(action("⚙","Control settings") { settings() },LayoutParams(dp(48),dp(48)))
        val segment = LinearLayout(context).apply { setPadding(dp(3),dp(3),dp(3),dp(3)); background = shape(0xff141416.toInt(),24) }
        keyboardTab = action("Keyboard") { selectKeyboard(true) }; trackpadTab = action("Trackpad") { selectKeyboard(false) }
        segment.addView(keyboardTab,LayoutParams(dp(92),dp(42))); segment.addView(trackpadTab,LayoutParams(dp(92),dp(42)))
        toolbar.addView(segment,LayoutParams(dp(190),dp(48)))
        toolbar.addView(action("Vol","Remote volume") { volume() },LayoutParams(dp(56),dp(48)))
        toolbar.addView(action("Display","Switch remote display") { displays() },LayoutParams(dp(72),dp(48)))
        battery.apply { text = "—"; contentDescription = "Remote battery unavailable"; setTextColor(0xffcccccc.toInt()); gravity = Gravity.CENTER; textSize = 12f }
        toolbar.addView(battery,LayoutParams(dp(48),dp(48)))
        addView(HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(toolbar) },LayoutParams(-1,dp(48)))
        source.apply { textSize = 10f; setTextColor(0xffaaaaaa.toInt()); setPadding(dp(10),0,0,0) }
        body.isClickable = true; addView(body,LayoutParams(-1,0,1f))
        padArea.isClickable = true; padArea.addView(pad,FrameLayout.LayoutParams(-1,-1))
        body.addView(padArea,FrameLayout.LayoutParams(-1,-1))
        keyboardArea.orientation = VERTICAL; keyboardArea.isClickable = true; keyboardArea.visibility = GONE
        keyboardArea.addView(keyboardDock,LayoutParams(-1,dp(48)))
        keyboardArea.addView(HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(shortcuts) },LayoutParams(-1,dp(48)))
        keyboard = LaptopKeyboard(context,ready,key,release,text,layoutChanged)
        keyboardArea.addView(keyboard,LayoutParams(-1,0,1f))
        body.addView(keyboardArea,FrameLayout.LayoutParams(-1,-1))
        dock.isClickable = true; dock.isHorizontalScrollBarEnabled = false; dock.setPadding(dp(8),dp(2),dp(8),dp(2)); dock.background = shape(0xe6262628.toInt(),12); dock.addView(dockApps)
        padArea.addView(dock,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP or Gravity.LEFT))
        val bottom = FrameLayout(context).apply { isClickable = true }
        bottomMode = action("Keyboard","Switch to keyboard") { selectKeyboard(!keyboardSelected) }.apply { background = shape(0xff303034.toInt(),24) }
        bottom.addView(bottomMode,FrameLayout.LayoutParams(dp(144),dp(40),Gravity.CENTER))
        floating = action("●","Start dictation") { if (keyboardSelected && (tabletop || manual)) selectKeyboard(false) else openDictation() }.apply { background = shape(0xff3d87ff.toInt(),28); setTextColor(Color.WHITE); textSize = 22f }
        bottom.addView(floating,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })
        addView(bottom,LayoutParams(-1,dp(48)))
        dictation.isClickable = true; dictation.setBackgroundColor(0xcc111113.toInt()); dictation.visibility = GONE
        val voicePanel = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12)) }
        speechStatus.apply { text = "Dictation"; textSize = 13f; setTextColor(Color.WHITE) }; voicePanel.addView(speechStatus,LayoutParams(-1,dp(28)))
        transcript.apply { textSize = 20f; setTextColor(Color.BLACK); setPadding(dp(16),dp(16),dp(16),dp(16)); background = shape(Color.WHITE,16); minHeight = dp(72) }
        var swipeX = 0f
        transcript.setOnTouchListener { _,e -> when(e.actionMasked) { MotionEvent.ACTION_DOWN -> { swipeX = e.x; true }; MotionEvent.ACTION_UP -> { if(e.x-swipeX < -dp(60)) deleteWord(); true }; else -> true } }
        voicePanel.addView(ScrollView(context).apply { addView(transcript) },LayoutParams(-1,0,1f))
        val voiceButtons = LinearLayout(context)
        voiceButtons.addView(action("Undo") { cancelVoice(); if (textHistory.isNotEmpty()) { textDraft = textHistory.removeLast(); transcript.text = textDraft } },LayoutParams(0,dp(48),1f))
        voiceButtons.addView(action("Keyboard","Cancel dictation and show keyboard") { closeDictation(); selectKeyboard(true) },LayoutParams(0,dp(48),1f))
        insert = action("Insert","Insert dictated text into remote app") {
            if (textDraft.isNotBlank() && ready()) {
                cancelVoice(); listening = false
                if (remote(15,textDraft)) { insert.isEnabled = false; speechStatus.text = "Inserting…" }
            }
        }.apply { background = shape(0xff357dff.toInt(),24); setTextColor(Color.WHITE) }
        voiceButtons.addView(insert,LayoutParams(0,dp(48),1f))
        voicePanel.addView(voiceButtons,LayoutParams(-1,dp(48)))
        dictation.addView(voicePanel,FrameLayout.LayoutParams(-1,-1)); body.addView(dictation,FrameLayout.LayoutParams(-1,-1))
        rebuildShortcuts(); updateMode(); updateDock()
    }
    fun setPosture(tabletop: Boolean,manual: Boolean) { val changed = this.tabletop != tabletop || this.manual != manual; this.tabletop = tabletop; this.manual = manual; if(changed) rebuildShortcuts(); updateMode() }
    fun setSource(manual: Boolean) = setPosture(!manual,manual)
    fun keyboardLayoutName() = keyboard.name()
    fun showKeyboardSettings(anchor: View) = keyboard.showSettings(anchor)
    fun resetInputs() { keyboard.resetInputs(); pad.reset() }
    fun applyRemoteState(state: RemoteDesktopState) {
        desktop = state; battery.text = state.battery ?: "—"; battery.contentDescription = state.battery?.let { "Remote battery $it" } ?: "Remote battery unavailable"
        updateDock(); insert.isEnabled = "text" in state.capabilities
    }
    fun clearRemoteState() { desktop = null; updateDock(); battery.text = "—"; closeDictation() }
    fun onVoiceResult(value: String,recording: Boolean,error: String?) { textDraft = value; transcript.text = value; listening = recording; speechStatus.text = error ?: if(recording) "Listening…" else "Dictation"; insert.isEnabled = value.isNotBlank() && desktop?.capabilities?.contains("text") == true }
    fun insertionFinished(success: Boolean,error: String?) { insert.isEnabled = true; if(success) { textDraft = ""; transcript.text = ""; closeDictation() } else speechStatus.text = error ?: "Text was not inserted" }
    fun hasDictation() = dictation.visibility == VISIBLE
    fun closeDictation() { cancelVoice(); listening = false; dictation.visibility = GONE; textDraft = ""; transcript.text = ""; textHistory.clear() }
    private fun openDictation() {
        if (!ready()) { notice("Connect before dictating"); return }
        if(listening) { cancelVoice(); listening = false; speechStatus.text = "Dictation"; return }
        release(); textHistory.clear(); textHistory.addLast(textDraft); transcript.text = textDraft; dictation.visibility = VISIBLE; insert.isEnabled = textDraft.isNotBlank() && desktop?.capabilities?.contains("text") == true; speechStatus.text = "Dictation"; startVoice(textDraft)
    }
    private fun deleteWord() { cancelVoice(); listening = false; textHistory.addLast(textDraft); if(textHistory.size > 20) textHistory.removeFirst(); textDraft = textDraft.trimEnd().replace(Regex("\\S+\\s*$"),"").trimEnd(); transcript.text = textDraft; speechStatus.text = "Dictation" }
    private fun selectKeyboard(show: Boolean) {
        if(keyboardSelected == show) return
        release(); closeDictation(); keyboardSelected = show
        val leaving = if(show) padArea else keyboardArea; val entering = if(show) keyboardArea else padArea
        leaving.animate().cancel(); entering.animate().cancel()
        // Commit the actual mode independently of animator completion/cancellation.
        leaving.visibility = GONE; leaving.alpha = 1f
        entering.visibility = VISIBLE; entering.alpha = 1f
        (dock.parent as? android.view.ViewGroup)?.removeView(dock)
        if(show) keyboardDock.addView(dock,FrameLayout.LayoutParams(-1,-1)) else padArea.addView(dock,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP or Gravity.LEFT))
        body.requestLayout()
        entering.translationY = dp(8).toFloat()
        entering.animate().translationY(0f).setDuration(140).start()
        updateMode(); refresh()
    }
    private fun updateMode() {
        keyboardTab.setTextColor(if(keyboardSelected) Color.WHITE else 0xff999999.toInt()); trackpadTab.setTextColor(if(keyboardSelected) 0xff999999.toInt() else Color.WHITE)
        keyboardTab.background = shape(if(keyboardSelected) 0xff45454a.toInt() else Color.TRANSPARENT,20); trackpadTab.background = shape(if(keyboardSelected) Color.TRANSPARENT else 0xff45454a.toInt(),20)
        bottomMode.text = if(keyboardSelected) "Trackpad" else "Keyboard"; bottomMode.contentDescription = "Switch to ${if(keyboardSelected) "trackpad" else "keyboard"}"
        floating.visibility = if(keyboardSelected || tabletop || manual) VISIBLE else GONE
        val flex = tabletop || manual
        floating.text = if(keyboardSelected && flex) "▱" else "●"; floating.contentDescription = if(keyboardSelected && flex) "Switch to trackpad" else "Start dictation"
        floating.background = shape(if(flex) 0xff3d87ff.toInt() else 0xffe34b55.toInt(),28)
    }
    private fun settings() {
        PopupMenu(context,toolbar.getChildAt(0)).apply {
            menu.add("Keyboard layout").setOnMenuItemClickListener { keyboard.showSettings(toolbar.getChildAt(0)); true }
            menu.add("Connection").setOnMenuItemClickListener { connection(); true }
            menu.add("Refresh open apps").setOnMenuItemClickListener { refresh(); true }
            show()
        }
    }
    private fun invoke(kind: Int,capability: String? = null) {
        if (!ready()) { notice("Connect first"); return }
        if (capability != null && desktop?.capabilities?.contains(capability) != true) { notice("${capability.replaceFirstChar { it.uppercase() }} is unavailable on this host"); return }
        remote(kind,"")
    }
    private fun volume() { PopupMenu(context,toolbar.getChildAt(2)).apply { for((label,kind) in listOf("Volume up" to 6,"Volume down" to 7,"Mute" to 8)) menu.add(label).setOnMenuItemClickListener { invoke(kind,"volume"); true }; show() } }
    private fun chord(code: Int) { if(ready()) { key(29,true,"laptop-shortcut"); key(code,true,"laptop-shortcut"); key(code,false,"laptop-shortcut"); key(29,false,"laptop-shortcut") } }
    private fun shortcut(label: String,description: String = label,run: () -> Unit) { shortcuts.addView(action(label,description,run),LayoutParams(dp(48),dp(48))) }
    private fun rebuildShortcuts() {
        shortcuts.removeAllViews(); shortcut("esc") { if(ready()) { key(1,true,"laptop-esc"); key(1,false,"laptop-esc") } }
        if(tabletop || manual) {
            shortcut("●","Start dictation") { openDictation() }; (shortcuts.getChildAt(shortcuts.childCount-1) as? Button)?.setTextColor(0xffff6363.toInt()); shortcut("↶","Undo") { chord(44) }; shortcut("Copy") { chord(46) }; shortcut("Paste") { chord(47) }; shortcut("Apps","Remote launcher") { invoke(14,"launcher") }
        } else {
            shortcut("Side","Open app list") { overview() }; shortcut("▦","Workspace overview") { overview() }; shortcut("☀−","Brightness down") { invoke(13,"brightness") }; shortcut("☀+","Brightness up") { invoke(12,"brightness") }
        }
        shortcut("⏮","Previous media") { invoke(11,"media") }; shortcut("▶","Play or pause media") { invoke(9,"media") }; shortcut("⏭","Next media") { invoke(10,"media") }
        shortcut("Mute") { invoke(8,"volume") }; shortcut("Vol−","Volume down") { invoke(7,"volume") }; shortcut("Vol+","Volume up") { invoke(6,"volume") }
        if(!tabletop && !manual) { shortcut("Search","Remote search") { invoke(14,"launcher") }; shortcut("●","Start dictation") { openDictation() }; (shortcuts.getChildAt(shortcuts.childCount-1) as? Button)?.setTextColor(0xffff6363.toInt()) }
    }
    private fun updateDock() {
        dockApps.removeAllViews()
        val apps = desktop?.apps.orEmpty()
        if(apps.isEmpty()) dockApps.addView(action("Open apps") { refresh(); overview() },LayoutParams(dp(100),dp(44)))
        apps.forEach { app ->
            // The host supplies window class/title, not icon artwork. Keep the monogram honest.
            val name = app.app.ifBlank { "Window" }
            val shortName = name.substringAfterLast('.').ifBlank { name }
            val words = shortName.split(Regex("[\\s_-]+")).filter { it.isNotBlank() }
            val initials = if(words.size > 1) words.take(2).joinToString("") { it.take(1) } else shortName.take(2)
            val description = if(app.title.isBlank()) name else "$name: ${app.title}"
            dockApps.addView(action(initials.uppercase(),description) { if(ready()) remote(2,app.address) }.apply {
                textSize = 15f; setPadding(0,0,0,0); tooltipText = description
                background = shape(0xff3b3b40.toInt(),10)
            },LayoutParams(dp(44),dp(44)).apply { marginEnd = dp(6) })
        }
    }
    private fun overview() {
        refresh(); val snapshot = desktop
        if(snapshot == null) { notice("Waiting for open apps"); return }
        val options = snapshot.workspaces.map { "Workspace ${it.name.ifBlank { it.id.toString() }}" }+snapshot.apps.map { it.title.ifBlank { it.app } }
        if(options.isEmpty()) { notice("No open windows reported"); return }
        android.app.AlertDialog.Builder(context).setTitle("Remote workspaces and apps").setItems(options.toTypedArray()) { _,index ->
            if(index < snapshot.workspaces.size) remote(5,snapshot.workspaces[index].id.toString()) else remote(2,snapshot.apps[index-snapshot.workspaces.size].address)
        }.setNegativeButton("Close",null).show()
    }
    private inner class Pad(context: Context) : View(context) {
        private var x0 = 0f; private var y0 = 0f; private var lastX = 0f; private var lastY = 0f; private var distance = 0f
        private var down = 0L; private var fingers = 1; private var hold = false; private var active = false; private var pinching = false
        private var initialSpan = 0f; private var firstY = 0f; private var lastThreeY = 0f
        private var cx = 0f; private var cy = 0f; private var ring = 1f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val longPress = Runnable { if(active && ready() && fingers == 1 && distance < slop) { hold = true; button(272,true,"laptop-hold"); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }
        private val scale = ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(d: ScaleGestureDetector): Boolean { pinching = true; axis(0,0.0,true); axis(1,0.0,true); viewport(d.scaleFactor,0f,0f); return true } })
        init { isClickable = true; setBackgroundColor(0xff29292c.toInt()); contentDescription = "Remote trackpad: tap to click, hold and drag, two fingers scroll, pinch to zoom, three fingers up for open apps" }
        fun reset() { removeCallbacks(longPress); active = false; hold = false; pinching = false; fingers = 1; distance = 0f }
        private fun center(e: MotionEvent): Pair<Float,Float> { var x = 0f; var y = 0f; for(i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }; return x/e.pointerCount to y/e.pointerCount }
        private fun pulse(x: Float,y: Float) { cx = x; cy = y; ValueAnimator.ofFloat(0f,1f).apply { duration = 280; addUpdateListener { ring = it.animatedValue as Float; invalidate() }; start() }; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        override fun onDraw(canvas: Canvas) { if(ring < 1f) { paint.alpha = ((1f-ring)*220).toInt(); canvas.drawCircle(cx,cy,dp(10)+dp(20)*ring,paint) } }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(!ready()) { if(e.actionMasked == MotionEvent.ACTION_CANCEL) release(); return true }
            if(!active && e.actionMasked != MotionEvent.ACTION_DOWN) return true
            scale.onTouchEvent(e)
            val c = center(e)
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { active = true; fingers = 1; pinching = false; hold = false; distance = 0f; down = e.eventTime; x0 = c.first; y0 = c.second; lastX = c.first; lastY = c.second; postDelayed(longPress,500) }
                MotionEvent.ACTION_POINTER_DOWN -> { fingers = maxOf(fingers,e.pointerCount); removeCallbacks(longPress); if(hold) { button(272,false,"laptop-hold"); hold = false }; lastX = c.first; lastY = c.second; if(e.pointerCount == 3) { firstY = c.second; lastThreeY = c.second }; if(e.pointerCount == 2) initialSpan = hypot((e.getX(0)-e.getX(1)).toDouble(),(e.getY(0)-e.getY(1)).toDouble()).toFloat() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = c.first-lastX; val dy = c.second-lastY; distance += hypot(dx.toDouble(),dy.toDouble()).toFloat()
                    if(distance > slop) removeCallbacks(longPress)
                    if(e.pointerCount >= 3) lastThreeY = c.second
                    else if(pinching) viewport(1f,dx,dy)
                    else if(e.pointerCount == 2) { if(abs(dy) > 0) axis(0,-dy.toDouble(),false); if(abs(dx) > 0) axis(1,-dx.toDouble(),false) }
                    else if(fingers == 1) move(dx,dy,width)
                    lastX = c.first; lastY = c.second
                }
                MotionEvent.ACTION_POINTER_UP -> { axis(0,0.0,true); axis(1,0.0,true); if(e.pointerCount == 3 && firstY-lastThreeY > dp(60)) overview() }
                MotionEvent.ACTION_UP -> { removeCallbacks(longPress); if(hold) button(272,false,"laptop-hold") else if(distance <= slop && !pinching) { val code = if(fingers == 2) 273 else 272; if(fingers <= 2) { button(code,true,"laptop-tap"); button(code,false,"laptop-tap"); pulse(e.x,e.y) } }; reset() }
                MotionEvent.ACTION_CANCEL -> { release(); reset() }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); if(ready()) { button(272,true,"laptop-accessibility"); button(272,false,"laptop-accessibility") }; return true }
    }
}
