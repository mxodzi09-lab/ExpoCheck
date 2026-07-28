package pl.expocheck

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object PriceParser {
    private val catalogRegex = Regex("(?i)(?:nr\\s*kat\\.?|numer\\s*katalogowy)?\\s*(100\\d{6})")
    private val eanRegex = Regex("(?<!\\d)(\\d{13})(?!\\d)")
    private val priceRegex = Regex("(?i)(\\d{1,4}(?:[ .]\\d{3})*[,.]\\d{2})\\s*zł(?:\\s*/\\s*(m²|m2|mb|szt\\.?|opak\\.?|op\\.?))?")

    fun parsePageJson(payload: String): PageSnapshot {
        val json = JSONObject(payload)
        return parsePage(
            title = json.optString("title"),
            body = json.optString("body"),
            url = json.optString("url"),
            image = json.optString("image"),
        )
    }

    fun parsePage(title: String, body: String, url: String, image: String): PageSnapshot {
        val text = body.replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ")
        val catalog = catalogRegex.find(text)?.groupValues?.getOrNull(1)
            ?: Regex("(?<!\\d)(100\\d{6})(?!\\d)").find(url)?.groupValues?.getOrNull(1).orEmpty()

        val withoutInstallation = findPriceAfter(text, "Bez montażu")
        val installation = findPriceAfter(text, "Przy zakupie montażu")
        val lowest = findPriceAfter(text, "Najniższa cena z 30 dni przed obniżką")
            ?: findPriceAfter(text, "Najniższa cena z 30 dni")
        val savings = findPriceAfter(text, "Oszczędzasz")

        val allPrices = priceRegex.findAll(text).mapNotNull { match ->
            val value = parseMoney(match.groupValues[1]) ?: return@mapNotNull null
            PriceCandidate(value, normalizeUnit(match.groupValues.getOrNull(2).orEmpty()))
        }.toList()

        val current = withoutInstallation ?: allPrices.firstOrNull()
        val discount = Regex("-(\\d{1,2})%").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return PageSnapshot(
            name = cleanTitle(title),
            catalogNumber = catalog,
            currentPrice = current?.value,
            unit = current?.unit.orEmpty(),
            installationPrice = installation?.value,
            lowest30Price = lowest?.value,
            discountPercent = discount,
            savings = savings?.value,
            url = url,
            imageUrl = image,
        )
    }

    fun parseLabel(rawText: String, barcodeValues: List<String>): LabelScan {
        val text = rawText.replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ")
        val candidates = priceRegex.findAll(text).mapNotNull { match ->
            val value = parseMoney(match.groupValues[1]) ?: return@mapNotNull null
            PriceCandidate(value, normalizeUnit(match.groupValues.getOrNull(2).orEmpty()))
        }.toList()

        val preferred = chooseLabelPrice(text, candidates)
        val catalog = catalogRegex.find(text)?.groupValues?.getOrNull(1)
            ?: Regex("(?<!\\d)(100\\d{6})(?!\\d)").find(text)?.groupValues?.getOrNull(1).orEmpty()
        val ean = barcodeValues.firstOrNull { it.length in 8..14 && it.all(Char::isDigit) }
            ?: eanRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()

        return LabelScan(
            price = preferred?.value,
            unit = preferred?.unit.orEmpty(),
            catalogNumber = catalog,
            ean = ean,
            rawText = rawText,
        )
    }

    fun pricesMatch(page: PageSnapshot, label: LabelScan): Boolean? {
        val online = page.currentPrice ?: return null
        val shelf = label.price ?: return null
        val unitsCompatible = page.unit.isBlank() || label.unit.isBlank() || normalizeUnit(page.unit) == normalizeUnit(label.unit)
        return unitsCompatible && kotlin.math.abs(online - shelf) < 0.011
    }

    fun money(value: Double?): String = value?.let { String.format(Locale("pl", "PL"), "%.2f", it) } ?: "—"

    private fun chooseLabelPrice(text: String, candidates: List<PriceCandidate>): PriceCandidate? {
        if (candidates.isEmpty()) return null
        val normalized = normalizeText(text)

        // Cenówki często zawierają ratę, najniższą cenę lub wartość za opakowanie.
        // W v0.1 preferujemy największą wyraźną kwotę, ale omijamy wartości blisko słów "rata".
        val lines = text.lines()
        val scored = candidates.map { candidate ->
            var score = candidate.value
            val formattedComma = String.format(Locale.US, "%.2f", candidate.value).replace('.', ',')
            val line = lines.firstOrNull { it.replace('.', ',').contains(formattedComma) }.orEmpty()
            val lineNorm = normalizeText(line)
            if ("rata" in lineNorm || "mies" in lineNorm) score -= 10_000
            if ("najni" in lineNorm || "30 dni" in lineNorm) score -= 5_000
            if ("promoc" in lineNorm || "cena" in lineNorm) score += 500
            candidate to score
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun findPriceAfter(text: String, marker: String): PriceCandidate? {
        val markerIndex = text.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null
        val slice = text.substring(markerIndex, minOf(text.length, markerIndex + 220))
        val match = priceRegex.find(slice) ?: return null
        val value = parseMoney(match.groupValues[1]) ?: return null
        return PriceCandidate(value, normalizeUnit(match.groupValues.getOrNull(2).orEmpty()))
    }

    private fun parseMoney(raw: String): Double? {
        val compact = raw.replace(" ", "")
        val normalized = if (compact.contains(',')) {
            compact.replace(".", "").replace(',', '.')
        } else {
            compact
        }
        return normalized.toDoubleOrNull()
    }

    private fun cleanTitle(raw: String): String = raw
        .substringBefore(" | Komfort", raw)
        .substringBefore(" - Komfort", raw)
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pl", "PL")) else it.toString() }

    private fun normalizeUnit(raw: String): String {
        val unit = normalizeText(raw).replace(" ", "")
        return when {
            unit.contains("m2") || raw.contains("m²") -> "zł/m²"
            unit.contains("mb") -> "zł/mb"
            unit.contains("szt") -> "zł/szt."
            unit.contains("opak") || unit == "op" -> "zł/op."
            raw.startsWith("zł/") -> raw
            else -> raw.trim()
        }
    }

    private fun normalizeText(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale("pl", "PL")), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "")
            .replace('ł', 'l')
            .replace('²', '2')
    }

    private data class PriceCandidate(val value: Double, val unit: String)
}
