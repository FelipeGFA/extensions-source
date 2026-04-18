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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

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
        .rateLimit(3, 1)
        .addInterceptor(ImageDecryptInterceptor())
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
        val chapter = document.extractNextJs<ChapterDto>() ?: throw IOException("Capítulo não encontrado")

        return List(document.select("#chapter-pages canvas").size) { index ->
            Page(index, imageUrl = "$baseUrl/api/read/${chapter.chapterId}/${index + 1}?v=2#${chapter.toJsonString()}")
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!.toHttpUrl()
        val dto = url.fragment!!.parseAs<ChapterDto>()
        val imageHeaders = headers.newBuilder()
            .set("X-Pluma-Token", dto.chapterToken)
            .build()
        return GET(url, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = ""
}
