package com.sangege.moqpublisher

import java.nio.ByteBuffer

/** Copies one camera/codec plane without assuming tightly packed or non-interleaved chroma. */
internal object YuvPlaneCopy {
    /** Reused per plane by the encoder; avoid allocating row arrays for every frame. */
    class Scratch {
        internal var source = ByteArray(0)
        internal var target = ByteArray(0)

        internal fun ensure(sourceBytes: Int, targetBytes: Int) {
            if (source.size < sourceBytes) source = ByteArray(sourceBytes)
            if (target.size < targetBytes) target = ByteArray(targetBytes)
        }
    }

    fun copy(
        source: ByteBuffer,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        target: ByteBuffer,
        targetRowStride: Int,
        targetPixelStride: Int,
        width: Int,
        height: Int,
        scratch: Scratch = Scratch()
    ) {
        require(width > 0 && height > 0)
        require(sourcePixelStride > 0 && targetPixelStride > 0)
        require(sourceRowStride >= (width - 1) * sourcePixelStride + 1)
        require(targetRowStride >= (width - 1) * targetPixelStride + 1)
        val input = source.duplicate()
        val output = target.duplicate()
        val inputStart = input.position()
        val outputStart = output.position()
        require(input.remaining() >= (height - 1) * sourceRowStride + (width - 1) * sourcePixelStride + 1)
        require(output.remaining() >= (height - 1) * targetRowStride + (width - 1) * targetPixelStride + 1)

        val sourceSpan = (width - 1) * sourcePixelStride + 1
        val targetSpan = (width - 1) * targetPixelStride + 1
        val packed = sourcePixelStride == 1 && targetPixelStride == 1
        if (!packed) scratch.ensure(sourceSpan, targetSpan)

        for (row in 0 until height) {
            val from = inputStart + row * sourceRowStride
            val to = outputStart + row * targetRowStride
            if (packed) {
                input.limit(from + width).position(from)
                output.position(to)
                output.put(input)
                input.limit(source.limit())
            } else {
                // A per-pixel DirectByteBuffer get/put crosses Android's native
                // memory boundary millions of times per frame. Transfer rows in
                // bulk and shuffle chroma in ordinary arrays instead. Preserve
                // the other channel when U/V codec planes overlap (NV12/NV21).
                input.position(from)
                input.get(scratch.source, 0, sourceSpan)
                if (targetPixelStride != 1) {
                    output.position(to)
                    output.get(scratch.target, 0, targetSpan)
                }
                for (column in 0 until width) {
                    scratch.target[column * targetPixelStride] = scratch.source[column * sourcePixelStride]
                }
                output.position(to)
                output.put(scratch.target, 0, targetSpan)
            }
        }
    }
}
