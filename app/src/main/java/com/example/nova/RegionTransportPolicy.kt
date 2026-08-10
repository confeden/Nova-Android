package com.example.nova

import java.util.Locale

/**
 * Что приложению позволено делать с выбранным выходом, когда он не поднимается.
 *
 * Дефект, ради которого это появилось: при выбранном EU неудачный цикл Opera
 * поднимал WARP «ненадолго, ради discovery Opera endpoints» — и оставался на нём.
 * Снаружи это выглядело как молчаливая подмена: пользователь просил EU, а через
 * минуту работал WARP RU. Тот же цикл показывал «1/50» — длину списка встроенных
 * WARP-профилей, к Opera отношения не имеющую, — и лишь потом «1/54» от
 * собственного плана запуска Opera.
 *
 * Правило то же, что у явного выбора MASQUE и импортированных профилей
 * ([MasqueStartPolicy]): **подменять выбранное приложение вправе только в
 * «Авто»**. Там выбор делает само приложение, и цепочка WARP → Opera и есть
 * его выбор. Везде ещё выбор сделал пользователь, и честных ответов на неудачу
 * ровно два — повторить выбранное или сказать, что не вышло.
 */
object RegionTransportPolicy {

    const val AUTO = "auto"

    /**
     * Выход назван пользователем: EU, US, RU, MASQUE или VLESS.
     *
     * Незнакомое значение считается явным выбором намеренно: ошибиться в сторону
     * «уважаем выбор» безопаснее, чем в сторону «подменяем молча».
     */
    fun isExplicitChoice(regionPreference: String?): Boolean = normalize(regionPreference) != AUTO

    /**
     * Можно ли ради выбранного выхода поднять другой протокол — хотя бы временно
     * и хотя бы «только для discovery».
     */
    fun allowsTransportSubstitution(regionPreference: String?): Boolean =
        !isExplicitChoice(regionPreference)

    /**
     * Идёт ли фаза, чей счётчик попыток — это список встроенных WARP-профилей.
     *
     * У Opera (EU/US), MASQUE и VLESS перебор свой и куда короче, поэтому заглушка
     * по длине встроенного списка для них не просто неточна, а обманчива: «1/50»
     * читается как «идёт перебор профилей WARP».
     *
     * Регион проверяется отдельно от транспорта потому, что в первые доли секунды
     * цикла фаза ещё не назвала себя: служба только собирается стартовать Opera, а
     * метки транспорта нет. Выбранный EU/US — достаточное основание не показывать
     * счётчик WARP уже тогда.
     */
    fun countsWarpProfileList(
        transportLabel: String?,
        backendLabel: String?,
        regionPreference: String?,
    ): Boolean {
        val transport = transportLabel?.trim()?.uppercase(Locale.US).orEmpty()
        if (transport in OWN_COUNTER_TRANSPORTS) return false
        if (backendLabel?.trim()?.uppercase(Locale.US)?.startsWith(NovaVpnService.BACKEND_OPERA) == true) {
            return false
        }
        return when (normalize(regionPreference)) {
            "eu", "us" -> false
            else -> true
        }
    }

    /** Транспорты, у которых перебор собственный, а не список встроенных профилей. */
    private val OWN_COUNTER_TRANSPORTS = setOf(
        NovaVpnService.TRANSPORT_MASQUE,
        NovaVpnService.TRANSPORT_OPERA,
        NovaVpnService.TRANSPORT_VLESS,
    )

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: AUTO
}
