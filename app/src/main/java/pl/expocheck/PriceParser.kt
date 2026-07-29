package pl.expocheck

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

object PriceParser {
    private val catalogRegex = Regex("(?i)(?:nr\\s*kat\\.?|numer\\s*katalogowy)?\\s*(100\\d{6})")
    private val eanRegex = Regex("(?<!\\d)(\\d{13})(?!\\d)")

    // Standardowy zapis, np. 149,26 zł/m².
    private val priceRegex = Regex(
        "(?i)(?<!\\d)(\\d{1,4}(?:[ .]\\d{3})*[,.]\\d{2})(?!\\d)\\s*[*¹²³…]*\\s*zł(?:\\s*/\\s*(m²|m2|mb|szt\\.?|opak\\.?|op\\.?))?"
    )

    // Cenówki Komfortu często rozbijają grosze na osobny, podniesiony fragment:
    // 149  26*  zł/m² albo nawet trzy osobne linie OCR.
    private val splitPriceRegex = Regex(
        "(?i)(?<!\\d)(\\d{1,4})[ \\t\\r\\n]+(\\d{2})\\s*[*¹²³…]*\\s*(?:zł)?(?:\\s*/\\s*(m²|m2|mb|szt\\.?|opak\\.?|op\\.?))?(?!\\d)"
    )

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

        val withoutInstallation = findPriceNear(
            text,
            markers = listOf("Bez montażu", "bez usługi montażu", "cena bez montażu"),
        )
        val installation = findPriceNear(
            text,
            markers = listOf("Przy zakupie montażu", "z montażem", "z usługą montażu", "cena z montażem"),
            excludedStarts = setOfNotNull(withoutInstallation?.start),
        )
        val lowest = findPriceNear(
            text,
            markers = listOf("Najniższa cena z 30 dni przed obniżką", "Najniższa cena z 30 dni"),
        )
        val savings = findPriceNear(text, markers = listOf("Oszczędzasz"))

