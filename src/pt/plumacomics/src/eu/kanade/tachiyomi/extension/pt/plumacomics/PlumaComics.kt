package eu.kanade.tachiyomi.extension.pt.plumacomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class PlumaComics : HttpSource() {

    private val drmHelper by lazy { PlumaComicsDrmHelper(baseUrl) }
    private val stripClient by lazy {
        // Avoid interceptor recursion when fetching strip segments for a composed page.
        super.client.newBuilder()
            .rateLimit(2)
            .build()
    }
    private val imageBuilder by lazy { PlumaComicsImageBuilder(stripClient, drmHelper) }
    private val chapterSessions = ConcurrentHashMap<String, ChapterSession>()

    override val name: String = "Pluma Comics"

    override val lang: String = "pt-BR"

    override val baseUrl: String = "https://plumacomics.cloud"

    override val supportsLatest: Boolean = true

    override val versionId: Int = 5

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override val client = super.client.newBuilder()
        .rateLimit(2)
        .addInterceptor(::interceptReadImageRequest)
        .build()

    // ==================== Popular ==========================
    override fun popularMangaRequest(page: Int): Request {
        val suffix = if (page > 1) "&page=$page" else ""
        return GET("$baseUrl/series?sort=popular$suffix", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.group[href*=series]").map { it.toSManga() }
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.selectFirst("a[href*='page=${currentPage + 1}']") != null
        return MangasPage(mangas, hasNextPage = hasNextPage)
    }

    // ==================== Latest ==========================
    override fun latestUpdatesRequest(page: Int): Request {
        val suffix = if (page > 1) "?page=$page" else ""
        return GET("$baseUrl/series$suffix", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    // ==================== Search ==========================
    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        return MangasPage(
            mangas = dto.results.map { it.toSManga() },
            hasNextPage = false,
        )
    }

    // ==================== Details ==========================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val pageTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("title")!!.text()

            title = pageTitle.substringBeforeLast(" |", pageTitle)
            thumbnail_url = document.selectFirst("img.cover-img")?.absUrl("src")
            description = document.selectFirst("div.card > p.text-sm")?.text()
            genre = document.select(".flex.flex-wrap > span").joinToString { it.text() }
            status = when (document.selectFirst(".flex.items-center span.text-xs.font-bold.uppercase:last-child")?.text()?.lowercase()) {
                "em andamento" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            setUrlWithoutDomain(document.location().toSMangaUrl())
        }
    }

    // ==================== Chapters ==========================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".card a[href*=ler]").map { it.toSChapter() }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Accept-Encoding", "identity")
            .set("Cache-Control", "no-cache")
            .set("Pragma", "no-cache")
            .set("Connection", "close")
            .set("Upgrade-Insecure-Requests", "1")
            .build()

        return GET(baseUrl + chapter.url, chapterHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val chapterUrl = document.location()
        val chapterId = drmHelper.extractChapterId(chapterUrl)
        val payload = drmHelper.extractReaderPayload(document)
            ?: throw IllegalStateException("Missing reader payload")

        chapterSessions[chapterId] = ChapterSession(
            token = payload.token,
            baseSeed = payload.baseSeed,
            stripCountByPage = payload.pages.associate { it.pageNumber to it.stripCount },
        )

        return payload.pages.mapIndexed { index, page ->
            Page(
                index = index,
                url = chapterUrl,
                imageUrl = drmHelper.buildPageImageUrl(chapterId, page.pageNumber),
            )
        }
    }

    // ==================== Pages ==========================
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("Missing image URL")
        val chapterId = imageUrl.toHttpUrl().pathSegments.getOrNull(2)
            ?: throw IllegalStateException("Missing chapter id")
        val session = chapterSessions[chapterId]
            ?: throw IllegalStateException("Missing chapter session")

        val imageHeaders = headers.newBuilder()
            .set("Referer", page.url)
            .set("X-Pluma-Token", session.token)
            .set("Accept-Encoding", "identity")
            .build()

        return GET(imageUrl, imageHeaders)
    }

    // ==================== Dto ==========================
    @Serializable
    private class SearchDto(
        val results: List<MangaDto>,
    )

    @Serializable
    private class MangaDto(
        val title: String,
        val slug: String,
        val coverPath: String,
    )

    // ==================== Helpers ==========================
    private fun interceptReadImageRequest(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/api/read/")) {
            return chain.proceed(request)
        }

        val normalizedRequest = request.withIdentityEncoding()
        val chapterId = normalizedRequest.url.pathSegments.getOrNull(2)
            ?: return chain.proceed(normalizedRequest)
        val session = chapterSessions[chapterId]
            ?: return chain.proceed(normalizedRequest)
        val pageNumber = normalizedRequest.url.pathSegments.getOrNull(3)?.toIntOrNull()

        if (pageNumber != null &&
            normalizedRequest.url.pathSegments.size == 4 &&
            session.requiresComposite(pageNumber)
        ) {
            return imageBuilder.buildCompositeResponse(
                request = normalizedRequest,
                chapterId = chapterId,
                pageNumber = pageNumber,
                session = session,
            )
        }

        val response = chain.proceed(normalizedRequest)
        if (!response.isSuccessful) {
            return response
        }

        return response.decryptImage(session.baseSeed)
    }

    private fun Request.withIdentityEncoding(): Request = newBuilder()
        .header("Accept-Encoding", "identity")
        .build()

    private fun Response.decryptImage(baseSeed: IntArray): Response {
        val responseBody = body
        val decryptedBytes = PlumaImageDecrypt.decryptImageBytes(
            encryptedBytes = responseBody.bytes(),
            baseSeed = baseSeed,
        )

        return newBuilder()
            .body(decryptedBytes.toResponseBody(responseBody.contentType() ?: IMAGE_MEDIA_TYPE))
            .build()
    }

    private fun Element.toSManga(): SManga = SManga.create().apply {
        title = selectFirst("h3")!!.text()
        thumbnail_url = selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(absUrl("href").toSMangaUrl())
    }

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        thumbnail_url = "$baseUrl/api/cover/${this@toSManga.coverPath}"
        setUrlWithoutDomain(this@toSManga.slug.toSMangaUrl())
    }

    private fun Element.toSChapter(): SChapter = SChapter.create().apply {
        name = selectFirst("span:first-child")!!.text()
        setUrlWithoutDomain(absUrl("href").toSChapterUrl())
    }

    private fun String.toSMangaUrl(): String = "/series/${extractLastPathSegment()}"

    private fun String.toSChapterUrl(): String = "/ler/${extractLastPathSegment()}"

    private fun String.extractLastPathSegment(): String {
        val pathSegment = runCatching {
            toHttpUrl().pathSegments.lastOrNull()
        }.getOrNull()

        if (!pathSegment.isNullOrBlank()) {
            return pathSegment
        }

        return substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .ifBlank { throw IllegalStateException("Missing path segment") }
    }

    private companion object {
        val IMAGE_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
