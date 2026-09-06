package com.example.nova

import com.example.nova.ProfileIssueLabels.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты для [ProfileIssueLabels]: сопоставление региона с источником профилей
 * и формирование подписей на кнопке выпуска.
 */
class ProfileIssueLabelsTest {

    @Test
    fun `kindFor returns CLOUDFLARE for warp regions and fallback values`() {
        for (region in listOf("auto", "ru", "masque")) {
            assertEquals(Kind.CLOUDFLARE, ProfileIssueLabels.kindFor(region, protonPreparationRequested = false))
        }
        assertEquals(Kind.CLOUDFLARE, ProfileIssueLabels.kindFor(null, protonPreparationRequested = false))
        assertEquals(Kind.CLOUDFLARE, ProfileIssueLabels.kindFor("unknown", protonPreparationRequested = false))
    }

    @Test
    fun `kindFor returns PROTON for proton and whenever preparation is requested`() {
        assertEquals(Kind.PROTON, ProfileIssueLabels.kindFor("proton", protonPreparationRequested = false))
        for (region in listOf("auto", "ru", "masque", "eu", "us", "vless", "tor", "unknown", null)) {
            assertEquals(Kind.PROTON, ProfileIssueLabels.kindFor(region, protonPreparationRequested = true))
        }
    }

    @Test
    fun `kindFor returns NONE for unsupported regions`() {
        for (region in listOf("eu", "us", "vless", "tor")) {
            assertEquals(Kind.NONE, ProfileIssueLabels.kindFor(region, protonPreparationRequested = false))
        }
    }

    @Test
    fun `cloudflare button labels reflect exists and busy states`() {
        assertEquals("Сгенерировать свои профили Cloudflare", ProfileIssueLabels.label(Kind.CLOUDFLARE, exists = false, busy = false))
        assertEquals("Обновить свои профили Cloudflare", ProfileIssueLabels.label(Kind.CLOUDFLARE, exists = true, busy = false))
        assertEquals("Профили Cloudflare выпускаются…", ProfileIssueLabels.label(Kind.CLOUDFLARE, exists = true, busy = true))
    }

    @Test
    fun `proton button labels reflect exists and busy states`() {
        assertEquals("Выпустить профили Proton", ProfileIssueLabels.label(Kind.PROTON, exists = false, busy = false))
        assertEquals("Обновить профили Proton", ProfileIssueLabels.label(Kind.PROTON, exists = true, busy = false))
        assertEquals("Профили Proton выпускаются…", ProfileIssueLabels.label(Kind.PROTON, exists = true, busy = true))
    }

    @Test
    fun `busy state wins over exists for both kinds`() {
        assertEquals("Профили Cloudflare выпускаются…", ProfileIssueLabels.label(Kind.CLOUDFLARE, exists = false, busy = true))
        assertEquals("Профили Cloudflare выпускаются…", ProfileIssueLabels.label(Kind.CLOUDFLARE, exists = true, busy = true))
        assertEquals("Профили Proton выпускаются…", ProfileIssueLabels.label(Kind.PROTON, exists = false, busy = true))
        assertEquals("Профили Proton выпускаются…", ProfileIssueLabels.label(Kind.PROTON, exists = true, busy = true))
    }

    @Test
    fun `label for NONE is empty string regardless of exists or busy`() {
        for (exists in listOf(false, true)) {
            for (busy in listOf(false, true)) {
                assertEquals("", ProfileIssueLabels.label(Kind.NONE, exists = exists, busy = busy))
            }
        }
    }
}