        val allPrices = extractTextCandidates(text)
        val current = withoutInstallation ?: allPrices.firstOrNull {
            it.start != installation?.start && it.start != lowest?.start && it.start != savings?.start
        } ?: allPrices.firstOrNull()
        val discount = Regex("-(\\d{1,2})%").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return PageSnapshot(
            name = cleanTitle(title),
            catalogNumber = catalog,
            currentPrice = current?.value,
            unit = current?.unit.orEmpty().ifBlank { installation?.unit.orEmpty() },
            installationPrice = installation?.value,
            lowest30Price = lowest?.value,
            discountPercent = discount,
            savings = savings?.value,
            url = url,
            imageUrl = image,
        )
    }

    fun parseLabel(
        rawText: String,
        barcodeValues: List<String>,
        ocrTokens: List<OcrToken> = emptyList(),
    ): LabelScan {
        val text = rawText.replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ")

        val spatialCandidates = extractSpatialPrices(ocrTokens)
        val textCandidates = extractTextCandidates(text)
        val allCandidates = mergeCandidates(spatialCandidates + textCandidates)
        val preferred = chooseLabelPrice(text, allCandidates) ?: allCandidates.firstOrNull()

        val catalog = catalogRegex.find(text)?.groupValues?.getOrNull(1)
            ?: Regex("(?<!\\d)(100\\d{6})(?!\\d)").find(text)?.groupValues?.getOrNull(1).orEmpty()
        val ean = barcodeValues.firstOrNull { it.length in 8..14 && it.all(Char::isDigit) }
            ?: eanRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()

        return LabelScan(
            price = preferred?.value,
            unit = preferred?.unit.orEmpty(),
            prices = allCandidates.map { DetectedPrice(value = it.value, unit = it.unit) },
            catalogNumber = catalog,
            ean = ean,
            rawText = rawText,
        )
    }

    fun detectedPrices(label: LabelScan): List<DetectedPrice> {
        val list = runCatching { label.prices }.getOrNull().orEmpty()
        val fallback = label.price?.let { listOf(DetectedPrice(it, label.unit)) }.orEmpty()
        return (list + fallback)
            .filter { it.value in 0.01..99_999.99 }
            .groupBy { String.format(Locale.US, "%.2f", it.value) }
            .map { (_, group) -> group.maxByOrNull { if (it.unit.isNotBlank()) 1 else 0 } ?: group.first() }
            .sortedBy { it.value }
    }

    fun comparablePagePrices(page: PageSnapshot): List<ComparablePagePrice> {
        val result = mutableListOf<ComparablePagePrice>()
        page.currentPrice?.let {
            result += ComparablePagePrice("Bez montażu", it, page.unit)
        }
        page.installationPrice?.let {
            result += ComparablePagePrice("Z montażem", it, page.unit)
        }
        return result.distinctBy { "${it.label}:${priceKey(it.value, it.unit)}" }
    }

    fun comparePrices(page: PageSnapshot, label: LabelScan): List<PriceComparison> {
        val shelfPrices = detectedPrices(label)
        return comparablePagePrices(page).map { online ->
            val match = shelfPrices.firstOrNull { shelf ->
                unitsCompatible(online.unit, shelf.unit) && abs(online.value - shelf.value) < 0.011
            }
            PriceComparison(online = online, matchedLabelPrice = match)
        }
    }

    fun pricesMatch(page: PageSnapshot, label: LabelScan): Boolean? {
        val expected = comparablePagePrices(page)
        val shelf = detectedPrices(label)
        if (expected.isEmpty() || shelf.isEmpty()) return null
        return comparePrices(page, label).all { it.matchedLabelPrice != null }
    }

    fun money(value: Double?): String = value?.let { String.format(Locale("pl", "PL"), "%.2f", it) } ?: "—"

    private fun extractTextCandidates(text: String): List<PriceCandidate> {
        val candidates = mutableListOf<PriceCandidate>()

        priceRegex.findAll(text).forEach { match ->
            val value = parseMoney(match.groupValues[1]) ?: return@forEach
            val unit = match.groupValues.getOrNull(2).orEmpty()
                .ifBlank { findNearbyUnit(text, match.range.last + 1) }
            candidates += PriceCandidate(
                value = value,
                unit = normalizeUnit(unit),
                start = match.range.first,
                source = match.value,
                confidence = 5_000.0,
            )
        }

        splitPriceRegex.findAll(text).forEach { match ->
            val whole = match.groupValues[1]
            val cents = match.groupValues[2]
            val value = "$whole.$cents".toDoubleOrNull() ?: return@forEach
            val unit = match.groupValues.getOrNull(3).orEmpty()
                .ifBlank { findNearbyUnit(text, match.range.last + 1) }
            candidates += PriceCandidate(
                value = value,
                unit = normalizeUnit(unit),
                start = match.range.first,
                source = match.value,
                confidence = 4_600.0,
            )
        }

        return mergeCandidates(candidates)
    }

    private fun extractSpatialPrices(tokens: List<OcrToken>): List<PriceCandidate> {
        if (tokens.isEmpty()) return emptyList()

        val clean = tokens.mapNotNull { token ->
            val value = token.text.trim()
            if (value.isBlank()) null else token.copy(text = value)
        }
        if (clean.isEmpty()) return emptyList()

        val medianHeight = clean.map { it.height }.sorted().let { heights ->
            heights.getOrElse(heights.size / 2) { 1 }
        }.coerceAtLeast(1)

        val unitTokens = clean.filter {
            val value = normalizeText(it.text).replace(" ", "")
            value.contains("zl") || value.contains("m2") || value.contains("szt") || value.contains("mb")
        }

        val found = mutableListOf<PriceCandidate>()

        // Cena zapisana w jednym elemencie OCR, np. 169,99.
        clean.forEach { token ->
            val match = Regex("(?<!\\d)(\\d{1,4})[,.](\\d{2})(?!\\d)").find(token.text) ?: return@forEach
            val value = "${match.groupValues[1]}.${match.groupValues[2]}".toDoubleOrNull() ?: return@forEach
            val nearbyUnit = nearestUnit(token, unitTokens)
            val sizeScore = token.height * 14.0 + token.width * 0.35
            val unitBonus = if (nearbyUnit != null) 2_800.0 else 0.0
            found += PriceCandidate(
                value = value,
                unit = normalizeUnit(nearbyUnit?.text.orEmpty()),
                start = token.top,
                source = token.text,
                confidence = sizeScore + unitBonus,
            )
        }

        // Cena rozbita na duże złote i podniesione grosze: „149” + „26*”.
        val wholeTokens = clean.filter { it.text.trim().matches(Regex("\\d{1,4}")) }
        val centsTokens = clean.filter { it.text.trim().matches(Regex("\\d{2}\\s*[*¹²³…]*")) }

        for (whole in wholeTokens) {
            val wholeDigits = tokenDigits(whole.text)
            val wholeValue = wholeDigits.toIntOrNull() ?: continue
            if (wholeValue > 9_999) continue

            for (cents in centsTokens) {
                if (whole === cents) continue
                val centsDigits = tokenDigits(cents.text)
                val centsValue = centsDigits.toIntOrNull() ?: continue

                val horizontalGap = cents.left - whole.right
                val verticalOverlap = minOf(whole.bottom, cents.bottom) - maxOf(whole.top, cents.top)
                val centerDelta = abs(whole.centerY - cents.centerY)
                val maxGap = max(whole.height * 2.2, 170.0)
                val verticallyPlausible = verticalOverlap > -whole.height * 0.95 || centerDelta < whole.height * 1.2
                if (horizontalGap < -whole.width * 0.15 || horizontalGap > maxGap || !verticallyPlausible) continue

                val value = wholeValue + centsValue / 100.0
                if (value !in 0.01..99_999.99) continue

                val combined = OcrToken(
                    text = "${wholeDigits},${centsDigits}",
                    left = minOf(whole.left, cents.left),
                    top = minOf(whole.top, cents.top),
                    right = maxOf(whole.right, cents.right),
                    bottom = maxOf(whole.bottom, cents.bottom),
                )
                val nearbyUnit = nearestUnit(combined, unitTokens)
                val hasStar = cents.text.any { it == '*' || it in "¹²³…" }
                val visuallyLarge = whole.height >= medianHeight * 1.25

                // Chroni przed przypadkowym połączeniem fragmentów numeru katalogowego.
                if (nearbyUnit == null && !hasStar && !visuallyLarge) continue

                val sizeScore = whole.height * 15.0 + whole.width * 0.42 + cents.height * 4.0
                val closeness = max(0.0, 900.0 - abs(horizontalGap) * 3.0 - centerDelta * 1.2)
                val unitBonus = if (nearbyUnit != null) 3_400.0 else 0.0
                val starBonus = if (hasStar) 220.0 else 0.0

                found += PriceCandidate(
                    value = value,
                    unit = normalizeUnit(nearbyUnit?.text.orEmpty()),
                    start = combined.top,
                    source = combined.text,
                    confidence = sizeScore + closeness + unitBonus + starBonus,
                )
            }
        }

        return mergeCandidates(found)
            .sortedByDescending { it.confidence }
            .take(8)
            .sortedBy { it.start }
    }

    private fun mergeCandidates(candidates: List<PriceCandidate>): List<PriceCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .filter { it.value in 0.01..99_999.99 }
            .groupBy { String.format(Locale.US, "%.2f", it.value) }
            .map { (_, group) ->
                group.maxWithOrNull(
                    compareBy<PriceCandidate> { if (it.unit.isNotBlank()) 1 else 0 }
                        .thenBy { it.confidence }
                ) ?: group.first()
            }
            .sortedWith(compareBy<PriceCandidate> { it.start }.thenByDescending { it.confidence })
    }

    private fun chooseLabelPrice(text: String, candidates: List<PriceCandidate>): PriceCandidate? {
        if (candidates.isEmpty()) return null

        val scored = candidates.mapIndexed { index, candidate ->
            val from = max(0, candidate.start - 55)
            val to = minOf(text.length, candidate.start + candidate.source.length + 70)
            val context = if (candidate.start in text.indices) normalizeText(text.substring(from, to)) else ""
            var score = candidate.confidence + 5_000.0 - index * 120.0

            if (candidate.unit.isNotBlank()) score += 800
            if ("zl" in context || "m2" in context || "szt" in context || "mb" in context) score += 300
            if ("promoc" in context || "cena specjal" in context || "cena brutto" in context) score += 450
            if ("rata" in context || "mies" in context) score -= 8_000
            if ("najni" in context || "30 dni" in context) score -= 7_000

            candidate to score
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun nearestUnit(price: OcrToken, units: List<OcrToken>): OcrToken? {
        return units
            .map { unit ->
                val dx = when {
                    unit.left > price.right -> unit.left - price.right
                    price.left > unit.right -> price.left - unit.right
                    else -> 0
                }
                val dy = when {
                    unit.top > price.bottom -> unit.top - price.bottom
                    price.top > unit.bottom -> price.top - unit.bottom
                    else -> 0
                }
                val distance = dx + dy * 1.4
                unit to distance
            }
            .filter { (_, distance) -> distance < max(price.height * 4.5, 520.0) }
            .minByOrNull { it.second }
            ?.first
    }

    private fun tokenDigits(value: String): String = value.filter(Char::isDigit)

    private fun findNearbyUnit(text: String, after: Int): String {
        if (after !in 0..text.length) return ""
        val slice = text.substring(after, minOf(text.length, after + 65))
        return Regex("(?i)(?:zł\\s*/?\\s*)?(m²|m2|mb|szt\\.?|opak\\.?|op\\.?)").find(slice)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun findPriceNear(
        text: String,
        markers: List<String>,
        excludedStarts: Set<Int> = emptySet(),
    ): PriceCandidate? {
        val all = extractTextCandidates(text).filterNot { it.start in excludedStarts }
        if (all.isEmpty()) return null

        val occurrences = markers.flatMap { marker ->
            Regex(Regex.escape(marker), RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { match -> match.range.last + 1 }
                .toList()
        }
        if (occurrences.isEmpty()) return null

        // Na stronie Komfortu opis zwykle stoi PRZED właściwą kwotą:
        // "Bez montażu 349,00", "Oszczędzasz 10,00",
        // "Najniższa cena z 30 dni: 59,97".
        // Najpierw szukamy więc najbliższej ceny po markerze.
        val after = all
            .mapNotNull { candidate ->
                val distance = occurrences
                    .map { markerEnd -> candidate.start - markerEnd }
                    .filter { it >= 0 }
                    .minOrNull()
                distance?.let { candidate to it }
            }
            .filter { (_, distance) -> distance <= 320 }
            .minByOrNull { it.second }
            ?.first

        if (after != null) return after

        // Awaryjnie obsługujemy nietypowy układ, w którym kwota stoi przed opisem.
        return all
            .map { candidate ->
                val distance = occurrences.minOf { markerEnd -> abs(candidate.start - markerEnd) }
                candidate to distance
            }
            .filter { (_, distance) -> distance <= 180 }
            .minByOrNull { it.second }
            ?.first
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

    private fun unitsCompatible(first: String, second: String): Boolean {
        return first.isBlank() || second.isBlank() || normalizeUnit(first) == normalizeUnit(second)
    }

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

    private fun priceKey(value: Double, unit: String): String =
        "${String.format(Locale.US, "%.2f", value)}:${normalizeUnit(unit)}"

    private data class PriceCandidate(
        val value: Double,
        val unit: String,
        val start: Int = 0,
        val source: String = "",
        val confidence: Double = 0.0,
    )
}
