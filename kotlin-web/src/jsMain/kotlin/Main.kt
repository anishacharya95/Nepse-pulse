import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

private fun element(tag: String, className: String = "", text: String = ""): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    node.className = className
    node.textContent = text
    return node
}

fun main() {
    val root = document.getElementById("app") ?: return
    val shell = element("main", "shell")
    val header = element("header", "top")
    val brand = element("div", "brand", "Q  NEPSE PULSE")
    val status = element("span", "status", "KOTLIN MIGRATION PREVIEW")
    header.appendChild(brand)
    header.appendChild(status)
    shell.appendChild(header)

    val intro = element("section", "panel")
    intro.appendChild(element("h1", "", "Market intelligence, rebuilt with Kotlin"))
    intro.appendChild(element("p", "muted", "This is the isolated Kotlin/JS migration workspace. The existing production site and services remain unchanged while features are audited and ported."))
    shell.appendChild(intro)

    val audit = element("section", "panel")
    audit.appendChild(element("h2", "", "Verified integrations to preserve"))
    val grid = element("div", "grid")
    listOf(
        "Supabase authentication and cloud watchlists",
        "Render NEPSE data backend",
        "TradingView UDF chart endpoints",
        "Google AdMob Android placements",
        "Existing NEPSE market and analytics services"
    ).forEach { label ->
        val tile = element("article", "tile")
        tile.appendChild(element("h3", "", label))
        tile.appendChild(element("p", "muted", "Audited — migration pending"))
        grid.appendChild(tile)
    }
    audit.appendChild(grid)
    shell.appendChild(audit)

    val note = element("section", "panel")
    note.appendChild(element("p", "muted", "Feature parity is not claimed yet. Each original screen and API must be ported and tested before this Kotlin site replaces the production website."))
    shell.appendChild(note)
    root.replaceChildren(shell)

    // Keep runtime diagnostics visible without exposing Supabase credentials.
    window.console.info("NEPSE Pulse Kotlin/JS migration shell started")
}
