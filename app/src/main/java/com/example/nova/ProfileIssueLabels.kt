package com.example.nova

/**
 * Подписи кнопки выпуска профилей.
 *
 * Кнопка одна на два источника — личные профили Cloudflare и профили Proton, — и
 * её текст обязан отвечать на вопрос «что будет, если нажать»: выпустить впервые
 * или обновить уже выпущенное. Раньше он был написан в трёх местах
 * (`bindProtonRefreshButton`, восстановление после прогона и опрос состояния
 * каждые 1,5 с), и после прогона надпись откатывалась на прежнюю: три писателя
 * на один вид спорили друг с другом.
 *
 * Объект чистый — ни `Context`, ни ввода-вывода: подпись обязана проверяться
 * тестом, а не наблюдением на устройстве.
 */
object ProfileIssueLabels {

    /** Какой источник профилей обслуживает кнопка при этом выбранном регионе. */
    enum class Kind { CLOUDFLARE, PROTON, NONE }

    /**
     * @param storedRegion сохранённое предпочтение региона.
     * @param protonPreparationRequested идёт ли выпуск Proton прямо сейчас.
     */
    fun kindFor(storedRegion: String?, protonPreparationRequested: Boolean): Kind {
        if (protonPreparationRequested) return Kind.PROTON
        return when (RegionTransportPolicy.normalizeKnown(storedRegion)) {
            "proton" -> Kind.PROTON
            // Личные профили Cloudflare работают и в «Авто», и на явном WARP, и на
            // MASQUE: все три поднимаются на одной и той же личности.
            "auto", "ru", "masque" -> Kind.CLOUDFLARE
            // Opera и импортированные профили Cloudflare не используют вовсе.
            else -> Kind.NONE
        }
    }

    /**
     * Текст кнопки.
     *
     * @param exists выпускались ли профили этого вида хоть раз.
     * @param busy идёт ли выпуск прямо сейчас.
     */
    fun label(kind: Kind, exists: Boolean, busy: Boolean): String = when (kind) {
        Kind.CLOUDFLARE -> when {
            busy -> "Профили Cloudflare выпускаются…"
            exists -> "Обновить свои профили Cloudflare"
            else -> "Сгенерировать свои профили Cloudflare"
        }
        Kind.PROTON -> when {
            busy -> "Профили Proton выпускаются…"
            exists -> "Обновить профили Proton"
            else -> "Выпустить профили Proton"
        }
        Kind.NONE -> ""
    }
}
