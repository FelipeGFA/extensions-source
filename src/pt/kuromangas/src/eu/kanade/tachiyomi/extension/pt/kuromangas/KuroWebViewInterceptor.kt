package eu.kanade.tachiyomi.extension.pt.kuromangas

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KuroWebViewInterceptor {

    @SuppressLint("SetJavaScriptEnabled")
    fun getLocalStorageToken(baseUrl: String): String? {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var token: String? = null
        var webView: WebView? = null

        handler.post {
            runCatching {
                val currentWebView = createWebView().also { webView = it }

                configureCookies(currentWebView)
                currentWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                }

                currentWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(LOCAL_STORAGE_TOKEN_SCRIPT) { value ->
                            token = value
                                ?.takeUnless { it == "null" }
                                ?.removeSurrounding("\"")
                                ?.takeIf { it.isNotBlank() }
                            latch.countDown()
                        }
                    }
                }

                currentWebView.loadDataWithBaseURL("$baseUrl/", " ", "text/html", null, null)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            destroyWebView(webView)
        }

        return token
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getDecryptedData(pageUrl: String, expectPages: Boolean): String? {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = KuroJsInterface(latch)
        var webView: WebView? = null
        val hookScript = getHookScript()

        handler.post {
            runCatching {
                val currentWebView = createWebView().also { webView = it }

                configureCookies(currentWebView)
                currentWebView.clearCache(true)
                currentWebView.clearHistory()
                currentWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = MOBILE_USER_AGENT
                }
                currentWebView.addJavascriptInterface(jsInterface, JS_INTERFACE_NAME)

                currentWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view.evaluateJavascript("window.__kuroExpectPages = $expectPages;", null)
                        view.evaluateJavascript(hookScript, null)
                    }
                }

                currentWebView.loadUrl(pageUrl)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val capturedData = jsInterface.capturedData
        handler.post {
            destroyWebView(webView)
        }

        return capturedData.takeIf { it.isNotEmpty() }
    }

    private fun createWebView(): WebView = WebView(Injekt.get<Application>())

    private fun configureCookies(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun destroyWebView(webView: WebView?) {
        webView?.run {
            stopLoading()
            destroy()
        }
    }

    private fun getHookScript(): String = webviewHookScript.replace("__IFACE_PLACEHOLDER__", JS_INTERFACE_NAME)

    private class KuroJsInterface(private val latch: CountDownLatch) {
        @Volatile
        var capturedData = ""

        @JavascriptInterface
        fun onDataParsed(data: String) {
            if (capturedData.isNotEmpty() || data.length <= MIN_CAPTURE_LENGTH) return

            capturedData = data
            latch.countDown()
        }

        @JavascriptInterface
        fun onDebug(message: String) = Unit
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MIN_CAPTURE_LENGTH = 50
        private const val JS_INTERFACE_NAME = "KuroBridge"
        private const val LOCAL_STORAGE_TOKEN_SCRIPT = "window.localStorage.getItem('token')"
        private const val HOOK_SCRIPT_PATH = "/assets/webview-hook.js"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val webviewHookScript by lazy {
            KuroWebViewInterceptor::class.java.getResource(HOOK_SCRIPT_PATH)?.readText()
                ?: error("webview-hook.js nao foi encontrado no classpath")
        }
    }
}
