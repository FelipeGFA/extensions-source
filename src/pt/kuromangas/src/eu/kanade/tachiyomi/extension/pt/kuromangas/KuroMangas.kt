package eu.kanade.tachiyomi.extension.pt.kuromangas

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KuroMangas : HttpSource() {

    override val name = "KuroMangas"
    override val baseUrl = "https://beta.kuromangas.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"
    private val cdnUrl = "https://cdn.kuromangas.com"

    private val preferences by getPreferencesLazy()
    private val webViewInterceptor = KuroWebViewInterceptor()

    override val client by lazy {
        val cdnHost = cdnUrl.toHttpUrl().host

        network.cloudflareClient.newBuilder()
            .rateLimit(2)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == cdnHost) {
                    return@addInterceptor chain.proceed(request)
                }

                val token = getToken()
                val authenticatedRequest = request.withAuth(token)
                val response = chain.proceed(authenticatedRequest)

                if (response.code != 401) return@addInterceptor response

                response.close()
                clearToken()
                requireLoginInWebView()
            }
            .addInterceptor(
                KuroWebViewDecrypt(
                    baseUrl = baseUrl,
                    tokenProvider = { getToken() },
                    webViewInterceptor = webViewInterceptor,
                ),
            )
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")
        .add("Referer", baseUrl)

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun Request.withAuth(token: String): Request = if (token.isNotBlank()) {
        newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    } else {
        this
    }

    private fun getToken(): String {
        val savedToken = preferences.getString(PREF_TOKEN, "")?.trim().orEmpty()
        if (savedToken.isNotEmpty()) {
            return savedToken
        }

        val token = webViewInterceptor.getLocalStorageToken(baseUrl).orEmpty()
        if (token.isNotEmpty()) {
            preferences.edit().putString(PREF_TOKEN, token).apply()
        }
        return token
    }

    private fun clearToken() {
        preferences.edit().remove(PREF_TOKEN).apply()
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requireLoginInWebView(): Nothing {
        val message = "Faca login na WebView da KuroMangas e tente novamente"
        showToast(message)
        throw IOException(message)
    }

    private fun buildMangaListUrl(page: Int, sort: String, order: String = "DESC") = "$apiUrl/mangas".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", PAGE_LIMIT.toString())
        .addQueryParameter("sort", sort)
        .addQueryParameter("order", order)
        .build()

    private fun parseMangaList(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        return MangasPage(result.data.map { it.toSManga(cdnUrl) }, result.pagination.hasNextPage())
    }

    override fun popularMangaRequest(page: Int) = GET(buildMangaListUrl(page, "view_count"), headers)
    override fun popularMangaParse(response: Response) = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int) = GET(buildMangaListUrl(page, "created_at"), headers)
    override fun latestUpdatesParse(response: Response) = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) url.addQueryParameter("search", query)

        filters.filterIsInstance<SortFilter>().firstOrNull()?.let {
            url.addQueryParameter("sort", it.selectedSort)
            url.addQueryParameter("order", it.selectedOrder)
        } ?: run {
            url.addQueryParameter("sort", "created_at")
            url.addQueryParameter("order", "DESC")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = parseMangaList(response)

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/mangas/${manga.url.substringAfterLast("/")}", headers)

    override fun mangaDetailsParse(response: Response) = response.parseAs<MangaDetailsResponse>().manga.toSManga(cdnUrl)

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<MangaDetailsResponse>()
        return result.chapters.map { it.toSChapter(result.manga.id, dateFormat) }.sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (getToken().isEmpty()) requireLoginInWebView()

        val parts = chapter.url.split("/").filter { it.isNotEmpty() }
        val mangaId = parts.getOrNull(1) ?: ""
        val chapterId = parts.getOrNull(2) ?: chapter.url.substringAfterLast("/")
        val body = """{"page":1}""".toRequestBody("application/json".toMediaType())
        return POST("$apiUrl/chapters/$chapterId/read?manga_id=$mangaId", headers, body)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ChapterPagesResponse>().pages.mapIndexed { index, pageUrl ->
        val fixedUrl = pageUrl.replaceFirst("^/uploads/".toRegex(), "/")
        Page(index, imageUrl = if (fixedUrl.startsWith("http")) fixedUrl else "$cdnUrl$fixedUrl")
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url.substringAfterLast("/")}"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.removePrefix("/chapter/").split("/")
        return "$baseUrl/reader/${parts.getOrNull(0) ?: ""}/${parts.getOrNull(1) ?: ""}"
    }

    override fun getFilterList() = getFilters()

    companion object {
        private const val PAGE_LIMIT = 24
        private const val PREF_TOKEN = "pref_token"
    }
}
