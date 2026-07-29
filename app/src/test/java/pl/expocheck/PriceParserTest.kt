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

}
