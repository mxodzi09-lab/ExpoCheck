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

    // Pełne złote bez groszy, np. 529 zł/szt. Wymagamy jawnego „zł”,
    // dzięki czemu numery katalogowe i procenty nie są traktowane jako ceny.
    private val wholePriceRegex = Regex(
        "(?i)(?<![\\d,.])(\\d{1,4}(?:[ .]\\d{3})*)(?![\\d,.])\\s*zł(?:\\s*/\\s*(m²|m2|mb|szt\\.?|opak\\.?|op\\.?))?"
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
        // Gdy mamy współrzędne OCR, nie łączymy luźnych fragmentów tekstu
        // w ceny. To właśnie tworzyło fałszywe 4,26 / 29,67 / 529,67.
        val textCandidates = if (ocrTokens.isEmpty()) {
            extractTextCandidates(text = text, allowSplitPrices = true)
        } else {
            emptyList()
        }
        val allCandidates = mergeCandidates(spatialCandidates + textCandidates)
        val preferred = chooseLabelPrice(text, allCandidates) ?: allCandidates.firstOrNull()

        val barcodeCatalog = barcodeValues.firstNotNullOfOrNull { value ->
            catalogRegex.find(value)?.groupValues?.getOrNull(1)
                ?: Regex("(?<!\\d)(100\\d{6})(?!\\d)")
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
        }
        val catalog = barcodeCatalog
            ?: catalogRegex.find(text)?.groupValues?.getOrNull(1)
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
        page.lowest30Price?.let {
            result += ComparablePagePrice("Najniższa z 30 dni", it, page.unit)
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

    private fun extractTextCandidates(
        text: String,
        allowSplitPrices: Boolean = true,
    ): List<PriceCandidate> {
        val candidates = mutableListOf<PriceCandidate>()

        // Pełne zakresy cen rozbitych, np. „338 67 zł/szt.”.
        // Potrzebujemy ich, aby nie zapisać fragmentu „67 zł/szt.”
        // jako osobnej ceny 67,00 zł.
        val splitMatches = if (allowSplitPrices) {
            splitPriceRegex.findAll(text).toList()
        } else {
            emptyList()
        }

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

        wholePriceRegex.findAll(text).forEach { match ->
            val belongsToSplitPrice = splitMatches.any { split ->
                match.range.first >= split.range.first &&
                    match.range.last <= split.range.last
            }
            if (belongsToSplitPrice) return@forEach

            val value = match.groupValues[1]
                .replace(" ", "")
                .replace(".", "")
                .toDoubleOrNull()
                ?: return@forEach
            val unit = match.groupValues.getOrNull(2).orEmpty()
                .ifBlank { findNearbyUnit(text, match.range.last + 1) }
            candidates += PriceCandidate(
                value = value,
                unit = normalizeUnit(unit),
                start = match.range.first,
                source = match.value,
                confidence = 4_900.0,
            )
        }

        if (allowSplitPrices) {
            splitMatches.forEach { match ->
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
        }

        return mergeCandidates(candidates)
    }

/**
 * Tryb cenówki: bierzemy wyłącznie największe cyfry widoczne w kadrze.
 *
 * Przykłady:
 * - 1111 + małe 92 => 1111,92 zł/szt.
 * - 777 + małe 36  => 777,36 zł/szt.
 *
 * Małe liczby z opisu, daty, EAN, procenty i kody są odrzucane.
 */
private fun extractSpatialPrices(
    tokens: List<OcrToken>,
): List<PriceCandidate> {
    if (tokens.isEmpty()) return emptyList()

    val clean = tokens.mapNotNull { token ->
        val value = token.text.trim()
        if (value.isBlank()) null else token.copy(text = value)
    }
    if (clean.isEmpty()) return emptyList()

    val heights = clean.map { it.height }.sorted()
    val medianHeight = heights
        .getOrElse(heights.size / 2) { 1 }
        .coerceAtLeast(1)

    val unitTokens = clean.filter {
        val value = normalizeText(it.text).replace(" ", "")
        value.contains("zl") ||
            value.contains("m2") ||
            value.contains("szt") ||
            value.contains("mb") ||
            value.contains("opak")
    }

    val numericTokens = clean.filter { token ->
        val digits = tokenDigits(token.text)
        digits.length in 1..4 &&
            token.text.all {
                it.isDigit() ||
                    it.isWhitespace() ||
                    it == '.' ||
                    it == ','
            }
    }

    if (numericTokens.isEmpty()) return emptyList()

    val wholeTokens = mergeAdjacentWholeTokens(numericTokens)
    val maxWholeHeight = wholeTokens
        .maxOfOrNull { it.height }
        ?.coerceAtLeast(1)
        ?: return emptyList()

    // Tylko naprawdę duży druk. Dolny próg zależy zarówno od typowego
    // tekstu na etykiecie, jak i od największej cyfry w aktualnym kadrze.
    val largeHeightThreshold = max(
        medianHeight * 1.55,
        maxWholeHeight * 0.43,
    )

    val largeWholes = wholeTokens.filter {
        it.height >= largeHeightThreshold
    }

    val centsTokens = clean.filter {
        val digits = tokenDigits(it.text)
        digits.length == 2 &&
            it.text.all { char ->
                char.isDigit() ||
                    char.isWhitespace() ||
                    char == '*' ||
                    char in "¹²³…"
            }
    }

    val found = mutableListOf<PriceCandidate>()
    val pairedWholeKeys = mutableSetOf<String>()

    fun key(token: OcrToken): String =
        "${token.left}:${token.top}:${token.right}:${token.bottom}:${token.text}"

    // Jedno pole OCR: np. „1111,92”.
    clean.forEach { token ->
        val match = Regex(
            "(?<!\\d)(\\d{1,4})[,.](\\d{2})(?!\\d)"
        ).find(token.text) ?: return@forEach

        if (token.height < largeHeightThreshold * 0.72) {
            return@forEach
        }

        val value =
            "${match.groupValues[1]}.${match.groupValues[2]}"
                .toDoubleOrNull()
                ?: return@forEach
        val unit = nearestUnit(token, unitTokens) ?: return@forEach

        found += PriceCandidate(
            value = value,
            unit = normalizeUnit(unit.text),
            start = token.top,
            source = token.text,
            confidence =
                token.height * 22.0 +
                token.width * 0.45 +
                7_000.0,
        )
    }

    // Duże złote + mniejsze grosze po prawej stronie.
    for (whole in largeWholes) {
        val wholeDigits = tokenDigits(whole.text)
        val wholeValue = wholeDigits.toIntOrNull() ?: continue
        if (wholeValue !in 1..9_999) continue

        val matches = centsTokens.mapNotNull { cents ->
            val centsDigits = tokenDigits(cents.text)
            val centsValue = centsDigits.toIntOrNull()
                ?: return@mapNotNull null

            val horizontalGap = cents.left - whole.right
            val centerDelta = abs(whole.centerY - cents.centerY)
            val centsRatio = cents.height / whole.height.toDouble()
            val maxGap = max(whole.height * 1.45, 170.0)

            val geometryOk =
                horizontalGap >= -whole.width * 0.04 &&
                horizontalGap <= maxGap &&
                centerDelta <= whole.height * 0.62 &&
                centsRatio in 0.20..0.82

            if (!geometryOk) return@mapNotNull null

            val combined = OcrToken(
                text = "$wholeDigits,$centsDigits",
                left = minOf(whole.left, cents.left),
                top = minOf(whole.top, cents.top),
                right = maxOf(whole.right, cents.right),
                bottom = maxOf(whole.bottom, cents.bottom),
            )
            val unit = nearestUnit(combined, unitTokens)
                ?: return@mapNotNull null

            val distancePenalty =
                abs(horizontalGap) * 2.2 +
                    centerDelta * 1.3

            Triple(centsValue, unit, 2_000.0 - distancePenalty)
        }

        val best = matches.maxByOrNull { it.third } ?: continue
        val value = wholeValue + best.first / 100.0
        if (value !in 0.01..99_999.99) continue

        found += PriceCandidate(
            value = value,
            unit = normalizeUnit(best.second.text),
            start = whole.top,
            source = whole.text,
            confidence =
                whole.height * 24.0 +
                whole.width * 0.55 +
                best.third +
                8_000.0,
        )
        pairedWholeKeys += key(whole)
    }

    // Duża cena bez groszy, ale tylko gdy ma obok jawne „zł/...”.
    for (whole in largeWholes) {
        if (key(whole) in pairedWholeKeys) continue

        val digits = tokenDigits(whole.text)
        val value = digits.toDoubleOrNull() ?: continue
        if (value !in 1.0..99_999.0) continue

        val unit = nearestUnit(whole, unitTokens) ?: continue

        found += PriceCandidate(
            value = value,
            unit = normalizeUnit(unit.text),
            start = whole.top,
            source = whole.text,
            confidence =
                whole.height * 21.0 +
                whole.width * 0.45 +
                6_500.0,
        )
    }

    if (found.isEmpty()) return emptyList()

    val bestConfidence = found.maxOf { it.confidence }

    // Zostawiamy maksymalnie trzy dominujące ceny. Kandydat musi być
    // wizualnie zbliżony do największego druku w kadrze.
    return mergeCandidates(found)
        .filter { it.confidence >= bestConfidence * 0.58 }
        .sortedByDescending { it.confidence }
        .take(3)
        .sortedBy { it.start }
}

/**
 * OCR potrafi rozbić „1 111” na dwa elementy: „1” i „111”.
 * Łączymy je, gdy mają podobną wysokość i leżą w tym samym wierszu.
 * Oryginalne części są wtedy usuwane, aby nie powstała cena 111,92.
 */
private fun mergeAdjacentWholeTokens(
    tokens: List<OcrToken>,
): List<OcrToken> {
    if (tokens.isEmpty()) return emptyList()

    val merged = mutableListOf<OcrToken>()
    val consumed = mutableSetOf<Int>()

    for (leftIndex in tokens.indices) {
        if (leftIndex in consumed) continue
        val left = tokens[leftIndex]
        val leftDigits = tokenDigits(left.text)
        if (leftDigits.isEmpty()) continue

        val candidate = tokens.indices
            .asSequence()
            .filter { it != leftIndex && it !in consumed }
            .map { rightIndex -> rightIndex to tokens[rightIndex] }
            .filter { (_, right) ->
                right.left >= left.right &&
                    tokenDigits(right.text).isNotEmpty()
            }
            .mapNotNull { (rightIndex, right) ->
                val rightDigits = tokenDigits(right.text)
                val combinedDigits = leftDigits + rightDigits
                if (combinedDigits.length !in 2..4) {
                    return@mapNotNull null
                }

                val gap = right.left - left.right
                val centerDelta = abs(left.centerY - right.centerY)
                val heightRatio =
                    right.height / left.height.toDouble()
                val maxGap = max(left.height * 0.42, 70.0)

                val sameLargeRow =
                    gap.toDouble() in 0.0..maxGap &&
                        centerDelta <= max(
                            left.height,
                            right.height,
                        ) * 0.28 &&
                        heightRatio in 0.72..1.38

                if (!sameLargeRow) return@mapNotNull null

                Triple(
                    rightIndex,
                    right,
                    gap + centerDelta,
                )
            }
            .minByOrNull { it.third }

        if (candidate != null) {
            val rightIndex = candidate.first
            val right = candidate.second
            val combinedDigits =
                leftDigits + tokenDigits(right.text)

            merged += OcrToken(
                text = combinedDigits,
                left = left.left,
                top = minOf(left.top, right.top),
                right = right.right,
                bottom = maxOf(left.bottom, right.bottom),
            )
            consumed += leftIndex
            consumed += rightIndex
        }
    }

    tokens.indices
        .filter { it !in consumed }
        .forEach { merged += tokens[it] }

    return merged
        .distinctBy {
            "${it.left}:${it.top}:${it.right}:${it.bottom}:${tokenDigits(it.text)}"
        }
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
            .mapNotNull { unit ->
                val horizontalOverlap = minOf(price.right, unit.right) - maxOf(price.left, unit.left)
                val horizontalGap = when {
                    unit.left > price.right -> unit.left - price.right
                    price.left > unit.right -> price.left - unit.right
                    else -> 0
                }
                val verticalGap = when {
                    unit.top > price.bottom -> unit.top - price.bottom
                    price.top > unit.bottom -> price.top - unit.bottom
                    else -> 0
                }
                val centerDelta = abs(price.centerY - unit.centerY)

                val sameRow =
                    centerDelta <= max(price.height * 0.85, unit.height * 1.35) &&
                    horizontalGap <= max(price.height * 2.2, 260.0)
                val directlyBelow =
                    unit.top >= price.top &&
                    verticalGap <= max(price.height * 0.9, 125.0) &&
                    horizontalOverlap >= -price.width * 0.35

                if (!sameRow && !directlyBelow) return@mapNotNull null
                val distance = horizontalGap + verticalGap * 1.7 + centerDelta * 0.35
                unit to distance
            }
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
