
package com.nepsepulse.app

import android.app.Activity
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

        if (savedInstanceState == null) {
            webView.loadUrl(homeUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
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
