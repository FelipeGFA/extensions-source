package eu.kanade.tachiyomi.extension.pt.plumacomics

import android.util.Log
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    fun extractReaderPayload(document: Document, chapterId: String): ChapterReaderPayload? {
        val chapterPages = document.extractNextJs<ChapterPagesPayload> { element ->
            element is JsonObject &&
                element["id"]?.jsonPrimitive?.contentOrNull == CHAPTER_PAGES_ID
        } ?: return null

        val pageEntries = chapterPages.children
            .mapNotNull { parsePageEntry(it, chapterId) }
            .distinct()
            .sorted()
            .toList()

        val chapterToken = pageEntries.firstNotNullOfOrNull { it.chapterToken?.takeIf(String::isNotBlank) }.orEmpty()
        val baseSeed = pageEntries.firstNotNullOfOrNull { it.baseSeed?.takeIf(List<Int>::isNotEmpty) }
            .orEmpty()
        val pageNumbers = pageEntries.map { it.pageNumber }

        if (pageNumbers.isEmpty() || chapterToken.isBlank() || baseSeed.isEmpty()) {
            Log.d(
                TAG,
                "Reader payload missing data chapter=$chapterId pages=${pageNumbers.size} tokenLen=${chapterToken.length} baseSeed=${baseSeed.size}",
            )
            return null
        }

        return ChapterReaderPayload(
            pageNumbers = pageNumbers,
            token = chapterToken,
            baseSeed = baseSeed,
        )
    }

    fun extractTotalPages(document: Document, chapterId: String): Int {
        val pages = document.select("#chapter-pages canvas[aria-label]")
        val fallback = document.select("#chapter-pages > div")
        val payloadTotal = extractReaderPayload(document, chapterId)?.pageNumbers?.maxOrNull() ?: 0
        val totalPages = maxOf(payloadTotal, pages.size, fallback.size)

        if (totalPages > 0) return totalPages

        throw IllegalStateException("Could not extract total pages from chapter document")
    }

    private fun parsePageEntry(element: JsonElement, chapterId: String): ChapterPageEntry? {
        val props = runCatching {
            element.jsonArray.getOrNull(3)?.jsonObject
        }.getOrNull() ?: return null

        if (props["chapterId"]?.jsonPrimitive?.contentOrNull != chapterId) return null

        val pageNumber = props["pageNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        val chapterToken = props["chapterToken"]?.jsonPrimitive?.contentOrNull
        val baseSeed = runCatching {
            props["baseSeed"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        return ChapterPageEntry(
            pageNumber = pageNumber,
            chapterToken = chapterToken,
            baseSeed = baseSeed,
        )
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

    fun decryptImageBytes(encryptedBytes: ByteArray, baseSeed: List<Int>): ByteArray {
        if (encryptedBytes.isEmpty() || baseSeed.isEmpty()) return encryptedBytes

        val result = encryptedBytes.copyOf()
        val seedSize = baseSeed.size
        val limit = minOf(result.size, HEADER_DECRYPT_LIMIT)

        // The WASM decrypts the payload in place but only mutates the first min(dataLen, 1024) bytes.
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

    companion object {
        private const val TAG = "PlumaComics"
        private val NUMBER_REGEX = """-?\d+""".toRegex()
        private val NEXT_ACTION_REGEX =
            """(?:createServerReference\s*\(|createServerReference\)\()\s*['\"]([a-zA-Z0-9]{30,})['\"][\s\S]{0,300}?['\"]requestImageToken['\"]""".toRegex()
        private const val CHAPTER_PAGES_ID = "chapter-pages"
        private const val HEADER_DECRYPT_LIMIT = 1024
        private const val SEED_XOR_KEY = 75
        private const val SEED_INDEX_MULTIPLIER = 3
    }
}

internal class ChapterReaderPayload(
    val pageNumbers: List<Int>,
    val token: String,
    val baseSeed: List<Int>,
)

internal class ReaderSession(
    val token: String,
    val baseSeed: List<Int>,
)

private class ChapterPageEntry(
    val pageNumber: Int,
    val chapterToken: String?,
    val baseSeed: List<Int>?,
) : Comparable<ChapterPageEntry> {
    override fun compareTo(other: ChapterPageEntry): Int = pageNumber.compareTo(other.pageNumber)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterPageEntry) return false
        return pageNumber == other.pageNumber
    }

    override fun hashCode(): Int = pageNumber
}

@Serializable
private class ChapterPagesPayload(
    val id: String,
    val children: List<JsonElement>,
)
