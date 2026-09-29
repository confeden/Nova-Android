package com.example.nova

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нижние пределы MTU обязаны попадать в конфигурацию клиента: с умолчаниями
 * движка (100 вверх, 1000 вниз) на МегаФоне отбраковывались все российские
 * резолверы — а под белым списком других несущих нет (замер 2026-09-29).
 */
class StormDnsMtuFloorTest {

    @Test
    fun `client config lowers the MTU floors below what Russian resolvers give`() {
        val text = StormDnsConfig.clientConfig(DnsProfileList.BUILT_IN_PROFILE, listenPort = 18000)
        assertTrue(text.contains("MIN_UPLOAD_MTU = ${StormDnsConfig.MIN_UPLOAD_MTU}"))
        assertTrue(text.contains("MIN_DOWNLOAD_MTU = ${StormDnsConfig.MIN_DOWNLOAD_MTU}"))
        // Худшие принятые на МегаФоне: 84 Б вверх (оператор), 650 Б вниз (связка из 10).
        assertTrue(StormDnsConfig.MIN_UPLOAD_MTU <= 84)
        assertTrue(StormDnsConfig.MIN_DOWNLOAD_MTU <= 650)
    }
}
