import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

object Auth {
    var client: dynamic = null
    var user: dynamic = null
    var ready: Boolean = false
    var lastError: String? = null

    /** True between opening the provider page and receiving its callback. */
    var oauthPending: Boolean = false
        private set

    private var callbackInFlight = false
    private var deepLinksRegistered = false
    private var pendingCallbackUrl: String? = null

    // Supabase stores the PKCE verifier under this flow id. It survives an app
    // restart only if we persist it, which a killed external-browser hand-off
    // requires.
    private var pendingFlowId: String? = null
    private val flowKey = "oauthFlow"
    private val handledCodes = LinkedHashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    val signedIn: Boolean get() = user != null && user != undefined

    fun displayName(): String {
        if (!signedIn) return "Guest"
        val metadata = user.user_metadata
        val name = text(pick(metadata, "full_name", "name", "display_name"))
        if (name != null) return name
        val email = text(user.email)
        if (email != null) return email.substringBefore("@")
        val phone = text(user.phone)
        if (phone != null) return phone
        return "NEPSE trader"
    }

    fun email(): String = text(user?.email) ?: "—"

    fun phone(): String = text(user?.phone) ?: "—"

    fun provider(): String {
        if (!signedIn) return "—"
        val provider = text(pick(user?.app_metadata, "provider"))
        return when (provider) {
            null -> "email"
            "email" -> "Email & password"
            "google" -> "Google"
            "facebook" -> "Facebook"
            "phone" -> "Phone OTP"
            else -> provider.replaceFirstChar { it.uppercase() }
        }
    }

