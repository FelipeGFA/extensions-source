package eu.kanade.tachiyomi.extension.pt.mugiwarasoficial

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class MugiwarasOficial :
    Madara(
        "Mugiwaras Oficial",
        "https://mugiwarasoficial.com",
        "pt-BR",
        SimpleDateFormat("d 'de' MMM 'de' yyyy", Locale("pt", "BR")),
    ) {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(2)
        .build()

    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Estado) + .summary-content"

    override fun pageListParse(response: Response): List<Page> {
        val chapterDocument = response.asJsoup()
        launchIO { countViews(chapterDocument) }

        val protectedImageUrl = extractProtectedImageUrl(chapterDocument)
        val readerUrl = "$baseUrl/campanha.php".toHttpUrl().newBuilder()
            .addQueryParameter("auth", protectedImageUrl)
            .build()

        val cookieHeader = buildCookieHeader(response, readerUrl)
        val readerRequest = GET(
            readerUrl,
            headersBuilder()
                .set("Referer", chapterDocument.location())
                .apply {
                    if (cookieHeader.isNotBlank()) {
                        set("Cookie", cookieHeader)
                    }
                }
                .build(),
        )

        val readerResponse = client.newCall(readerRequest).execute()
        if (!readerResponse.isSuccessful) {
            val code = readerResponse.code
            readerResponse.close()
            throw Exception("Falha ao abrir o leitor protegido (HTTP $code).")
        }

        return readerResponse.use { parseReaderPages(it.asJsoup()) }
    }

    private fun extractProtectedImageUrl(chapterDocument: Document): String {
        val protectedLink = chapterDocument
            .selectFirst("div.page-break.no-gaps.page-link-wrap a[href*='suaap.com/api/start/cpurl/']")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Nao foi possivel encontrar o link protegido do capitulo.")

        val normalizedLink = protectedLink
            .replace("&#038;", "&")
            .replace("&amp;", "&")

        val encodedAuth = authUrlRegex.find(normalizedLink)?.groupValues?.getOrNull(1)
            ?: throw Exception("Nao foi possivel extrair o parametro t do link protegido.")

        return URLDecoder.decode(encodedAuth, Charsets.UTF_8.name())
    }

    private fun buildCookieHeader(chapterResponse: Response, readerUrl: HttpUrl): String {
        val cookies = linkedMapOf<String, String>()

        client.cookieJar.loadForRequest(readerUrl).forEach { cookie ->
            cookies[cookie.name] = cookie.value
        }

        chapterResponse.headers("Set-Cookie")
            .mapNotNull(::parseSetCookie)
            .forEach { (name, value) ->
                cookies[name] = value
            }

        val prioritized = requiredCookies.mapNotNull { name ->
            cookies[name]?.let { value -> "$name=$value" }
        }

        val remaining = cookies
            .filterKeys { it !in requiredCookies }
            .map { (name, value) -> "$name=$value" }

        return (prioritized + remaining).joinToString("; ")
    }

    private fun parseSetCookie(setCookieHeader: String): Pair<String, String>? {
        val cookieValue = setCookieHeader.substringBefore(";")
        val separatorIndex = cookieValue.indexOf('=')
        if (separatorIndex <= 0) {
            return null
        }

        val name = cookieValue.substring(0, separatorIndex)
        val value = cookieValue.substring(separatorIndex + 1)
        if (name.isBlank() || value.isBlank()) {
            return null
        }

        return name to value
    }

    private fun parseReaderPages(readerDocument: Document): List<Page> {
        val readerUrl = readerDocument.location()
        val imageUrls = readerDocument.select("div.manga-content img")
            .mapNotNull(::imageFromElement)
            .filter(String::isNotBlank)

        if (imageUrls.isEmpty()) {
            throw Exception("Nenhuma imagem encontrada no leitor protegido.")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, readerUrl, imageUrl)
        }
    }

    companion object {
        private val requiredCookies = listOf("wpmanga-reading-history", "PHPSESSID", "cf_clearance")
        private val authUrlRegex = Regex("""(?:\?|&)t=([^&]+)""")
    }
}
