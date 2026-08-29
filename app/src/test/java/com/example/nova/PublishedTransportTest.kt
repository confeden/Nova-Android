package com.example.nova

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: туннель шёл через Proton
 * (`tun0 = 10.2.0.2`, выход NL), служба публиковала `transport=AWG PROTON`, а на
 * бейдже стояло «WARP: NL».
 *
 * Причина — регистр. `ClientData.getServiceTransport()` приводит прочитанное к
 * верхнему регистру, а `TRANSPORT_AWG_PROTON` — единственная константа со
 * строчными буквами, поэтому прямое `==` было ложным всегда. У `MASQUE`, `AWG`,
 * `WARP` и `OPERA` буквы и так заглавные, и мимо проходил ровно Proton: одна
 * ветка из пяти, которую глазами не отличить от рабочих.
 */
class PublishedTransportTest {

    /** Ровно то, что делает `ClientData.getServiceTransport()` с прочитанным. */
    private fun published(value: String) = value.trim().uppercase(Locale.US)

    @Test
    fun `метка транспорта переживает приведение к верхнему регистру`() {
        listOf(
            NovaVpnService.TRANSPORT_MASQUE,
            NovaVpnService.TRANSPORT_VLESS,
            NovaVpnService.TRANSPORT_WARP,
            NovaVpnService.TRANSPORT_AWG,
            NovaVpnService.TRANSPORT_AWG_PROTON,
            NovaVpnService.TRANSPORT_OPERA,
        ).forEach { transport ->
            assertTrue(
                "метка $transport не узнаётся после uppercase — бейдж покажет чужой транспорт",
                NovaVpnService.isPublishedTransport(published(transport), transport)
            )
        }
    }

    @Test
    fun `именно Proton ломался при прямом сравнении`() {
        // Пояснение к тесту выше: без учёта регистра ложным было только это равенство.
        assertFalse(published(NovaVpnService.TRANSPORT_AWG_PROTON) == NovaVpnService.TRANSPORT_AWG_PROTON)
        assertTrue(published(NovaVpnService.TRANSPORT_MASQUE) == NovaVpnService.TRANSPORT_MASQUE)
    }

    @Test
    fun `чужая метка не выдаёт себя за нашу`() {
        assertFalse(NovaVpnService.isPublishedTransport("MASQUE", NovaVpnService.TRANSPORT_AWG_PROTON))
        assertFalse(NovaVpnService.isPublishedTransport("AWG", NovaVpnService.TRANSPORT_AWG_PROTON))
        assertFalse(NovaVpnService.isPublishedTransport("", NovaVpnService.TRANSPORT_AWG_PROTON))
        assertFalse(NovaVpnService.isPublishedTransport(null, NovaVpnService.TRANSPORT_AWG_PROTON))
        // Пробелы по краям файл состояния переживал и раньше.
        assertTrue(NovaVpnService.isPublishedTransport("  awg proton  ", NovaVpnService.TRANSPORT_AWG_PROTON))
    }
}
