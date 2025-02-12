package io.github.shaksternano.gifcodec

import kotlinx.io.Source
import kotlinx.io.readUByte
import kotlin.properties.Delegates

internal fun Source.readLzwIndexStream(maxColors: Int): ByteList {
    val lzwMinCodeSize = readUByte().toInt()
    val endOfInformationCode = maxColors + 1

    // Sub-block byte count
    var blockSize = readUByte().toInt()
    val initialCodeSize = lzwMinCodeSize + 1
    var currentCodeSize = initialCodeSize
    var currentBits = 0
    var currentBitPosition = 0

    var reset = true
    var previousCode by Delegates.notNull<Int>()

    val codeTable = mutableListOf<ByteList>()
    val indexStream = ByteList()
    while (blockSize > 0) {
        repeat(blockSize) {
            val byte = readUByte().toInt()
            currentBits = currentBits or (byte shl currentBitPosition)
            currentBitPosition += Byte.SIZE_BITS
            while (currentBitPosition >= currentCodeSize) {
                // Extract the required number of bits
                val mask = (1 shl currentCodeSize) - 1
                val code = currentBits and mask

                currentBits = currentBits ushr currentCodeSize
                currentBitPosition -= currentCodeSize

                // Clear code
                if (code == maxColors) {
                    currentCodeSize = initialCodeSize
                    initCodeTable(codeTable, maxColors)
                    reset = true
                } else if (code == endOfInformationCode) {
                    return@repeat
                } else if (reset) {
                    reset = false
                    val indices = codeTable[code]
                    indexStream.addAll(indices)
                    previousCode = code
                } else {
                    val indices = codeTable.getOrNull(code)
                    val previousIndices = codeTable[previousCode]
                    if (indices == null) {
                        val firstIndex = previousIndices.first()
                        val nextSequence = previousIndices + firstIndex
                        indexStream.addAll(nextSequence)
                        codeTable.add(nextSequence)
                    } else {
                        indexStream.addAll(indices)
                        val firstIndex = indices.first()
                        val nextSequence = previousIndices + firstIndex
                        codeTable.add(nextSequence)
                    }
                    previousCode = code
                    if (codeTable.size == 2.pow(currentCodeSize)) {
                        currentCodeSize++
                    }
                }
            }
        }
        blockSize = readUByte().toInt()
    }
    return indexStream
}

private fun initCodeTable(codeTable: MutableList<ByteList>, maxColors: Int) {
    codeTable.clear()
    repeat(maxColors) { i ->
        codeTable.add(ByteList(i.toByte()))
    }
    codeTable.add(ByteList()) // Clear code
    codeTable.add(ByteList()) // End of information code
}
