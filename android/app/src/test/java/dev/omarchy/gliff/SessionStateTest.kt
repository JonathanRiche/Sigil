package dev.omarchy.gliff

import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {
    @Test fun delayedReplacementAndOldCallbacksCannotActivateGeometry() {
        val state = SessionState(); val token = state.begin()
        assertFalse(state.ready(token))
        val first = state.configure(token,640,360,1000)
        assertNull(state.rendered(token,first.generation))
        state.connected(token)
        assertEquals(first,state.rendered(token,first.generation))
        val replacement = state.configure(token,360,640,1500)
        assertNull(state.displayed())
        assertNull(state.rendered(token,first.generation))
        assertNull(state.displayed())
        assertEquals(replacement,state.rendered(token,replacement.generation))
        assertEquals(replacement,state.displayed())
    }
    @Test fun disconnectInvalidatesClipboardReadinessAndDecoderCallbacks() {
        val state = SessionState(); val old = state.begin(); state.connected(old)
        val geometry = state.configure(old,640,360,1000)
        state.stop(); val next = state.begin()
        assertFalse(state.current(old)); assertFalse(state.ready(old)); assertFalse(state.ready(next))
        state.connected(old)
        assertFalse(state.ready(next)); assertNull(state.rendered(old,geometry.generation))
        state.connected(next); assertTrue(state.ready(next))
        state.end(next); assertFalse(state.ready(next)); assertFalse(state.current(next))
    }
    @Test fun bufferedFramesHaveUniqueTokensAndExactlyOneAckEvenWithRepeatedSourcePts() {
        val frames = FrameTracker()
        // Source PTS deliberately has no role in token allocation.
        val sameSourcePts = 42L
        val tokens = listOf(100L to sameSourcePts,200L to sameSourcePts).map { frames.submit(it.first) }
        assertNotEquals(tokens[0],tokens[1])
        assertEquals(200L,frames.processed(tokens[1]))
        assertNull(frames.processed(tokens[1]))
        assertEquals(100L,frames.processed(tokens[0]))
        val stale = frames.submit(300); frames.reset()
        assertNull(frames.processed(stale)); assertNotEquals(stale,frames.submit(400))
    }
    @Test fun pointerReleasePreservesPhysicalAndSharedScreenModifiers() {
        val emitted = ArrayList<Triple<Int,Int,Int>>()
        val owners = InputOwners { kind,code,down -> emitted.add(Triple(kind,code,down)) }
        owners.set(2,29,"physical",true); owners.set(2,29,"screen",true)
        owners.set(4,272,"gesture",true); owners.set(4,272,"gesture",false)
        assertEquals(listOf(Triple(2,29,1),Triple(4,272,1),Triple(4,272,0)),emitted)
        owners.set(2,29,"screen",false)
        assertEquals(3,emitted.size)
        owners.set(2,29,"physical",false)
        assertEquals(Triple(2,29,0),emitted.last())
    }
    @Test fun pathologicalNalCountAndOversizedParameterSetsAreRejected() {
        val tiny = byteArrayOf(0,0,1,9,0)
        val pathological = ByteArray(tiny.size*4097) { tiny[it%tiny.size] }
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(pathological) }
        val huge = byteArrayOf(0,0,0,1,7)+ByteArray(65537) { 2 }
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(huge) }
        val valid = byteArrayOf(0,0,1,7,100,0,40,0,0,1,8,1,0,0,1,5,1)
        val parameters = AnnexB.parameters(valid)
        assertTrue(parameters.idr); assertNotNull(parameters.sps); assertNotNull(parameters.pps)
    }
    @Test fun fpsUpdatesKeepDecoderAndGeometryWhileScaleUpdatesKeepOnlyDecoder() {
        val base = StreamConfiguration(640,360,1000,1280,720,30)
        val fps = base.copy(fps=15)
        assertFalse(fps.resetsDecoder(base)); assertFalse(fps.changesGeometry(base))
        val scaled = fps.copy(scale=1500,viewWidth=1920,viewHeight=1080)
        assertFalse(scaled.resetsDecoder(fps)); assertTrue(scaled.changesGeometry(fps))
        val coded = scaled.copy(width=960,height=540)
        assertTrue(coded.resetsDecoder(scaled)); assertTrue(coded.changesGeometry(scaled))
    }
    @Test fun queuedOldFramesCannotCommitScaleOnlyGeometryChanges() {
        val state = SessionState(); val token = state.begin(); state.connected(token)
        val first = state.configure(token,640,360,1000)
        val renders = RenderTokens(); renders.submit(1,first.generation)
        val replacement = state.configure(token,640,360,1500)
        renders.submit(2,replacement.generation)
        assertNull(state.rendered(token,renders.rendered(1)!!))
        assertEquals(replacement,state.rendered(token,renders.rendered(2)!!))
        assertNull(renders.rendered(2))
    }

    @Test fun spsChecksCroppedSizeAndRejectsMalformedOrOversizedInput() {
        val valid = sps(widthMbs=40,heightMbs=23,cropBottom=4)
        val info = AvcSps.validate(valid,640,360)
        assertEquals(640,info.codedWidth); assertEquals(368,info.codedHeight)
        assertThrows(IllegalArgumentException::class.java) { AvcSps.validate(valid,640,368) }
        assertThrows(IllegalArgumentException::class.java) { AvcSps.parse(valid.copyOf(8)) }
        assertThrows(IllegalArgumentException::class.java) { AvcSps.parse(sps(257,23,4)) }
        assertThrows(IllegalArgumentException::class.java) { AvcSps.parse(sps(40,23,4,chroma=3)) }
        assertThrows(IllegalArgumentException::class.java) { AvcSps.parse(byteArrayOf(0,0,0,1,103,100,0,31)+ByteArray(20)) }
    }
    @Test fun syntheticDecoderFixtureHasValidatedSps() {
        val data = java.io.File("src/main/assets/decoder-test.h264").readBytes()
        val sequence = checkNotNull(AnnexB.parameters(data).sps)
        val info = AvcSps.validate(sequence,640,360)
        assertEquals(100,info.profile)
    }
    @Test fun everySpsIsCheckedIncludingDuplicateAndSubsequentUpdates() {
        val valid = sps(40,23,4)
        val oversized = sps(80,46,0)
        var checks = 0
        val validate: (ByteArray) -> Unit = { checks++; AvcSps.validate(it,640,360) }
        AnnexB.parameters(valid,validate)
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(valid+oversized,validate) }
        assertEquals(3,checks)
        val hugeSecond = byteArrayOf(0,0,0,1,103)+ByteArray(65537) { 2 }
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(valid+hugeSecond,validate) }
        val hugePps = byteArrayOf(0,0,0,1,104)+ByteArray(65537) { 2 }
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(valid+hugePps,validate) }
        assertThrows(IllegalArgumentException::class.java) { AnnexB.parameters(oversized,validate) }
        assertThrows(IllegalArgumentException::class.java) { AvcSps.parse(sps(240,135,0)) }
    }
    @Test fun portraitAndFoldResizeStayWithinAdvertisedAxesAndBudget() {
        val caps = 1920 to 1080
        for ((w,h) in listOf(1080 to 2400,2400 to 1080,2208 to 1840,1840 to 2208)) {
            val fitted = Viewport.fit(w,h,caps.first,caps.second,1920L*1080)
            assertTrue(fitted.first <= caps.first && fitted.second <= caps.second)
            assertTrue(fitted.first.toLong()*fitted.second <= 1920L*1080)
            assertEquals(0,fitted.first%2); assertEquals(0,fitted.second%2)
            assertTrue(kotlin.math.abs(fitted.first.toDouble()/fitted.second-w.toDouble()/h) < 0.01)
        }
        val portrait = Viewport.fit(1080,2400,1920,1080,1920L*1080)
        assertEquals(1080,portrait.second)
    }
    @Test fun viewAspectUsesCodedPixelsAndEffectiveScaleForPointerCoordinates() {
        val geometry = SessionState.Geometry(1,960,540,500,1920,1080)
        val center = checkNotNull(PointerMapping.remote(500.0,500.0,1000,1000,geometry))
        assertEquals(960.0,center.first,0.001); assertEquals(540.0,center.second,0.001)
        assertNull(PointerMapping.remote(500.0,20.0,1000,1000,geometry))
        val viewAspect = geometry.copy(viewWidth=1920,viewHeight=1200)
        // 1000x625 is the VIEW aspect. The entire view maps to normalized CODED coordinates.
        val rightBottom = checkNotNull(PointerMapping.remote(1000.0,625.0,1000,625,viewAspect))
        assertEquals(1920.0,rightBottom.first,0.001); assertEquals(1080.0,rightBottom.second,0.001)
        val state = SessionState(); val token = state.begin()
        assertEquals(250,state.configure(token,480,270,250,1920,1080).scale)
    }
    @Test fun explicitDestinationParsesUserAndTailscaleHostWithoutAmbientDefaults() {
        assertEquals(ConnectionDestination("alice","workstation"),ConnectionDestination.parse(" alice@workstation "))
        assertEquals(ConnectionDestination("user","host.tailnet.example"),ConnectionDestination.parse("user@host.tailnet.example"))
        assertEquals(ConnectionDestination("user","2001:db8::1"),ConnectionDestination.parse("user@[2001:db8::1]"))
        for (invalid in listOf("lightning","@lightning","user@","a@b@c","user@host:22","user@host name")) assertNull(ConnectionDestination.parse(invalid))
    }
    private fun sps(widthMbs: Int, heightMbs: Int, cropBottom: Int, chroma: Int = 1): ByteArray {
        val bits = StringBuilder()
        fun fixed(value: Int, count: Int) { for (n in count-1 downTo 0) bits.append(if (((value ushr n) and 1) == 0) '0' else '1') }
        fun ue(value: Int) { val binary = (value+1).toString(2); repeat(binary.length-1) { bits.append('0') }; bits.append(binary) }
        fixed(100,8); fixed(0,8); fixed(31,8); ue(0)
        ue(chroma); ue(0); ue(0); fixed(0,1); fixed(0,1)
        ue(0); ue(0); ue(0); ue(3); fixed(0,1)
        ue(widthMbs-1); ue(heightMbs-1); fixed(1,1); fixed(1,1); fixed(1,1)
        ue(0); ue(0); ue(0); ue(cropBottom); fixed(0,1); fixed(1,1)
        while (bits.length%8 != 0) bits.append('0')
        val raw = ByteArray(bits.length/8) { bits.substring(it*8,it*8+8).toInt(2).toByte() }
        val escaped = java.io.ByteArrayOutputStream(); var zeros = 0
        for (byte in raw) {
            val value = byte.toInt() and 255
            if (zeros >= 2 && value <= 3) { escaped.write(3); zeros = 0 }
            escaped.write(value); zeros = if (value == 0) zeros+1 else 0
        }
        return byteArrayOf(0,0,0,1,103)+escaped.toByteArray()
    }

}
