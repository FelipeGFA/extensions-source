package eu.kanade.tachiyomi.extension.pt.leituramanga

import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

object LeituraMangaDecrypt {

    fun getImageList(document: Document): List<ImageDto> {
        val readerPayload = document.extractNextJs<ReaderPayload>()
            ?: error("Nao foi possivel encontrar os dados de decrypt do capitulo")

        val encryptedPayload = document.extractNextFlight()
            .resolvePayload(readerPayload.payload)

        val decryptedPayload = CryptoAES.decrypt(
            encryptedPayload,
            readerPayload.variant.joinToString(separator = ""),
        ).ifEmpty {
            error("Nao foi possivel descriptografar o payload do capitulo")
        }

        return decryptedPayload.parseAs()
    }

    private fun Document.extractNextFlight(): String = select("script:not([src]):containsData(self.__next_f.push)")
        .mapNotNull { extractNextFlightChunk(it.data()) }
        .joinToString(separator = "")
        .ifEmpty { error("Nao foi possivel reconstruir o Next Flight do capitulo") }

    private fun extractNextFlightChunk(script: String): String? {
        val raw = NEXT_F_SCRIPT_REGEX.find(script)?.groupValues?.getOrNull(1) ?: return null
        val chunk = jsonInstance.parseToJsonElement(raw).jsonArray
        return chunk.getOrNull(1)?.jsonPrimitive?.contentOrNull
    }

    private fun String.resolvePayload(payloadToken: String): String {
        if (payloadToken.startsWith(PREFIX_SALT)) {
            return payloadToken
        }

        val payloadId = payloadToken.removePrefix(PAYLOAD_REFERENCE_PREFIX)
        val headerStart = indexOf("$payloadId:T")
        require(headerStart >= 0) {
            "Nao foi possivel resolver o payload referenciado por $payloadToken"
        }

        val lengthStart = headerStart + payloadId.length + 2
        val commaIndex = indexOf(',', lengthStart)
        require(commaIndex >= 0) {
            "Nao foi possivel ler o tamanho do payload referenciado por $payloadToken"
        }

        val byteLength = substring(lengthStart, commaIndex).toIntOrNull(radix = 16)
            ?: error("Nao foi possivel ler o tamanho do payload referenciado por $payloadToken")

        val contentStart = commaIndex + 1
        val contentEnd = contentStart + byteLength

        require(contentEnd <= length) {
            "Payload referenciado fora dos limites do Next Flight: $payloadToken"
        }

        return substring(contentStart, contentEnd)
    }

    @Serializable
    private class ReaderPayload(
        val mangaSlug: String,
        val payload: String,
        val variant: List<String>,
    )

    private const val PREFIX_SALT = "U2FsdGVkX1"
    private const val PAYLOAD_REFERENCE_PREFIX = "$"

    private val NEXT_F_SCRIPT_REGEX =
        Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)
}
