package com.example.nova

import android.content.Context
import android.content.pm.PackageManager

/**
 * Кто из установленных приложений идёт мимо туннеля всегда.
 *
 * Источников два: закрытый список имён [RussianDirectApps] и свой выбор
 * пользователя из [ClientData.getDirectApps]. Здесь — то, что без Android не
 * решается: стоит ли пакет на устройстве.
 *
 * **Источник установки не проверяется ни у кого.** Прежде банки из Play
 * оставались в туннеле (владелец: «банковские приложения, которые установлены не
 * с play market»), но 2026-09-15 владелец решил иначе: банки из Play ломаются с
 * зарубежного адреса так же. Правило к тому же подвело на деле — Ozon числился
 * банком, ставится из Play, и экран «Прямого потока» рисовал ему галочку, а
 * служба оставляла его в туннеле.
 */
object DirectAppsPolicy {

    /** Столько живёт готовый список: опрос `PackageManager` не бесплатен. */
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedPackages: Set<String> = emptySet()

    @Volatile
    private var cachedAtMs = 0L

    /** Свой выбор пользователя, с которым посчитан кэш. */
    @Volatile
    private var cachedCustom: Set<String> = emptySet()

    /** Состояние мастер-переключателя, с которым посчитан кэш. */
    @Volatile
    private var cachedEnabled = false

    /** Снятое с закрытого списка, с которым посчитан кэш. */
    @Volatile
    private var cachedExcluded: Set<String> = emptySet()

    /**
     * Пакеты, которые надо увести из туннеля.
     *
     * Выключенный мастер-переключатель убирает только закрытый список: свои
     * приложения пользователь назвал руками, и отменять их чужой настройкой
     * нельзя.
     */
    fun resolve(context: Context, clientData: ClientData = ClientData(context)): Set<String> {
        val custom = clientData.getDirectApps()
        val excluded = clientData.getDirectAppsExcluded()
        val enabled = clientData.isRussianDirectAppsEnabled()
        val now = System.currentTimeMillis()
        // Заполненность кэша определяется временем, а не размером. Прежняя
        // проверка `cachedPackages.isNotEmpty()` считала законный пустой ответ
        // (ни одного пакета из списка не установлено) непосчитанным и гоняла
        // опрос PackageManager на каждый вызов. Плюс кэш теперь зависит от двух
        // настроек, и переиспользовать его можно только пока обе те же.
        if (cachedAtMs != 0L &&
            cachedEnabled == enabled &&
            cachedCustom == custom &&
            cachedExcluded == excluded &&
            now - cachedAtMs < CACHE_TTL_MS
        ) {
            return cachedPackages
        }
        val packageManager = context.packageManager
        val installedCustom = custom.filter { isInstalled(packageManager, it) }.toSet()
        val resolved = if (!enabled) {
            installedCustom
        } else {
            RussianDirectApps.all().filter { pkg ->
                // Снятое человеком сильнее списка: список — это предложение, а
                // не запрет, и настоять на своём он должен уметь.
                pkg !in excluded && isInstalled(packageManager, pkg)
            }.toSet() + installedCustom
        }
        cachedPackages = resolved
        cachedCustom = custom
        cachedExcluded = excluded
        cachedEnabled = enabled
        cachedAtMs = now
        return resolved
    }

    /** Сбрасывает кэш — после смены самой настройки ждать полминуты незачем. */
    fun invalidate() {
        cachedPackages = emptySet()
        cachedCustom = emptySet()
        cachedExcluded = emptySet()
        cachedEnabled = false
        cachedAtMs = 0L
    }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }
}
