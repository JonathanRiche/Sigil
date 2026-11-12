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
    private val appearance: () -> Unit,private val zoom: () -> Unit
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
    private var brightnessKey: View = this
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
        orientation = VERTICAL; isClickable = true; setPadding(dp(12),dp(10),dp(12),dp(12))
        leading.isClickable = true; trailing.isClickable = true; strip.isClickable = true; center.isClickable = true
        strip.gravity = Gravity.CENTER_VERTICAL
        dock.isClickable = true; dock.isHorizontalScrollBarEnabled = false; dock.isFillViewport = true; dock.addView(dockApps)
        dockApps.gravity = Gravity.CENTER_VERTICAL
        body.isClickable = true
        padArea.isClickable = true; padArea.addView(pad,FrameLayout.LayoutParams(-1,-1))
        body.addView(padArea,FrameLayout.LayoutParams(-1,-1))
        keyboardArea.orientation = VERTICAL; keyboardArea.isClickable = true; keyboardArea.visibility = GONE
        keyboardArea.addView(HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(shortcuts,FrameLayout.LayoutParams(-1,-1)) },LayoutParams(-1,dp(40)))
        keyboard = LaptopKeyboard(context,ready,key,release,text,layoutChanged,theme)
        keyboardArea.addView(keyboard,LayoutParams(-1,0,1f))
        body.addView(keyboardArea,FrameLayout.LayoutParams(-1,-1))
        // Dictation drops over the top of the keyboard as a dark fade; the keys stay visible
        // underneath but the overlay consumes their touches until it is closed.
        dictation.isClickable = true; dictation.visibility = GONE
        dictation.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xf70a0a0c.toInt(),0xee0a0a0c.toInt(),0x000a0a0c)).apply { cornerRadius = dp(18).toFloat() }
        voicePanel.orientation = VERTICAL; voicePanel.setPadding(dp(18),dp(10),dp(14),0)
        speechStatus.apply { text = "Dictation"; textSize = 12f; gravity = Gravity.CENTER_VERTICAL; setTextColor(0xff9d9da6.toInt()); typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        voicePanel.addView(speechStatus,LayoutParams(-1,dp(22)))
        transcript.apply { textSize = 20f; setTextColor(Color.WHITE); setHintTextColor(0xff7c7c86.toInt()); setPadding(0,dp(2),0,dp(8)); hint = "Speak, then insert. Swipe left to delete a word." }
        var swipeX = 0f
        transcript.setOnTouchListener { _,e -> when(e.actionMasked) { MotionEvent.ACTION_DOWN -> { swipeX = e.x; true }; MotionEvent.ACTION_UP -> { if(e.x-swipeX < -dp(60)) deleteWord(); true }; else -> true } }
        voicePanel.addView(ScrollView(context).apply { addView(transcript) },LayoutParams(-1,0,1f))
        voiceButtons.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        voicePanel.addView(voiceButtons,LayoutParams(-1,dp(46)))
        voicePanel.addView(View(context),LayoutParams(-1,0,0.8f))
        dictation.addView(voicePanel,FrameLayout.LayoutParams(-1,-1)); body.addView(dictation,FrameLayout.LayoutParams(-1,-1))
        battery.apply { gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL); contentDescription = "Remote battery unavailable" }
        applyTheme(); arrange(true)
    }
    /** Re-read the saved or system appearance and repaint every deck control. Sessions are unaffected. */
    fun refreshTheme() = applyTheme()
    private fun applyTheme() {
        theme = DeckTheme.of(context)
        background = shape(theme.deck,28)
        keyboard.setTheme(theme)
        battery.setTextColor(theme.onDeck)
        voiceButtons.removeAllViews()
        fun round(view: View,width: Int = 46) = voiceButtons.addView(view,LayoutParams(dp(width),dp(40)).apply { marginStart = dp(8) })
        round(control(KeyView.RAIL,"undo","Undo dictation edit") { cancelVoice(); if (textHistory.isNotEmpty()) { textDraft = textHistory.removeLast(); transcript.text = textDraft } })
        round(control(KeyView.RAIL,"close","Close dictation") { closeDictation() })
        insert = control(KeyView.ACCENT,"return","Insert dictated text into remote app","Insert") {
            if (textDraft.isNotBlank() && ready()) {
                cancelVoice(); setListening(false)
                if (remote(15,textDraft)) { setInsertEnabled(false); speechStatus.text = "Inserting…" }
            }
        }.also { it.isEnabled = insertEnabled; round(it,112) }
        rebuildShortcuts(); updateDock(); pad.invalidate()
        if (wide != null) buildRails()
        appearance()
    }
    private fun setInsertEnabled(enabled: Boolean) { insertEnabled = enabled; insert?.isEnabled = enabled }
    private fun setListening(active: Boolean) { listening = active; micKey?.let { it.fill = if (active) theme.danger else theme.accent } }
    private fun arrange(nowWide: Boolean) {
        if (wide == nowWide) return
        wide = nowWide
        removeAllViews(); center.removeAllViews()
        for (view in listOf<View>(leading,trailing,body)) (view.parent as? ViewGroup)?.removeView(view)
        addView(strip,LayoutParams(-1,dp(38)).apply { bottomMargin = dp(8) })
        if (nowWide) {
            // Short, wide panes keep the keys full height: zoom and overview move to a left
            // rail, dictation and the mode switch to a right rail.
            center.orientation = HORIZONTAL; leading.orientation = VERTICAL; trailing.orientation = VERTICAL
            center.addView(leading,LayoutParams(dp(48),-1)); center.addView(body,LayoutParams(0,-1,1f).apply { setMargins(dp(8),0,dp(8),0) }); center.addView(trailing,LayoutParams(dp(54),-1))
        } else {
            center.orientation = VERTICAL; leading.orientation = HORIZONTAL; trailing.orientation = HORIZONTAL
            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; isClickable = true }
            bar.addView(leading,LayoutParams(-2,-1)); bar.addView(View(context),LayoutParams(0,1,1f)); bar.addView(trailing,LayoutParams(-2,-1))
            center.addView(body,LayoutParams(-1,0,1f)); center.addView(bar,LayoutParams(-1,dp(50)).apply { topMargin = dp(8) })
        }
        addView(center,LayoutParams(-1,0,1f))
        buildRails()
    }
    private fun buildRails() {
        val column = wide == true
        leading.removeAllViews(); trailing.removeAllViews(); strip.removeAllViews()
        (dock.parent as? ViewGroup)?.removeView(dock); (battery.parent as? ViewGroup)?.removeView(battery)
        fun pill(view: View,last: Boolean = false) = strip.addView(view,LayoutParams(dp(48),dp(34)).apply { if (!last) marginEnd = dp(6) })
        val settings = control(KeyView.RAIL,"settings","Control settings") { settings() }; settingsKey = settings
        pill(settings); pill(control(KeyView.RAIL,"display","Switch remote display") { displays() })
        strip.addView(dock,LayoutParams(0,-1,1f).apply { marginEnd = dp(6) })
        strip.addView(battery,LayoutParams(-2,-1).apply { marginEnd = dp(8) })
        val brightness = control(KeyView.RAIL,"brightness_up","Remote brightness") { brightness() }; brightnessKey = brightness; pill(brightness)
        val volume = control(KeyView.RAIL,"volume","Remote volume") { volume() }; volumeKey = volume; pill(volume)
        pill(control(KeyView.RAIL,"appearance","Switch deck appearance") { cycleAppearance() },true)
        val zoomKey = control(KeyView.RAIL,"zoom","Zoom the desktop") { if (ready()) zoom() else notice("Connect first") }
        val overview = control(KeyView.RAIL,"overview","Workspaces and open apps") { overview() }
        val mode = control(KeyView.RAIL,"keyboard","Switch to keyboard") { selectKeyboard(!keyboardSelected) }.apply { vertical = column; textSp = 13.5f }; modeKey = mode
        val mic = control(KeyView.ACCENT,"mic","Start dictation") { openDictation() }; micKey = mic
        if (column) {
            fun slot(length: Int) = LayoutParams(-1,dp(length)).apply { bottomMargin = dp(8) }
            leading.addView(zoomKey,slot(48)); leading.addView(overview,slot(48))
            trailing.addView(mic,slot(54)); trailing.addView(mode,slot(124))
        } else {
            fun slot(length: Int) = LayoutParams(dp(length),-1).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }
            leading.addView(zoomKey,slot(56)); leading.addView(overview,slot(56))
            trailing.addView(mode,slot(136)); trailing.addView(mic,slot(68))
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
    fun closeDictation() { cancelVoice(); setListening(false); dictation.animate().cancel(); dictation.visibility = GONE; textDraft = ""; transcript.text = ""; textHistory.clear() }
    private fun openDictation() {
        if (!ready()) { notice("Connect before dictating"); return }
        if(listening) { cancelVoice(); setListening(false); speechStatus.text = "Dictation"; return }
        release(); textHistory.clear(); textHistory.addLast(textDraft); transcript.text = textDraft
        if (dictation.visibility != VISIBLE) { dictation.visibility = VISIBLE; dictation.alpha = 0f; dictation.translationY = -dp(12).toFloat(); dictation.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        setInsertEnabled(textDraft.isNotBlank() && desktop?.capabilities?.contains("text") == true); speechStatus.text = "Dictation"; startVoice(textDraft)
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
        entering.scaleX = 0.97f; entering.scaleY = 0.97f
        entering.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        updateMode(); refresh()
    }
    private fun updateMode() {
        modeKey?.apply {
            val target = if (keyboardSelected) "trackpad" else "keyboard"
            icon = DeckIcon(target,resources.displayMetrics.density)
            label = target.replaceFirstChar { it.uppercase() }
            contentDescription = "Switch to $target"; tooltipText = contentDescription
        }
    }
    private fun cycleAppearance() {
        val next = when (DeckTheme.mode(context)) { DeckTheme.AUTO -> if (theme.dark) DeckTheme.LIGHT else DeckTheme.DARK; DeckTheme.DARK -> DeckTheme.LIGHT; else -> DeckTheme.DARK }
        preferences.edit().putString("deck_theme",next).apply(); applyTheme()
    }
    private fun brightness() { PopupMenu(context,brightnessKey).apply { for((label,kind) in listOf("Brightness up" to 12,"Brightness down" to 13)) menu.add(label).setOnMenuItemClickListener { invoke(kind,"brightness"); true }; show() } }
    private fun settings() {
        PopupMenu(context,settingsKey).apply {
            menu.add("Keyboard layout: ${keyboard.name()}").setOnMenuItemClickListener { keyboard.showSettings(settingsKey); true }
            if (DeckTheme.mode(context) != DeckTheme.AUTO) menu.add("Appearance: follow system").setOnMenuItemClickListener { preferences.edit().remove("deck_theme").apply(); applyTheme(); true }
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
        val gap = (3.5f*resources.displayMetrics.density).toInt()
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
                textSize = 12f; setTextColor(theme.onDeck); alpha = 0.75f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),0)
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
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4),0,dp(12),0); isClickable = true; isFocusable = true
                contentDescription = description; tooltipText = description
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(theme.railDown),shape(theme.rail,17),null)
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if(ready()) remote(2,app.address) }
            }
            chip.addView(TextView(context).apply {
                text = initials.uppercase(); textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.HSVToColor(floatArrayOf(hue.toFloat(),0.5f,0.86f)),Color.HSVToColor(floatArrayOf(hue.toFloat(),0.62f,0.62f)))).apply { cornerRadius = dp(13).toFloat() }
            },LayoutParams(dp(26),dp(26)))
            chip.addView(TextView(context).apply {
                text = shortName; textSize = 12.5f; setTextColor(theme.railIcon); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(112); setPadding(dp(8),0,0,0); typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
            })
            dockApps.addView(chip,LayoutParams(-2,dp(34)).apply { marginEnd = dp(6) })
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
