package com.example.nova

import java.util.Locale

/**
 * Что считается выбранным, когда пользователь подключается своими профилями.
 *
 * Дефект, ради которого это появилось: пользователь импортировал подписку VLESS,
 * экран подписывал бэкенд «VLESS», а служба фазу VLESS не запускала вовсе и гасла
 * через полминуты. Причина — «AUTO»: точки импорта включают режим «только
 * импортированные», но выбранный протокол не трогают, а проверка требовала ровно
 * значения `vless`. Схлопывание «AUTO» в единственную доступную семью жило в UI,
 * поэтому экран и служба расходились в том, что именно выбрано.
 *
 * Второй дефект той же природы: регион. В режиме импортированных экран настроек
 * показывает не регионы, а протоколы, так что оставшийся с прошлого раза `eu`,
 * `masque` или `vless` выбором уже не является — сменить его пользователю нечем.
 * Пока решение принималось по региону, включённый режим отменялся целиком.
 *
 * Правило одно: **в режиме импортированных решает протокол, вне его — регион.**
 */
object ImportedSourcePolicy {

    const val AUTO = "auto"
    const val VLESS = "vless"

    /**
     * Какой протокол среди импортированных считать выбранным.
     *
     * «AUTO» при единственной доступной семье — это не выбор, а его отсутствие:
     * выбирать не из чего, и подставлять надо то единственное, что есть.
     *
     * Протокол, которого больше нет ни у одного профиля (все AWG удалили, остались
     * WireGuard), тоже откатывается в «AUTO»: иначе сохранённый выбор молча отсекает
     * весь список, shortlist получается пустым и подключение останавливается.
     */
    fun resolveEffectiveProtocol(preference: String?, availableFamilies: List<String>): String {
        val families = availableFamilies
            .map { normalize(it) }
            .filter { it.isNotEmpty() && it != AUTO }
            .distinct()
        if (families.isEmpty()) return AUTO
        val normalizedPreference = normalize(preference)
        if (normalizedPreference != AUTO && normalizedPreference.isNotEmpty()) {
            return if (normalizedPreference in families) normalizedPreference else AUTO
        }
        return families.singleOrNull() ?: AUTO
    }

    /**
     * Назвал ли пользователь VLESS выбранным транспортом.
     *
     * Это же условие обязано стоять и на входе в фазу VLESS, и на выходе из неё.
     * Пока выход проверял более узкое условие (только режим импортированных, без
     * региона), провал VLESS уводил перебор на встроенные профили WARP: вход и
     * выход проверяли разные вещи.
     */
    fun isVlessChosen(
        importedSourceActive: Boolean,
        effectiveImportedProtocol: String?,
        regionPreference: String?,
    ): Boolean = if (importedSourceActive) {
        normalize(effectiveImportedProtocol) == VLESS
    } else {
        normalize(regionPreference) == VLESS
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase(Locale.US).orEmpty()
}
