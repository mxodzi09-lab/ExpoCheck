package pl.expocheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object KomfortRemoteLookup {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36 ExpoCheck/0.5.1"

    suspend fun resolve(code: String): PageSnapshot? = withContext(Dispatchers.IO) {
        val normalized = code.filter(Char::isDigit).take(14)
        if (normalized.length < 8) return@withContext null

        SeedProducts.items.firstOrNull { it.catalogNumber == normalized }?.let { seed ->
            return@withContext PageSnapshot(
                name = seed.name,
                catalogNumber = seed.catalogNumber,
                currentPrice = seed.currentPrice,
                unit = seed.unit,
                installationPrice = seed.installationPrice,
                lowest30Price = seed.lowest30Price,
                discountPercent = seed.discountPercent,
                savings = seed.savings,
                url = seed.url,
            )
        }

        runCatching {
            val encoded = URLEncoder.encode(
                normalized,
                StandardCharsets.UTF_8.name(),
            )
            val searchUrl = "https://komfort.pl/search?q=$encoded"
            val searchDocument = fetch(searchUrl)

            val productUrl =
                directProductLocation(searchDocument, normalized)
                    ?: productUrlFromLinks(searchDocument, normalized)
                    ?: productUrlFromHtml(searchDocument.html(), normalized)
                    ?: return@runCatching null

            val productDocument = fetch(productUrl)
            val title = productDocument.selectFirst("h1")?.text()
                ?: productDocument.title()
            val body = productDocument.body()?.text().orEmpty()
            val image = productDocument
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                .orEmpty()

            val parsed = PriceParser.parsePage(
                title = title,
                body = body,
                url = productDocument.location().ifBlank { productUrl },
                image = image,
            )

            parsed.takeIf {
                it.currentPrice != null &&
                    (
                        it.catalogNumber == normalized ||
                            it.url.contains(normalized)
                    )
            }
        }.getOrNull()
    }

    private fun fetch(url: String): Document =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "pl-PL,pl;q=0.9")
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .timeout(12_000)
            .maxBodySize(5 * 1024 * 1024)
            .followRedirects(true)
            .get()

    private fun directProductLocation(
        document: Document,
        code: String,
    ): String? =
        document.location()
            .takeIf { it.contains("/p/") && it.contains(code) }

    private fun productUrlFromLinks(
        document: Document,
        code: String,
    ): String? =
        document.select("a[href*=/p/]")
            .asSequence()
            .map { it.absUrl("href").ifBlank { it.attr("href") } }
            .firstOrNull { it.contains(code) }
            ?.let(::absoluteKomfortUrl)

    private fun productUrlFromHtml(
        html: String,
        code: String,
    ): String? {
        val decoded = html
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val patterns = listOf(
            Regex(
                """(?i)https?://(?:www\.)?komfort\.pl/p/[^"' <>\]+$code[^"' <>\]*"""
            ),
            Regex(
                """(?i)["'](/p/[^"']*$code[^"']*)["']"""
            ),
        )

        for (pattern in patterns) {
            val value = pattern.find(decoded)?.groupValues?.lastOrNull()
                ?: continue
            return absoluteKomfortUrl(value)
        }
        return null
    }

    private fun absoluteKomfortUrl(url: String): String = when {
        url.startsWith("https://") -> url
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        url.startsWith("/") -> "https://komfort.pl$url"
        else -> "https://komfort.pl/$url"
    }
}
