package com.pixelhero.app

import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Self-contained animated GIF89a encoder.
 * Builds a global color table (max 255 colors + 1 transparent) and LZW-compresses each frame.
 */
class GifEncoder(private val width: Int, private val height: Int) {

    data class GifFrame(val pixels: IntArray, val delayMs: Int)

    private val frames = mutableListOf<GifFrame>()

    fun addFrame(pixels: IntArray, delayMs: Int) {
        require(pixels.size == width * height) { "Frame size mismatch" }
        frames.add(GifFrame(pixels.copyOf(), delayMs))
    }

    fun encode(out: OutputStream) {
        val (palette, lookup) = buildPalette()
        // Header
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor
        writeShort(out, width)
        writeShort(out, height)
        out.write(0xF7)  // GCT, 256 entries, 8 bit
        out.write(0)     // bg color index
        out.write(0)     // aspect
        // GCT (256 * 3)
        out.write(palette)

        // NETSCAPE2.0 looping (loop forever)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))

        for (frame in frames) {
            // GCE
            val delayCs = (frame.delayMs / 10).coerceAtLeast(2)
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
            out.write(0x09) // disposal=2 (restore bg) + transparent flag
            writeShort(out, delayCs)
            out.write(0x00) // transparent index = 0
            out.write(0x00) // block terminator
            // Image Descriptor
            out.write(0x2C)
            writeShort(out, 0); writeShort(out, 0)
            writeShort(out, width); writeShort(out, height)
            out.write(0x00) // no LCT, no interlace
            // Pixel indices
            val indices = ByteArray(width * height)
            for (i in indices.indices) {
                val c = frame.pixels[i]
                indices[i] = if (Color.alpha(c) < 128) 0
                    else lookup[(c.and(0xFFFFFF))] ?: nearestIndex(c, palette)
            }
            // LZW
            out.write(8) // min code size
            val lzw = lzwEncode(indices, 8)
            var p = 0
            while (p < lzw.size) {
                val chunk = minOf(255, lzw.size - p)
                out.write(chunk)
                out.write(lzw, p, chunk)
                p += chunk
            }
            out.write(0x00) // block terminator
        }
        out.write(0x3B) // trailer
        out.flush()
    }

    fun encodeToBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        encode(baos)
        return baos.toByteArray()
    }

    private fun writeShort(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun buildPalette(): Pair<ByteArray, MutableMap<Int, Byte>> {
        // Collect unique opaque RGB colors across all frames
        val counts = HashMap<Int, Int>(1024)
        for (f in frames) {
            for (c in f.pixels) {
                if (Color.alpha(c) < 128) continue
                val rgb = c and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        val sorted = counts.entries.sortedByDescending { it.value }
        // Reduce to <= 255 by simple bucket quantization if needed
        val palette = ByteArray(256 * 3)
        val lookup = HashMap<Int, Byte>(256)
        // index 0 = transparent (black)
        palette[0] = 0; palette[1] = 0; palette[2] = 0
        if (sorted.size <= 255) {
            for ((i, e) in sorted.withIndex()) {
                val rgb = e.key
                palette[(i+1)*3]   = ((rgb shr 16) and 0xFF).toByte()
                palette[(i+1)*3+1] = ((rgb shr 8) and 0xFF).toByte()
                palette[(i+1)*3+2] = (rgb and 0xFF).toByte()
                lookup[rgb] = (i + 1).toByte()
            }
        } else {
            // Quantize: group by 5-bit per channel
            val bins = HashMap<Int, IntArray>(1024) // key -> [count, rSum, gSum, bSum]
            for ((rgb, cnt) in counts) {
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                val e = bins.getOrPut(key) { IntArray(4) }
                e[0] += cnt; e[1] += r * cnt; e[2] += g * cnt; e[3] += b * cnt
            }
            val arr = bins.values.sortedByDescending { it[0] }.take(255)
            for ((i, e) in arr.withIndex()) {
                val r = e[1] / e[0]; val g = e[2] / e[0]; val b = e[3] / e[0]
                palette[(i+1)*3]   = r.toByte()
                palette[(i+1)*3+1] = g.toByte()
                palette[(i+1)*3+2] = b.toByte()
                // Map all RGBs in this bin to this index
            }
            // Build lookup by scanning all unique colors and finding nearest
            for (rgb in counts.keys) {
                lookup[rgb] = nearestIndex(0xFF000000.toInt() or rgb, palette)
            }
        }
        return palette to lookup
    }

    private fun nearestIndex(c: Int, palette: ByteArray): Byte {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var best = 1; var bestDist = Int.MAX_VALUE
        for (i in 1..255) {
            val pr = palette[i*3].toInt() and 0xFF
            val pg = palette[i*3+1].toInt() and 0xFF
            val pb = palette[i*3+2].toInt() and 0xFF
            val dr = r - pr; val dg = g - pg; val db = b - pb
            val d = dr*dr + dg*dg + db*db
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best.toByte()
    }

    // -- LZW encoder --
    private fun lzwEncode(pixels: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1
        val dict = HashMap<String, Int>(4096)
        fun resetDict() {
            dict.clear()
            for (i in 0 until clearCode) dict[i.toChar().toString()] = i
            codeSize = minCodeSize + 1
            nextCode = eoiCode + 1
        }
        resetDict()
        val out = ByteArrayOutputStream()
        var buffer = 0; var bufferBits = 0
        fun writeCode(code: Int) {
            buffer = buffer or (code shl bufferBits)
            bufferBits += codeSize
            while (bufferBits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bufferBits -= 8
            }
        }
        writeCode(clearCode)
        var current = StringBuilder()
        for (px in pixels) {
            val c = (px.toInt() and 0xFF).toChar().toString()
            val next = current.toString() + c
            if (dict.containsKey(next)) {
                current = StringBuilder(next)
            } else {
                writeCode(dict[current.toString()]!!)
                if (nextCode < 4096) {
                    dict[next] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    writeCode(clearCode); resetDict()
                }
                current = StringBuilder(c)
            }
        }
        if (current.isNotEmpty()) writeCode(dict[current.toString()]!!)
        writeCode(eoiCode)
        if (bufferBits > 0) out.write(buffer and 0xFF)
        return out.toByteArray()
    }
}
