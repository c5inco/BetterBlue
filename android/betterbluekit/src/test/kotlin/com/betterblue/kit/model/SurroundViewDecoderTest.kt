package com.betterblue.kit.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurroundViewDecoderTest {

    private fun jpeg(payload: ByteArray, withEnd: Boolean = true): ByteArray {
        val start = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val end = if (withEnd) byteArrayOf(0xFF.toByte(), 0xD9.toByte()) else ByteArray(0)
        return start + payload + end
    }

    @Test
    fun `extracts a single frame`() {
        val frame = jpeg(byteArrayOf(1, 2, 3))
        val frames = SurroundViewDecoder.extractJpegFrames(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0].contentEquals(frame))
    }

    @Test
    fun `extracts concatenated frames`() {
        val first = jpeg(byteArrayOf(1, 2, 3))
        val second = jpeg(byteArrayOf(4, 5))
        val frames = SurroundViewDecoder.extractJpegFrames(first + second)
        assertEquals(2, frames.size)
        assertTrue(frames[0].contentEquals(first))
        assertTrue(frames[1].contentEquals(second))
    }

    @Test
    fun `embedded thumbnail end marker does not truncate the frame`() {
        // A frame whose payload contains its own FFD9 (an EXIF thumbnail);
        // cutting at the first end marker would truncate the real image.
        val payloadWithInnerEnd = byteArrayOf(1, 0xFF.toByte(), 0xD9.toByte(), 2, 3)
        val frame = jpeg(payloadWithInnerEnd)
        val next = jpeg(byteArrayOf(9))
        val frames = SurroundViewDecoder.extractJpegFrames(frame + next)
        assertEquals(2, frames.size)
        assertTrue(frames[0].contentEquals(frame))
    }

    @Test
    fun `padding after the end marker is dropped`() {
        val frame = jpeg(byteArrayOf(1, 2))
        val padded = frame + byteArrayOf(0, 0, 0, 0)
        val frames = SurroundViewDecoder.extractJpegFrames(padded)
        assertEquals(1, frames.size)
        assertTrue(frames[0].contentEquals(frame))
    }

    @Test
    fun `no start marker yields no frames`() {
        assertTrue(SurroundViewDecoder.extractJpegFrames(byteArrayOf(1, 2, 3)).isEmpty())
    }

    // Tile geometry

    @Test
    fun `composite strip is sliced into four cameras plus top-down`() {
        // [4472, 720, 960, 720, 632, 720] = 4×960 fisheye + 632 bird's-eye
        val tiles = SurroundViewDecoder.tiles(listOf(4472, 720, 960, 720, 632, 720), frameCount = 1)
        assertEquals(5, tiles.size)
        assertEquals(SurroundViewCameraPosition.FRONT, tiles[0].position)
        assertEquals(SurroundViewCameraPosition.REAR, tiles[1].position)
        assertEquals(SurroundViewCameraPosition.LEFT, tiles[2].position)
        assertEquals(SurroundViewCameraPosition.RIGHT, tiles[3].position)
        assertEquals(SurroundViewCameraPosition.TOP_DOWN, tiles[4].position)
        assertEquals(SurroundViewCrop(0, 0, 960, 720), tiles[0].crop)
        assertEquals(SurroundViewCrop(2880, 0, 960, 720), tiles[3].crop)
        assertEquals(SurroundViewCrop(3840, 0, 632, 720), tiles[4].crop)
    }

    @Test
    fun `unfamiliar geometry falls back to the whole frame`() {
        val tiles = SurroundViewDecoder.tiles(listOf(1000, 720, 999, 720, 500, 720), frameCount = 1)
        assertEquals(1, tiles.size)
        assertEquals(SurroundViewCameraPosition.COMPOSITE, tiles[0].position)
        assertNull(tiles[0].crop)
    }

    @Test
    fun `short imageSize array falls back to the whole frame`() {
        val tiles = SurroundViewDecoder.tiles(listOf(4472, 720), frameCount = 1)
        assertEquals(1, tiles.size)
        assertEquals(SurroundViewCameraPosition.COMPOSITE, tiles[0].position)
    }

    @Test
    fun `multiple frames map one camera per frame`() {
        val tiles = SurroundViewDecoder.tiles(emptyList(), frameCount = 5)
        assertEquals(5, tiles.size)
        assertEquals(SurroundViewCameraPosition.FRONT, tiles[0].position)
        assertEquals(SurroundViewCameraPosition.RIGHT, tiles[3].position)
        assertEquals(SurroundViewCameraPosition.COMPOSITE, tiles[4].position)
        assertTrue(tiles.all { it.crop == null })
    }

    @Test
    fun `zero frames yields no tiles`() {
        assertTrue(SurroundViewDecoder.tiles(listOf(4472, 720, 960, 720, 632, 720), frameCount = 0).isEmpty())
    }
}
