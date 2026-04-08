package eu.kanade.tachiyomi.extension.pt.kuromangas

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class KuroWebViewDecrypt(
    private val baseUrl: String,
    private val tokenProvider: () -> String,
    private val webViewInterceptor: KuroWebViewInterceptor = KuroWebViewInterceptor(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (!url.contains("/api/")) {
            return chain.proceed(request)
        }

        val token = tokenProvider()
        if (token.isBlank()) {
            failLoginRequired()
        }

        val decryptedData = tryWebViewDecrypt(url)
        if (decryptedData == null) {
            failLoginRequired()
        }

        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(decryptedData.toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun tryWebViewDecrypt(apiUrl: String): String? {
        val pageUrl = getPageUrlForApi(apiUrl)
        val expectPages = apiUrl.contains("/api/chapters/") && apiUrl.contains("/read")
        return webViewInterceptor.getDecryptedData(pageUrl, expectPages)
    }

    private fun getPageUrlForApi(apiUrl: String) = when {
        apiUrl.contains("/api/mangas") && !apiUrl.contains("/api/mangas/") -> buildCatalogoUrl(apiUrl)
        apiUrl.contains("/api/mangas/") -> "$baseUrl/manga/${apiUrl.substringAfter("/api/mangas/").substringBefore("?")}"
        apiUrl.contains("/api/chapters/") && apiUrl.contains("/read") -> {
            val chapterId = apiUrl.substringAfter("/api/chapters/").substringBefore("/read")
            val mangaId = apiUrl.substringAfter("manga_id=", "").substringBefore("&").ifEmpty { "0" }
            "$baseUrl/reader/$mangaId/$chapterId"
        }
        else -> "$baseUrl/catalogo"
    }

    private fun buildCatalogoUrl(apiUrl: String): String {
        val sourceUrl = apiUrl.toHttpUrl()
        val targetUrl = "$baseUrl/catalogo".toHttpUrl().newBuilder()

        sourceUrl.queryParameter("page")?.takeIf { it != "1" }?.let {
            targetUrl.addQueryParameter("page", it)
        }
        sourceUrl.queryParameter("sort")?.takeIf { it.isNotBlank() }?.let {
            targetUrl.addQueryParameter("sort", it)
        }
        sourceUrl.queryParameter("search")?.takeIf { it.isNotBlank() }?.let {
            targetUrl.addQueryParameter("search", it)
        }

        return targetUrl.build().toString()
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun failLoginRequired(): Nothing {
        val message = "Faca login na WebView da KuroMangas e tente novamente"
        showToast(message)
        throw IOException(message)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
