package io.github.shaksternano.gifcodec

import io.github.shaksternano.gifcodec.internal.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlin.time.Duration

private const val DEFAULT_CACHE_FRAME_INTERVAL: Int = 50

/*
 * Reference:
 * https://www.matthewflickinger.com/lab/whatsinagif/bits_and_bytes.asp
 */
class GifDecoder(
    private val data: RandomAccessData,
    private val cacheFrameInterval: Int = DEFAULT_CACHE_FRAME_INTERVAL,
) : AutoCloseable {

    constructor(
        path: Path,
        cacheFrameInterval: Int = DEFAULT_CACHE_FRAME_INTERVAL,
    ) : this(
        data = FileData(path),
        cacheFrameInterval = cacheFrameInterval,
    )

    constructor(
        bytes: ByteArray,
        cacheFrameInterval: Int = DEFAULT_CACHE_FRAME_INTERVAL,
    ) : this(
        data = ByteArrayData(bytes),
        cacheFrameInterval = cacheFrameInterval,
    )

    val width: Int
    val height: Int
    val frameCount: Int
    val duration: Duration
    val loopCount: Int
    val frameInfos: List<FrameInfo>
        get() = frames.map {
            FrameInfo(
                duration = it.duration,
                timestamp = it.timestamp,
            )
        }

    private val globalColorTable: ByteArray?
    private val globalColorTableColors: Int
    private val backgroundColorIndex: Int
    private val frames: List<RawImage>

    init {
        val gifInfo = data.read().buffered().use { source ->
            source.readGif(cacheFrameInterval)
        }

        width = gifInfo.width
        height = gifInfo.height
        frameCount = gifInfo.frameCount
        duration = gifInfo.duration
        loopCount = gifInfo.loopCount
        globalColorTable = gifInfo.globalColorTable
        globalColorTableColors = gifInfo.globalColorTableColors
        backgroundColorIndex = gifInfo.backgroundColorIndex
        frames = gifInfo.frames

        val firstFrame = frames.firstOrNull()
        if (firstFrame != null && firstFrame.timestamp < Duration.ZERO) {
            throw IllegalStateException("First frame timestamp is negative")
        }
    }

    fun readFrame(index: Int): ImageFrame {
        if (frames.isEmpty()) {
            throw NoSuchElementException("No frames available")
        }
        if (index !in frames.indices) {
            throw IndexOutOfBoundsException("Index out of bounds: $index, size: ${frames.size}")
        }

        val keyframe = findLastKeyframe(index)
        var imageArgb: IntArray? = null
        decodeImages(startIndex = keyframe.index, endIndex = index) { argb, _, _, _ ->
            imageArgb = argb
        }
        if (imageArgb == null) {
            throw IllegalStateException("Seeked image is null, this shouldn't happen")
        }

        val targetFrame = frames[index]
        return ImageFrame(
            imageArgb,
            width,
            height,
            targetFrame.duration,
            targetFrame.timestamp,
            targetFrame.index,
        )
    }

    private fun findLastKeyframe(index: Int): RawImage {
        for (i in index downTo 0) {
            val frame = frames[i]
            if (frame.isKeyFrame || frame.argb.isNotEmpty()) {
                return frame
            }
        }
        throw IllegalStateException("No keyframe found, this should never be reached")
    }

    operator fun get(index: Int): ImageFrame =
        readFrame(index)

    fun readFrame(timestamp: Duration): ImageFrame {
        if (frames.isEmpty()) {
            throw NoSuchElementException("No frames available")
        }
        if (timestamp < Duration.ZERO) {
            throw IllegalArgumentException("Timestamp cannot be negative")
        }
        if (timestamp > duration) {
            throw IllegalArgumentException("Timestamp cannot be greater than duration")
        }

        val index = if (timestamp == frames[0].timestamp) {
            0
        } else if (timestamp < frames[frames.size - 1].timestamp) {
            findIndex(timestamp, frames)
        } else {
            frames.size - 1
        }
        return readFrame(index)
    }

    private fun findIndex(timestamp: Duration, frames: List<RawImage>): Int {
        var low = 0
        var high = frames.size - 1
        while (low <= high) {
            val middle = low + (high - low) / 2
            val frameTimestamp = frames[middle].timestamp
            if (frameTimestamp == timestamp
                || (frameTimestamp < timestamp && frames[middle + 1].timestamp > timestamp)
            ) {
                return middle
            } else if (frameTimestamp < timestamp) {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        throw IllegalStateException("This should never be reached. Timestamp: $timestamp, frames: $frames")
    }

    operator fun get(timestamp: Duration): ImageFrame =
        readFrame(timestamp)

    fun asSequence(): Sequence<ImageFrame> = sequence {
        decodeImages(startIndex = 0, endIndex = frameCount - 1) { argb, duration, timestamp, index ->
            yield(
                ImageFrame(
                    argb,
                    width,
                    height,
                    duration,
                    timestamp,
                    index,
                )
            )
        }
    }

    private inline fun decodeImages(
        startIndex: Int,
        endIndex: Int,
        onImageDecode: (
            argb: IntArray,
            duration: Duration,
            timestamp: Duration,
            index: Int,
        ) -> Unit,
    ) {
        var previousImageArgb: IntArray? = null
        for (i in startIndex..endIndex) {
            val frame = frames[i]
            val imageArgb = if (frame.argb.isNotEmpty()) {
                frame.argb
            } else {
                val imageData = data.read(frame.byteOffset).buffered().monitored().use { source ->
                    // Block introducer
                    source.skip(1)
                    source.readGifImage(decodeImage = true)
                }
                val currentColorTable = imageData.localColorTable ?: globalColorTable
                ?: throw InvalidGifException("Frame $i has no color table")

                getImageArgb(
                    width,
                    height,
                    imageData.descriptor.left,
                    imageData.descriptor.top,
                    imageData.descriptor.width,
                    imageData.descriptor.height,
                    imageData.colorIndices,
                    globalColorTable,
                    globalColorTableColors,
                    currentColorTable,
                    backgroundColorIndex,
                    frame.transparentColorIndex,
                    previousImageArgb,
                )
            }

            onImageDecode(
                imageArgb,
                frame.duration,
                frame.timestamp,
                frame.index,
            )

            val disposedImage = disposeImage(
                imageArgb,
                previousImageArgb,
                frame.disposalMethod,
                width,
                height,
                frame.left,
                frame.top,
                frame.width,
                frame.height,
                usesGlobalColorTable = !frame.usesLocalColorTable,
                globalColorTable,
                globalColorTableColors,
                backgroundColorIndex,
            )
            if (disposedImage != null) {
                previousImageArgb = disposedImage
            }
        }
    }

    override fun close() {
        data.close()
    }
}
