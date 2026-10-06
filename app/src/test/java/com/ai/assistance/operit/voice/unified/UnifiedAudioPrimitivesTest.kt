package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedAudioPrimitivesTest {
    @Test
    fun audioFrameBusFansOutTheSameFrameWithoutOwningCapture() {
        val bus = AudioFrameBus()
        val seenA = mutableListOf<FloatArray>()
        val seenB = mutableListOf<FloatArray>()
        val a = bus.subscribe { seenA += it }
        bus.subscribe { seenB += it }

        val frame = floatArrayOf(0.1f, -0.2f)
        bus.publish(frame)

        assertEquals(1, seenA.size)
        assertEquals(1, seenB.size)
        assertEquals(frame, seenA.single())
        assertEquals(frame, seenB.single())

        a.close()
        bus.publish(frame)
        assertEquals(1, seenA.size)
        assertEquals(2, seenB.size)
    }

    @Test
    fun ringBufferKeepsOnlyNewestSamplesAcrossWrap() {
        val ring = PcmRingBuffer(capacitySamples = 5)
        ring.append(floatArrayOf(1f, 2f, 3f))
        ring.append(floatArrayOf(4f, 5f, 6f, 7f))

        assertEquals(5, ring.sizeSamples())
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f), ring.snapshotLast(), 0f)
        assertArrayEquals(floatArrayOf(6f, 7f), ring.snapshotLast(2), 0f)
    }

    @Test
    fun ringBufferClearDropsPreroll() {
        val ring = PcmRingBuffer(capacitySamples = 4)
        ring.append(floatArrayOf(1f, 2f))
        ring.clear()

        assertEquals(0, ring.sizeSamples())
        assertArrayEquals(floatArrayOf(), ring.snapshotLast(), 0f)
    }
}
