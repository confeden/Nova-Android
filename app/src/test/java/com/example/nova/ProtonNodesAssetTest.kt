package com.example.nova

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Актив `proton_nodes.json` проверяется тестом, а не глазами, по одной причине:
 * он собирается скриптом из файла телефона, и цена ошибки несимметрична.
 *
 * Лишний узел без ключа — это просто мёртвый кандидат. А вот утёкший `seed` или
 * приватный ключ означал бы один аккаунт Proton на всех пользователей сразу, при
 * `MaxConnect: 2` на бесплатном тарифе: подключились бы двое, остальные получили бы
 * отказ. Поэтому тест сторожит и полноту, и **отсутствие** лишнего.
 */
class ProtonNodesAssetTest {

    private val allowedFields = setOf("server_name", "country", "city", "entry_ip", "peer_public_key")

    private fun asset(): JSONObject {
        // Рабочий каталог юнит-тестов Gradle — модуль `app`, но подстрахуемся и от
        // запуска из корня репозитория.
        val candidates = listOf(
            File("src/main/assets/${ProtonNodeCatalog.ASSET_NAME}"),
            File("app/src/main/assets/${ProtonNodeCatalog.ASSET_NAME}"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("не найден ${ProtonNodeCatalog.ASSET_NAME}; искали: $candidates")
        return JSONObject(file.readText())
    }

    @Test
    fun `в активе полсотни пригодных узлов`() {
        val nodes = asset().getJSONArray("nodes")
        assertEquals(50, nodes.length())
        val seen = mutableSetOf<Pair<String, String>>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val entryIp = node.optString("entry_ip")
            val peerKey = node.optString("peer_public_key")
            assertTrue("узел $index без адреса входа", entryIp.isNotBlank())
            assertTrue("узел $index без ключа пира", peerKey.isNotBlank())
            assertTrue("узел $index: ключ пира не похож на x25519", peerKey.length >= 40)
            assertTrue("узел $index без страны", node.optString("country").isNotBlank())
            assertTrue("узел $index повторяется", seen.add(entryIp to peerKey))
        }
    }

    @Test
    fun `в активе нет ничего кроме публичных фактов об узле`() {
        val nodes = asset().getJSONArray("nodes")
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            node.keys().forEach { key ->
                assertTrue(
                    "узел $index несёт лишнее поле «$key» — в прошивку едут только публичные факты",
                    key in allowedFields
                )
            }
        }
    }

    @Test
    fun `голова очереди не из одной страны`() {
        // Иначе при недоступности одной страны перебор буксует на первых кандидатах.
        val nodes = asset().getJSONArray("nodes")
        val head = (0 until minOf(4, nodes.length()))
            .map { nodes.getJSONObject(it).optString("country") }
        assertEquals("первые четыре узла обязаны быть из разных стран", head.size, head.toSet().size)
    }
}
