package eu.kanade.tachiyomi.extension.pt.plumacomics

import keiyoushi.utils.extractNextJs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

internal class PlumaComicsDrmHelper(private val baseUrl: String) {

    fun extractChapterId(chapterUrl: String): String {
        val pathSegments = chapterUrl.toHttpUrl().pathSegments
        return pathSegments.lastOrNull().orEmpty().ifEmpty {
            throw IllegalStateException("Missing chapter id")
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

        val token = pageEntries.firstNotNullOfOrNull { it.chapterToken?.takeIf(String::isNotBlank) } ?: return null
        val pageNumbers = pageEntries.map { it.pageNumber }
        if (pageNumbers.isEmpty()) return null

        return ChapterReaderPayload(
            pageNumbers = pageNumbers,
            token = token,
        )
    }

    fun buildPageImageUrl(chapterId: String, pageNumber: Int): String = "$baseUrl/api/read/$chapterId/$pageNumber"

    private fun parsePageEntry(element: JsonElement, chapterId: String): ChapterPageEntry? {
        val props = runCatching { element.jsonArray.getOrNull(3)?.jsonObject }.getOrNull() ?: return null
        if (props["chapterId"]?.jsonPrimitive?.contentOrNull != chapterId) return null

        val pageNumber = props["pageNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        val chapterToken = props["chapterToken"]?.jsonPrimitive?.contentOrNull

        return ChapterPageEntry(
            pageNumber = pageNumber,
            chapterToken = chapterToken,
        )
    }

    private companion object {
        private const val CHAPTER_PAGES_ID = "chapter-pages"
    }
}

internal class ChapterReaderPayload(
    val pageNumbers: List<Int>,
    val token: String,
)

private class ChapterPageEntry(
    val pageNumber: Int,
    val chapterToken: String?,
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
