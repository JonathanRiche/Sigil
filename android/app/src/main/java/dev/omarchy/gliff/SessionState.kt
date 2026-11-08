package dev.omarchy.gliff

/** Small synchronized state model shared by the decoder worker and main thread. */
class SessionState {
    data class Geometry(val generation: Int, val width: Int, val height: Int, val scale: Int, val viewWidth: Int = width, val viewHeight: Int = height)
    private var epoch = 0L
    private var active = false
    private var ready = false
    private var generation = 0
    private var pending: Geometry? = null
    private var displayed: Geometry? = null
    @Synchronized fun begin(): Long { epoch++; active = true; ready = false; pending = null; displayed = null; return epoch }
    @Synchronized fun stop() { epoch++; active = false; ready = false; pending = null; displayed = null }
    @Synchronized fun end(token: Long) { if (epoch == token) { active = false; ready = false; displayed = null; pending = null } }
    @Synchronized fun current(token: Long) = active && epoch == token
    @Synchronized fun token() = epoch
    @Synchronized fun ready(token: Long) = current(token) && ready
    @Synchronized fun connected(token: Long) { if (current(token)) ready = true }
    @Synchronized fun configure(token: Long, w: Int, h: Int, scale: Int, viewW: Int = w, viewH: Int = h): Geometry {
        check(current(token)); require(w in 2..4096 && h in 2..4096 && scale in 1..16000 && viewW in 2..4096 && viewH in 2..4096)
        generation++; displayed = null
        return Geometry(generation,w,h,scale,viewW,viewH).also { pending = it }
    }
    @Synchronized fun pending(token: Long, gen: Int) = current(token) && pending?.generation == gen
    @Synchronized fun rendered(token: Long, gen: Int): Geometry? {
        if (!ready(token) || pending?.generation != gen) return null
        return pending.also { displayed = it }
    }
    @Synchronized fun displayed(): Geometry? = if (active && ready) displayed else null
}

/** Codec timestamps are opaque unique tokens, never source timestamps. Worker owned. */
class FrameTracker {
    private var next = 0L
    private val frames = HashMap<Long,Long>()
    fun submit(frameId: Long): Long {
        check(frames.size < 119) { "Decoder output stalled" }
        check(next < Long.MAX_VALUE)
        return (++next).also { frames[it] = frameId }
    }
    fun processed(token: Long): Long? = frames.remove(token)
    fun pendingCount() = frames.size
    fun reset() { frames.clear() }
}

/** UI-owned input sources share a remote key/button without releasing each other. */
class InputOwners(private val emit: (Int,Int,Int) -> Unit) {
    private val owners = HashMap<Pair<Int,Int>,MutableSet<String>>()
    fun set(kind: Int, code: Int, owner: String, down: Boolean) {
        val key = kind to code
        val held = owners.getOrPut(key) { HashSet() }
        val before = held.isNotEmpty()
        if (down) held.add(owner) else held.remove(owner)
        val after = held.isNotEmpty()
        if (before != after) emit(kind,code,if (after) 1 else 0)
        if (!after) owners.remove(key)
    }
    fun clear() { owners.clear() }
}

/** Stream config changes are independent from codec reference-chain changes. */
data class StreamConfiguration(val width: Int, val height: Int, val scale: Int,
    val viewWidth: Int, val viewHeight: Int, val fps: Int) {
    fun resetsDecoder(old: StreamConfiguration?) = old == null || width != old.width || height != old.height
    fun changesGeometry(old: StreamConfiguration?) = old == null || resetsDecoder(old) ||
        scale != old.scale || viewWidth != old.viewWidth || viewHeight != old.viewHeight
}

/** Render callbacks may be coalesced. Keep frame geometry separate from output ACK ownership. */
class RenderTokens {
    private val generations = java.util.TreeMap<Long,Int>()
    @Synchronized fun submit(token: Long, generation: Int) {
        check(generations.size < 120) { "Surface rendering stalled" }
        generations[token] = generation
    }
    @Synchronized fun rendered(token: Long): Int? {
        val generation = generations[token] ?: return null
        generations.headMap(token,true).clear()
        return generation
    }
    @Synchronized fun discard(token: Long) { generations.remove(token) }
    @Synchronized fun pendingCount() = generations.size
    @Synchronized fun reset() { generations.clear() }
}

object Viewport {
    fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int, pixelBudget: Long): Pair<Int,Int> {
        require(width > 0 && height > 0 && maxWidth >= 2 && maxHeight >= 2 && pixelBudget >= 4)
        val scale = minOf(1.0,maxWidth.toDouble()/width,maxHeight.toDouble()/height,
            kotlin.math.sqrt(pixelBudget.toDouble()/(width.toDouble()*height)))
        val w = maxOf(2,((width*scale).toInt()/2)*2)
        val h = maxOf(2,((height*scale).toInt()/2)*2)
        require(w <= maxWidth && h <= maxHeight && w.toLong()*h <= pixelBudget)
        return w to h
    }
}

object PointerMapping {
    fun remote(x: Double, y: Double, surfaceWidth: Int, surfaceHeight: Int, geometry: SessionState.Geometry): Pair<Double,Double>? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
        val fit = minOf(surfaceWidth.toDouble()/geometry.viewWidth,surfaceHeight.toDouble()/geometry.viewHeight)
        val pictureWidth = geometry.viewWidth*fit; val pictureHeight = geometry.viewHeight*fit
        val nx = (x-(surfaceWidth-pictureWidth)/2)/pictureWidth
        val ny = (y-(surfaceHeight-pictureHeight)/2)/pictureHeight
        if (nx !in 0.0..1.0 || ny !in 0.0..1.0) return null
        return nx*geometry.width*1000.0/geometry.scale to ny*geometry.height*1000.0/geometry.scale
    }
}
