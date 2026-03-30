package eu.kanade.tachiyomi.extension.pt.yomumangas

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YomuMangas : HttpSource() {

    override val name = "Yomu Mangas"

    override val baseUrl = "https://yomumangas.com"

    override val lang = "pt-BR"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    private val apiHeaders: Headers by lazy { apiHeadersBuilder().build() }
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val webViewCookieManager by lazy { CookieManager.getInstance() }
    private var chapterSession: ChapterSession? = null

    private val pagesClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().withSessionCookies())
        }
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", ACCEPT_JSON)
        .add("Content-Type", ACCEPT_JSON)

    override fun popularMangaRequest(page: Int): Request = mangaListRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterState = filters.toSearchFilterState()
        val urlBuilder = "$API_URL/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("hentai", filterState.showHentai.toString())
            .addQueryParameter("nsfw", filterState.showNsfw.toString())

        filterState.selectedFormat?.let { urlBuilder.addQueryParameter("type", it) }
        filterState.selectedStatus?.let { urlBuilder.addQueryParameter("status", it) }
        filterState.includedGenres.forEach { urlBuilder.addQueryParameter("genres", it) }
        filterState.includedTags.forEach { urlBuilder.addQueryParameter("tags", it) }

        return GET(urlBuilder.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<YomuMangasSearchDto>()
        val mangas = result.mangas.map(YomuMangasMangaDto::toSManga)

        return MangasPage(mangas, result.hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$API_URL/mangas/${mangaIdFromUrl(manga.url)}", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<YomuMangasDetailsDto>().manga.toSManga()

    override fun chapterListRequest(manga: SManga): Request = GET("$API_URL/mangas/${mangaIdFromUrl(manga.url)}/chapters", apiHeaders).newBuilder()
        .tag(String::class.java, mangaSlugFromUrl(manga.url))
        .build()

    override fun chapterListParse(response: Response) = response.parseAs<YomuMangasChaptersDto>().run {
        val mangaId = response.request.url.pathSegments[1].toInt()
        val slug = response.request.tag(String::class.java).orEmpty()
        val scansById = scans.associateBy(YomuMangasScanDto::id)

        chapters
            .sortedWith(
                compareByDescending<YomuMangasChapterDto> { it.volume ?: Int.MIN_VALUE }
                    .thenByDescending { it.chapter.toFloatOrNull() ?: Float.MIN_VALUE }
                    .thenByDescending(YomuMangasChapterDto::id),
            )
            .map { it.toSChapter(mangaId, slug, scansById) }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        fetchPageListWithWebView(chapter)
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", pageHeaders(chapter))

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("li[data-type=page] img[src]").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = yomuMangasFilters

    private fun mangaListRequest(page: Int): Request {
        val url = "$API_URL/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("query", "")
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, apiHeaders)
    }

    private fun mangaIdFromUrl(url: String): Int = url.trim('/').split('/')[1].toInt()

    private fun mangaSlugFromUrl(url: String): String = url.trim('/').split('/')[2]

    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchPageListWithWebView(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var pages: List<Page>? = null
        var mainFrameError: Throwable? = null
        var challengeUrl: String? = null

        handler.post {
            val innerWebView = WebView(context)
            webView = innerWebView

            with(innerWebView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = true
                chapterSession?.userAgent?.let { userAgentString = it }
            }

            innerWebView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url?.contains("__cf_chl_tk=") == true) {
                        challengeUrl = url
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val currentView = view ?: return
                    handler.postDelayed({
                        currentView.evaluateJavascript(EXTRACT_PAGE_IMAGE_SCRIPT) { value ->
                            val imageUrls = parseJavascriptStringArray(value)
                            if (imageUrls.isEmpty() || pages != null) {
                                return@evaluateJavascript
                            }

                            chapterSession = ChapterSession(
                                chapterUrl = chapterUrl,
                                userAgent = currentView.settings.userAgentString,
                                referrer = challengeUrl ?: url ?: "$baseUrl/",
                            )
                            pages = imageUrls.mapIndexed { index, imageUrl ->
                                Page(index, imageUrl = imageUrl)
                            }
                            latch.countDown()
                        }
                    }, PAGE_EXTRACTION_DELAY)
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    if (failingUrl == chapterUrl) {
                        mainFrameError = IOException(description ?: CHAPTER_LOAD_ERROR_MESSAGE)
                    }
                }
            }
            innerWebView.loadUrl(chapterUrl)
        }

        latch.await(PAGE_LOAD_TIMEOUT, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        return pages ?: throw (mainFrameError ?: IOException(CHAPTER_LOAD_ERROR_MESSAGE))
    }

    private fun fetchPageListWithRequest(chapter: SChapter): List<Page> = pagesClient.newCall(pageListRequest(chapter)).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        pageListParse(response)
    }

    private fun pageHeaders(chapter: SChapter): Headers = Headers.Builder()
        .add("User-Agent", chapterSession?.userAgent ?: DEFAULT_PAGE_USER_AGENT)
        .add("Accept", PAGE_ACCEPT)
        .add("Accept-Language", PAGE_ACCEPT_LANGUAGE)
        .add(
            "Referer",
            chapterSession
                ?.takeIf { it.chapterUrl == "$baseUrl${chapter.url}" }
                ?.referrer
                ?: "$baseUrl/",
        )
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Priority", "u=0, i")
        .add("Pragma", "no-cache")
        .add("Cache-Control", "no-cache")
        .build()

    private fun Request.withSessionCookies(): Request {
        val cookies = webViewCookieManager.getCookie(url.toString())?.takeIf(String::isNotBlank) ?: return this
        val cookieHeader = buildList {
            header("Cookie")?.takeIf(String::isNotBlank)?.let(::add)
            add(cookies)
        }.joinToString("; ")

        return newBuilder()
            .header("Cookie", cookieHeader)
            .build()
    }

    private fun parseJavascriptStringArray(value: String?): List<String> {
        if (value.isNullOrBlank() || value == "null") return emptyList()

        return runCatching {
            JSONArray(value).let { array ->
                List(array.length(), array::getString)
            }
        }.getOrDefault(emptyList())
    }

    private data class ChapterSession(
        val chapterUrl: String,
        val userAgent: String?,
        val referrer: String?,
    )

    companion object {
        private const val ACCEPT_JSON = "application/json"
        private const val PAGE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val PAGE_ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val DEFAULT_PAGE_USER_AGENT = "Mozilla/5.0 (Android 16; Mobile; rv:148.0) Gecko/148.0 Firefox/148.0"
        private const val PAGE_LOAD_TIMEOUT = 20L
        private const val PAGE_EXTRACTION_DELAY = 750L
        private const val CHAPTER_LOAD_ERROR_MESSAGE = "Abra o capitulo no WebView para atualizar a sessao do Cloudflare"
        private const val EXTRACT_PAGE_IMAGE_SCRIPT =
            """(function(){return Array.from(document.querySelectorAll('li[data-type="page"] img[src]')).map(function(image){return image.src;}).filter(Boolean);})();"""

        internal const val API_URL = "https://api.yomumangas.com"

        internal fun mangaUrl(id: Int, slug: String, chapter: String? = null): String = buildString {
            append("/mangas/")
            append(id)
            append('/')
            append(slug)
            chapter?.let {
                append('/')
                append(it)
            }
        }

        internal fun toImageUrl(image: String?): String? {
            val value = image?.takeIf(String::isNotBlank) ?: return null
            val normalized = if (value.startsWith("mangas/")) "s3://$value" else value
            val type = listOf("s3://", "cdn://", "b2://").firstOrNull(normalized::startsWith)
                ?: return null
            val (scheme, path) = normalized.split("://", limit = 2)
            val prefix = if (type == "s3://") "images/" else ""

            return "https://$scheme.yomumangas.com/$prefix$path"
        }
    }
}
