package com.betterblue.kit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Which camera a surround-view image came from.
 *
 * The payload doesn't label its images, so position is derived from their
 * order: Hyundai returns the fisheye cameras front, rear, left, right,
 * followed by the stitched bird's-eye view.
 */
@Serializable
enum class SurroundViewCameraPosition {
    @SerialName("front")
    FRONT,

    @SerialName("rear")
    REAR,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,

    @SerialName("topDown")
    TOP_DOWN,

    @SerialName("composite")
    COMPOSITE,
    ;

    val displayName: String
        get() =
            when (this) {
                FRONT -> "Front"
                REAR -> "Rear"
                LEFT -> "Left"
                RIGHT -> "Right"
                TOP_DOWN -> "Top"
                COMPOSITE -> "Full"
            }
}

/** A rectangle inside a JPEG frame, in pixels from the top-left. */
@Serializable
data class SurroundViewCrop(
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
)

/**
 * One camera view within a capture: which frame it lives in, and where inside
 * that frame. `crop == null` means the frame *is* the image.
 */
@Serializable
data class SurroundViewTile(
    val position: SurroundViewCameraPosition,
    val frameIndex: Int,
    val crop: SurroundViewCrop? = null,
) {
    val id: String get() = "${position.name}@$frameIndex"
}

/**
 * A single surround-view capture: the images the vehicle took at one moment,
 * plus the context the API reports alongside them.
 *
 * Equality is identity-based on purpose (id + byte count): value equality
 * would byte-compare megabytes of JPEG on every recomposition diff.
 */
class SurroundViewCapture(
    val vin: String,
    /** When the vehicle took the images; null when missing or unparseable. */
    val capturedAt: Instant?,
    val location: VehicleStatus.Location? = null,
    /** Vehicle heading in degrees at capture time (0 = north). */
    val heading: Int? = null,
    val doorOpen: VehicleStatus.DoorStatus? = null,
    val trunkOpen: Boolean? = null,
    val sideMirrorOpen: Boolean? = null,
    /**
     * The JPEG frames the payload carried. Usually one wide composite strip;
     * some payloads concatenate one JPEG per camera instead.
     */
    val frames: List<ByteArray>,
    /** How to read [frames] as individual camera views. */
    val tiles: List<SurroundViewTile>,
) {
    val id: String get() = "$vin-${capturedAt?.epochSecond ?: "unknown"}"
    val isEmpty: Boolean get() = frames.isEmpty()

    /** Total bytes of imagery. */
    val byteCount: Int get() = frames.sumOf { it.size }

    override fun equals(other: Any?): Boolean =
        other is SurroundViewCapture && other.id == id && other.byteCount == byteCount

    override fun hashCode(): Int = 31 * id.hashCode() + byteCount
}

object SurroundViewDecoder {
    private val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * Splits a decoded SVM payload into its JPEG frames.
     *
     * The buffer is *usually* a single wide composite, but the format allows
     * several JPEGs concatenated back to back, so scan for start markers
     * rather than assuming one image.
     */
    fun extractJpegFrames(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var searchFrom = 0
        while (true) {
            val start = indexOf(data, JPEG_START, searchFrom, data.size)
            if (start < 0) break
            starts.add(start)
            searchFrom = start + JPEG_START.size
        }

        return starts.mapIndexed { index, start ->
            // Cut at the NEXT start marker rather than the first end marker: an
            // embedded EXIF thumbnail carries its own 0xFFD9, which would
            // truncate the real image.
            val limit = if (index + 1 < starts.size) starts[index + 1] else data.size
            // Drop any padding that follows the frame's own end marker.
            val end = lastIndexOf(data, JPEG_END, start, limit)
            if (end >= 0) {
                data.copyOfRange(start, end + JPEG_END.size)
            } else {
                data.copyOfRange(start, limit)
            }
        }
    }

    /**
     * Works out where each camera view sits, from the payload's `imageSize`
     * array and the number of frames decoded.
     *
     * `imageSize` describes a composite strip as
     * `[totalW, totalH, cameraW, cameraH, topDownW, topDownH]` — e.g.
     * `[4472, 720, 960, 720, 632, 720]` is four 960-wide fisheye views followed
     * by a 632-wide bird's-eye view. Anything that doesn't fit that shape falls
     * back to "the frame is the image".
     */
    fun tiles(imageSize: List<Int>, frameCount: Int): List<SurroundViewTile> {
        if (frameCount <= 0) return emptyList()

        if (frameCount != 1) {
            // One JPEG per camera — nothing to slice.
            return (0 until frameCount).map { index ->
                SurroundViewTile(position = cameraPosition(index), frameIndex = index)
            }
        }

        val wholeFrame = listOf(SurroundViewTile(SurroundViewCameraPosition.COMPOSITE, frameIndex = 0))

        if (imageSize.size < 6) return wholeFrame
        val (totalWidth, totalHeight) = imageSize[0] to imageSize[1]
        val (cameraWidth, cameraHeight) = imageSize[2] to imageSize[3]
        val (topDownWidth, topDownHeight) = imageSize[4] to imageSize[5]

        if (totalWidth <= 0 || totalHeight <= 0 || cameraWidth <= 0 || topDownWidth <= 0 ||
            totalWidth <= topDownWidth ||
            (totalWidth - topDownWidth) % cameraWidth != 0
        ) {
            return wholeFrame
        }

        val cameraCount = (totalWidth - topDownWidth) / cameraWidth
        if (cameraCount <= 0) return wholeFrame

        val tiles =
            (0 until cameraCount).mapTo(mutableListOf()) { index ->
                SurroundViewTile(
                    position = cameraPosition(index),
                    frameIndex = 0,
                    crop =
                        SurroundViewCrop(
                            originX = index * cameraWidth,
                            originY = 0,
                            width = cameraWidth,
                            height = minOf(cameraHeight, totalHeight),
                        ),
                )
            }

        tiles.add(
            SurroundViewTile(
                position = SurroundViewCameraPosition.TOP_DOWN,
                frameIndex = 0,
                crop =
                    SurroundViewCrop(
                        originX = cameraCount * cameraWidth,
                        originY = 0,
                        width = topDownWidth,
                        height = minOf(topDownHeight, totalHeight),
                    ),
            ),
        )

        return tiles
    }

    private fun cameraPosition(index: Int): SurroundViewCameraPosition {
        val order =
            listOf(
                SurroundViewCameraPosition.FRONT,
                SurroundViewCameraPosition.REAR,
                SurroundViewCameraPosition.LEFT,
                SurroundViewCameraPosition.RIGHT,
            )
        return order.getOrElse(index) { SurroundViewCameraPosition.COMPOSITE }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        outer@ for (i in from..(until - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        outer@ for (i in (until - needle.size) downTo from) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
