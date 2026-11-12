package dev.omarchy.gliff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.hypot

/** Lower control deck: side rails with remote actions around a keyboard or trackpad, plus dictation. */
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
    private val notice: (String) -> Unit,
    private val disconnect: () -> Unit,private val fullscreen: () -> Unit,
    private val appearance: () -> Unit
) : LinearLayout(context) {
    private val preferences = context.getSharedPreferences("controls",Context.MODE_PRIVATE)
    private var theme = DeckTheme.of(context)
    private val leading = LinearLayout(context)
    private val trailing = LinearLayout(context)
    private val strip = LinearLayout(context)
    private val center = LinearLayout(context)
    private val body = FrameLayout(context)
    private val padArea = FrameLayout(context)
    private val keyboardArea = LinearLayout(context)
    private val dock = HorizontalScrollView(context)
    private val dockApps = LinearLayout(context)
    private val shortcuts = LinearLayout(context)
    private val keyboard: LaptopKeyboard
    private val battery = TextView(context)
    private val pad = Pad(context)
    private val dictation = FrameLayout(context)
    private val voicePanel = LinearLayout(context)
    private val voiceButtons = LinearLayout(context)
    private val transcript = TextView(context)
    private val speechStatus = TextView(context)
    private var insert: KeyView? = null
    private var settingsKey: View = this
    private var volumeKey: View = this
    private var modeKey: KeyView? = null
    private var micKey: KeyView? = null
    private var textDraft = ""
    private val textHistory = ArrayDeque<String>()
    private var listening = false
    private var keyboardSelected = false
    private var tabletop = false
    private var manual = false
    private var wide: Boolean? = null
    private var insertEnabled = false
    private var desktop: RemoteDesktopState? = null
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun shape(color: Int,radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun control(kind: Int,glyph: String?,description: String,label: String = "",run: () -> Unit) = KeyView(context,theme,kind).apply {
        if (glyph != null) icon = DeckIcon(glyph,resources.displayMetrics.density)
        this.label = label; textSp = 12.5f; contentDescription = description; tooltipText = description
        if (kind != KeyView.KEY) radiusDp = 100f
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); run() }
    }
    init {
        isClickable = true; setPadding(dp(6),dp(6),dp(6),dp(8))
        leading.isClickable = true; trailing.isClickable = true; strip.isClickable = true
        center.orientation = VERTICAL; center.isClickable = true
        dock.isClickable = true; dock.isHorizontalScrollBarEnabled = false; dock.isFillViewport = true; dock.addView(dockApps)
        dockApps.gravity = Gravity.CENTER_VERTICAL
        center.addView(dock,LayoutParams(-1,dp(44)))
        body.isClickable = true; center.addView(body,LayoutParams(-1,0,1f))
        padArea.isClickable = true
        padArea.addView(pad,FrameLayout.LayoutParams(-1,-1).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
        body.addView(padArea,FrameLayout.LayoutParams(-1,-1))
        keyboardArea.orientation = VERTICAL; keyboardArea.isClickable = true; keyboardArea.visibility = GONE
        keyboardArea.addView(HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(shortcuts,FrameLayout.LayoutParams(-1,-1)) },LayoutParams(-1,dp(40)))
        keyboard = LaptopKeyboard(context,ready,key,release,text,layoutChanged,theme)
        keyboardArea.addView(keyboard,LayoutParams(-1,0,1f))
        body.addView(keyboardArea,FrameLayout.LayoutParams(-1,-1))
        dictation.isClickable = true; dictation.visibility = GONE
        voicePanel.orientation = VERTICAL; voicePanel.setPadding(dp(12),dp(4),dp(12),dp(4))
        speechStatus.apply { text = "Dictation"; textSize = 13f; gravity = Gravity.CENTER_VERTICAL; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        voicePanel.addView(speechStatus,LayoutParams(-1,dp(28)))
        transcript.apply { textSize = 20f; setPadding(dp(16),dp(14),dp(16),dp(14)); minHeight = dp(72); hint = "Speak, then insert. Swipe left to delete a word." }
        var swipeX = 0f
        transcript.setOnTouchListener { _,e -> when(e.actionMasked) { MotionEvent.ACTION_DOWN -> { swipeX = e.x; true }; MotionEvent.ACTION_UP -> { if(e.x-swipeX < -dp(60)) deleteWord(); true }; else -> true } }
        voicePanel.addView(ScrollView(context).apply { isFillViewport = true; addView(transcript) },LayoutParams(-1,0,1f))
        voicePanel.addView(voiceButtons,LayoutParams(-1,dp(52)))
        dictation.addView(voicePanel,FrameLayout.LayoutParams(-1,-1)); body.addView(dictation,FrameLayout.LayoutParams(-1,-1))
        battery.apply { gravity = Gravity.CENTER; textSize = 10.5f; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL); contentDescription = "Remote battery unavailable" }
        applyTheme(); arrange(true)
    }
    /** Re-read the saved or system appearance and repaint every deck control. Sessions are unaffected. */
    fun refreshTheme() = applyTheme()
    private fun applyTheme() {
        theme = DeckTheme.of(context)
        setBackgroundColor(theme.deck)
        keyboard.setTheme(theme)
        battery.setTextColor(theme.dim)
        dictation.setBackgroundColor(theme.deck)
        speechStatus.setTextColor(theme.dim)
        transcript.setTextColor(theme.label); transcript.setHintTextColor(theme.hint); transcript.background = shape(theme.card,18)
        voiceButtons.removeAllViews()
        fun voiceParams() = LayoutParams(0,-1,1f).apply { setMargins(dp(3),dp(8),dp(3),0) }
        voiceButtons.addView(control(KeyView.RAIL,"undo","Undo dictation edit","Undo") { cancelVoice(); if (textHistory.isNotEmpty()) { textDraft = textHistory.removeLast(); transcript.text = textDraft } },voiceParams())
        voiceButtons.addView(control(KeyView.RAIL,"keyboard","Cancel dictation and show keyboard","Keyboard") { closeDictation(); selectKeyboard(true) },voiceParams())
        insert = control(KeyView.ACCENT,"return","Insert dictated text into remote app","Insert") {
            if (textDraft.isNotBlank() && ready()) {
                cancelVoice(); setListening(false)
                if (remote(15,textDraft)) { setInsertEnabled(false); speechStatus.text = "Inserting…" }
            }
        }.also { it.isEnabled = insertEnabled; voiceButtons.addView(it,voiceParams()) }
        rebuildShortcuts(); updateDock(); pad.invalidate()
        if (wide != null) buildRails()
        appearance()
    }
    private fun setInsertEnabled(enabled: Boolean) { insertEnabled = enabled; insert?.isEnabled = enabled }
    private fun setListening(active: Boolean) { listening = active; micKey?.let { it.fill = if (active) theme.danger else theme.accent } }
    private fun arrange(nowWide: Boolean) {
        if (wide == nowWide) return
        wide = nowWide
        removeAllViews(); strip.removeAllViews()
        for (rail in listOf(leading,trailing)) (rail.parent as? ViewGroup)?.removeView(rail)
        if (nowWide) {
            orientation = HORIZONTAL; leading.orientation = VERTICAL; trailing.orientation = VERTICAL
            addView(leading,LayoutParams(dp(52),-1)); addView(center,LayoutParams(0,-1,1f).apply { setMargins(dp(4),0,dp(4),0) }); addView(trailing,LayoutParams(dp(56),-1))
        } else {
            orientation = VERTICAL; leading.orientation = HORIZONTAL; trailing.orientation = HORIZONTAL
            strip.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(leading,FrameLayout.LayoutParams(-2,-1)) },LayoutParams(0,-1,1f))
            strip.addView(trailing,LayoutParams(-2,-1))
            addView(strip,LayoutParams(-1,dp(48))); addView(center,LayoutParams(-1,0,1f))
        }
        buildRails()
    }
    private fun buildRails() {
        val column = wide == true
        leading.removeAllViews(); trailing.removeAllViews()
        fun slot(length: Int = 44) = if (column) LayoutParams(-1,dp(length)).apply { setMargins(dp(4),dp(3),dp(4),dp(3)) } else LayoutParams(dp(length),-1).apply { setMargins(dp(3),dp(4),dp(3),dp(4)) }
        fun spacer() = View(context) to if (column) LayoutParams(-1,0,1f) else LayoutParams(dp(4),-1)
        val settings = control(KeyView.RAIL,"settings","Control settings") { settings() }; settingsKey = settings
        val display = control(KeyView.RAIL,"display","Switch remote display") { displays() }
        val overview = control(KeyView.RAIL,"overview","Workspaces and open apps") { overview() }
        val search = control(KeyView.RAIL,"search","Remote launcher") { invoke(14,"launcher") }
        val volume = control(KeyView.RAIL,"volume","Remote volume") { volume() }; volumeKey = volume
        val mode = control(KeyView.RAIL,"keyboard","Switch to keyboard") { selectKeyboard(!keyboardSelected) }.apply { vertical = column; textSp = 13f }; modeKey = mode
        val mic = control(KeyView.ACCENT,"mic","Start dictation") { openDictation() }; micKey = mic
        if (column) {
            for (item in listOf(display,overview,search)) leading.addView(item,slot())
            spacer().let { leading.addView(it.first,it.second) }
            leading.addView(battery,LayoutParams(-1,dp(22)))
            leading.addView(settings,slot())
            trailing.addView(mic,slot(46)); trailing.addView(mode,LayoutParams(-1,dp(112)).apply { setMargins(dp(4),dp(3),dp(4),dp(3)) })
            spacer().let { trailing.addView(it.first,it.second) }
            trailing.addView(volume,slot())
        } else {
            for (item in listOf(settings,display,overview,search,volume)) leading.addView(item,slot())
            leading.addView(battery,LayoutParams(dp(44),-1))
            trailing.addView(mode,slot(52)); trailing.addView(mic,slot(52))
        }
        setListening(listening); updateMode()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val nowWide = w >= dp(560)
        if (nowWide != wide) post { arrange(nowWide) }
    }
    fun setPosture(tabletop: Boolean,manual: Boolean) { this.tabletop = tabletop; this.manual = manual; updateMode() }
    fun setSource(manual: Boolean) = setPosture(!manual,manual)
    fun keyboardLayoutName() = keyboard.name()
    fun showKeyboardSettings(anchor: View) = keyboard.showSettings(anchor)
    fun resetInputs() { keyboard.resetInputs(); pad.reset() }
    fun applyRemoteState(state: RemoteDesktopState) {
        desktop = state; battery.text = state.battery ?: ""; battery.contentDescription = state.battery?.let { "Remote battery $it" } ?: "Remote battery unavailable"
        updateDock(); setInsertEnabled("text" in state.capabilities && textDraft.isNotBlank())
    }
    fun clearRemoteState() { desktop = null; updateDock(); battery.text = ""; battery.contentDescription = "Remote battery unavailable"; closeDictation() }
    fun onVoiceResult(value: String,recording: Boolean,error: String?) { textDraft = value; transcript.text = value; setListening(recording); speechStatus.text = error ?: if(recording) "Listening…" else "Dictation"; setInsertEnabled(value.isNotBlank() && desktop?.capabilities?.contains("text") == true) }
    fun insertionFinished(success: Boolean,error: String?) { setInsertEnabled(true); if(success) { textDraft = ""; transcript.text = ""; closeDictation() } else speechStatus.text = error ?: "Text was not inserted" }
    fun hasDictation() = dictation.visibility == VISIBLE
    fun closeDictation() { cancelVoice(); setListening(false); dictation.visibility = GONE; textDraft = ""; transcript.text = ""; textHistory.clear() }
    private fun openDictation() {
        if (!ready()) { notice("Connect before dictating"); return }
        if(listening) { cancelVoice(); setListening(false); speechStatus.text = "Dictation"; return }
        release(); textHistory.clear(); textHistory.addLast(textDraft); transcript.text = textDraft; dictation.visibility = VISIBLE; setInsertEnabled(textDraft.isNotBlank() && desktop?.capabilities?.contains("text") == true); speechStatus.text = "Dictation"; startVoice(textDraft)
    }
    private fun deleteWord() { cancelVoice(); setListening(false); textHistory.addLast(textDraft); if(textHistory.size > 20) textHistory.removeFirst(); textDraft = textDraft.trimEnd().replace(Regex("\\S+\\s*$"),"").trimEnd(); transcript.text = textDraft; speechStatus.text = "Dictation" }
    private fun selectKeyboard(show: Boolean) {
        if(keyboardSelected == show) return
        release(); closeDictation(); keyboardSelected = show
        val leaving = if(show) padArea else keyboardArea; val entering = if(show) keyboardArea else padArea
        leaving.animate().cancel(); entering.animate().cancel()
        // Commit the actual mode independently of animator completion/cancellation.
        leaving.visibility = GONE; leaving.alpha = 1f
        entering.visibility = VISIBLE; entering.alpha = 0f
        entering.translationY = dp(10).toFloat()
        entering.animate().translationY(0f).alpha(1f).setDuration(160).start()
        updateMode(); refresh()
    }
    private fun updateMode() {
        modeKey?.apply {
            val target = if (keyboardSelected) "trackpad" else "keyboard"
            icon = DeckIcon(target,resources.displayMetrics.density)
            label = if (wide == true) target.replaceFirstChar { it.uppercase() } else ""
            contentDescription = "Switch to $target"; tooltipText = contentDescription
        }
    }
    private fun settings() {
        PopupMenu(context,settingsKey).apply {
            menu.add("Keyboard layout: ${keyboard.name()}").setOnMenuItemClickListener { keyboard.showSettings(settingsKey); true }
            val mode = DeckTheme.mode(context)
            menu.add("Appearance: ${mode.replaceFirstChar { it.uppercase() }}").setOnMenuItemClickListener {
                val next = when (mode) { DeckTheme.AUTO -> DeckTheme.DARK; DeckTheme.DARK -> DeckTheme.LIGHT; else -> DeckTheme.AUTO }
                preferences.edit().putString("deck_theme",next).apply(); applyTheme(); true
            }
            menu.add("Fullscreen").setOnMenuItemClickListener { fullscreen(); true }
            menu.add("Refresh open apps").setOnMenuItemClickListener { refresh(); true }
            menu.add("Connection").setOnMenuItemClickListener { connection(); true }
            menu.add("Disconnect").setOnMenuItemClickListener { disconnect(); true }
            show()
        }
    }
    private fun invoke(kind: Int,capability: String? = null) {
        if (!ready()) { notice("Connect first"); return }
        if (capability != null && desktop?.capabilities?.contains(capability) != true) { notice("${capability.replaceFirstChar { it.uppercase() }} is unavailable on this host"); return }
        remote(kind,"")
    }
    private fun volume() { PopupMenu(context,volumeKey).apply { for((label,kind) in listOf("Volume up" to 6,"Volume down" to 7,"Mute" to 8)) menu.add(label).setOnMenuItemClickListener { invoke(kind,"volume"); true }; show() } }
    private fun chord(code: Int) { if(ready()) { key(29,true,"laptop-shortcut"); key(code,true,"laptop-shortcut"); key(code,false,"laptop-shortcut"); key(29,false,"laptop-shortcut") } }
    private fun rebuildShortcuts() {
        shortcuts.removeAllViews()
        val gap = (2.5f*resources.displayMetrics.density).toInt()
        fun shortcut(glyph: String?,description: String,label: String = "",run: () -> Unit) {
            shortcuts.addView(control(KeyView.KEY,glyph,description,label,run).apply { muted = true; textSp = 11.5f; minimumWidth = dp(40) },LayoutParams(0,-1,1f).apply { setMargins(gap,gap,gap,gap) })
        }
        shortcut(null,"Escape","esc") { if(ready()) { key(1,true,"laptop-esc"); key(1,false,"laptop-esc") } }
        shortcut("brightness_down","Brightness down") { invoke(13,"brightness") }; shortcut("brightness_up","Brightness up") { invoke(12,"brightness") }
        shortcut("overview","Workspace overview") { overview() }; shortcut("search","Remote launcher") { invoke(14,"launcher") }
        shortcut("undo","Undo") { chord(44) }; shortcut("copy","Copy") { chord(46) }; shortcut("paste","Paste") { chord(47) }
        shortcut("previous","Previous media") { invoke(11,"media") }; shortcut("play","Play or pause media") { invoke(9,"media") }; shortcut("next","Next media") { invoke(10,"media") }
        shortcut("mute","Mute") { invoke(8,"volume") }; shortcut("volume_down","Volume down") { invoke(7,"volume") }; shortcut("volume_up","Volume up") { invoke(6,"volume") }
    }
    private fun updateDock() {
        dockApps.removeAllViews()
        val apps = desktop?.apps.orEmpty()
        if(apps.isEmpty()) {
            dockApps.addView(TextView(context).apply {
                text = if (desktop == null) "Open apps appear here once connected" else "No open windows"
                textSize = 12f; setTextColor(theme.hint); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10),0,dp(10),0)
                setOnClickListener { refresh() }
            },LayoutParams(-2,-1))
            return
        }
        apps.forEach { app ->
            // The host supplies window class/title, not icon artwork. Keep the monogram honest.
            val name = app.app.ifBlank { "Window" }
            val shortName = name.substringAfterLast('.').ifBlank { name }
            val words = shortName.split(Regex("[\\s_-]+")).filter { it.isNotBlank() }
            val initials = if(words.size > 1) words.take(2).joinToString("") { it.take(1) } else shortName.take(2)
            val description = if(app.title.isBlank()) name else "$name: ${app.title}"
            val hue = ((name.hashCode()%360)+360)%360
            val chip = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5),0,dp(12),0); isClickable = true; isFocusable = true
                contentDescription = description; tooltipText = description
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(theme.railDown),shape(theme.rail,13),null)
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if(ready()) remote(2,app.address) }
            }
            chip.addView(TextView(context).apply {
                text = initials.uppercase(); textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.HSVToColor(floatArrayOf(hue.toFloat(),0.5f,0.86f)),Color.HSVToColor(floatArrayOf(hue.toFloat(),0.62f,0.62f)))).apply { cornerRadius = dp(8).toFloat() }
            },LayoutParams(dp(28),dp(28)))
            chip.addView(TextView(context).apply {
                text = shortName; textSize = 12.5f; setTextColor(theme.label); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(112); setPadding(dp(8),0,0,0); typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
            })
            dockApps.addView(chip,LayoutParams(-2,dp(38)).apply { marginStart = dp(2); marginEnd = dp(4) })
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
        private var cx = 0f; private var cy = 0f; private var ring = 1f; private var used = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val longPress = Runnable { if(active && ready() && fingers == 1 && distance < slop) { hold = true; button(272,true,"laptop-hold"); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }
        private val scale = ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(d: ScaleGestureDetector): Boolean { pinching = true; axis(0,0.0,true); axis(1,0.0,true); viewport(d.scaleFactor,0f,0f); return true } })
        init { isClickable = true; contentDescription = "Remote trackpad: tap to click, hold and drag, two fingers scroll, pinch to zoom, three fingers up for open apps" }
        fun reset() { removeCallbacks(longPress); active = false; hold = false; pinching = false; fingers = 1; distance = 0f }
        private fun center(e: MotionEvent): Pair<Float,Float> { var x = 0f; var y = 0f; for(i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }; return x/e.pointerCount to y/e.pointerCount }
        private fun pulse(x: Float,y: Float) { cx = x; cy = y; ValueAnimator.ofFloat(0f,1f).apply { duration = 280; addUpdateListener { ring = it.animatedValue as Float; invalidate() }; start() }; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        override fun onDraw(canvas: Canvas) {
            val line = maxOf(1f,dp(1).toFloat()); val radius = dp(22).toFloat()
            paint.style = Paint.Style.FILL; paint.color = theme.pad
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = line; paint.color = theme.padLine
            canvas.drawRoundRect(line/2,line/2,width-line/2,height-line/2,radius,radius,paint)
            if(!used && height > dp(90)) {
                paint.style = Paint.Style.FILL; paint.color = theme.hint; paint.textAlign = Paint.Align.CENTER
                paint.textSize = 12.5f*resources.displayMetrics.scaledDensity
                val hint = if (width > dp(420)) "Tap to click  ·  Hold to drag  ·  Two fingers to scroll  ·  Pinch to zoom" else "Tap · Hold to drag · Two-finger scroll"
                canvas.drawText(hint,width/2f,height-dp(18).toFloat(),paint)
            }
            if(ring < 1f) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = theme.accent; paint.alpha = ((1f-ring)*230).toInt()
                canvas.drawCircle(cx,cy,dp(10)+dp(22)*ring,paint); paint.alpha = 255
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(!ready()) { if(e.actionMasked == MotionEvent.ACTION_CANCEL) release(); return true }
            if(!active && e.actionMasked != MotionEvent.ACTION_DOWN) return true
            scale.onTouchEvent(e)
            val c = center(e)
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { if(!used) { used = true; invalidate() }; active = true; fingers = 1; pinching = false; hold = false; distance = 0f; down = e.eventTime; x0 = c.first; y0 = c.second; lastX = c.first; lastY = c.second; postDelayed(longPress,500) }
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
