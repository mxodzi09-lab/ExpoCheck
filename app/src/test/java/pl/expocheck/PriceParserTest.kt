package pl.expocheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceParserTest {
    @Test
    fun parsesDeckPromotion() {
        val body = """
            deska tarasowa kompozytowa natural
            Nr kat. 100630301
            49,97 zł/szt
            -16%
            Oszczędzasz 10,00 zł
            Najniższa cena z 30 dni przed obniżką: 59,97 zł/szt
        """.trimIndent()
        val page = PriceParser.parsePage("Deska | Komfort", body, "https://komfort.pl/p/x-100630301", "")
        assertEquals("100630301", page.catalogNumber)
        assertEquals(49.97, page.currentPrice!!, 0.001)
        assertEquals(59.97, page.lowest30Price!!, 0.001)
        assertEquals(16, page.discountPercent)
    }

    @Test
    fun parsesFloorPrices() {
        val body = """
            Nr kat. 100246460
            Bez montażu
            349,00 zł/m²
            Przy zakupie montażu
            306,44 zł/m²
            -22%
            Oszczędzasz 100,99 zł
            Najniższa cena z 30 dni: 449,99 zł
        """.trimIndent()
        val page = PriceParser.parsePage("Dąb Salt | Komfort", body, "", "")
        assertEquals(349.0, page.currentPrice!!, 0.001)
        assertEquals(306.44, page.installationPrice!!, 0.001)
        assertEquals("zł/m²", page.unit)
    }

    @Test
    fun comparesShelfLabel() {
        val page = PageSnapshot(currentPrice = 99.97, unit = "zł/m²")
        val label = PriceParser.parseLabel("Nr kat. 100159819 Cena 99,97 zł/m²", emptyList())
        assertTrue(PriceParser.pricesMatch(page, label) == true)
    }

    @Test
    fun parsesEveryPriceFromComfortLabel() {
        val text = """
            PANEL PODŁOGOWY
            149 26*
            zł/m²
            169 99**
            zł/m²
            Nr kat. 100622680
        """.trimIndent()
        val label = PriceParser.parseLabel(text, emptyList())
        val values = PriceParser.detectedPrices(label).map { it.value }
        assertTrue(values.any { kotlin.math.abs(it - 149.26) < 0.001 })
        assertTrue(values.any { kotlin.math.abs(it - 169.99) < 0.001 })
        assertEquals("100622680", label.catalogNumber)
    }

    @Test
    fun comparesBothWithoutAndWithInstallation() {
        val page = PageSnapshot(
            currentPrice = 169.99,
            installationPrice = 149.26,
            unit = "zł/m²",
        )
        val label = PriceParser.parseLabel(
            "149 26* zł/m² 169 99** zł/m² Nr kat. 100622680",
            emptyList(),
        )
        val comparisons = PriceParser.comparePrices(page, label)
        assertEquals(2, comparisons.size)
        assertTrue(comparisons.all { it.matchedLabelPrice != null })
        assertTrue(PriceParser.pricesMatch(page, label) == true)
    }

    @Test
    fun parsesBothSpatialPricesOnComfortLabel() {
        val tokens = listOf(
            OcrToken("149", 110, 180, 430, 430),
            OcrToken("26*", 438, 210, 545, 330),
            OcrToken("zł/m²", 450, 350, 570, 400),
            OcrToken("169", 120, 500, 310, 620),
            OcrToken("99**", 315, 510, 390, 575),
            OcrToken("zł/m²", 320, 585, 420, 625),
            OcrToken("100622680", 180, 800, 390, 845),
        )
        val label = PriceParser.parseLabel("149 26 zł/m² 169 99 zł/m² 100622680", emptyList(), tokens)
        val values = PriceParser.detectedPrices(label).map { it.value }
        assertTrue(values.any { kotlin.math.abs(it - 149.26) < 0.001 })
        assertTrue(values.any { kotlin.math.abs(it - 169.99) < 0.001 })
    }

@Test
fun recognizesCatalogNumberForAutomaticLookup() {
    val label = PriceParser.parseLabel(
        "PANEL PODŁOGOWY Nr kat. 100622680 149 26 zł/m² 169 99 zł/m²",
        emptyList(),
    )
    assertEquals("100622680", label.catalogNumber)
    assertEquals(2, PriceParser.detectedPrices(label).size)
}

    @Test
    fun parsesWholeLowestPriceFromProductPage() {
        val body = """
            Nr kat. 100344378
            338,67 zł/szt
            -35%
            Oszczędzasz 190,33 zł
            Najniższa cena z 30 dni przed obniżką: 529 zł/szt
            Cena katalogowa: 625 zł/szt
        """.trimIndent()
        val page = PriceParser.parsePage("Bateria Excellent", body, "", "")
        assertEquals(338.67, page.currentPrice!!, 0.001)
        assertEquals(529.0, page.lowest30Price!!, 0.001)
        assertTrue(PriceParser.comparablePagePrices(page).any { it.label == "Najniższa z 30 dni" && kotlin.math.abs(it.value - 529.0) < 0.001 })
    }

    @Test
    fun ignoresFalsePriceCombinationsFromOtherLines() {
        val tokens = listOf(
            OcrToken("338", 80, 280, 330, 500),
            OcrToken("67", 342, 295, 440, 405),
            OcrToken("zł/szt.", 338, 420, 480, 465),
            OcrToken("529", 455, 250, 565, 320),
            OcrToken("zł/szt.", 568, 285, 640, 315),
            // Szum OCR z innych fragmentów cenówki.
            OcrToken("4", 40, 90, 65, 120),
            OcrToken("26", 210, 85, 250, 115),
            OcrToken("29", 455, 120, 500, 150),
            OcrToken("100344378", 90, 190, 280, 225),
        )
        val label = PriceParser.parseLabel(
            "Kod: 100344378 338 67 zł/szt. 529 zł/szt.",
            emptyList(),
            tokens,
        )
        val values = PriceParser.detectedPrices(label).map { it.value }
        assertEquals(2, values.size)
        assertTrue(values.any { kotlin.math.abs(it - 338.67) < 0.001 })
        assertTrue(values.any { kotlin.math.abs(it - 529.0) < 0.001 })
    }

    @Test
    fun readsCatalogNumberFromQrUrl() {
        val label = PriceParser.parseLabel(
            rawText = "Bateria umywalkowa",
            barcodeValues = listOf("https://komfort.pl/p/bateria-x-100344378"),
        )
        assertEquals("100344378", label.catalogNumber)
    }


@Test
fun doesNotTreatCentsAsSeparateWholePrices() {
    val label = PriceParser.parseLabel(
        "Nr kat. 100622680 149 26 zł/m² 169 99 zł/m²",
        emptyList(),
    )
    val values = PriceParser.detectedPrices(label).map { it.value }

    assertEquals(2, values.size)
    assertTrue(values.any { kotlin.math.abs(it - 149.26) < 0.001 })
    assertTrue(values.any { kotlin.math.abs(it - 169.99) < 0.001 })
    assertTrue(values.none { kotlin.math.abs(it - 26.0) < 0.001 })
    assertTrue(values.none { kotlin.math.abs(it - 99.0) < 0.001 })
}

}
