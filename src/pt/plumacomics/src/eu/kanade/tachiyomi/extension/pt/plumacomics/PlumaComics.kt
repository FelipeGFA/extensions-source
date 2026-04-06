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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

class PlumaComics : HttpSource() {

    private val drmHelper by lazy { PlumaComicsDrmHelper(baseUrl) }
    private val chapterTokenById = ConcurrentHashMap<String, String>()

    override val name: String = "Pluma Comics"

    override val lang: String = "pt-BR"

    override val baseUrl: String = "https://plumacomics.cloud"

    override val supportsLatest: Boolean = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override val client = super.client.newBuilder()
        .rateLimit(2)
        .addInterceptor { chain ->
            val request = chain.request()
            val isReadEndpoint = request.url.encodedPath.startsWith("/api/read/")
            val requestWithHeaders = if (isReadEndpoint) {
                request.newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build()
            } else {
                request
            }
            val response = chain.proceed(requestWithHeaders)

            if (!isReadEndpoint) {
                return@addInterceptor response
            }

            if (!response.isSuccessful) {
                return@addInterceptor response
            }
            val responseBody = response.body
            val encryptedBytes = responseBody.bytes()
            val decrypted = PlumaImageDecrypt.decryptImageBytes(encryptedBytes)

            response.newBuilder()
                .body(decrypted.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
        .build()

    override val versionId = 5

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val suffix = if (page > 1) "&page=$page" else ""
        return GET("$baseUrl/series?sort=popular$suffix", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.group[href*=series]").map { element ->
            SManga.create().apply {
                title = element.selectFirst("h3")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.selectFirst("a[href*='page=${currentPage + 1}']") != null
        return MangasPage(mangas, hasNextPage = hasNextPage)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val suffix = if (page > 1) "?page=$page" else ""
        return GET("$baseUrl/series$suffix", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.results.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl/api/cover/${it.coverPath}"
                url = "/series/${it.slug}"
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("meta[property*=title]")!!.text().substringBeforeLast("|")
            thumbnail_url = document.selectFirst("img.cover-img")?.absUrl("src")
            description = document.selectFirst("div.card > p.text-sm")?.text()
            genre = document.select(".flex.flex-wrap > span").joinToString { it.text() }
            document.selectFirst(".flex.items-center span.text-xs.font-bold.uppercase:last-child")?.text()?.let {
                status = when (it.lowercase()) {
                    "em andamento" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
            setUrlWithoutDomain(document.location())
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".card a[href*=ler]").mapIndexed { index, element ->
            SChapter.create().apply {
                name = element.selectFirst("span:first-child")!!.text()
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val chapterUrl = document.location()
        val chapterId = drmHelper.extractChapterId(chapterUrl)
        val payload = drmHelper.extractReaderPayload(document, chapterId)
            ?: throw IllegalStateException("Missing reader payload")
        chapterTokenById[chapterId] = payload.token

        return payload.pageNumbers.mapIndexed { index, pageNumber ->
            Page(index, chapterUrl, drmHelper.buildPageImageUrl(chapterId, pageNumber))
        }
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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("Missing image URL")
        val chapterId = imageUrl.toHttpUrl().pathSegments.getOrNull(2) ?: throw IllegalStateException("Missing chapter id")
        val token = chapterTokenById[chapterId] ?: throw IllegalStateException("Missing chapter token")

        val imageHeaders = headers.newBuilder()
            .set("Referer", page.url)
            .set("X-Pluma-Token", token)
            .set("Accept-Encoding", "identity")
            .build()

        return GET(imageUrl, imageHeaders)
    }

    // Utils

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
}
