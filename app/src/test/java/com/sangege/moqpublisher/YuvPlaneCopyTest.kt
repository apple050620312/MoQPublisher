package com.sangege.moqpublisher

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class YuvPlaneCopyTest {
    @Test
    fun copiesPaddedLumaWithoutMovingCameraBufferPosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 9, 4, 5, 6)).apply { position(1) }
        val target = ByteBuffer.allocate(6)
        YuvPlaneCopy.copy(source, 4, 1, target, 3, 1, 3, 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), target.array())
        assertEquals(1, source.position())
        assertEquals(0, target.position())
    }

    @Test
    fun convertsInterleavedCameraChromaToPlanarCodecChroma() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 91, 2, 92, 0, 0, 3, 93, 4))
        val target = ByteBuffer.allocate(4)
        YuvPlaneCopy.copy(source, 6, 2, target, 2, 1, 2, 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.array())
    }

    @Test
    fun fillsOverlappingNv12PlanesWithCorrectUvOrder() {
        val u = ByteBuffer.wrap(byteArrayOf(10, 11, 12, 13))
        val v = ByteBuffer.wrap(byteArrayOf(20, 21, 22, 23))
        val target = ByteBuffer.allocate(8)
        YuvPlaneCopy.copy(u, 2, 1, target, 4, 2, 2, 2)
        YuvPlaneCopy.copy(v, 2, 1, target.duplicate().apply { position(1) }, 4, 2, 2, 2)
        assertArrayEquals(byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23), target.array())
    }

    @Test
    fun supportsDirectBuffersAndCodecPadding() {
        val source = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(1, 2, 3, 4)); flip() }
        val target = ByteBuffer.allocateDirect(11).apply { position(2) }
        YuvPlaneCopy.copy(source, 2, 1, target, 6, 2, 2, 2)
        assertEquals(1.toByte(), target.get(2))
        assertEquals(2.toByte(), target.get(4))
        assertEquals(3.toByte(), target.get(8))
        assertEquals(4.toByte(), target.get(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUndersizedPlane() {
        YuvPlaneCopy.copy(ByteBuffer.allocate(3), 2, 1, ByteBuffer.allocate(4), 2, 1, 2, 2)
    }

    @Test
    fun bulkCopiesDirectRowsAcrossLayoutsWithoutChangingPaddingOrBufferPositions() {
        val scratch = YuvPlaneCopy.Scratch()
        for (width in listOf(1, 3, 8)) for (height in listOf(1, 3)) {
            for (sourceStride in 1..2) for (targetStride in 1..2) {
                val sourceRow = width * sourceStride + 3
                val targetRow = width * targetStride + 5
                val source = ByteBuffer.allocateDirect(3 + sourceRow * height)
                val target = ByteBuffer.allocateDirect(5 + targetRow * height)
                val expected = ByteArray(target.capacity()) { 99 }
                target.put(expected).clear()
                for (row in 0 until height) for (column in 0 until width) {
                    val value = (row * 31 + column * 7).toByte()
                    source.put(3 + row * sourceRow + column * sourceStride, value)
                    expected[5 + row * targetRow + column * targetStride] = value
                }
                source.position(3).limit(3 + (height - 1) * sourceRow + (width - 1) * sourceStride + 1)
                target.position(5).limit(5 + (height - 1) * targetRow + (width - 1) * targetStride + 1)
                YuvPlaneCopy.copy(source, sourceRow, sourceStride, target, targetRow, targetStride, width, height, scratch)
                val actual = ByteArray(target.capacity())
                target.duplicate().apply { clear() }.get(actual)
                assertArrayEquals(expected, actual)
                assertEquals(3, source.position())
                assertEquals(5, target.position())
            }
        }
    }

    @Test
    fun convertsSharedNv21ToNv12WithoutOverwritingTheOtherChannelInEitherPlaneOrder() {
        val source = ByteBuffer.allocateDirect(8).apply { put(byteArrayOf(20, 10, 21, 11, 22, 12, 23, 13)); clear() }
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val target = ByteBuffer.allocateDirect(8)
            for (plane in order) {
                YuvPlaneCopy.copy(
                    source.duplicate().apply { position(1 - plane) }, 4, 2,
                    target.duplicate().apply { position(plane) }, 4, 2, 2, 2
                )
            }
            val actual = ByteArray(8)
            target.get(actual)
            assertArrayEquals(byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23), actual)
        }
    }
}
