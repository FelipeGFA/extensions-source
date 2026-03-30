package eu.kanade.tachiyomi.extension.pt.plumacomics

import android.util.Log
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document

internal class PlumaComicsDrmHelper(private val baseUrl: String) {

    fun parseChapterDocument(response: Response): Document = response.asJsoup()

    fun extractChapterId(chapterUrl: String): String {
        val pathSegments = chapterUrl.toHttpUrl().pathSegments
        return pathSegments.lastOrNull().orEmpty().ifEmpty {
            throw IllegalStateException("Could not extract chapter id from URL: $chapterUrl")
        }
    }

    fun extractTotalPages(document: Document, chapterId: String): Int {
        val payloadPages = extractPagesFromNextJsPayload(document, chapterId)
            .distinct()
            .sorted()

        if (payloadPages.isNotEmpty()) return payloadPages.size

        val pages = document.select("#chapter-pages canvas[aria-label]")
        if (pages.isNotEmpty()) return pages.size

        val fallback = document.select("#chapter-pages > div")
        if (fallback.isNotEmpty()) return fallback.size

        throw IllegalStateException("Could not extract total pages from chapter document")
    }

    private fun extractPagesFromNextJsPayload(document: Document, chapterId: String): List<Int> {
        val chapterItems = document.extractNextJs<JsonArray>(::isChapterItemArray) ?: return emptyList()

        return chapterItems.mapNotNull { item ->
            val itemArray = item as? JsonArray ?: return@mapNotNull null
            val data = itemArray.getOrNull(3) ?: return@mapNotNull null
            val page = runCatching { data.parseAs<ChapterPageDto>() }.getOrNull() ?: return@mapNotNull null
            if (page.chapterId.toString() != chapterId) return@mapNotNull null
            page.pageNumber
        }
    }

    private fun isChapterItemArray(element: JsonElement): Boolean {
        if (element !is JsonArray || element.isEmpty()) return false
        val first = element.firstOrNull() as? JsonArray ?: return false
        return first.size > 3
    }

    fun requestReaderSession(
        client: OkHttpClient,
        chapterUrl: String,
        chapterId: String,
        page: String,
        nextActionId: String,
    ): ReaderSession {
        val body = "[$chapterId,$page]"
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(chapterUrl)
            .header("Accept", "text/x-component")
            .header("Origin", baseUrl)
            .header("Referer", chapterUrl)
            .header("Accept-Encoding", "identity")
            .header("next-action", nextActionId)
            .post(body)
            .build()

        Log.d(TAG, "Session request chapter=$chapterId page=$page action=${nextActionId.take(12)}...")

        return client.newCall(request).execute().use(::parseReaderSession)
    }

    fun resolveNextActionId(client: OkHttpClient, document: Document, chapterUrl: String): String {
        val scriptUrls = document.select("script[src]")
            .map { it.absUrl("src") }
            .filter { "/_next/static/chunks/" in it }

        scriptUrls.forEach { scriptUrl ->
            val request = Request.Builder()
                .url(scriptUrl)
                .header("Referer", chapterUrl)
                .header("Accept-Encoding", "identity")
                .get()
                .build()

            val script = client.newCall(request).execute().use { it.body.string() }
            NEXT_ACTION_REGEX.find(script)?.groupValues?.getOrNull(1)?.let { actionId ->
                return actionId
            }
        }

        throw IllegalStateException("Could not resolve next-action id from reader scripts")
    }

    fun buildPageImageUrl(chapterId: String, pageNumber: Int): String = "$baseUrl/api/read/$chapterId/$pageNumber"

    fun decryptImageHeader(encryptedBytes: ByteArray, baseSeed: List<Int>): ByteArray {
        if (encryptedBytes.isEmpty() || baseSeed.isEmpty()) return encryptedBytes

        val result = encryptedBytes.copyOf()
        val seedSize = baseSeed.size
        val limit = minOf(result.size, HEADER_DECRYPT_LIMIT)

        for (i in 0 until limit) {
            val seedByte = baseSeed[i % seedSize] and 0xFF
            val derivedKeyByte = ((seedByte xor SEED_XOR_KEY) - ((i % seedSize) * SEED_INDEX_MULTIPLIER)) and 0xFF
            result[i] = (result[i].toInt() xor derivedKeyByte).toByte()
        }

        return result
    }

    private fun parseReaderSession(response: Response): ReaderSession {
        val body = response.body.string()
        Log.d(TAG, "Session response code=${response.code} size=${body.length}")

        val sessionDto = body.extractNextJsRsc<ReaderSessionDto>()
            ?: parseEscapedOrInlineSession(body)

        Log.d(TAG, "Session parsed tokenLen=${sessionDto.token.length} baseSeedSize=${sessionDto.baseSeed.size}")

        if (sessionDto.token.isBlank() || sessionDto.baseSeed.isEmpty()) {
            throw IllegalStateException("Invalid token/baseSeed in RSC response")
        }

        return ReaderSession(token = sessionDto.token, baseSeed = sessionDto.baseSeed)
    }

    private fun parseEscapedOrInlineSession(body: String): ReaderSessionDto {
        val inlineRegex =
            """"token":"([^"]+)","baseSeed":\[([^\]]+)]""".toRegex()
        inlineRegex.find(body)?.let {
            val token = it.groupValues[1]
            val baseSeed = NUMBER_REGEX.findAll(it.groupValues[2]).map { n -> n.value.toInt() }.toList()
            return ReaderSessionDto(token = token, baseSeed = baseSeed)
        }

        val escapedRegex =
            """\\"token\\":\\"([^\\"]+)\\",\\"baseSeed\\":\\\[([^\]]+)]""".toRegex()
        escapedRegex.find(body)?.let {
            val token = it.groupValues[1]
            val baseSeed = NUMBER_REGEX.findAll(it.groupValues[2]).map { n -> n.value.toInt() }.toList()
            return ReaderSessionDto(token = token, baseSeed = baseSeed)
        }

        throw IllegalStateException("Could not parse token/baseSeed from RSC response")
    }

    @Serializable
    private class ReaderSessionDto(
        val token: String,
        val baseSeed: List<Int>,
    )

    @Serializable
    private class ChapterPageDto(
        val chapterId: Int,
        val pageNumber: Int,
    )

    @Serializable
    companion object {
        private const val TAG = "PlumaComics"
        private val NUMBER_REGEX = """-?\d+""".toRegex()
        private val NEXT_ACTION_REGEX =
            """(?:createServerReference\s*\(|createServerReference\)\()\s*['\"]([a-zA-Z0-9]{30,})['\"][\s\S]{0,300}?['\"]requestImageToken['\"]""".toRegex()
        private const val HEADER_DECRYPT_LIMIT = 1024
        private const val SEED_XOR_KEY = 75
        private const val SEED_INDEX_MULTIPLIER = 3
    }
}

internal class ReaderSession(
    val token: String,
    val baseSeed: List<Int>,
)
