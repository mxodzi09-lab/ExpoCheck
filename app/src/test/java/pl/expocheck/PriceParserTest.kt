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
}
