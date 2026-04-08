package com.example.chuchu.service.terminal

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ImagePlacement(
    val destX: Int,
    val destY: Int,
    val destW: Int,
    val destH: Int,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    val imgW: Int,
    val imgH: Int,
    val bitmap: Bitmap,
)

data class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val defaultBgArgb: Int,
    val defaultFgArgb: Int,
    val codepoints: IntArray,
    val fgArgb: IntArray,
    val bgArgb: IntArray,
    val flags: ByteArray,
    val images: List<ImagePlacement> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalSnapshot) return false
        return cols == other.cols && rows == other.rows &&
            cursorX == other.cursorX && cursorY == other.cursorY &&
            cursorVisible == other.cursorVisible &&
            defaultBgArgb == other.defaultBgArgb &&
            defaultFgArgb == other.defaultFgArgb &&
            codepoints.contentEquals(other.codepoints) &&
            fgArgb.contentEquals(other.fgArgb) &&
            bgArgb.contentEquals(other.bgArgb) &&
            flags.contentEquals(other.flags) &&
            images == other.images
    }

    override fun hashCode(): Int {
        var result = cols
        result = 31 * result + rows
        result = 31 * result + cursorX
        result = 31 * result + cursorY
        result = 31 * result + cursorVisible.hashCode()
        result = 31 * result + defaultBgArgb
        result = 31 * result + defaultFgArgb
        result = 31 * result + codepoints.contentHashCode()
        result = 31 * result + fgArgb.contentHashCode()
        result = 31 * result + bgArgb.contentHashCode()
        result = 31 * result + flags.contentHashCode()
        result = 31 * result + images.hashCode()
        return result
    }

    companion object {
        private const val HEADER_I32_COUNT = 11
        private const val CELL_SIZE_BYTES = 11
        private const val IMAGE_HEADER_BYTES = 44

        private fun packArgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun fromByteBuffer(
            buffer: ByteBuffer,
            images: List<ImagePlacement> = emptyList(),
        ): TerminalSnapshot {
            val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            wrapped.position(0)

            val cols = wrapped.int
            val rows = wrapped.int
            val cursorX = wrapped.int
            val cursorY = wrapped.int
            val cursorVisible = wrapped.int == 1
            val defaultBgR = wrapped.int
            val defaultBgG = wrapped.int
            val defaultBgB = wrapped.int
            val defaultFgR = wrapped.int
            val defaultFgG = wrapped.int
            val defaultFgB = wrapped.int

            val cellCount = cols * rows
            val expectedSize = (HEADER_I32_COUNT * 4) + (cellCount * CELL_SIZE_BYTES)
            require(buffer.capacity() >= expectedSize) {
                "Snapshot buffer too small: cap=${buffer.capacity()} expected=$expectedSize"
            }

            val codepoints = IntArray(cellCount)
            val fgArgb = IntArray(cellCount)
            val bgArgb = IntArray(cellCount)
            val flags = ByteArray(cellCount)

            for (i in 0 until cellCount) {
                codepoints[i] = wrapped.int
                val fgR = wrapped.get().toInt() and 0xff
                val fgG = wrapped.get().toInt() and 0xff
                val fgB = wrapped.get().toInt() and 0xff
                val bgR = wrapped.get().toInt() and 0xff
                val bgG = wrapped.get().toInt() and 0xff
                val bgB = wrapped.get().toInt() and 0xff
                flags[i] = wrapped.get()
                fgArgb[i] = packArgb(fgR, fgG, fgB)
                bgArgb[i] = packArgb(bgR, bgG, bgB)
            }

            return TerminalSnapshot(
                cols = cols,
                rows = rows,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorVisible = cursorVisible,
                defaultBgArgb = packArgb(defaultBgR, defaultBgG, defaultBgB),
                defaultFgArgb = packArgb(defaultFgR, defaultFgG, defaultFgB),
                codepoints = codepoints,
                fgArgb = fgArgb,
                bgArgb = bgArgb,
                flags = flags,
                images = images,
            )
        }

        fun parseImages(buffer: ByteBuffer?): List<ImagePlacement> {
            if (buffer == null || buffer.capacity() < 4) return emptyList()
            val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            wrapped.position(0)
            val count = wrapped.int
            if (count <= 0) return emptyList()

            val images = ArrayList<ImagePlacement>(count)
            for (i in 0 until count) {
                if (wrapped.remaining() < IMAGE_HEADER_BYTES) break
                val destX = wrapped.int
                val destY = wrapped.int
                val destW = wrapped.int
                val destH = wrapped.int
                val srcX = wrapped.int
                val srcY = wrapped.int
                val srcW = wrapped.int
                val srcH = wrapped.int
                val imgW = wrapped.int
                val imgH = wrapped.int
                val dataLen = wrapped.int

                val expectedLen = imgW.toLong() * imgH.toLong() * 4L
                if (imgW <= 0 || imgH <= 0 || dataLen <= 0 ||
                    expectedLen > Int.MAX_VALUE ||
                    dataLen != expectedLen.toInt() ||
                    wrapped.remaining() < dataLen
                ) {
                    Log.w(
                        "TerminalSnapshot",
                        "bad image record: img=${imgW}x$imgH dataLen=$dataLen expected=$expectedLen remaining=${wrapped.remaining()}",
                    )
                    break
                }

                val pixelBytes = wrapped.slice().order(ByteOrder.nativeOrder())
                pixelBytes.limit(dataLen)

                val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(pixelBytes)
                wrapped.position(wrapped.position() + dataLen)

                images += ImagePlacement(
                    destX = destX,
                    destY = destY,
                    destW = destW,
                    destH = destH,
                    srcX = srcX,
                    srcY = srcY,
                    srcW = srcW,
                    srcH = srcH,
                    imgW = imgW,
                    imgH = imgH,
                    bitmap = bitmap,
                )
            }
            return images
        }
    }
}
