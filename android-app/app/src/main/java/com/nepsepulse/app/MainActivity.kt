package com.nepsepulse.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.content.Intent
import android.view.ViewGroup

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val homeUrl =
        "https://anishacharya95.github.io/Nepse-pulse/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        webView.webViewClient = object : WebViewClient() {
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
