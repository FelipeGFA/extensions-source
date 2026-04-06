package eu.kanade.tachiyomi.extension.pt.mangastop

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MangaStop :
    MangaThemesia(
        "Manga Stop",
        "https://mangastop.net",
        "pt-BR",
        dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("pt", "BR")),
    ),
    ConfigurableSource {

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            // For covers
            if (chain.request().url.host.contains("images")) {
                val newRequest = request.newBuilder().apply {
                    header("Sec-Fetch-Dest", "image")
                    header("Sec-Fetch-Mode", "no-cors")
                    header("Sec-Fetch-Site", "same-site")
                }.build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        .addNetworkInterceptor(
            CookieInterceptor(baseUrl.substringAfter("//"), "wpmanga-ada" to "1"),
        )
        .addInterceptor(ClientHintsInterceptor())
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .setRandomUserAgent()

    override fun getMangaUrl(manga: SManga) = manga.url.toAbsoluteUrl()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url.toAbsoluteUrl(), headers)

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        url = url.toRelativeUrl()
    }

    override fun chapterFromElement(element: Element) = super.chapterFromElement(element).apply {
        url = url.toRelativeUrl()
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = super.pageListParse(document)
            .map { page ->
                Page(
                    page.index,
                    page.url.toAbsoluteUrl(),
                    page.imageUrl?.toAbsoluteUrl(),
                )
            }
            .filterNot { it.imageUrl?.contains("mihon", true) == true }

        if (pages.isNotEmpty()) return pages

        return MangaThemesia.JSON_IMAGE_LIST_REGEX.find(document.toString())
            ?.groupValues?.get(1)
            ?.let { json.parseToJsonElement(it).jsonArray }
            ?.mapIndexed { i, el ->
                Page(i, document.location().toAbsoluteUrl(), el.jsonPrimitive.content.toAbsoluteUrl())
            }
            .orEmpty()
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-site")
            .set("Referer", page.url.toAbsoluteUrl())
            .build()

        return GET(page.imageUrl!!.toAbsoluteUrl(), newHeaders)
    }

    override fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }.unwrapProtectedUrl()

    private fun String.toAbsoluteUrl(): String {
        val cleanUrl = unwrapProtectedUrl()
        return if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            cleanUrl
        } else {
            "$baseUrl${if (cleanUrl.startsWith("/")) cleanUrl else "/$cleanUrl"}"
        }
    }

    private fun String.toRelativeUrl(): String {
        val cleanUrl = unwrapProtectedUrl()
        return cleanUrl.removePrefix(baseUrl).ifBlank { cleanUrl }
    }

    private fun String.unwrapProtectedUrl(): String {
        val authPayload = when {
            contains(AUTH_QUERY_MARKER) -> substringAfter(AUTH_QUERY_MARKER)
            contains(AUTH_PARAM_MARKER) -> substringAfter(AUTH_PARAM_MARKER)
            contains(AUTH_TOKEN_MARKER) -> substringAfter(AUTH_TOKEN_MARKER)
            else -> ""
        }
            .substringBefore('&')
            .substringBefore('#')

        if (authPayload.isNotBlank()) {
            return authPayload.decodeBase64OrSelf()
        }

        val securePayload = SECURE_LINK_MARKERS.firstNotNullOfOrNull { marker ->
            substringAfter(marker, "").takeIf { it.isNotBlank() }
        }
            ?.substringBefore('?')
            ?.substringBefore('#')

        if (!securePayload.isNullOrBlank()) {
            return securePayload.decodeBase64OrSelf()
        }

        return this
    }

    private fun String.decodeBase64OrSelf(): String {
        val encoded = this
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(encoded)
    }

    override fun getFilterList(): FilterList {
        val filters = super.getFilterList().filterNot { it is AuthorFilter || it is YearFilter }
        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    private companion object {
        const val AUTH_QUERY_MARKER = "?_auth="
        const val AUTH_PARAM_MARKER = "&_auth="
        const val AUTH_TOKEN_MARKER = "?_token="

        val SECURE_LINK_MARKERS = arrayOf(
            "/secure-v87/check-access/",
            "secure-v87/check-access/",
        )
    }
}
