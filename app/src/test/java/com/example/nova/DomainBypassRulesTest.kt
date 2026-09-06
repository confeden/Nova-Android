package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBypassRulesTest {

    // ---- parseZones --------------------------------------------------------

    @Test
    fun `parseZones strips leading dot and handles comma-space mix`() {
        val zones = DomainBypassRules.parseZones(".ru, su")
        assertEquals(setOf("ru", "su"), zones)
    }

    // ---- parseDomains -------------------------------------------------------

    @Test
    fun `parseDomains strips scheme www and path`() {
        val domains = DomainBypassRules.parseDomains("https://www.ozon.ru/path/to/page")
        assertEquals(setOf("ozon.ru"), domains)
    }

    @Test
    fun `parseDomains drops entries without a dot`() {
        val domains = DomainBypassRules.parseDomains("localhost ozon.ru")
        assertFalse(domains.contains("localhost"))
        assertTrue(domains.contains("ozon.ru"))
    }

    // ---- matches: domain rules ---------------------------------------------

    @Test
    fun `exact domain rule matches the domain itself`() {
        val rules = DomainBypassRules.rulesFrom("", "ozon.ru", false)
        assertTrue(DomainBypassRules.matches("ozon.ru", rules))
    }

    @Test
    fun `domain rule matches www subdomain`() {
        val rules = DomainBypassRules.rulesFrom("", "ozon.ru", false)
        assertTrue(DomainBypassRules.matches("www.ozon.ru", rules))
    }

    @Test
    fun `domain rule matches arbitrary subdomain`() {
        val rules = DomainBypassRules.rulesFrom("", "ozon.ru", false)
        assertTrue(DomainBypassRules.matches("market.ozon.ru", rules))
    }

    @Test
    fun `domain rule does NOT match when suffix is not on a label boundary`() {
        val rules = DomainBypassRules.rulesFrom("", "ozon.ru", false)
        assertFalse(DomainBypassRules.matches("67ozon.ru", rules))
    }

    @Test
    fun `domain rule does NOT match domain used as suffix of another TLD`() {
        val rules = DomainBypassRules.rulesFrom("", "ozon.ru", false)
        assertFalse(DomainBypassRules.matches("ozon.ru.evil.com", rules))
    }

    // ---- matches: zone rules -----------------------------------------------

    @Test
    fun `zone ru matches multi-label host`() {
        val rules = DomainBypassRules.rulesFrom("ru", "", false)
        assertTrue(DomainBypassRules.matches("a.b.ru", rules))
    }

    @Test
    fun `zone ru does NOT match different TLD`() {
        val rules = DomainBypassRules.rulesFrom("ru", "", false)
        assertFalse(DomainBypassRules.matches("example.com", rules))
    }

    @Test
    fun `zone ru does NOT match bare TLD without a preceding label`() {
        val rules = DomainBypassRules.rulesFrom("ru", "", false)
        assertFalse(DomainBypassRules.matches("ru", rules))
    }

    // ---- matches: Cyrillic zones -------------------------------------------

    @Test
    fun `cyrillicZones matches punycode host that decodes to Cyrillic TLD`() {
        // xn--80aswg.xn--p1ai decodes to сайт.рф — TLD рф is Cyrillic
        val rules = DomainBypassRules.rulesFrom("", "", true)
        assertTrue(DomainBypassRules.matches("xn--80aswg.xn--p1ai", rules))
    }

    @Test
    fun `cyrillicZones matches Unicode Cyrillic host directly`() {
        val rules = DomainBypassRules.rulesFrom("", "", true)
        assertTrue(DomainBypassRules.matches("сайт.рф", rules))
    }

    @Test
    fun `cyrillicZones false does NOT match Cyrillic TLD`() {
        val rules = DomainBypassRules.rulesFrom("", "", false)
        assertFalse(DomainBypassRules.matches("сайт.рф", rules))
    }

    // ---- edge cases --------------------------------------------------------

    @Test
    fun `empty host returns false`() {
        val rules = DomainBypassRules.rulesFrom("ru", "ozon.ru", true)
        assertFalse(DomainBypassRules.matches("", rules))
    }

    @Test
    fun `empty rules return false`() {
        val rules = DomainBypassRules.rulesFrom("", "", false)
        assertFalse(DomainBypassRules.matches("ozon.ru", rules))
    }
}
