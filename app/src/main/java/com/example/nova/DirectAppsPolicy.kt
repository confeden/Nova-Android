package com.example.nova

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Кто из установленных приложений идёт мимо туннеля всегда.
 *
 * Источников два: закрытый список имён [RussianDirectApps] и свой выбор
 * пользователя из [ClientData.getDirectApps]. Здесь — то, что без Android не
 * решается: стоит ли пакет на устройстве и откуда он поставлен.
 *
 * **Источник установки проверяется только у банков.** Формулировка владельца —
 * «все банковские российские приложения, которые установлены не с play market».
 * Смысл в том, что версия из Play живёт по общим правилам магазина, а сборка из
 * RuStore или из APK — это как раз тот банк, который отказывается работать с
 * зарубежным адресом. Остальные приложения списка (карты, госуслуги, операторы)
 * ведут себя так независимо от магазина, и сужать их источником значило бы
 * выключить настройку для большинства устройств.
 */
object DirectAppsPolicy {

    private const val PLAY_STORE_PACKAGE = "com.android.vending"

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
        // Источник установки у своего выбора не проверяется вовсе: правило про
        // Play придумано для банков из закрытого списка, а здесь пользователь
        // уже сказал, чего хочет.
        val installedCustom = custom.filter { isInstalled(packageManager, it) }.toSet()
        val resolved = if (!enabled) {
            installedCustom
        } else {
            RussianDirectApps.all().filter { pkg ->
                // Снятое человеком сильнее списка: список — это предложение, а
                // не запрет, и настоять на своём он должен уметь.
                pkg !in excluded &&
                    isInstalled(packageManager, pkg) &&
                    (!RussianDirectApps.isBanking(pkg) || !installedFromPlay(packageManager, pkg))
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

    /**
     * Пришёл ли пакет из Google Play.
     *
     * На Android 11 и новее источник отдаёт `getInstallSourceInfo`, раньше —
     * `getInstallerPackageName`. Оба могут вернуть пусто (боковая загрузка,
     * `adb install`), и это как раз «не из Play».
     */
    private fun installedFromPlay(packageManager: PackageManager, packageName: String): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        return installer == PLAY_STORE_PACKAGE
    }
}
