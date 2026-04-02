package eu.kanade.tachiyomi.extension.pt.plumacomics

internal object PlumaImageDecrypt {

    private const val HEADER_DECRYPT_LIMIT = 1024
    private const val SEED_XOR_KEY = 75
    private const val SEED_INDEX_MULTIPLIER = 3
    private const val SAMPLE_SEED_LENGTH = 16

    private val SAMPLE_JPEG_HEADER = intArrayOf(
        0xFF, 0xD8, 0xFF, 0xE0,
        0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01,
        0x01, 0x01, 0x00, 0x00,
    )

    fun decryptImageBytes(encryptedBytes: ByteArray): ByteArray {
        if (encryptedBytes.isEmpty()) return encryptedBytes

        val seed = recoverSampleSeed(encryptedBytes)
        val result = encryptedBytes.copyOf()
        val limit = minOf(result.size, HEADER_DECRYPT_LIMIT)

        for (i in 0 until limit) {
            val seedIndex = i % seed.size
            val mask = ((seed[seedIndex] xor SEED_XOR_KEY) - (seedIndex * SEED_INDEX_MULTIPLIER)) and 0xFF
            result[i] = (result[i].toInt() xor mask).toByte()
        }

        return result
    }

    private fun recoverSampleSeed(encryptedBytes: ByteArray): IntArray {
        val seedLength = minOf(encryptedBytes.size, SAMPLE_SEED_LENGTH)
        val seed = IntArray(seedLength)

        for (offset in 0 until seedLength) {
            val cipherByte = encryptedBytes[offset].toInt() and 0xFF
            val plainByte = SAMPLE_JPEG_HEADER[offset]
            seed[offset] = deriveSeedByte(cipherByte, plainByte, offset)
        }

        return seed
    }

    private fun deriveSeedByte(cipherByte: Int, plainByte: Int, seedIndex: Int): Int {
        val mask = cipherByte xor plainByte
        return ((mask + (seedIndex * SEED_INDEX_MULTIPLIER)) and 0xFF) xor SEED_XOR_KEY
    }
}
