package dev.omarchy.gliff

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Aggregate pipeline metadata only. Never records peer, keys, text, URLs or frame bytes. */
class StreamDiagnostics {
    private val handler = Handler(Looper.getMainLooper())
    private val received = AtomicLong()
    private val accepted = AtomicLong()
    private val rejected = AtomicLong()
    private val discarded = AtomicLong()
    private val outputs = AtomicLong()
    private val acks = AtomicLong()
    private val renders = AtomicLong()
    private val requests = AtomicLong()
    private val configs = AtomicLong()
    private val start = SystemClock.elapsedRealtime()
    @Volatile var phase = "starting"
    @Volatile var lowLatency = "unknown"
    @Volatile var codec = "none"
    @Volatile var outputFormat = "none"
    @Volatile var pendingOutput = 0
    @Volatile var pendingRender = 0
    @Volatile private var dimensions = "none"
    @Volatile private var lastReceived = start
    @Volatile private var lastAccepted = start
    @Volatile private var lastOutput = start
    @Volatile private var lastAck = start
    @Volatile private var active = false
    private val tick = object : Runnable {
        override fun run() { if (active) { report(); handler.postDelayed(this,1000) } }
    }
    fun start() { active = true; handler.post(tick) }
    fun stop() { active = false; handler.removeCallbacks(tick); report() }
    fun received() { received.incrementAndGet(); lastReceived = SystemClock.elapsedRealtime() }
    fun accepted() { accepted.incrementAndGet(); lastAccepted = SystemClock.elapsedRealtime() }
    fun rejected() { rejected.incrementAndGet() }
    fun discarded(count: Int) { discarded.addAndGet(count.toLong()) }
    fun output() { outputs.incrementAndGet(); lastOutput = SystemClock.elapsedRealtime() }
    fun ack() { acks.incrementAndGet(); lastAck = SystemClock.elapsedRealtime() }
    fun rendered() { renders.incrementAndGet() }
    fun renderedCount() = renders.get()
    fun keyframe() { requests.incrementAndGet() }
    fun config(c: StreamConfiguration) {
        configs.incrementAndGet()
        dimensions = "${c.width}x${c.height},view=${c.viewWidth}x${c.viewHeight},scale=${c.scale},fps=${c.fps}"
    }
    private fun report() {
        val now = SystemClock.elapsedRealtime()
        Log.i("GliffStream", "ms=${now-start} phase=$phase ackMode=bounded-input rx=${received.get()} input=${accepted.get()} rejected=${rejected.get()} discarded=${discarded.get()} pendingOutput=$pendingOutput pendingRender=$pendingRender output=${outputs.get()} ackQueued=${acks.get()} rendered=${renders.get()} configs=${configs.get()} keyRequests=${requests.get()} lowLatency=$lowLatency codec=$codec format=$outputFormat config=$dimensions rxAge=${now-lastReceived} inputAge=${now-lastAccepted} outputAge=${now-lastOutput} ackAge=${now-lastAck}")
    }
}
