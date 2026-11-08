package dev.omarchy.gliff

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import android.widget.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var status: TextView
    private lateinit var surface: SurfaceView
    private lateinit var cursorOverlay: RemoteCursorOverlay
    private lateinit var desktopViewport: DesktopViewport
    private lateinit var voice: VoiceDictation
    private var innerDisplay = false
    private var reportedTabletop = false
    private var selectedOutput: String? = null
    private var activeOutput: String? = null
    private var activePrivate = false
    private var activeHost = ""
    private var activeUser = ""
    private var activePort = 22
    private var activeUseKey = false
    private var remoteDesktop: RemoteDesktopState? = null
    private val pendingActions = HashSet<Int>()
    private var textCompletion: ((Boolean,String?) -> Unit)? = null
    private var microphonePermission = false
    private var microphoneToken = 0L
    private var startVoiceWhenResumed = false
    private var voiceDraft = ""
    private val microphoneTimeout = Runnable { if(microphonePermission) { microphonePermission = false; stopSession("Microphone permission timed out") } }
    private val desktopRefresh = object : Runnable {
        override fun run() { if(foreground && state.ready(state.token())) { queryDesktop(); ui.postDelayed(this,5000) } }
    }
    private lateinit var display: FrameLayout
    private lateinit var destination: EditText
    private lateinit var advanced: LinearLayout
    private lateinit var advancedButton: Button
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var keyButton: Button
    private lateinit var useImportedKey: CheckBox
    private lateinit var privateScreen: CheckBox
    private lateinit var manualFlex: CheckBox
    private lateinit var keyboardLayoutSetting: Button
    private lateinit var flexControls: LaptopControls
    private lateinit var foldObserver: FoldLayoutObserver
    private var tabletopHinge: android.graphics.Rect? = null
    private var desktopPane = android.graphics.Rect()
    private var flexPane = android.graphics.Rect()
    private var flexActive = false
    private var pointerGeometry: SessionState.Geometry? = null
    private var pointerKnown = false
    private var lastPointerCommandMs = 0L
    private var pointerX = 0.0
    private var pointerY = 0.0
    private lateinit var keys: ImportedKey
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var connectionForm: ScrollView
    private lateinit var sessionLabel: TextView
    private lateinit var statusDot: TextView
    private lateinit var idleLabel: TextView
    private lateinit var diagnosticsLabel: TextView
    private lateinit var tray: LinearLayout
    private lateinit var trayButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private var connectionDialog: android.app.AlertDialog? = null
    private var diagnosticsDialog: android.app.AlertDialog? = null
    private var fullscreen = false
    private var localTest = false
    private var activeSessionLabel = "Sigil"
    private var scrollY = 0f
    private var worker: Thread? = null
    private var running = AtomicBoolean(false)
    @Volatile private var handle = 0L
    @Volatile private var decoderCaps: Pair<Int,Int>? = null
    private val state = SessionState()
    private val held = InputOwners { kind, code, down -> control(kind,c=code,d=down) }
    private lateinit var cover: View
    private var gestureLeft = false
    private var scrolling = false
    @Volatile private var foreground = false
    private var trustDialog: android.app.AlertDialog? = null
    private var approvalDialog: android.app.AlertDialog? = null
    @Volatile private var approvalBrowserToken = 0L
    @Volatile private var approvalExcursion = false
    @Volatile private var approvalDeadline = 0L
    private var approvalUrl: String? = null
    private val approvalTimeout = Runnable {
        if (approvalBrowserToken != 0L && state.current(approvalBrowserToken)) stopSession("Tailscale approval timed out")
    }
    private var importThread: Thread? = null
    private var importSignal: android.os.CancellationSignal? = null
    private val importSerial = java.util.concurrent.atomic.AtomicInteger()
    private val importStream = java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>()
    private val surfaceVersion = java.util.concurrent.atomic.AtomicInteger()
    private val backgroundTimeout = Runnable { if(!foreground && !(approvalExcursion && state.current(approvalBrowserToken) && !state.ready(approvalBrowserToken)) && !microphonePermission) stopSession("Disconnected while app is in background") }
    private val resize = Runnable { releaseAll(); sendResize(display.width,desktopHeight()) }
    private fun post(token: Long, action: () -> Unit) { ui.post { if (state.token() == token && !isDestroyed) action() } }
    private fun message(text: String, token: Long = state.token()) { post(token) {
        status.text = text; status.visibility = View.VISIBLE; refreshChrome()
        if (text.startsWith("LOCAL DECODER TEST PASSED") || text.startsWith("LOCAL DECODER TEST FAILED")) {
            if (foreground) diagnosticsDialog = android.app.AlertDialog.Builder(this).setTitle("Local decoder test").setMessage(text).setPositiveButton("Done",null).show()
        } else if (connectionDialog?.isShowing != true && foreground && text != "Connected" && text != "Disconnected" &&
            !text.startsWith("Connecting") && !text.startsWith("LOCAL DECODER TEST")) {
            Toast.makeText(this,text,Toast.LENGTH_LONG).show()
        }
    } }
    private fun releaseAll() {
        if (::flexControls.isInitialized) flexControls.resetInputs()
        control(5,c=0,d=1); control(5,c=1,d=1)
        held.clear(); gestureLeft = false; scrolling = false; control(8) }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 8) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun quietButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setSingleLine(true); textSize = 13f
        setTextColor(0xffcccccc.toInt()); minimumWidth = 0; minWidth = 0
        setPadding(dp(12),0,dp(12),0); setOnClickListener { action() }
    }
    private fun chromeButton(icon: String, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(ChromeIcon(icon,resources.displayMetrics.density))
        contentDescription = description; background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33ffffff),null,null)
        setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff202020.toInt(); window.navigationBarColor = android.graphics.Color.BLACK
        keys = ImportedKey(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6),0,0,0); setBackgroundColor(0xff202020.toInt()) }
        val chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),0)
            background = rounded(0xff303030.toInt()); isClickable = true; isFocusable = true
            contentDescription = "Current session and connection setup"
            setOnClickListener { showConnection() }
        }
        sessionLabel = TextView(this).apply {
            textSize = 14f; setTextColor(0xffbbbbbb.toInt()); setTypeface(typeface,android.graphics.Typeface.BOLD)
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusDot = TextView(this).apply { text = "●"; textSize = 11f; setPadding(dp(8),0,0,0) }
        chip.addView(sessionLabel,LinearLayout.LayoutParams(0,-2,1f)); chip.addView(statusDot)
        top.addView(chip,LinearLayout.LayoutParams(0,dp(48),1f).apply { topMargin = dp(4); bottomMargin = dp(4) })
        top.addView(chromeButton("plus","Connection setup") { showConnection() },LinearLayout.LayoutParams(dp(48),dp(48)))
        trayButton = chromeButton("controls","Show controls") { toggleTray() }
        top.addView(trayButton,LinearLayout.LayoutParams(dp(48),dp(48)))
        fullscreenButton = chromeButton("fullscreen","Enter fullscreen") { toggleFullscreen() }
        top.addView(fullscreenButton,LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(top,LinearLayout.LayoutParams(-1,dp(56)))

        status = TextView(this).apply { textSize = 13f; setTextColor(0xffaaaaaa.toInt()); setPadding(0,dp(16),0,dp(12)); text = if (NativeCore.available) "Disconnected" else "Native core unavailable" }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),dp(8)) }
        fun field(label: String, value: String = "", placeholder: String = "", parent: LinearLayout = form): EditText {
            val input = EditText(this).apply {
                hint = placeholder; setText(value); isSingleLine = true; id = View.generateViewId()
                setTextColor(0xffeeeeee.toInt()); setHintTextColor(0xff777777.toInt()); textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                minimumHeight = dp(48)
            }
            parent.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(0xff999999.toInt()); labelFor = input.id; setPadding(0,dp(12),0,0) })
            parent.addView(input); return input
        }
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        host = EditText(this).apply { setText(prefs.getString("host","")) }
        user = EditText(this).apply { setText(prefs.getString("user","")) }
        val savedDestination = if (host.text.isNotEmpty() && user.text.isNotEmpty()) "${user.text}@${host.text}" else ""
        destination = field("Destination",savedDestination,"user@host")
        advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        advancedButton = quietButton("Advanced") { setAdvanced(advanced.visibility != View.VISIBLE) }
        form.addView(advancedButton,LinearLayout.LayoutParams(-1,dp(48)))
        form.addView(advanced)
        privateScreen = CheckBox(this).apply {
            text = "Private virtual screen"; textSize = 13f; setTextColor(0xffcccccc.toInt())
            isChecked = false; minHeight = dp(48)
        }
        advanced.addView(privateScreen,LinearLayout.LayoutParams(-1,dp(48)))
        advanced.addView(TextView(this).apply {
            text = "A separate workspace instead of your current desktop."; textSize = 12f
            setTextColor(0xff999999.toInt())
        })
        manualFlex = CheckBox(this).apply {
            text = "Manual Flex layout"; textSize = 13f; setTextColor(0xffcccccc.toInt()); minHeight = dp(48)
            isChecked = false
            setOnCheckedChangeListener { _, _ -> updateFlexLayout() }
        }
        advanced.addView(manualFlex,LinearLayout.LayoutParams(-1,dp(48)))
        advanced.addView(TextView(this).apply {
            text = "Manual split when tabletop posture is not reported."; textSize = 12f; setTextColor(0xff999999.toInt())
        })
        keyboardLayoutSetting = quietButton("Keyboard: " + if (getSharedPreferences("controls",MODE_PRIVATE).getBoolean("split_keyboard",false)) "Split" else "Standard") {
            flexControls.showKeyboardSettings(keyboardLayoutSetting)
        }
        advanced.addView(keyboardLayoutSetting,LinearLayout.LayoutParams(-1,dp(48)))
        port = field("SSH port",prefs.getString("port","22")!!,parent=advanced).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        useImportedKey = CheckBox(this).apply {
            text = "Use imported SSH key (optional)"; textSize = 13f; setTextColor(0xffcccccc.toInt())
            isChecked = prefs.getBoolean("use_imported_key",false); minHeight = dp(48)
        }
        advanced.addView(useImportedKey,LinearLayout.LayoutParams(-1,dp(48)))
        keyButton = quietButton(if (keys.exists) "Replace imported key" else "Import private key") {
            if (importThread?.isAlive == true) message("Previous key import is still finishing")
            else { connectionDialog?.dismiss(); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) },10) }
        }
        advanced.addView(keyButton,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(12) })
        advanced.addView(TextView(this).apply {
            text = "Tailscale SSH does not need a private key. Enable an imported key only for a server that requires it. Imported unencrypted OpenSSH/PEM keys are stored encrypted. Host identity is remembered after your first approval."
            textSize = 12f; setTextColor(0xff999999.toInt()); setPadding(0,dp(12),0,0)
        })
        advanced.addView(quietButton("Diagnostics") { connectionDialog?.dismiss(); showDiagnostics() },LinearLayout.LayoutParams(-1,dp(48)))
        status.visibility = if (NativeCore.available) View.GONE else View.VISIBLE
        form.addView(status)
        connectionForm = ScrollView(this).apply { addView(form); isFillViewport = false }

        surface = SurfaceView(this).apply { isFocusableInTouchMode = true; holder.addCallback(this@MainActivity) }
        cursorOverlay = RemoteCursorOverlay(this)
        desktopViewport = DesktopViewport(this,surface,cursorOverlay,{ inputReady() },{ x,y -> remoteTap(x,y) },{ releaseAll() },{ direction -> performRemote(if(direction > 0) 4 else 3,"") })
        display = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(desktopViewport,FrameLayout.LayoutParams(-1,-1))
            cover = View(this@MainActivity).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            addView(cover,FrameLayout.LayoutParams(-1,-1))
            idleLabel = TextView(this@MainActivity).apply { text = "Not connected"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xff8f8f8f.toInt()) }
            addView(idleLabel,FrameLayout.LayoutParams(-1,-1))
            diagnosticsLabel = TextView(this@MainActivity).apply {
                text = "LOCAL DECODER TEST · NO NETWORK"; textSize = 11f; setTextColor(0xffd7b873.toInt())
                setPadding(dp(12),dp(8),dp(12),dp(8)); visibility = View.GONE
            }
            addView(diagnosticsLabel,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right-left != oldRight-oldLeft || bottom-top != oldBottom-oldTop) {
                    updateFlexLayout(); state.displayed()?.let { fitSurface(it) }
                    ui.removeCallbacks(resize); ui.postDelayed(resize,200)
                }
            }
        }
        tray = LinearLayout(this).apply {
            // ViewGroup dispatch still offers events to child controls first. Clickability makes
            // unhandled padding/gap events stop here instead of reaching the SurfaceView below.
            isClickable = true
            orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(6)); background = rounded(0xf2252525.toInt(),12); visibility = View.GONE }
        val shortcuts = LinearLayout(this)
        for ((label,code) in shortcutKeys) {
            shortcuts.addView(quietButton(label) {
                if (state.ready(state.token())) {
                    val kind = if (code == 273) 4 else 2
                    held.set(kind,code,"accessibility:$code",true); held.set(kind,code,"accessibility:$code",false)
                }
            }.apply {
                contentDescription = if (code == 273) "Right mouse button" else label
                setOnTouchListener { _, event ->
                    if (state.ready(state.token())) {
                        val kind = if (code == 273) 4 else 2
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) held.set(kind,code,"screen:$code",true)
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) held.set(kind,code,"screen:$code",false)
                    }
                    true
                }
            },LinearLayout.LayoutParams(dp(60),dp(48)))
        }
        tray.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(shortcuts) },LinearLayout.LayoutParams(-1,dp(48)))
        val actions = LinearLayout(this)
        actions.addView(quietButton("Send text") { sendText() },LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(quietButton("Get text") {
            if (!state.ready(state.token())) message("Wait for Connected before requesting text")
            else if (!NativeCore.requestClipboard(handle)) stopSession("Clipboard request rejected")
        },LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(quietButton("Disconnect") { stopSession("Disconnected") },LinearLayout.LayoutParams(0,dp(48),1f))
        tray.addView(actions)
        display.addView(tray,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(dp(8),0,dp(8),dp(8)) })
        flexControls = LaptopControls(this,
            ready = { flexActive && inputReady() },
            move = { dx,dy,width -> relativeMotion(dx,dy,width) },
            button = { code,down,owner -> if (inputReady()) held.set(4,code,owner,down) },
            axis = { orientation,value,stop -> control(5,value,c=orientation,d=if (stop) 1 else 0) },
            key = { code,down,owner -> if (inputReady()) held.set(2,code,owner,down) },
            release = { releaseAll() }, text = { systemTextInput() },
            layoutChanged = { split -> keyboardLayoutSetting.text = "Keyboard: " + if (split) "Split" else "Standard" },
            remote = { kind,identity -> performRemote(kind,identity) }, refresh = { queryDesktop() },
            displays = { selectDisplay() }, connection = { showConnection() },
            startVoice = { seed -> beginDictation(seed) }, cancelVoice = { if(::voice.isInitialized) voice.cancel() },
            viewport = { factor,dx,dy -> desktopViewport.transform(factor,dx,dy) }, notice = { text -> message(text) }
        ).apply { visibility = View.GONE }
        display.addView(flexControls,FrameLayout.LayoutParams(-1,0))
        root.addView(display,LinearLayout.LayoutParams(-1,0,1f))
        voice = VoiceDictation(this) { text,listening,error -> voiceDraft = text; flexControls.onVoiceResult(text,listening,error) }
        foldObserver = FoldLayoutObserver(this) { posture -> tabletopHinge = posture.horizontalBounds; innerDisplay = posture.inner; reportedTabletop = posture.tabletop; updateFlexLayout() }
        setContentView(root); root.requestApplyInsets(); refreshChrome()
    }
    private fun refreshChrome() {
        if (!::sessionLabel.isInitialized) return
        val ready = state.ready(state.token()) && handle != 0L
        val name = host.text.toString().trim(); val username = user.text.toString().trim()
        sessionLabel.text = if (localTest && running.get()) "Local diagnostics" else if (running.get()) activeSessionLabel else if (name.isEmpty()) "Sigil" else if (username.isEmpty()) name else "$username@$name"
        statusDot.setTextColor(if (ready) 0xff71dfa3.toInt() else 0xff747474.toInt())
        statusDot.contentDescription = if (ready) "Connected" else "Not connected"
        idleLabel.text = if (running.get() && !localTest) { if (ready) "Waiting for desktop…" else status.text.toString().takeIf { it.startsWith("Connecting") } ?: "Connecting…" } else "Not connected"
        idleLabel.visibility = if (cover.visibility == View.VISIBLE && !localTest) View.VISIBLE else View.GONE
        diagnosticsLabel.visibility = if (localTest && running.get()) View.VISIBLE else View.GONE
        refreshSetupState()
    }
    private fun refreshSetupState(target: android.app.AlertDialog? = connectionDialog) {
        val dialog = target ?: return
        if (!dialog.isShowing) return
        // Session-active state updates immediately on stop. Connect keeps its separate worker
        // shutdown guard, so a still-finishing worker cannot be replaced by an early tap.
        val busy = running.get()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        for (input in listOf(destination,port)) input.isEnabled = !busy
        keyButton.isEnabled = !busy
        useImportedKey.isEnabled = !busy
        privateScreen.isEnabled = !busy
        manualFlex.isEnabled = true
    }
    private fun showConnection() {
        if (connectionDialog?.isShowing == true) return
        (connectionForm.parent as? android.view.ViewGroup)?.removeView(connectionForm)
        connectionForm.visibility = View.VISIBLE
        advanced.visibility = View.GONE; advancedButton.text = "Advanced"
        if (status.text.startsWith("LOCAL DECODER TEST")) status.visibility = View.GONE
        connectionDialog = android.app.AlertDialog.Builder(this).setTitle("Connect").setView(connectionForm)
            .setNegativeButton("Close",null).setPositiveButton("Connect",null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { connect() }
                    refreshSetupState(dialog)
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    resizeConnectionDialog()
                    dialog.window?.setLayout(minOf(dp(480),resources.displayMetrics.widthPixels-dp(24)),WindowManager.LayoutParams.WRAP_CONTENT)
                }
                dialog.show()
            }
    }
    private fun setAdvanced(show: Boolean) {
        advanced.visibility = if (show) View.VISIBLE else View.GONE
        advancedButton.text = if (show) "Hide advanced" else "Advanced"
        resizeConnectionDialog()
    }
    private fun resizeConnectionDialog() {
        if (connectionForm.parent == null) return
        connectionForm.layoutParams = connectionForm.layoutParams.apply {
            height = if (advanced.visibility == View.VISIBLE) minOf(dp(520),(resources.displayMetrics.heightPixels*0.58).toInt()) else WindowManager.LayoutParams.WRAP_CONTENT
        }
    }
    private fun showDiagnostics() {
        diagnosticsDialog = android.app.AlertDialog.Builder(this).setTitle("Diagnostics")
            .setMessage("Local decoder test uses synthetic video. It does not connect to a host.\n\nPhysical keyboard: US layout. Text transfer: explicit clipboard offer.\n\nLast status: ${status.text}")
            .setNegativeButton("Close",null).setPositiveButton("Local decoder test (no network)") { _, _ -> localDecoderTest() }.show()
    }
    private fun toggleTray() {
        if (tray.visibility == View.VISIBLE) for ((_,code) in shortcutKeys) {
            held.set(if (code == 273) 4 else 2,code,"screen:$code",false)
        }
        tray.visibility = if (tray.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        trayButton.contentDescription = if (tray.visibility == View.VISIBLE) "Hide controls" else "Show controls"
        trayButton.isSelected = tray.visibility == View.VISIBLE
    }
    private fun toggleFullscreen() {
        releaseAll(); fullscreen = !fullscreen
        window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) it.hide(WindowInsets.Type.systemBars()) else it.show(WindowInsets.Type.systemBars())
        }
        fullscreenButton.contentDescription = if (fullscreen) "Exit fullscreen" else "Enter fullscreen"
    }
    @Deprecated("Legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        ui.post { if (!isDestroyed && !isFinishing) showConnection() }
        cancelImport()
        val serial = importSerial.incrementAndGet()
        val signal = android.os.CancellationSignal().also { importSignal = it }
        message("Importing selected key...")
        importThread = Thread({
            var bytes: ByteArray? = null
            var opened: java.io.InputStream? = null
            try {
                val descriptor = contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: error("No key data")
                descriptor.use { asset ->
                    asset.createInputStream().use { stream ->
                        opened = stream; importStream.set(stream)
                        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(4096)
                        while (out.size() <= 65536) {
                            signal.throwIfCanceled()
                            val n = stream.read(chunk,0,minOf(chunk.size,65537-out.size()))
                            if (n < 0) break
                            out.write(chunk,0,n)
                        }
                        bytes = out.toByteArray(); chunk.fill(0)
                    }
                }
                signal.throwIfCanceled()
                if (serial == importSerial.get()) keys.save(bytes!!) { serial == importSerial.get() && !signal.isCanceled }
                ui.post { if (serial == importSerial.get() && foreground && !isDestroyed) {
                    keyButton.text = "Replace imported key"; status.text = "Key imported into encrypted app storage"
                } }
            } catch (_: Exception) {
                ui.post { if (serial == importSerial.get() && foreground && !isDestroyed) status.text = "Key import failed (maximum 64 KiB)" }
            } finally { bytes?.fill(0); importStream.compareAndSet(opened,null) }
        },"gliff-key-import").apply { start() }
    }
    private fun cancelImport() {
        importSerial.incrementAndGet()
        val signal = importSignal; importSignal = null
        val stream = importStream.getAndSet(null)
        importThread?.interrupt()
        if (signal != null || stream != null) Thread({ signal?.cancel(); try { stream?.close() } catch (_: Exception) { } },"gliff-import-cancel").start()
    }

    private fun connect(nativeCloseDeadline: Long = 0L) {
        if (importThread?.isAlive == true) { message("Wait for key import to finish"); return }
        if (worker?.isAlive == true) { message("Wait for previous session to stop"); return }
        if (!NativeCore.available || !surface.holder.surface.isValid) { message("Native core or display unavailable"); return }
        val parsed = ConnectionDestination.parse(destination.text.toString())
        if (parsed == null) { message("Enter a destination as user@host"); return }
        val hostname = parsed.host; val username = parsed.user
        host.setText(hostname); user.setText(username)
        val number = port.text.toString().toIntOrNull()
        if (number == null || number !in 1..65535) { setAdvanced(true); message("Enter a valid SSH port in Advanced"); return }
        val fingerprint = savedHostPin(hostname,number)
        val useKey = useImportedKey.isChecked
        val privateMode = privateScreen.isChecked
        val outputName = selectedOutput
        if (useKey && !keys.exists) { setAdvanced(true); message("Import a key or turn off Use imported SSH key for Tailscale SSH."); return }
        getSharedPreferences("connection",MODE_PRIVATE).edit().putBoolean("use_imported_key",useKey).putString("host",hostname).putString("port",number.toString()).putString("user",username).remove("pin").apply()
        activeSessionLabel = "$username@$hostname"
        activeOutput = outputName; activePrivate = privateMode
        activeHost = hostname; activeUser = username; activePort = number; activeUseKey = useKey; pendingActions.clear(); remoteDesktop = null; flexControls.clearRemoteState(); desktopViewport.reset()
        running = AtomicBoolean(true); val active = running; val token = state.begin()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(destination.windowToken,0)
        localTest = false; connectionDialog?.dismiss(); surface.requestFocus(); cover.visibility = View.VISIBLE; cursorOverlay.reset(); pointerKnown = false; lastPointerCommandMs = 0; refreshChrome()
        message("Connecting…",token)
        val initialWidth = display.width; val initialHeight = desktopHeight()
        val metrics = StreamDiagnostics().apply { start() }
        val cursors = CursorUpdates(cursorOverlay,{ state.current(token) }) { x,y,visible ->
            if (visible && android.os.SystemClock.elapsedRealtime()-lastPointerCommandMs > 150) {
                pointerGeometry = state.displayed(); pointerX = x; pointerY = y; pointerKnown = true
            }
        }
        worker = Thread({
            var decoder: AvcDecoder? = null
            val frames = FrameTracker()
            val renderTokens = RenderTokens()
            var config: StreamConfiguration? = null
            var geometry: SessionState.Geometry? = null
            var decoderSurfaceVersion = surfaceVersion.get()
            var surfaceMissingSince = 0L
            var session = 0L
            var discoveredPin: String? = null
            try {
                metrics.phase = "native-open"
                val caps = AvcDecoder.capabilities(); decoderCaps = caps
                val bytes = if (useKey) keys.read() else ByteArray(0)
                try {
                    do {
                        session = if (outputName != null && !privateMode) NativeCore.openSessionOnOutput(hostname,number,username,bytes.toString(Charsets.UTF_8),"",fingerprint,caps.first,caps.second,outputName)
                        else if (privateMode) NativeCore.openSessionWithMode(hostname,number,username,bytes.toString(Charsets.UTF_8),"",fingerprint,caps.first,caps.second,true)
                        else NativeCore.openSession(hostname,number,username,bytes.toString(Charsets.UTF_8),"",fingerprint,caps.first,caps.second)
                        if (session != 0L || android.os.SystemClock.elapsedRealtime() >= nativeCloseDeadline) break
                        Thread.sleep(25)
                    } while (active.get() && state.current(token))
                } finally { bytes.fill(0) }
                check(session > 0) { "Session could not start" }
                if (!state.current(token)) return@Thread
                handle = session
                fun freshDecoder() = AvcDecoder(surface.holder.surface, { pts ->
                    // Output identity is independent of transport flow credit. MediaCodec can
                    // retain reference/reorder frames beyond the server's byte window.
                    frames.processed(pts)
                    metrics.pendingOutput = frames.pendingCount()
                }, { pts ->
                    metrics.rendered()
                    val generation = renderTokens.rendered(pts)
                    metrics.pendingRender = renderTokens.pendingCount()
                    // Already delivered on the main thread by MediaCodec.
                    if (generation != null && foreground && state.current(token)) {
                        state.rendered(token,generation)?.let { geometry ->
                            fitSurface(geometry)
                            // Keep the cover through the layout traversal so new input and pixels agree.
                            surface.postOnAnimation { if (state.pending(token,generation) && state.displayed() == geometry) { cover.visibility = View.GONE; refreshChrome() } }
                        }
                    }
                },metrics)
                fun requestKeyframe() { if (control(7)) metrics.keyframe() }
                sendResize(initialWidth,initialHeight)
                while (active.get() && state.current(token)) {
                    if (approvalBrowserToken == token && (!foreground || !surface.holder.surface.isValid)) {
                        check(android.os.SystemClock.elapsedRealtime() < approvalDeadline) { "Tailscale approval timed out" }
                        Thread.sleep(10); continue
                    }
                    if (!surface.holder.surface.isValid) {
                        if(surfaceMissingSince == 0L) surfaceMissingSince = android.os.SystemClock.elapsedRealtime()
                        check(android.os.SystemClock.elapsedRealtime()-surfaceMissingSince < 3000) { "Display surface did not return" }
                        metrics.phase = "waiting-surface"; Thread.sleep(10); continue
                    }
                    surfaceMissingSince = 0L
                    if(decoderSurfaceVersion != surfaceVersion.get()) {
                        decoder?.close(); decoder = null
                        metrics.discarded(frames.pendingCount()); frames.reset(); renderTokens.reset()
                        metrics.pendingOutput = 0; metrics.pendingRender = 0
                        decoderSurfaceVersion = surfaceVersion.get()
                        config?.let { saved ->
                            geometry = state.configure(token,saved.width,saved.height,saved.scale,saved.viewWidth,saved.viewHeight)
                            post(token) { releaseAll(); cover.visibility = View.VISIBLE; refreshChrome() }
                            decoder = freshDecoder(); decoder!!.configure(saved.width,saved.height); requestKeyframe()
                        }
                    }
                    metrics.phase = "poll-event"
                    val event = NativeCore.pollEvent(session)
                    if (event == null) { decoder?.pump(); metrics.phase = "waiting-video"; Thread.sleep(5); continue }
                    require(event.isNotEmpty())
                    val b = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
                    when (b.get().toInt()) {
                        1 -> {
                            val text = event.copyOfRange(1,event.size).toString(Charsets.UTF_8)
                            if (text == "Connected") {
                                state.connected(token)
                                post(token) { clearApproval(); queryDesktop(); ui.removeCallbacks(desktopRefresh); ui.postDelayed(desktopRefresh,5000) }
                            }
                            message(text,token)
                        }
                        2 -> {
                            require(event.size == 25)
                            val next = StreamConfiguration(b.int,b.int,b.int,b.int,b.int,b.int)
                            metrics.config(next); metrics.phase = "configuration"
                            val resetDecoder = next.resetsDecoder(config)
                            if (next.changesGeometry(config)) {
                                val replacement = state.configure(token,next.width,next.height,next.scale,next.viewWidth,next.viewHeight)
                                geometry = replacement
                                val prepared = java.util.concurrent.CountDownLatch(1)
                                post(token) { if (state.pending(token,replacement.generation)) { releaseAll(); cover.visibility = View.VISIBLE }; prepared.countDown() }
                                check(prepared.await(2,java.util.concurrent.TimeUnit.SECONDS)) { "Display transition timed out" }
                            }
                            if (resetDecoder || decoder == null) {
                                metrics.discarded(frames.pendingCount())
                                frames.reset(); renderTokens.reset(); metrics.pendingOutput = 0; metrics.pendingRender = 0
                                decoder?.close(); decoder = freshDecoder()
                                decoder!!.configure(next.width,next.height)
                                requestKeyframe()
                            }
                            config = next
                        }
                        3 -> {
                            metrics.received(); metrics.phase = "video"
                            require(event.size > 18); val id = b.long; b.long; b.get()
                            val generation = checkNotNull(geometry) { "Video before stream configuration" }.generation
                            val pts = frames.submit(id); renderTokens.submit(pts,generation)
                            val admitted = checkNotNull(decoder).frame(event.copyOfRange(18,event.size),pts)
                            if (!admitted) {
                                // Deliberate keyframe recovery discard, never successful decoding.
                                metrics.rejected(); frames.processed(pts); renderTokens.discard(pts)
                            }
                            // Exactly one flow ACK after bounded codec admission (or explicit
                            // recovery discard), never again from output/render callbacks.
                            metrics.phase = "queue-flow-ack"
                            if (state.current(token) && control(6,c=id.toInt(),d=(id ushr 32).toInt())) metrics.ack()
                            if (!admitted) requestKeyframe()
                            metrics.pendingOutput = frames.pendingCount(); metrics.pendingRender = renderTokens.pendingCount()
                        }
                        4 -> {
                            val text = event.copyOfRange(1,event.size).toString(Charsets.UTF_8)
                            post(token) { if (foreground && state.ready(token)) {
                                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Remote text",text))
                                status.text = "Remote text copied to local clipboard"
                            } }
                        }
                        5 -> { message(event.copyOfRange(1,event.size).toString(Charsets.UTF_8),token); active.set(false); state.end(token) }
                        6 -> { message("Remote session closed",token); active.set(false); state.end(token) }
                        7 -> {
                            val url = event.copyOfRange(1,event.size).toString(Charsets.UTF_8)
                            require(HostIdentity.approvalUrl(url)) { "Unsupported Tailscale approval link" }
                            post(token) { if (foreground && state.current(token) && !state.ready(token)) showApproval(token,url) }
                        }
                        11 -> {
                            val snapshot = runCatching { RemoteDesktopState.parse(event.copyOfRange(1,event.size).toString(Charsets.UTF_8)) }.getOrNull()
                            post(token) { if(snapshot != null && state.ready(token)) { remoteDesktop = snapshot; flexControls.applyRemoteState(snapshot) } }
                        }
                        12 -> {
                            if(event.size >= 6) {
                                val kind = b.int; val success = b.get().toInt() == 1
                                val error = if(event.size > 6) event.copyOfRange(6,event.size).toString(Charsets.UTF_8) else null
                                post(token) { remoteCompleted(kind,success,error) }
                            }
                        }
                        9 -> cursors.image(event)
                        10 -> cursors.position(event)
                        8 -> {
                            require(fingerprint.isEmpty()) { "Unexpected host identity replacement" }
                            val pin = event.copyOfRange(1,event.size).toString(Charsets.UTF_8)
                            require(HostIdentity.validPin(pin)) { "Invalid host fingerprint" }
                            discoveredPin = pin; active.set(false); state.end(token)
                        }
                    }
                }
            } catch (_: InterruptedException) { message("Disconnected",token) }
            catch (_: LinkageError) { message("Native session bridge unavailable; install a complete matching build",token) }
            catch (e: Exception) { message("Session stopped: ${e.message ?: e.javaClass.simpleName}",token) }
            finally {
                metrics.phase = "closing"; metrics.stop(); cursors.clear()
                state.end(token); active.set(false)
                try { decoder?.close() } catch (_: Exception) { }
                if (handle == session) handle = 0
                if (session != 0L) NativeCore.closeSession(session)
                post(token) {
                    ui.removeCallbacks(desktopRefresh); pendingActions.clear(); textCompletion = null; flexControls.clearRemoteState()
                    held.clear(); cover.visibility = View.VISIBLE; cursorOverlay.reset(); localTest = false; clearApproval(); refreshChrome()
                    discoveredPin?.let { if (foreground) showHostTrust(token,username,hostname,number,it,useKey,privateMode,outputName) }
                }
            }
        }, "gliff-session").apply { start() }
    }

    private fun performRemote(kind: Int,identity: String): Boolean {
        if (!foreground || !state.ready(state.token()) || handle == 0L) return false
        if (kind in pendingActions) { if(kind != 1) message("Previous action is still running"); return false }
        if(kind == 15 && (identity.indexOf('\u0000') >= 0 || identity.toByteArray(Charsets.UTF_8).size > 16384)) { message("Text exceeds the remote input limit"); return false }
        val accepted = try { NativeCore.remoteAction(handle,kind,identity) } catch (_: LinkageError) { false }
        if(accepted) pendingActions.add(kind) else if(kind != 1) message("Remote action unavailable or busy")
        return accepted
    }
    private fun queryDesktop() { performRemote(1,"") }
    private fun remoteCompleted(kind: Int,success: Boolean,error: String?) {
        if(!pendingActions.remove(kind)) return
        if(kind == 15) { flexControls.insertionFinished(success,error); textCompletion?.invoke(success,error); textCompletion = null }
        if(!success && kind != 1) message(error?.take(256) ?: "Remote action failed")
        if(success && kind in 2..5) queryDesktop()
    }
    private fun selectDisplay() {
        if(!inputReady()) return
        if(activePrivate) { message("Display selection is available when mirroring"); return }
        val outputs = remoteDesktop?.outputs.orEmpty()
        if(outputs.isEmpty()) { queryDesktop(); message("No remote displays reported yet"); return }
        android.app.AlertDialog.Builder(this).setTitle("Remote display")
            .setItems(outputs.map { "${it.name} · ${it.width} × ${it.height}" }.toTypedArray()) { _,index ->
                selectedOutput = outputs[index].name
                destination.setText("$activeUser@$activeHost"); port.setText(activePort.toString())
                useImportedKey.isChecked = activeUseKey; privateScreen.isChecked = false
                stopSession("Switching display")
                val stopped = state.token(); val deadline = android.os.SystemClock.elapsedRealtime()+2000
                fun reconnect() {
                    if(!foreground || state.token() != stopped) return
                    if(worker?.isAlive == true || handle != 0L) {
                        if(android.os.SystemClock.elapsedRealtime() < deadline) ui.postDelayed({ reconnect() },25)
                        else message("Previous session is still closing; tap Connect again")
                    } else connect(deadline)
                }
                reconnect()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun systemTextInput() {
        if(!inputReady()) return
        val token = state.token()
        val input = EditText(this).apply { hint = "Text to type in the focused remote app"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Type remote text").setView(input)
            .setNegativeButton("Cancel",null).setPositiveButton("Insert",null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(state.current(token) && performRemote(15,input.text.toString())) { input.text.clear(); dialog.dismiss() }
        } }
        dialog.show(); input.requestFocus(); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
    private fun beginDictation(seed: String) {
        if(!inputReady()) return
        releaseAll(); voiceDraft = seed
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { voice.start(seed); return }
        microphoneToken = state.token(); microphonePermission = true
        ui.removeCallbacks(microphoneTimeout); ui.postDelayed(microphoneTimeout,30000)
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),44)
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode != 44) return
        microphonePermission = false; ui.removeCallbacks(microphoneTimeout)
        if(!state.current(microphoneToken)) return
        if(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(foreground && state.ready(microphoneToken)) voice.start(voiceDraft) else startVoiceWhenResumed = true
        } else flexControls.onVoiceResult(voiceDraft,false,"Microphone permission denied")
    }
    @Deprecated("Legacy Activity back dispatch")
    override fun onBackPressed() { if(flexControls.hasDictation()) flexControls.closeDictation() else super.onBackPressed() }
    private fun savedHostPin(hostname: String, number: Int): String {
        val trust = getSharedPreferences("host_trust",MODE_PRIVATE)
        val key = HostIdentity.key(hostname,number)
        trust.getString(key,null)?.let { return if (HostIdentity.validPin(it)) it else "" }
        // Preserve explicit trust entered in earlier versions for this same endpoint only.
        val old = getSharedPreferences("connection",MODE_PRIVATE)
        val oldHost = old.getString("host","")!!; val oldPort = old.getString("port","22")!!.toIntOrNull()
        val oldPin = old.getString("pin","")!!
        if (oldPort == number && HostIdentity.key(oldHost,number) == key && HostIdentity.validPin(oldPin)) {
            trust.edit().putString(key,oldPin).apply(); return oldPin
        }
        return ""
    }
    private fun showHostTrust(token: Long, username: String, hostname: String, number: Int, fingerprint: String, useKey: Boolean, privateMode: Boolean, outputName: String?) {
        if (state.token() != token || !foreground) return
        trustDialog?.dismiss()
        trustDialog = android.app.AlertDialog.Builder(this).setTitle("Trust this host?")
            .setMessage("$hostname:$number\n\n$fingerprint\n\nRemember this host identity for future connections?")
            .setNegativeButton("Cancel") { _, _ -> stopSession("Connection cancelled") }
            .setOnCancelListener { stopSession("Connection cancelled") }
            .setPositiveButton("Trust and connect") { _, _ ->
                if (state.token() == token && foreground) {
                    getSharedPreferences("host_trust",MODE_PRIVATE).edit().putString(HostIdentity.key(hostname,number),fingerprint).apply()
                    destination.setText("$username@$hostname"); port.setText(number.toString())
                    useImportedKey.isChecked = useKey; privateScreen.isChecked = privateMode; selectedOutput = outputName
                    val deadline = android.os.SystemClock.elapsedRealtime()+2000
                    fun retryWhenClosed() {
                        if (state.token() != token || !foreground) return
                        if (worker?.isAlive == true || handle != 0L) {
                            if (android.os.SystemClock.elapsedRealtime() < deadline) ui.postDelayed({ retryWhenClosed() },25)
                            else message("Previous session is still closing; tap Connect again")
                        } else connect(deadline)
                    }
                    retryWhenClosed()
                }
            }.show()
    }
    private fun showApproval(token: Long, url: String) {
        if (!state.current(token) || state.ready(token) || !foreground || !HostIdentity.approvalUrl(url)) return
        approvalUrl = url; approvalDialog?.dismiss()
        approvalDialog = android.app.AlertDialog.Builder(this).setTitle("Approve Tailscale SSH")
            .setMessage("Your tailnet requires approval. Continue in your browser, then return here.")
            .setNegativeButton("Cancel") { _, _ -> stopSession("Connection cancelled") }
            .setOnCancelListener { stopSession("Connection cancelled") }
            .setPositiveButton("Open browser") { _, _ ->
                if (foreground && state.current(token) && !state.ready(token)) {
                    approvalBrowserToken = token; approvalExcursion = true
                    approvalDeadline = android.os.SystemClock.elapsedRealtime()+120000
                    ui.removeCallbacks(approvalTimeout); ui.postDelayed(approvalTimeout,120000)
                    try { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: Exception) { approvalExcursion = false; stopSession("Could not open approval browser") }
                }
            }.show()
    }
    private fun clearApproval() {
        ui.removeCallbacks(approvalTimeout); approvalBrowserToken = 0; approvalExcursion = false
        approvalDeadline = 0; approvalUrl = null; approvalDialog?.dismiss(); approvalDialog = null
    }
    private fun localDecoderTest() {
        if (worker?.isAlive == true || !surface.holder.surface.isValid) { message("Stop the current session first"); return }
        running = AtomicBoolean(true); val active = running; val token = state.begin()
        localTest = true; connectionDialog?.dismiss(); diagnosticsDialog?.dismiss(); surface.requestFocus(); refreshChrome()
        cover.visibility = View.VISIBLE; message("LOCAL DECODER TEST: synthetic video, no network session",token)
        worker = Thread({
            var decoder: AvcDecoder? = null
            var outputs = 0
            val rendered = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                decoder = AvcDecoder(surface.holder.surface, { outputs++ }, {
                    if (state.current(token) && foreground) {
                        rendered.incrementAndGet()
                        fitSurface(SessionState.Geometry(0,640,360,1000))
                        surface.postOnAnimation { if (state.current(token)) { cover.visibility = View.GONE; refreshChrome() } }
                    }
                })
                decoder.configure(640,360)
                val frames = DecoderFixture.accessUnits(assets)
                for ((n,frame) in frames.withIndex()) {
                    if (!active.get()) throw InterruptedException()
                    check(decoder.frame(frame,n*100000L)) { "Synthetic IDR setup failed" }
                    Thread.sleep(100)
                    decoder.pump()
                }
                val deadline = android.os.SystemClock.elapsedRealtime()+1000
                while (outputs < frames.size && android.os.SystemClock.elapsedRealtime() < deadline && active.get()) {
                    decoder.pump(); Thread.sleep(5)
                }
                check(outputs == frames.size && rendered.get() > 0) { "Decoded $outputs/${frames.size} frames; rendered ${rendered.get()}" }
                message("LOCAL DECODER TEST PASSED: $outputs synthetic frames decoded, surface rendering observed. No network session.",token)
            } catch (_: InterruptedException) { message("Local decoder test stopped. No network session.",token) }
            catch (e: Exception) { message("LOCAL DECODER TEST FAILED: ${e.message}",token) }
            finally {
                try { decoder?.close() } catch (_: Exception) { }
                active.set(false); state.end(token)
                post(token) { cover.visibility = View.VISIBLE; localTest = false; refreshChrome() }
            }
        },"gliff-local-decoder-test").apply { start() }
    }
    private fun sendResize(width: Int, height: Int) {
        if (handle == 0L || width <= 0 || height <= 0) return
        val caps = decoderCaps ?: return
        val fitted = Viewport.fit(width,height,caps.first,caps.second,minOf(caps.first.toLong()*caps.second,3840L*2160))
        if (!AvcDecoder.supportsSize(fitted.first,fitted.second)) {
            stopSession("AVC decoder does not support fitted viewport ${fitted.first} x ${fitted.second}")
            return
        }
        control(1,fitted.first.toDouble(),fitted.second.toDouble(),1000)
    }
    private fun control(kind: Int, a: Double = 0.0, b: Double = 0.0, c: Int = 0, d: Int = 0): Boolean {
        if (kind in 2..5 && !state.ready(state.token())) return false
        val h = handle
        if (h == 0L) return false
        val queued = NativeCore.sendControl(h,kind,a,b,c,d)
        if (!queued) stopSession("Control queue rejected input; disconnected to release held keys")
        return queued
    }
    private fun stopSession(reason: String) {
        microphonePermission = false; startVoiceWhenResumed = false; ui.removeCallbacks(microphoneTimeout)
        state.stop(); val stoppedToken = state.token(); running.set(false)
        post(stoppedToken) {
            trustDialog?.dismiss(); trustDialog = null; clearApproval(); ui.removeCallbacks(desktopRefresh)
            pendingActions.clear(); textCompletion = null
            if(::flexControls.isInitialized) flexControls.clearRemoteState()
        }
        val h = handle; handle = 0
        if (h != 0L) { NativeCore.sendControl(h,8,0.0,0.0,0,0); NativeCore.closeSession(h) }
        worker?.interrupt()
        post(state.token()) { held.clear(); gestureLeft = false; scrolling = false; cover.visibility = View.VISIBLE; cursorOverlay.reset(); localTest = false; status.text = reason; refreshChrome() }
    }
    private fun sendText() {
        val token = state.token(); val session = handle
        if (!state.ready(token)) { message("Wait for Connected before offering text"); return }
        val input = EditText(this).apply { hint = "Explicit text offer (remote may request it)" }
        android.app.AlertDialog.Builder(this).setTitle("Offer text to remote clipboard").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Offer") { _, _ ->
            if (foreground && state.ready(token) && handle == session) {
                if (!NativeCore.setClipboard(session,input.text.toString())) message("Text offer rejected (maximum 256 KiB)",token)
                else message("Text offered. Use the remote app's paste shortcut.",token)
            }
            input.text.clear()
        }.show()
    }
    private fun desktopHeight() = if (desktopPane.height() > 0) desktopPane.height() else display.height
    private fun inputReady() = foreground && state.ready(state.token()) && state.displayed() != null && cover.visibility != View.VISIBLE
    private fun updateFlexLayout() {
        if (!::flexControls.isInitialized || display.width <= 0 || display.height <= 0) return
        val location = IntArray(2); display.getLocationInWindow(location)
        val hinge = tabletopHinge
        var top = hinge?.top?.minus(location[1]) ?: -1
        var bottom = hinge?.bottom?.minus(location[1]) ?: -1
        var actual = hinge != null && top >= dp(96) && bottom >= top && display.height-bottom >= dp(144)
        if (!actual && manualFlex.isChecked) { top = display.height/2; bottom = top }
        if (!actual && innerDisplay) { top = display.height/2; bottom = top }
        val active = actual || innerDisplay || manualFlex.isChecked
        val upper = if (active) android.graphics.Rect(0,0,display.width,top) else android.graphics.Rect(0,0,display.width,display.height)
        val lower = if (active) android.graphics.Rect(0,bottom,display.width,display.height) else android.graphics.Rect()
        val changed = upper != desktopPane || lower != flexPane || flexActive != active
        if (changed) { releaseAll(); desktopPane = upper; flexPane = lower; flexActive = active }
        flexControls.visibility = if (active) View.VISIBLE else View.GONE
        flexControls.setPosture(actual && reportedTabletop,manualFlex.isChecked && !(actual && reportedTabletop))
        if (active) flexControls.layoutParams = FrameLayout.LayoutParams(lower.width(),lower.height(),Gravity.TOP or Gravity.LEFT).apply { topMargin = lower.top }
        desktopViewport.layoutParams = FrameLayout.LayoutParams(upper.width(),upper.height(),Gravity.TOP or Gravity.LEFT)
        val coverParams = FrameLayout.LayoutParams(upper.width(),upper.height(),Gravity.TOP or Gravity.LEFT)
        cover.layoutParams = coverParams
        idleLabel.layoutParams = FrameLayout.LayoutParams(upper.width(),upper.height(),Gravity.TOP or Gravity.LEFT)
        (tray.layoutParams as FrameLayout.LayoutParams).let { params ->
            val margin = display.height-upper.bottom+dp(8)
            if (params.bottomMargin != margin) { params.bottomMargin = margin; tray.layoutParams = params }
        }
        state.displayed()?.let { fitSurface(it) }
        if (changed) {
            android.util.Log.i("GliffFlex","active=$active source=${if (actual && reportedTabletop) "horizontal-half-opened" else if (innerDisplay) "flat-inner" else if (active) "manual" else "flat"} desktop=${upper.width()}x${upper.height()} lowerTop=${lower.top} lowerHeight=${lower.height()}")
            ui.removeCallbacks(resize); ui.postDelayed(resize,200)
        }
    }
    private fun fitSurface(geometry: SessionState.Geometry) { desktopViewport.fit(geometry) }
    private fun remoteTap(x: Float,y: Float) {
        val geometry = state.displayed() ?: return
        if(!inputReady()) return
        val position = PointerMapping.remote(x.toDouble(),y.toDouble(),surface.width,surface.height,geometry) ?: return
        pointerGeometry = geometry; pointerX = position.first; pointerY = position.second; pointerKnown = true
        lastPointerCommandMs = android.os.SystemClock.elapsedRealtime()
        control(3,pointerX,pointerY); held.set(4,272,"desktop-tap",true); held.set(4,272,"desktop-tap",false)
    }
    private fun relativeMotion(dx: Float, dy: Float, trackpadWidth: Int) {
        val geometry = state.displayed() ?: return
        if (!inputReady() || !flexActive || trackpadWidth <= 0) return
        if (pointerGeometry != geometry) {
            pointerGeometry = geometry
            if (!pointerKnown) { pointerX = geometry.width*500.0/geometry.scale; pointerY = geometry.height*500.0/geometry.scale; pointerKnown = true }
        }
        val logicalWidth = geometry.width*1000.0/geometry.scale
        val logicalHeight = geometry.height*1000.0/geometry.scale
        val gain = logicalWidth/trackpadWidth
        pointerX = (pointerX+dx*gain).coerceIn(0.0,maxOf(0.0,logicalWidth-1))
        pointerY = (pointerY+dy*gain).coerceIn(0.0,maxOf(0.0,logicalHeight-1))
        lastPointerCommandMs = android.os.SystemClock.elapsedRealtime()
        control(3,pointerX,pointerY)
    }
    private fun touch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) { releaseAll(); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (gestureLeft) held.set(4,272,"gesture",false)
            if (scrolling) control(5,c=0,d=1)
            gestureLeft = false; scrolling = false; return true
        }
        val geometry = state.displayed() ?: return true
        if (cover.visibility == View.VISIBLE) return true
        if (flexActive) {
            val position = IntArray(2); display.getLocationOnScreen(position)
            if (!desktopPane.contains((e.rawX-position[0]).toInt(),(e.rawY-position[1]).toInt())) return true
        }
        val remote = PointerMapping.remote(e.x.toDouble(),e.y.toDouble(),surface.width,surface.height,geometry) ?: return true
        pointerGeometry = geometry; pointerX = remote.first; pointerY = remote.second; pointerKnown = true
        lastPointerCommandMs = android.os.SystemClock.elapsedRealtime()
        control(3,remote.first,remote.second)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { gestureLeft = true; held.set(4,272,"gesture",true) }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { held.set(4,272,"gesture",false); gestureLeft = false; scrolling = true; scrollY = e.y }
        if (e.pointerCount >= 2 && e.actionMasked == MotionEvent.ACTION_MOVE) { control(5,(scrollY-e.y).toDouble(),c=0); scrollY = e.y }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && scrolling) { control(5,c=0,d=1); scrolling = false }
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!state.ready(state.token()) || state.displayed() == null || cover.visibility == View.VISIBLE || flexControls.hasDictation() || currentFocus is EditText) return super.dispatchKeyEvent(event)
        val code = keyMap[event.keyCode] ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) held.set(2,code,"physical:${event.keyCode}",true)
        if (event.action == KeyEvent.ACTION_UP) held.set(2,code,"physical:${event.keyCode}",false)
        return true
    }
    override fun onStart() { super.onStart(); foldObserver.start() }
    override fun onStop() { foldObserver.stop(); super.onStop() }
    override fun surfaceCreated(holder: SurfaceHolder) { surfaceVersion.incrementAndGet() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceVersion.incrementAndGet(); releaseAll(); cover.visibility = View.VISIBLE
        // A fold can recreate the Surface without changing the authenticated session.
        // The worker waits at most three seconds, then recreates only its decoder.
    }
    override fun onResume() {
        super.onResume(); foreground = true; ui.removeCallbacks(backgroundTimeout)
        if(startVoiceWhenResumed && state.current(microphoneToken) && state.ready(microphoneToken)) { startVoiceWhenResumed = false; voice.start(voiceDraft) }
        if (approvalBrowserToken != 0L && android.os.SystemClock.elapsedRealtime() >= approvalDeadline) {
            stopSession("Tailscale approval timed out"); return
        }
        if (approvalExcursion) {
            approvalExcursion = false
            val token = approvalBrowserToken
            if (state.current(token) && !state.ready(token) && android.os.SystemClock.elapsedRealtime() < approvalDeadline) {
                approvalUrl?.let { showApproval(token,it) }
            }
        }
    }
    override fun onPause() {
        foreground = false; cancelImport(); voice.cancel()
        if(!microphonePermission) flexControls.closeDictation()
        releaseAll()
        if (!(microphonePermission && state.current(microphoneToken)) && !(approvalExcursion && state.current(approvalBrowserToken) && !state.ready(approvalBrowserToken))) {
            ui.removeCallbacks(backgroundTimeout); ui.postDelayed(backgroundTimeout,1500)
        }
        super.onPause()
    }
    override fun onDestroy() { ui.removeCallbacks(backgroundTimeout); voice.cancel(); ui.removeCallbacks(microphoneTimeout); ui.removeCallbacks(desktopRefresh); trustDialog?.dismiss(); clearApproval(); connectionDialog?.dismiss(); diagnosticsDialog?.dismiss(); ui.removeCallbacks(resize); stopSession("Disconnected"); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) releaseAll() }
    companion object {
        private val shortcutKeys = listOf("Esc" to 1,"Tab" to 15,"Ctrl" to 29,"Alt" to 56,"Super" to 125,"Right" to 273)
        private val keyMap = buildMap<Int,Int> {
            val letters = intArrayOf(30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)
            letters.forEachIndexed { i,c -> put(KeyEvent.KEYCODE_A+i,c) }
            for (i in 0..8) put(KeyEvent.KEYCODE_1+i,2+i)
            put(KeyEvent.KEYCODE_0,11)
            putAll(mapOf(KeyEvent.KEYCODE_ENTER to 28,KeyEvent.KEYCODE_DEL to 14,KeyEvent.KEYCODE_SPACE to 57,KeyEvent.KEYCODE_TAB to 15,KeyEvent.KEYCODE_ESCAPE to 1,KeyEvent.KEYCODE_CTRL_LEFT to 29,KeyEvent.KEYCODE_CTRL_RIGHT to 97,KeyEvent.KEYCODE_ALT_LEFT to 56,KeyEvent.KEYCODE_ALT_RIGHT to 100,KeyEvent.KEYCODE_SHIFT_LEFT to 42,KeyEvent.KEYCODE_SHIFT_RIGHT to 54,KeyEvent.KEYCODE_META_LEFT to 125,KeyEvent.KEYCODE_DPAD_UP to 103,KeyEvent.KEYCODE_DPAD_DOWN to 108,KeyEvent.KEYCODE_DPAD_LEFT to 105,KeyEvent.KEYCODE_DPAD_RIGHT to 106,KeyEvent.KEYCODE_FORWARD_DEL to 111,KeyEvent.KEYCODE_MOVE_HOME to 102,KeyEvent.KEYCODE_MOVE_END to 107,KeyEvent.KEYCODE_PAGE_UP to 104,KeyEvent.KEYCODE_PAGE_DOWN to 109,KeyEvent.KEYCODE_MINUS to 12,KeyEvent.KEYCODE_EQUALS to 13,KeyEvent.KEYCODE_LEFT_BRACKET to 26,KeyEvent.KEYCODE_RIGHT_BRACKET to 27,KeyEvent.KEYCODE_BACKSLASH to 43,KeyEvent.KEYCODE_SEMICOLON to 39,KeyEvent.KEYCODE_APOSTROPHE to 40,KeyEvent.KEYCODE_GRAVE to 41,KeyEvent.KEYCODE_COMMA to 51,KeyEvent.KEYCODE_PERIOD to 52,KeyEvent.KEYCODE_SLASH to 53))
        }
    }
}
