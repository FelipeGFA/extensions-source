package eu.kanade.tachiyomi.extension.pt.argoscomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException

class ArgosComics : HttpSource() {

    override val name: String = "Argos Comics"

    override val baseUrl: String = "https://aniargos.com"

    override val lang: String = "pt-BR"

    override val supportsLatest: Boolean = true

    override val versionId: Int = 3
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(10)
        .build()

    private val authLock = Any()

    @Volatile
    private var isAuthenticated = false

    private val nextActionCache = mutableMapOf<String, String>()
    private val pageChunksCache = mutableMapOf<String, List<String>>()

    private val popularToken: String by lazy {
        resolveNextAction(baseUrl, NextAction.POPULAR)
    }

    private val searchToken: String by lazy {
        resolveNextAction(baseUrl, NextAction.SEARCH)
    }

    override fun getFilterList(): FilterList = getArgosComicsFilterList()

    // ======================== Popular =============================

    override fun popularMangaRequest(page: Int): Request {
        ensureLoggedIn()

        val popularHeaders = headers.newBuilder()
            .set("Referer", baseUrl)
            .set("Next-Action", popularToken)
            .build()

        val payload = listOf(POPULAR_PERIOD)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(baseUrl, popularHeaders, payload)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.extractNextJs<List<MangaDto>>()
            ?: throw IOException("Nao foi possivel extrair a lista de populares")
        return MangasPage(dto.map(MangaDto::toSManga), false)
    }

    // ======================== Latest =============================

    override fun latestUpdatesRequest(page: Int): Request {
        ensureLoggedIn()

        return GET(
            "$baseUrl/api/home",
            headers.newBuilder()
                .set("Referer", baseUrl)
                .build(),
        )
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<HomeDto>()
        return MangasPage(dto.lastUpdates.map(MangaDto::toSManga), false)
    }

    // ======================== Search =============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        ensureLoggedIn()

        val selectedFilter = filters.selectedArgosComicsFilter()
        if (selectedFilter != null) {
            return projectsFilteredRequest(page, selectedFilter)
        }

        if (query.isBlank()) {
            return allProjectsRequest(page)
        }

        val searchHeaders = headers.newBuilder()
            .set("Referer", baseUrl)
            .set("Next-Action", searchToken)
            .build()