    fun initials(): String {
        val name = displayName().trim()
        if (name.isEmpty()) return "NP"
        val parts = name.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        } else {
            name.take(2).uppercase()
        }
    }

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyAll() {
        for (listener in listeners.toList()) {
            try {
                listener()
            } catch (error: dynamic) {
                logError("auth listener", error)
            }
        }
    }

    private fun applySession(session: dynamic) {
        val next = session?.user
        user = if (next == null || next == undefined) null else next
        ready = true
        notifyAll()
    }

    fun init() {
        val host = window.asDynamic()
        val config = host.NEPSE_PULSE_AUTH
        val library = host.supabase
        // PKCE is mandatory for the native external-browser hand-off: the code
        // verifier stays in this WebView's storage while the provider runs in
        // the system browser, and the callback code is exchanged here.
        if (config != null && config != undefined && library != null && library != undefined) {
            val options = js("({auth:{persistSession:true,autoRefreshToken:true,detectSessionInUrl:true,flowType:'pkce'}})")
            try {
                client = library.createClient(config.supabaseUrl, config.supabaseAnonKey, options)
            } catch (error: dynamic) {
                logError("supabase init", error)
                lastError = "Could not start Supabase authentication: " + errText(error)
            }
        } else {
            lastError = "Supabase is not configured for this build."
            ready = true
        }
        pendingFlowId = Store.get(flowKey)
        if (client != null) {
            try {
                val callback: (dynamic, dynamic) -> Unit = { event, session ->
                    val name = event?.toString() ?: ""
                    if (name == "SIGNED_OUT") {
                        user = null
                        ready = true
                        oauthPending = false
                        notifyAll()
                    } else if (name == "SIGNED_IN" || name == "TOKEN_REFRESHED" ||
                        name == "INITIAL_SESSION" || name == "USER_UPDATED"
                    ) {
                        lastError = null
                        oauthPending = false
                        applySession(session)
                    }
                }
                client.auth.onAuthStateChange(callback)
            } catch (error: dynamic) {
                logError("auth state listener", error)
            }
            refreshSession()
        }
        registerDeepLinks()
    }

    /** Human-readable state for the Profile screen. */
    fun statusMessage(): String {
        if (client == null) return lastError ?: "Supabase is not configured for this build."
        if (!ready) return "Restoring your session…"
        if (signedIn) return "Signed in as " + (text(user?.email) ?: displayName())
        if (oauthPending) return "Complete sign-in in the opened window, then return to NEPSE Pulse."
        if (lastError != null) return lastError!!
        return "Not signed in."
    }

    fun refreshSession(done: (() -> Unit)? = null) {
        if (client == null) {
            ready = true
            done?.invoke()
            return
        }
        (client.auth.getSession()).then(
            { result: dynamic ->
                lastError = null
                applySession(result?.data?.session)
                done?.invoke()
                null
            },
            { error ->
                lastError = errText(error)
                ready = true
                notifyAll()
                done?.invoke()
                null
            }
        )
    }

    private fun nativePlatform(): Boolean {
        val host = window.asDynamic()
        val capacitor = host.Capacitor
        if (capacitor != null && capacitor != undefined &&
            capacitor.isNativePlatform != null && capacitor.isNativePlatform() == true
        ) return true
        // The committed native WebView container (android-app) exposes this
        // JSON bridge instead of Capacitor. It is a native host, so provider
        // sign-in must use the app deep link rather than an https redirect.
        return host.NP_NATIVE_APP != null && host.NP_NATIVE_APP != undefined
    }

    fun oauthRedirect(): String {
        return if (nativePlatform()) {
            "com.nepsepulse.app://login-callback"
        } else {
            window.location.origin + window.location.pathname
        }
    }

    private fun decode(value: String): String {
        return try {
            js("decodeURIComponent")(value) as String
        } catch (_: Throwable) {
            value
        }
    }

    /**
     * Reads the callback parameters from the custom scheme. The parameters may
     * arrive after `?` (PKCE code flow) or after `#` (implicit fallback).
     */
    private fun callbackParams(url: String): Map<String, String> {
        val query = when {
            url.contains("?") -> url.substringAfter("?")
            url.contains("#") -> url.substringAfter("#")
            else -> ""
        }.substringBefore("#")
        val params = LinkedHashMap<String, String>()
        if (query.isBlank()) return params
        for (part in query.split("&")) {
            if (part.isBlank()) continue
            val key = decode(part.substringBefore("="))
            val value = decode(part.substringAfter("=", ""))
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    private fun isCallback(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("com.nepsepulse.app://login-callback") ||
            lower.startsWith("com.nepsepulse.app:/login-callback")
    }

    private fun rememberFlow(flowId: String?) {
        pendingFlowId = flowId
        if (flowId.isNullOrBlank()) Store.remove(flowKey) else Store.put(flowKey, flowId)
    }

    private fun flowForCallback(params: Map<String, String>): String? {
        val state = params["state"]
        if (!state.isNullOrBlank()) return state
        if (!pendingFlowId.isNullOrBlank()) return pendingFlowId
        return Store.get(flowKey)
    }

    private fun closeBrowser() {
        val browser = window.asDynamic().NP_CAP_BROWSER
        if (browser == null || browser == undefined) return
        try {
            browser.close().then(
                { _: dynamic -> null },
                { _: dynamic -> null }
            )
        } catch (error: dynamic) {
            logError("close browser", error)
        }
    }

    /**
     * Single entry point for an OAuth callback, whether it arrives from the
     * Capacitor App plugin (`appUrlOpen`), from the cold-start launch URL or
     * from the native WebView bridge (`NP_onNativeDeepLink`).
     */
    fun handleDeepLink(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty() || !isCallback(url)) return
        if (client == null) {
            // Supabase may still be loading from the CDN; retry once it exists.
            pendingCallbackUrl = url
            window.setTimeout({
                val pending = pendingCallbackUrl
                pendingCallbackUrl = null
                if (pending != null) handleDeepLink(pending)
                null
            }, 700)
            return
        }
        val params = callbackParams(url)
        val error = params["error"]
        val description = params["error_description"] ?: params["error_code"]
        if (error != null && error.isNotBlank()) {
            oauthPending = false
            rememberFlow(null)
            lastError = callbackError(error, description)
            notifyAll()
            closeBrowser()
            return
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            val accessToken = params["access_token"]
            val refreshToken = params["refresh_token"]
            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                val tokens = js("({})")
                tokens.access_token = accessToken
                tokens.refresh_token = refreshToken
                client.auth.setSession(tokens).then(
                    { result ->
                        if (result?.error != null) lastError = result.error.message.toString()
                        else applySession(result?.data?.session)
                        oauthPending = false
                        notifyAll()
                        closeBrowser()
                        null
                    },
                    { failure ->
                        lastError = errText(failure)
                        oauthPending = false
                        notifyAll()
                        closeBrowser()
                        null
                    }
                )
                return
            }
            oauthPending = false
            lastError = "The sign-in provider returned no authorization code. Check that the Supabase redirect allow-list contains com.nepsepulse.app://login-callback."
            notifyAll()
            closeBrowser()
            return
        }
        if (callbackInFlight || handledCodes.contains(code)) return
        handledCodes.add(code)
        while (handledCodes.size > 20) handledCodes.remove(handledCodes.first())
        callbackInFlight = true
        // exchangeCodeForSession takes the raw authorization code, not the URL.
        // The callback `state` is the PKCE flow id: the verifier for a native
        // hand-off is stored under that flow, and the WebView URL is the app
        // origin rather than the callback URL, so pass it explicitly.
        val exchangeOptions = js("({})")
        val flowId = flowForCallback(params)
        if (!flowId.isNullOrBlank()) exchangeOptions.flowId = flowId
        client.auth.exchangeCodeForSession(code, exchangeOptions).then(
            { result ->
                callbackInFlight = false
                val failure = result?.error
                oauthPending = false
                if (failure == null) rememberFlow(null)
                if (failure != null) {
                    lastError = "Could not complete sign-in: " + (failure.message?.toString() ?: "unknown error")
                    notifyAll()
                } else {
                    lastError = null
                    applySession(result?.data?.session)
                    // Re-read from storage so the UI never shows a stale user.
                    refreshSession()
                }
                closeBrowser()
                null
            },
            { failure ->
                callbackInFlight = false
                oauthPending = false
                rememberFlow(null)
                lastError = "Could not complete sign-in: " + errText(failure)
                notifyAll()
                closeBrowser()
                null
            }
        )
    }

    private fun callbackError(error: String, description: String?): String {
        val detail = description?.takeIf { it.isNotBlank() }?.replace("+", " ")
        return when (error) {
            "access_denied" -> "Sign-in was cancelled" + (detail?.let { " ($it)" } ?: ".")
            "server_error" -> "The sign-in provider reported a server error" + (detail?.let { ": $it" } ?: ".")
            "temporarily_unavailable" -> "The sign-in provider is temporarily unavailable."
            else -> "Sign-in failed: " + error + (detail?.let { " — $it" } ?: "")
        }
    }

    private fun registerDeepLinks() {
        if (deepLinksRegistered) return
        deepLinksRegistered = true
        val host = window.asDynamic()
        val app = host.NP_CAP_APP
        if (app != null && app != undefined) {
            try {
                val listener: (dynamic) -> Unit = { event ->
                    handleDeepLink(event?.url?.toString() ?: "")
                }
                app.addListener("appUrlOpen", listener)
                // A deep link that cold-starts a killed app is delivered as the
                // launch URL instead of an appUrlOpen event.
                try {
                    app.getLaunchUrl().then(
                        { result: dynamic ->
                            val url = text(result?.url)
                            if (url != null) handleDeepLink(url)
                            null
                        },
                        { _: dynamic -> null }
                    )
                } catch (error: dynamic) {
                    logError("launch url", error)
                }
            } catch (error: dynamic) {
                logError("deep link listener", error)
            }
        }
        val browser = host.NP_CAP_BROWSER
        if (browser != null && browser != undefined) {
            try {
                val closed: (dynamic) -> Unit = {
                    if (oauthPending && !callbackInFlight) {
                        oauthPending = false
                        refreshSession()
                    }
                }
                browser.addListener("browserFinished", closed)
            } catch (error: dynamic) {
                logError("browser listener", error)
            }
        }
        // Callback entry point used by the committed native WebView container.
        try {
            host.NP_onNativeDeepLink = { url: dynamic ->
                handleDeepLink(text(url) ?: "")
                null
            }
        } catch (error: dynamic) {
            logError("native deep link bridge", error)
        }
    }

    fun startOAuth(provider: String, done: (String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.")
            return
        }
        if (oauthPending) {
            done("Sign-in is already open in the browser. Finish it there, then return to NEPSE Pulse.")
            return
        }
        lastError = null
        oauthPending = true
        val options = js("({})")
        options.provider = provider
        val redirect = js("({})")
        redirect.redirectTo = oauthRedirect()
        options.options = redirect
        val native = nativePlatform()
        if (native) redirect.skipBrowserRedirect = true
        (client.auth.signInWithOAuth(options)).then(
            { result ->
                if (result?.error != null) {
                    oauthPending = false
                    rememberFlow(null)
                    done(result.error.message.toString())
                } else {
                    rememberFlow(text(result?.data?.flowId))
                    val url = text(result?.data?.url)
                    if (url != null && native) {
                        val host = window.asDynamic()
                        val browser = host.NP_CAP_BROWSER
                        if (browser != null && browser != undefined) {
                            val openOptions = js("({})")
                            openOptions.url = url
                            browser.open(openOptions).then(
                                { done(null); null },
                                { failure ->
                                    oauthPending = false
                                    done("Could not open the sign-in browser: " + errText(failure))
                                    null
                                }
                            )
                        } else if (host.NP_NATIVE_APP != null && host.NP_NATIVE_APP != undefined) {
                            try {
                                host.NP_NATIVE_APP.openBrowser(url)
                                done(null)
                            } catch (failure: dynamic) {
                                oauthPending = false
                                done("Could not open the sign-in browser: " + errText(failure))
                            }
                        } else {
                            window.location.href = url
                            done(null)
                        }
                    } else {
                        done(null)
                    }
                }
                null
            },
            { error ->
                oauthPending = false
                rememberFlow(null)
                done(errText(error))
                null
            }
        )
    }

    fun signIn(email: String, password: String, done: (String?, String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.", null)
            return
        }
        val credentials = js("({})")
        credentials.email = email
        credentials.password = password
        (client.auth.signInWithPassword(credentials)).then(
            { result ->
                val error = result?.error
                if (error != null) {
                    done(error.message.toString(), null)
                } else {
                    applySession(result?.data?.session)
                    done(null, "Signed in as " + (text(result?.data?.user?.email) ?: email))
                }
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun signUp(email: String, password: String, done: (String?, String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.", null)
            return
        }
        val credentials = js("({})")
        credentials.email = email
        credentials.password = password
        (client.auth.signUp(credentials)).then(
            { result ->
                val error = result?.error
                if (error != null) {
                    done(error.message.toString(), null)
                } else if (result?.data?.session == null) {
                    done(null, "Account created. Check your email to confirm your address.")
                } else {
                    applySession(result.data.session)
                    done(null, "Account created and signed in.")
                }
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun resetPassword(email: String, done: (String?, String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.", null)
            return
        }
        val options = js("({})")
        options.redirectTo = oauthRedirect()
        (client.auth.resetPasswordForEmail(email, options)).then(
            { result ->
                val error = result?.error
                if (error != null) done(error.message.toString(), null)
                else done(null, "Password reset email requested for $email.")
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun sendOtp(phone: String, done: (String?, String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.", null)
            return
        }
        val credentials = js("({})")
        credentials.phone = phone
        (client.auth.signInWithOtp(credentials)).then(
            { result ->
                val error = result?.error
                if (error != null) done(error.message.toString(), null)
                else done(null, "Verification code requested for $phone.")
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun verifyOtp(phone: String, token: String, done: (String?, String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.", null)
            return
        }
        val credentials = js("({})")
        credentials.phone = phone
        credentials.token = token
        credentials.type = "sms"
        (client.auth.verifyOtp(credentials)).then(
            { result ->
                val error = result?.error
                if (error != null) {
                    done(error.message.toString(), null)
                } else {
                    applySession(result?.data?.session)
                    done(null, "Phone number verified.")
                }
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun signOut(done: (String?) -> Unit) {
        if (client == null) {
            done(null)
            return
        }
        (client.auth.signOut()).then(
            { result ->
                val error = result?.error
                if (error != null) {
                    done(error.message.toString())
                } else {
                    user = null
                    notifyAll()
                    done(null)
                }
                null
            },
            { error ->
                done(errText(error))
                null
            }
        )
    }

    fun loadWatchlist(done: (List<String>?, String?) -> Unit) {
        if (client == null || !signedIn) {
            done(null, "Sign in to sync your cloud watchlist.")
            return
        }
        (client.from("watchlists").select("symbol").order("symbol")).then(
            { result ->
                val error = result?.error
                if (error != null) {
                    done(null, error.message.toString())
                } else {
                    val symbols = rowsOf(result?.data).mapNotNull { text(pick(it, "symbol"))?.uppercase() }
                    done(symbols, null)
                }
                null
            },
            { error ->
                done(null, errText(error))
                null
            }
        )
    }

    fun addToWatchlist(symbol: String, done: (String?, String?) -> Unit) {
        if (client == null || !signedIn) {
            done(null, "Sign in to sync your cloud watchlist.")
            return
        }
        val record = js("({})")
        record.user_id = user.id
        record.symbol = symbol
        val options = js("({ onConflict: 'user_id,symbol' })")
        (client.from("watchlists").upsert(record, options)).then(
            { result ->
                val error = result?.error
                if (error != null) done(error.message.toString(), null)
                else done(null, "Added $symbol to your cloud watchlist.")
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun removeFromWatchlist(symbol: String, done: (String?, String?) -> Unit) {
        if (client == null || !signedIn) {
            done(null, "Sign in to sync your cloud watchlist.")
            return
        }
        val query = client.from("watchlists").delete().eq("user_id", user.id).eq("symbol", symbol)
        query.then(
            { result ->
                val error = result?.error
                if (error != null) done(error.message.toString(), null)
                else done(null, "Removed $symbol from your cloud watchlist.")
                null
            },
            { error ->
                done(errText(error), null)
                null
            }
        )
    }

    fun tokenExpiry(): String {
        if (!signedIn) return "—"
        val expires = num(user?.last_sign_in_at) ?: return "—"
        return "Signed in"
    }

    fun openDeleteAccount() {
        val page = window.location.origin + window.location.pathname.substringBeforeLast("/") + "/delete-account.html"
        if (nativePlatform()) {
            val browser = window.asDynamic().NP_CAP_BROWSER
            if (browser != null && browser != undefined) {
                val options = js("({})")
                options.url = page
                browser.open(options)
                return
            }
        }
        window.open(page, "_blank")
    }
}
