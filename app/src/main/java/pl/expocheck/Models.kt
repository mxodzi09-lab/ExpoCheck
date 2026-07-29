package pl.expocheck

data class PageSnapshot(
    val name: String = "",
    val catalogNumber: String = "",
    val currentPrice: Double? = null,
    val unit: String = "",
    val installationPrice: Double? = null,
    val lowest30Price: Double? = null,
    val discountPercent: Int? = null,
    val savings: Double? = null,
    val url: String = "",
    val imageUrl: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
)

data class OcrToken(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Double get() = (top + bottom) / 2.0
}

data class DetectedPrice(
    val value: Double = 0.0,
    val unit: String = "",
)

data class ComparablePagePrice(
    val label: String,
    val value: Double,
    val unit: String,
)

data class PriceComparison(
    val online: ComparablePagePrice,
    val matchedLabelPrice: DetectedPrice? = null,
)

data class LabelScan(
    // Zachowane dla zgodności ze starszymi zapisami. W v0.3 właściwym źródłem
    // jest lista `prices`, bo cenówka może zawierać kilka poprawnych cen.
    val price: Double? = null,
    val unit: String = "",
    val prices: List<DetectedPrice> = emptyList(),
    val catalogNumber: String = "",
    val ean: String = "",
    val rawText: String = "",
)

enum class CheckStatus(val label: String) {
    DONE("Zrobione"),
    MISSING_PRICE("Brak ceny"),
    WRONG_PRICE("Zła cena"),
    TO_CHECK("Do sprawdzenia"),
    MISSING_PRODUCT("Brak produktu"),
}

data class ProductRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val nickname: String = "",
    val page: PageSnapshot = PageSnapshot(),
    val label: LabelScan = LabelScan(),
    val status: CheckStatus = CheckStatus.TO_CHECK,
    val note: String = "",
    val exposurePhotoPath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class SeedProduct(
    val catalogNumber: String,
    val name: String,
    val url: String,
    val currentPrice: Double,
    val unit: String,
    val installationPrice: Double? = null,
    val lowest30Price: Double? = null,
    val discountPercent: Int? = null,
    val savings: Double? = null,
)

object SeedProducts {
    // Dane startowe są tylko awaryjne. Po otwarciu produktu aplikacja odczytuje
    // aktualne wartości bezpośrednio ze strony Komfortu.
    val items = listOf(
        SeedProduct(
            catalogNumber = "100630301",
            name = "Deska tarasowa kompozytowa Natural 21×135×2200 mm Teak DLH",
            url = "https://komfort.pl/p/deska-tarasowa-kompozytowa-natural-21x135x2200-mm-teak-dlh-100630301",
            currentPrice = 49.97,
            unit = "zł/szt.",
            lowest30Price = 59.97,
            discountPercent = 16,
            savings = 10.00,
        ),
        SeedProduct(
            catalogNumber = "100246460",
            name = "Deska Barlinecka Dąb Salt jodełka francuska olej OXY",
            url = "https://komfort.pl/p/dab-salt-jodla-francuska-deska-barlinecka-100246460",
            currentPrice = 349.00,
            unit = "zł/m²",
            installationPrice = 306.44,
            lowest30Price = 449.99,
            discountPercent = 22,
            savings = 100.99,
        ),
        SeedProduct(
            catalogNumber = "100159819",
            name = "Wykładzina dywanowa Sweet perłowy 4 m",
            url = "https://komfort.pl/p/sweet-perlowy-100159819",
            currentPrice = 99.97,
            unit = "zł/m²",
            installationPrice = 87.78,
            lowest30Price = 139.00,
            discountPercent = 28,
            savings = 39.03,
        ),
    )
}
