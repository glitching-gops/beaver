package com.gops.spatialmapper.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * One ARCore 16-bit depth image, as saved at shutter time: [width] x [height] unsigned millimetre
 * values, row-major, in the camera SENSOR's orientation (not rotated upright like the photo — see
 * [DepthAlignment] for how a photo pixel finds its depth pixel).
 *
 * A value of 0 means "no depth estimate at this pixel", exactly as in the live image.
 *
 * Deliberately free of Android types so the file format and the sampling are unit-testable; the
 * Android half (reading an `android.media.Image`) lives in DepthCapture.kt.
 */
class DepthMap(val width: Int, val height: Int, private val millimeters: ShortArray) {

    init {
        require(width > 0 && height > 0) { "depth map must be non-empty, got ${width}x$height" }
        require(millimeters.size == width * height) {
            "expected ${width * height} samples for ${width}x$height, got ${millimeters.size}"
        }
    }

    /** Unsigned millimetres at ([x], [y]); 0 = no estimate. Out-of-range reads return 0. */
    fun millimetersAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return millimeters[y * width + x].toInt() and 0xFFFF
    }

    /** Raw copy of the samples, for encoding. */
    fun samples(): ShortArray = millimeters.copyOf()
}

/**
 * Half-width of the square window sampled around an ellipse centre, in DEPTH pixels: 2 gives a 5x5
 * window. At the typical 160x120 depth resolution that is ~3% of the frame width — wide enough that
 * one gap in the foliage (a 0, or the far background showing through) can't decide a tree's
 * distance on its own, narrow enough to stay on one crown when trees stand close together.
 */
const val DEPTH_SAMPLE_WINDOW_RADIUS = 2

/**
 * Median of the valid (non-zero) depth values in the (2r+1)x(2r+1) window around ([centerX],
 * [centerY]), in METRES, or null when the window holds no valid value at all.
 *
 * Median rather than mean: a crown seen against the sky or a farther stand is a mix of "this tree"
 * and "something much farther away", and a mean would land in between, on neither. The median sticks
 * to whichever the majority of the window is looking at.
 *
 * Window cells outside the image are ignored rather than clamped to the edge, so a centre near the
 * border uses fewer samples instead of the same edge sample several times over.
 */
fun DepthMap.medianDepthMeters(
    centerX: Int,
    centerY: Int,
    radius: Int = DEPTH_SAMPLE_WINDOW_RADIUS
): Float? {
    val valid = ArrayList<Int>((2 * radius + 1) * (2 * radius + 1))
    for (y in (centerY - radius)..(centerY + radius)) {
        for (x in (centerX - radius)..(centerX + radius)) {
            val mm = millimetersAt(x, y)
            if (mm > 0) valid += mm
        }
    }
    if (valid.isEmpty()) return null
    valid.sort()
    val mid = valid.size / 2
    val medianMm = if (valid.size % 2 == 1) {
        valid[mid].toFloat()
    } else {
        (valid[mid - 1] + valid[mid]) / 2f
    }
    return medianMm / 1000f
}

// -------------------------------------------------------------------------------------------------
// On-disk format
// -------------------------------------------------------------------------------------------------

/**
 * The saved depth file's extension. Not an image format any viewer knows, on purpose: a 16-bit
 * single-channel image has no lossless home in what Android's Bitmap can write (PNG through Bitmap is
 * 8-bit per channel), and a viewer that opened it as an image would only mislead.
 */
const val DEPTH_FILE_EXTENSION = "smdepth"

private val DEPTH_FILE_MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte())
private const val DEPTH_FILE_VERSION: Byte = 1
private const val DEPTH_HEADER_BYTES = 4 + 1 + 2 + 2

/**
 * Encodes [map] as a compact file:
 *
 *     "SMDP" (4 bytes) | version = 1 (1 byte) | width (uint16 LE) | height (uint16 LE)
 *     | DEFLATE( width*height uint16 LE millimetres, row-major )
 *
 * Lossless, because these are measurements. DEFLATE (java.util.zip, plain Java — nothing the
 * toolchain constraints rule out) does well on depth: neighbouring pixels of one surface differ by a
 * few millimetres, so the byte stream is highly repetitive. A 160x120 map is 38,400 bytes raw; see
 * the log line in DepthCapture for the real per-capture size.
 *
 * The header is uncompressed so a reader can reject a wrong or truncated file before inflating.
 */
fun encodeDepthMap(map: DepthMap): ByteArray {
    require(map.width <= 0xFFFF && map.height <= 0xFFFF) { "depth map too large to encode" }
    val payload = ByteBuffer.allocate(map.width * map.height * 2).order(ByteOrder.LITTLE_ENDIAN)
    map.samples().forEach { payload.putShort(it) }

    val out = ByteArrayOutputStream()
    out.write(DEPTH_FILE_MAGIC)
    out.write(DEPTH_FILE_VERSION.toInt())
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(map.width.toShort()).putShort(map.height.toShort()).array())
    DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(payload.array()) }
    return out.toByteArray()
}

/** Inverse of [encodeDepthMap]. Returns null (never throws) for anything that isn't a valid file. */
fun decodeDepthMap(bytes: ByteArray): DepthMap? = runCatching {
    if (bytes.size < DEPTH_HEADER_BYTES) return null
    if (!bytes.copyOfRange(0, 4).contentEquals(DEPTH_FILE_MAGIC)) return null
    if (bytes[4] != DEPTH_FILE_VERSION) return null
    val dims = ByteBuffer.wrap(bytes, 5, 4).order(ByteOrder.LITTLE_ENDIAN)
    val width = dims.short.toInt() and 0xFFFF
    val height = dims.short.toInt() and 0xFFFF
    if (width == 0 || height == 0) return null

    val raw = InflaterInputStream(
        ByteArrayInputStream(bytes, DEPTH_HEADER_BYTES, bytes.size - DEPTH_HEADER_BYTES)
    ).use { it.readBytes() }
    if (raw.size != width * height * 2) return null

    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    DepthMap(width, height, ShortArray(width * height) { buffer.short })
}.getOrNull()
