package eu.kanade.tachiyomi.extension.pt.plumacomics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

internal class PlumaComicsDrmHelper(private val baseUrl: String) {

    fun extractChapterId(chapterUrl: String): String {
        val pathSegments = chapterUrl.toHttpUrl().pathSegments
        return pathSegments.lastOrNull().orEmpty().ifEmpty {
            throw IllegalStateException("Missing chapter id")
        }
    }

    fun extractReaderPayload(document: Document): ChapterReaderPayload? {
        val documentHtml = document.html().replace("\\\"", "\"")
        val pages = document.select("#chapter-pages canvas[aria-label]")
            .mapNotNull { it.attr("aria-label").toPageNumber() }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .map { (pageNumber, stripCount) ->
                ChapterPageEntry(
                    pageNumber = pageNumber,
                    stripCount = stripCount,
                )
            }
        val token = CHAPTER_TOKEN_REGEX.find(documentHtml)?.groupValues?.getOrNull(1) ?: return null
        val baseSeed = BASE_SEED_REGEX.find(documentHtml)?.groupValues?.getOrNull(1)
            ?.toBaseSeed()
            ?: return null
        if (pages.isEmpty()) return null

        return ChapterReaderPayload(
            pages = pages,
            token = token,
            baseSeed = baseSeed,
        )
    }

    fun buildPageImageUrl(chapterId: String, pageNumber: Int): String = "$baseUrl/api/read/$chapterId/$pageNumber"

    fun buildStripImageUrl(chapterId: String, pageNumber: Int, stripIndex: Int): String = "$baseUrl/api/read/$chapterId/$pageNumber/$stripIndex?v=2"

    private fun String.toPageNumber(): Int? = PAGE_NUMBER_REGEX.find(this)?.value?.toIntOrNull()

    private fun String.toBaseSeed(): IntArray? = split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .takeIf { it.isNotEmpty() }
        ?.toIntArray()

    private companion object {
        val PAGE_NUMBER_REGEX = Regex("""\d+""")
        val CHAPTER_TOKEN_REGEX = Regex(""""chapterToken":"([^"]+)"""")
        val BASE_SEED_REGEX = Regex(""""baseSeed":\[(.*?)]""")
    }
}

internal data class ChapterReaderPayload(
    val pages: List<ChapterPageEntry>,
    val token: String,
    val baseSeed: IntArray,
)

internal data class ChapterPageEntry(
    val pageNumber: Int,
    val stripCount: Int,
)

internal data class ChapterSession(
    val token: String,
    val baseSeed: IntArray,
    val stripCountByPage: Map<Int, Int>,
) {
    fun requiresComposite(pageNumber: Int): Boolean = stripCount(pageNumber) > 0

    fun stripCount(pageNumber: Int): Int = stripCountByPage[pageNumber] ?: 0
}
