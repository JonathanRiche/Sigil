package dev.omarchy.gliff

import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One pending shape and position per session; pointer motion cannot flood the main queue. */
class CursorUpdates(
    private val overlay: RemoteCursorOverlay,
    private val current: () -> Boolean,
    private val observed: (Double,Double,Boolean) -> Unit
) {
    private data class Shape(val id: Int,val width: Int,val height: Int,val hotX: Int,val hotY: Int,val scale: Int,val packet: ByteArray)
    private data class Position(val id: Int,val x: Double,val y: Double,val visible: Boolean,val scale: Int)
    private val shape = AtomicReference<Shape?>()
    private val position = AtomicReference<Position?>()
    private val scheduled = AtomicBoolean(false)
    private var lastPositionLog = 0L
    private var positionCount = 0L
    private val handler = Handler(Looper.getMainLooper())
    fun image(packet: ByteArray) {
        require(packet.size >= 25)
        val b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN); require(b.get().toInt() == 9)
        val id = b.int; val w = b.int; val h = b.int; val hotX = b.int; val hotY = b.int; val scale = b.int
        require(w in 1..1024 && h in 1..1024 && hotX in 0 until w && hotY in 0 until h && scale in 1..16000)
        require(25L+w.toLong()*h*4 == packet.size.toLong())
        shape.set(Shape(id,w,h,hotX,hotY,scale,packet)); schedule()
    }
    fun position(packet: ByteArray) {
        require(packet.size == 26)
        val b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN); require(b.get().toInt() == 10)
        val x = b.double; val y = b.double; val id = b.int; val visible = b.get().toInt(); val scale = b.int
        require(x.isFinite() && y.isFinite() && x >= Int.MIN_VALUE.toDouble() && x <= Int.MAX_VALUE.toDouble() && y >= Int.MIN_VALUE.toDouble() && y <= Int.MAX_VALUE.toDouble())
        require(visible in 0..1 && scale in 1..16000)
        positionCount++
        val now = android.os.SystemClock.elapsedRealtime()
        if(lastPositionLog == 0L || now-lastPositionLog >= 1000) {
            android.util.Log.i("GliffCursor","position count=$positionCount id=${id.toUInt()} visible=$visible rawX=$x rawY=$y sourceScale=$scale")
            lastPositionLog = now
        }
        position.set(Position(id,x,y,visible == 1,scale)); schedule()
    }
    fun clear() { shape.set(null); position.set(null) }
    private fun schedule() {
        if (!scheduled.compareAndSet(false,true)) return
        handler.post {
            try {
                if (current()) {
                    shape.getAndSet(null)?.let { s ->
                        val pixels = IntArray(s.width*s.height)
                        var offset = 25
                        var opaque = 0
                        for (i in pixels.indices) {
                            val blue = s.packet[offset].toInt() and 255
                            val green = s.packet[offset+1].toInt() and 255
                            val red = s.packet[offset+2].toInt() and 255
                            val alpha = s.packet[offset+3].toInt() and 255
                            if(alpha != 0) opaque++
                            // Wayland ARGB8888 is premultiplied BGRA. createBitmap(IntArray)
                            // accepts straight ARGB color ints and performs its own premultiplication.
                            fun straight(channel: Int) = if (alpha == 0) 0 else minOf(255,(channel*255+alpha/2)/alpha)
                            pixels[i] = (alpha shl 24) or (straight(red) shl 16) or (straight(green) shl 8) or straight(blue)
                            offset += 4
                        }
                        android.util.Log.i("GliffCursor","shape id=${s.id.toUInt()} size=${s.width}x${s.height} hotspot=${s.hotX},${s.hotY} sourceScale=${s.scale} nonzeroAlpha=$opaque")
                        overlay.shape(s.id,s.width,s.height,s.hotX,s.hotY,1000.0/s.scale,pixels)
                    }
                    position.getAndSet(null)?.let { p ->
                        val x = p.x*1000.0/p.scale; val y = p.y*1000.0/p.scale
                        overlay.position(p.id,x,y,p.visible); observed(x,y,p.visible)
                    }
                } else clear()
            } finally {
                scheduled.set(false)
                if (current() && (shape.get() != null || position.get() != null)) schedule()
            }
        }
    }
}
