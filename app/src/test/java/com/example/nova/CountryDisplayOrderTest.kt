package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryDisplayOrderTest {

    @Test
    fun `the full preferred list in scrambled input order comes back in preferred order`() {
        val scrambled = listOf("SG", "US", "NL", "JP", "NO", "MX", "CA", "RO", "CH", "PL")
        val expected = listOf("NL", "NO", "PL", "RO", "CH", "US", "CA", "MX", "JP", "SG")
        assertEquals(expected, CountryDisplayOrder.order(scrambled))
    }

    @Test
    fun `only a subset available returns only that subset in preferred order`() {
        val subset = listOf("JP", "NL", "US")
        val expected = listOf("NL", "US", "JP")
        assertEquals(expected, CountryDisplayOrder.order(subset))
    }

    @Test
    fun `non-preferred codes appended grouped by region and sorted alphabetically`() {
        val input = listOf("ZA", "FR", "CN", "US", "AR", "AU", "DE", "IN", "NL", "BR")
        val expected = listOf("NL", "US", "DE", "FR", "AR", "BR", "CN", "IN", "AU", "ZA")
        assertEquals(expected, CountryDisplayOrder.order(input))
    }

    @Test
    fun `lowercase and whitespace input normalized`() {
        val input = listOf("  nl ", "us", " de\t", "br  ")
        val expected = listOf("NL", "US", "DE", "BR")
        assertEquals(expected, CountryDisplayOrder.order(input))
    }

    @Test
    fun `duplicates collapsed`() {
        val input = listOf("NL", "DE", "NL", "BR", "DE", "US", "BR")
        val expected = listOf("NL", "US", "DE", "BR")
        assertEquals(expected, CountryDisplayOrder.order(input))
    }

    @Test
    fun `invalid entries dropped`() {
        val input = listOf("", "USA", "1A", "  ", "N1", "N-", "NL", "A", "ABC")
        val expected = listOf("NL")
        assertEquals(expected, CountryDisplayOrder.order(input))
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<String>(), CountryDisplayOrder.order(emptyList()))
        assertEquals(emptyList<String>(), CountryDisplayOrder.order(listOf("", "   ", "TOOLONG", "12")))
    }

    @Test
    fun `regionOf on representative codes and unknown codes`() {
        assertEquals(CountryDisplayOrder.CountryRegion.EUROPE, CountryDisplayOrder.regionOf("DE"))
        assertEquals(CountryDisplayOrder.CountryRegion.EUROPE, CountryDisplayOrder.regionOf("FR"))
        assertEquals(CountryDisplayOrder.CountryRegion.AMERICA, CountryDisplayOrder.regionOf("BR"))
        assertEquals(CountryDisplayOrder.CountryRegion.AMERICA, CountryDisplayOrder.regionOf("AR"))
        assertEquals(CountryDisplayOrder.CountryRegion.ASIA, CountryDisplayOrder.regionOf("CN"))
        assertEquals(CountryDisplayOrder.CountryRegion.ASIA, CountryDisplayOrder.regionOf("IN"))
        assertEquals(CountryDisplayOrder.CountryRegion.OTHER, CountryDisplayOrder.regionOf("AU"))
        assertEquals(CountryDisplayOrder.CountryRegion.OTHER, CountryDisplayOrder.regionOf("NZ"))
        assertEquals(CountryDisplayOrder.CountryRegion.OTHER, CountryDisplayOrder.regionOf("ZA"))
        assertEquals(CountryDisplayOrder.CountryRegion.OTHER, CountryDisplayOrder.regionOf("XX"))
        assertEquals(CountryDisplayOrder.CountryRegion.OTHER, CountryDisplayOrder.regionOf("???"))
    }
}