        val payload = listOf(query)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(baseUrl, searchHeaders, payload)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath == PROJECTS_PATH) {
            val dto = response.extractNextJs<ProjectsPageDto>()
                ?: throw IOException("Nao foi possivel extrair a lista de projetos")
            return MangasPage(dto.projects.map(MangaDto::toSManga), dto.pagination.hasNextPage)
        }

        val dto = response.extractNextJs<List<MangaDto>>()
            ?: throw IOException("Nao foi possivel extrair a lista de busca")
        return MangasPage(dto.map(MangaDto::toSManga), false)
    }

    // ======================== Details =============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        ensureLoggedIn()

        val pageUrl = getMangaUrl(manga)
        val detailsHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Next-Action", resolveNextAction(pageUrl, NextAction.DETAILS))
            .build()

        val payload = getRoutePayload(pageUrl)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(pageUrl, detailsHeaders, payload)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.extractNextJs<MangaDetailsDto>()
        ?.toSManga()
        ?: throw IOException("Nao foi possivel extrair os detalhes da obra")

    // ======================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        ensureLoggedIn()

        val pageUrl = getMangaUrl(manga)
        val chaptersHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Next-Action", resolveNextAction(pageUrl, NextAction.CHAPTERS))
            .build()

        val payload = getRoutePayload(pageUrl)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(pageUrl, chaptersHeaders, payload)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val pathSegment = response.request.url.toString().substringAfter(baseUrl)
        val dto = response.extractNextJs<VolumeChapterDto>()
            ?: throw IOException("Nao foi possivel extrair a lista de capitulos")
        return dto.toChapterList(pathSegment)
    }

    // ======================== Pages =============================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request {
        ensureLoggedIn()

        val pageUrl = getChapterUrl(chapter)
        val pagesHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Next-Action", resolveNextAction(pageUrl, NextAction.PAGES))
            .build()

        val payload = getPagePayload(pageUrl)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(pageUrl, pagesHeaders, payload)
    }

    override fun pageListParse(response: Response): List<Page> = response.extractNextJs<PagesDto>()
        ?.toPageList()
        ?: throw IOException("Nao foi possivel extrair a lista de paginas")

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ======================== Helpers =============================

    private fun allProjectsRequest(page: Int): Request {
        val pageUrl = "$baseUrl$PROJECTS_PATH"
        val requestHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Next-Action", resolveNextAction(pageUrl, NextAction.PROJECTS))
            .build()

        val payload = listOf(page)
            .toJsonString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(pageUrl, requestHeaders, payload)
    }

    private fun projectsFilteredRequest(page: Int, filter: ArgosComicsFilterValue): Request {
        val pageUrl = "$baseUrl$PROJECTS_PATH"
        val requestHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Next-Action", resolveNextAction(pageUrl, NextAction.FILTERED_PROJECTS))
            .build()

        val payload = buildJsonArray {
            add(JsonPrimitive(filter.filter))
            add(JsonPrimitive(filter.term))
            add(JsonPrimitive(page))
        }.toString()
            .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

        return POST(pageUrl, requestHeaders, payload)
    }

    private fun ensureLoggedIn(force: Boolean = false) {
        if (!force && isAuthenticated) return

        synchronized(authLock) {
            if (!force && isAuthenticated) return

            val loginUrl = "$baseUrl/login?redirect=%2F"

            val loginHeaders = headers.newBuilder()
                .set("Referer", loginUrl)
                .set("Next-Action", resolveNextAction(loginUrl, NextAction.LOGIN))
                .set("Next-Router-State-Tree", LOGIN_ROUTER_STATE_TREE)
                .set("Origin", baseUrl)
                .build()

            val payload = listOf(LOGIN_EMAIL, LOGIN_PASSWORD)
                .toJsonString()
                .toRequestBody(TEXT_PLAIN_MEDIA_TYPE)

            client.newCall(POST(loginUrl, loginHeaders, payload)).execute().use { response ->
                val loginResponse = response.extractNextJs<LoginResponseDto> { element ->
                    element is JsonObject && ("user" in element || "message" in element)
                } ?: throw IOException("Nao foi possivel extrair a resposta de login")
                if (loginResponse.user == null) {
                    throw IOException(loginResponse.message ?: "Falha ao autenticar na fonte")
                }
            }

            pageChunksCache.clear()
            nextActionCache.clear()

            client.newCall(GET(baseUrl, headers)).execute().use { response ->
                if (isLoginResponse(response.request.url.toString())) {
                    throw IOException("Falha ao validar a sessao da fonte")
                }
            }

            isAuthenticated = true
        }
    }

    private fun resolveNextAction(pageUrl: String, action: NextAction): String {
        return nextActionCache.getOrPut(action.actionName) {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque(getPageChunkUrls(pageUrl))

            while (queue.isNotEmpty()) {
                val chunkUrl = queue.removeFirst()
                if (!visited.add(chunkUrl)) continue

                val script = client.newCall(GET(chunkUrl, headers)).execute().body.string()
                createNextActionRegex(action.actionName).find(script)?.groupValues?.get(1)?.let {
                    return@getOrPut it
                }

                extractChunkUrls(script)
                    .filterNot(visited::contains)
                    .forEach(queue::addLast)
            }

            throw IOException("Nao foi possivel encontrar token para ${action.actionName}")
        }
    }

    private fun getPageChunkUrls(pageUrl: String): List<String> {
        if (requiresAuthentication(pageUrl)) {
            ensureLoggedIn()
        }

        return pageChunksCache.getOrPut(pageUrl) {
            val document = client.newCall(GET(pageUrl, headers)).execute().asJsoup()
            if (requiresAuthentication(pageUrl) && isLoginResponse(document.location())) {
                pageChunksCache.remove(pageUrl)
                isAuthenticated = false
                ensureLoggedIn(force = true)
                return@getOrPut getPageChunkUrls(pageUrl)
            }

            val chunkUrls = linkedSetOf<String>()

            document.select("script:not([src])").forEach {
                extractChunkUrls(it.data()).forEach(chunkUrls::add)
            }

            document.select("script[src], link[rel=preload][as=script][href]").forEach {
                val attribute = if (it.hasAttr("src")) "src" else "href"
                it.absUrl(attribute)
                    .takeIf(::isChunkUrl)
                    ?.takeUnless(::shouldIgnoreChunkUrl)
                    ?.let(chunkUrls::add)
            }

            extractChunkUrls(document.html()).forEach(chunkUrls::add)

            chunkUrls.toList()
        }
    }

    private fun requiresAuthentication(pageUrl: String): Boolean = !isLoginResponse(pageUrl)

    private fun isLoginResponse(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return httpUrl.encodedPath.startsWith("/login")
    }

    private fun extractChunkUrls(content: String): List<String> {
        val chunkUrls = linkedSetOf<String>()

        CHUNK_PATH_REGEX.findAll(content).forEach {
            normalizeChunkUrl(it.value)?.let(chunkUrls::add)
        }

        return chunkUrls.toList()
    }

    private fun normalizeChunkUrl(path: String): String? {
        val normalizedUrl = when {
            path.startsWith("https://") || path.startsWith("http://") -> path
            path.startsWith("/_next/static/chunks/") -> "$baseUrl$path"
            path.startsWith("static/chunks/") -> "$baseUrl/_next/$path"
            else -> null
        }

        return normalizedUrl
            ?.takeIf(::isChunkUrl)
            ?.takeUnless(::shouldIgnoreChunkUrl)
    }

    private fun isChunkUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return httpUrl.encodedPath.contains("/_next/static/chunks/") && httpUrl.encodedPath.endsWith(".js")
    }

    private fun shouldIgnoreChunkUrl(url: String): Boolean {
        val chunkName = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return false
        return IGNORED_CHUNK_PREFIXES.any(chunkName::startsWith)
    }

    private fun getRoutePayload(pageUrl: String): List<String> = pageUrl.toHttpUrl().pathSegments.take(2)

    private fun getPagePayload(pageUrl: String): List<String> {
        val segments = pageUrl.toHttpUrl().pathSegments
        return listOf(segments.first(), segments.last())
    }

    companion object {
        private const val POPULAR_PERIOD = "all_time"
        private const val PROJECTS_PATH = "/projetos"
        private const val LOGIN_ROUTER_STATE_TREE =
            """["",{"children":["login",{"children":["__PAGE__",{},null,null]}]},null,null,true]"""
        private const val LOGIN_EMAIL = "tetek88238@kobace.com"
        private const val LOGIN_PASSWORD = "tetek88238@kobace.com"

        private val TEXT_PLAIN_MEDIA_TYPE = "text/plain;charset=UTF-8".toMediaTypeOrNull()
        private val CHUNK_PATH_REGEX = """(?:/_next/)?static/chunks/[A-Za-z0-9._-]+\.js""".toRegex()
        private val IGNORED_CHUNK_PREFIXES = listOf("turbopack-")

        private fun createNextActionRegex(actionName: String): Regex = "createServerReference\\)?\\(\"([^\"]+)\"[^)]*\"${Regex.escape(actionName)}\"".toRegex()
    }

    private enum class NextAction(val actionName: String) {
        LOGIN("login"),
        POPULAR("getMoreReadsProjects"),
        SEARCH("search"),
        PROJECTS("getAllWithoutFilters"),
        FILTERED_PROJECTS("getProjectsFiltered"),
        DETAILS("getOne"),
        CHAPTERS("getAllChapters"),
        PAGES("getPages"),
    }
}
