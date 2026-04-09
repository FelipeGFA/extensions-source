package eu.kanade.tachiyomi.extension.pt.plumacomics

internal object PlumaImageDecrypt {

    private const val HEADER_DECRYPT_LIMIT = 1024
    private const val SEED_XOR_KEY = 75
    private const val SEED_INDEX_MULTIPLIER = 3

    fun decryptImageBytes(encryptedBytes: ByteArray, baseSeed: IntArray): ByteArray {
        if (encryptedBytes.isEmpty()) return encryptedBytes
        require(baseSeed.isNotEmpty()) { "Missing base seed" }

        val result = encryptedBytes.copyOf()
        val limit = minOf(result.size, HEADER_DECRYPT_LIMIT)

        for (i in 0 until limit) {
            val seedIndex = i % baseSeed.size
            val mask = ((baseSeed[seedIndex] xor SEED_XOR_KEY) - (seedIndex * SEED_INDEX_MULTIPLIER)) and 0xFF
            result[i] = (result[i].toInt() xor mask).toByte()
        }

        return result
    }
}
