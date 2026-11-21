package com.nepsepulse.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView

    // A deep link can launch a cold process before the WebView has loaded the
    // Kotlin app, so it is queued until onPageFinished.
    private var pendingDeepLink: String? = null

    // Load the compiled Kotlin/JS app packaged into android_asset/www by CI.
    private val homeUrl = "https://anishacharya95.github.io/Nepse-pulse/"

    /**
     * Native side of the sign-in hand-off. The external provider page opens in
     * the system browser; the Supabase redirect returns through the
     * com.nepsepulse.app://login-callback intent filter and is delivered to the
     * single WebView that holds the PKCE verifier.
     */
    private inner class NativeAppBridge {
        @JavascriptInterface
        fun openBrowser(url: String) {
            if (url.isBlank()) return
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun isNative(): Boolean = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(NativeAppBridge(), "NP_NATIVE_APP")

        webView.webViewClient = object : WebViewClient() {
            // Keep the established HTTPS origin for Supabase OAuth/CORS while
            // serving the migrated Kotlin bundle from the APK itself.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.host != "anishacharya95.github.io" || !uri.path.orEmpty().startsWith("/Nepse-pulse")) {
                    return super.shouldInterceptRequest(view, request)
                }
                val asset = when (uri.path.orEmpty().removePrefix("/Nepse-pulse").ifEmpty { "/" }) {
                    "/", "/index.html" -> "www/index.html"
                    "/kotlin-web.js" -> "www/kotlin-web.js"
                    "/styles.css" -> "www/styles.css"
                    else -> return null
                }
                val mime = when {
                    asset.endsWith(".js") -> "application/javascript"
                    asset.endsWith(".css") -> "text/css"
                    else -> "text/html"
                }
                return try {
                    WebResourceResponse(mime, "UTF-8", assets.open(asset))
                } catch (_: Exception) {
                    null
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val host = uri.host ?: ""

                if (uri.scheme == "https" &&
                    (host == "anishacharya95.github.io" ||
                     host == "tradingview.com" ||
                     host.endsWith(".tradingview.com"))) {
                    return false
                }

                if (uri.scheme == "https" ||
                    uri.scheme == "http") {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, uri)
                    )
                    return true
                }

                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val queued = pendingDeepLink
                if (queued != null) {
                    pendingDeepLink = null
                    deliverDeepLink(queued)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        setContentView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        applySystemBarInsets()

        if (savedInstanceState == null) {
            webView.loadUrl(homeUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
        handleIntent(intent)
    }

    /**
     * Android 15 (API 35+) draws apps that target SDK 35 or higher
     * edge-to-edge: the WebView then extends behind the status and navigation
     * bars, which would hide the page header, the bottom navigation and the
     * bottom ad slot.
     *
     * The insets are consumed here as padding on the WebView itself, so the
     * page starts below the status bar and ends above the navigation bar while
     * scrolling still behaves normally. Because the WebView no longer overlaps
     * the bars, the page's own `env(safe-area-inset-*)` values become 0 and no
     * double padding is produced.
     *
     * On API 34 and below the window decor still fits the system bars, so no
     * padding is added there. Only non-deprecated framework APIs are used:
     * this replaces the deprecated status/navigation bar colour and
     * system-ui-visibility edge-to-edge handling entirely.
     */
    private fun applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        webView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        webView.requestApplyInsets()
    }

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "'" + escaped + "'"
    }

    private fun deliverDeepLink(rawUrl: String) {
        if (rawUrl.isBlank()) return
        if (webView.url == null) {
            pendingDeepLink = rawUrl
            return
        }
        val script = "(function(){var u=" + jsString(rawUrl) +
            ";if(window.NP_onNativeDeepLink){window.NP_onNativeDeepLink(u);}return true;})()"
        webView.evaluateJavascript(script, null)
    }

    private fun handleIntent(source: Intent?) {
        val uri = source?.data ?: return
        if (uri.scheme == "com.nepsepulse.app" && uri.host == "login-callback") {
            deliverDeepLink(uri.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
