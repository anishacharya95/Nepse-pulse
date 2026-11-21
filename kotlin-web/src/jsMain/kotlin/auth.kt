import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

object Auth {
    var client: dynamic = null
    var user: dynamic = null
    var ready: Boolean = false
    var lastError: String? = null
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
        if (config != null && config != undefined && library != null && library != undefined) {
            val options = js("({auth:{persistSession:true,autoRefreshToken:true,detectSessionInUrl:true,flowType:'pkce'}})")
            client = library.createClient(config.supabaseUrl, config.supabaseAnonKey, options)
        } else {
            lastError = "Supabase is not configured for this build."
            ready = true
        }
        if (client != null) {
            try {
                val callback: (dynamic, dynamic) -> Unit = { _, session ->
                    applySession(session)
                }
                client.auth.onAuthStateChange(callback)
            } catch (error: dynamic) {
                logError("auth state listener", error)
            }
            refreshSession()
        }
        registerDeepLinks()
    }

    fun refreshSession(done: (() -> Unit)? = null) {
        if (client == null) {
            ready = true
            done?.invoke()
            return
        }
        (client.auth.getSession()).then(
            { result ->
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
        val w = window.asDynamic()
        val capacitor = w.Capacitor
        val capacitorNative = capacitor != null && capacitor != undefined &&
            capacitor.isNativePlatform != null && capacitor.isNativePlatform() == true
        val androidBridge = w.AndroidAppBridge
        return capacitorNative || (androidBridge != null && androidBridge != undefined)
    }

    fun oauthRedirect(): String {
        return if (nativePlatform()) {
            "com.nepsepulse.app://login-callback"
        } else {
            window.location.origin + window.location.pathname
        }
    }

    private fun registerDeepLinks() {
        if (client == null) return
        val handleCallback: (String) -> Unit = { url ->
            if (url.startsWith("com.nepsepulse.app://login-callback")) {
                (client.auth.exchangeCodeForSession(url)).then(
                    { result ->
                        if (result?.error != null) {
                            lastError = result.error.message.toString()
                        } else {
                            refreshSession()
                        }
                        null
                    },
                    { error ->
                        lastError = errText(error)
                        null
                    }
                )
            }
        }
        try {
            val callback: (dynamic) -> Unit = { event ->
                val url = event?.detail?.toString() ?: ""
                if (url.isNotBlank()) handleCallback(url)
            }
            window.addEventListener("np-auth-callback", callback)
        } catch (error: dynamic) {
            logError("native auth callback listener", error)
        }
        val app = window.asDynamic().NP_CAP_APP
        if (app != null && app != undefined) {
            val listener: (dynamic) -> Unit = { event ->
                handleCallback(event?.url?.toString() ?: "")
            }
            try {
                app.addListener("appUrlOpen", listener)
            } catch (error: dynamic) {
                logError("Capacitor deep link listener", error)
            }
        }
    }

    fun startOAuth(provider: String, done: (String?) -> Unit) {
        if (client == null) {
            done("Supabase is not configured for this build.")
            return
        }
        val options = js("({})")
        options.provider = provider
        val redirect = js("({})")
        redirect.redirectTo = oauthRedirect()
        options.options = redirect
        val isNative = nativePlatform()
        if (isNative) {
            // The Android WebView must not navigate itself into OAuth. Keep the
            // PKCE verifier in this app's origin and hand the URL to Android.
            redirect.skipBrowserRedirect = true
        }
        (client.auth.signInWithOAuth(options)).then(
            { result ->
                if (result?.error != null) {
                    done(result.error.message.toString())
                } else {
                    val url = text(result?.data?.url)
                    if (url != null && isNative) {
                        val bridge = window.asDynamic().AndroidAppBridge
                        if (bridge != null && bridge != undefined && bridge.openAuthUrl != null) {
                            bridge.openAuthUrl(url)
                        } else {
                            done("Android browser bridge is unavailable. Please update the app.")
                            return@then null
                        }
                    }
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
        val page = "delete-account.html"
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
