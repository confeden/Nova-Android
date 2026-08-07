package com.example.nova

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Вендорные экраны «Автозапуск» и «Фон/батарея» для китайских прошивок
 * (HyperOS/MIUI, EMUI, MagicOS, ColorOS, OriginOS/Funtouch, Flyme, HiOS/XOS).
 *
 * На всех остальных прошивках (AOSP, Pixel, Samsung и т.д.) хелпер не
 * предлагает ничего: стандартной оптимизации батареи там достаточно, и
 * вендорные строки в настройках показываться не должны.
 */
object VendorBackgroundSettingsHelper {

    // Файловый кэш прежних версий: переживал OTA прошивки и хранил устаревшие
    // компоненты, из-за чего строки могли показываться там, где уже не работают.
    private const val LEGACY_CACHE_PREFS = "vendor_background_settings_cache"

    private enum class Skin { MIUI, EMUI, MAGICOS, COLOROS, VIVO, FLYME, TRANSSION, NONE }

    private data class Candidate(
        val label: String,
        val intent: Intent,
    )

    private data class ResolvedCandidates(
        val autoStart: List<Candidate> = emptyList(),
        val background: List<Candidate> = emptyList(),
    )

    @Volatile
    private var cached: ResolvedCandidates? = null

    fun primeCache(context: Context) {
        runCatching { context.deleteSharedPreferences(LEGACY_CACHE_PREFS) }
        loadOrResolve(context)
    }

    fun getVendorLabel(context: Context): String? = getBackgroundLabel(context)

    fun canOpen(context: Context): Boolean = canOpenBackground(context)

    fun open(context: Context): Boolean = openBackground(context)

    fun getBackgroundLabel(context: Context): String? {
        return loadOrResolve(context).background.firstOrNull()?.label
    }

    fun canOpenBackground(context: Context): Boolean {
        return loadOrResolve(context).background.isNotEmpty()
    }

    fun openBackground(context: Context): Boolean {
        return openFirstWorking(context, loadOrResolve(context).background)
    }

    fun getAutoStartLabel(context: Context): String? {
        return loadOrResolve(context).autoStart.firstOrNull()?.label
    }

    fun canOpenAutoStart(context: Context): Boolean {
        return loadOrResolve(context).autoStart.isNotEmpty()
    }

    fun openAutoStart(context: Context): Boolean {
        return openFirstWorking(context, loadOrResolve(context).autoStart)
    }

    private fun openFirstWorking(context: Context, candidates: List<Candidate>): Boolean {
        for (candidate in candidates) {
            try {
                context.startActivity(
                    Intent(candidate.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Throwable) {
                // Часть вендорных activity объявлена, но кидает SecurityException
                // при внешнем запуске — пробуем следующий кандидат.
            }
        }
        return false
    }

    private fun loadOrResolve(context: Context): ResolvedCandidates {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val resolved = runCatching { resolveCandidates(context) }
                .getOrDefault(ResolvedCandidates())
            cached = resolved
            return resolved
        }
    }

    private fun sysProp(name: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, name) as? String).orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun detectSkin(): Skin {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim().lowercase()
        val brand = Build.BRAND.orEmpty().trim().lowercase()
        val ids = setOf(manufacturer, brand)
        fun anyOf(vararg names: String) = ids.any { id -> names.any { id.contains(it) } }

        return when {
            sysProp("ro.miui.ui.version.name").isNotEmpty() ||
                sysProp("ro.mi.os.version.name").isNotEmpty() ||
                anyOf("xiaomi", "redmi", "poco", "blackshark") -> Skin.MIUI

            sysProp("ro.build.version.emui").isNotEmpty() || anyOf("huawei") -> Skin.EMUI

            anyOf("honor", "hihonor") -> Skin.MAGICOS

            sysProp("ro.build.version.opporom").isNotEmpty() ||
                sysProp("ro.build.version.oplusrom").isNotEmpty() ||
                anyOf("oppo", "realme", "oneplus", "oplus") -> Skin.COLOROS

            sysProp("ro.vivo.os.version").isNotEmpty() || anyOf("vivo", "iqoo") -> Skin.VIVO

            anyOf("meizu") ||
                Build.DISPLAY.orEmpty().contains("flyme", ignoreCase = true) -> Skin.FLYME

            anyOf("tecno", "infinix", "itel", "transsion") -> Skin.TRANSSION

            else -> Skin.NONE
        }
    }

    private fun isTelevision(context: Context): Boolean {
        return runCatching {
            val uiModeManager = context.getSystemService(android.app.UiModeManager::class.java)
            uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)
    }

    private fun resolveCandidates(context: Context): ResolvedCandidates {
        val skin = detectSkin()
        // На ТВ-приставках вендорных «центров безопасности» нет, а Build.MANUFACTURER
        // у Xiaomi/TCL совпадает с телефонным.
        if (skin == Skin.NONE || isTelevision(context)) return ResolvedCandidates()

        val packageManager = context.packageManager
        val appPackage = context.packageName
        val appLabel = runCatching {
            context.applicationInfo.loadLabel(packageManager).toString()
        }.getOrDefault("Nova")

        fun explicit(label: String, pkg: String, cls: String, extras: Intent.() -> Unit = {}): Candidate {
            return Candidate(
                label = label,
                intent = Intent().setComponent(ComponentName(pkg, cls)).apply(extras),
            )
        }

        fun action(label: String, intentAction: String, extras: Intent.() -> Unit = {}): Candidate {
            return Candidate(label = label, intent = Intent(intentAction).apply(extras))
        }

        // HyperOS (OS1.0+) выставляет ro.mi.os.version.name; классический MIUI — нет.
        val isHyperOs = sysProp("ro.mi.os.version.name").isNotEmpty()

        val autoStartCandidates = when (skin) {
            Skin.MIUI -> if (isHyperOs) listOf(
                // HyperOS 3 (проверено на Redmi Pad 2 Pro, OS3.0.4): пер-app редактор
                // разрешений больше НЕ содержит тумблер автозапуска, рабочий экран —
                // общий список «Автозапуск в фоне» Центра безопасности.
                explicit(
                    "Автозапуск",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                action("Автозапуск", "miui.intent.action.OP_AUTO_START"),
            ) else listOf(
                // Классический MIUI: пер-app редактор разрешений с тумблером «Автозапуск».
                action("Автозапуск Nova", "miui.intent.action.APP_PERM_EDITOR") {
                    putExtra("extra_pkgname", appPackage)
                },
                explicit(
                    "Автозапуск",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                action("Автозапуск", "miui.intent.action.OP_AUTO_START"),
            )

            Skin.EMUI -> listOf(
                explicit(
                    "Автозапуск",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            )

            Skin.MAGICOS -> listOf(
                explicit(
                    "Автозапуск",
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            )

            Skin.COLOROS -> listOf(
                explicit(
                    "Автозапуск",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
                // Легаси OxygenOS (OnePlus до 12-й версии).
                explicit(
                    "Автозапуск",
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            )

            Skin.VIVO -> listOf(
                explicit(
                    "Фоновый запуск",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            )

            Skin.FLYME -> listOf(
                // Пер-app страница безопасности Flyme с тумблером автозапуска.
                action("Автозапуск Nova", "com.meizu.safe.security.SHOW_APPSEC") {
                    putExtra("packageName", appPackage)
                },
                explicit(
                    "Фоновые разрешения",
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity",
                ),
                explicit(
                    "Разрешения",
                    "com.meizu.safe",
                    "com.meizu.safe.permission.PermissionMainActivity",
                ),
            )

            Skin.TRANSSION -> listOf(
                explicit(
                    "Автозапуск",
                    "com.transsion.phonemaster",
                    "com.cyin.himgr.autostart.AutoStartActivity",
                ),
                explicit(
                    "Автозапуск",
                    "com.transsion.phonemanager",
                    "com.itel.autobootmanager.activity.AutoBootMgrActivity",
                ),
            )

            Skin.NONE -> emptyList()
        }

        val backgroundCandidates = when (skin) {
            Skin.MIUI -> listOf(
                // Пер-app страница «Батарея» (MIUI PowerKeeper; на HyperOS 3 удалена —
                // отфильтруется проверкой резолва).
                explicit(
                    "Батарея: фон без ограничений",
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ) {
                    putExtra("package_name", appPackage)
                    putExtra("package_label", appLabel)
                },
                // HyperOS 3 перехватывает стандартный intent страницей «Сведения о
                // батарее» приложения (securitycenter/PowerDetailActivity, priority 999) —
                // проверено на Redmi Pad 2 Pro: открывает Battery saver именно для Nova.
                action("Батарея: фон без ограничений", "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") {
                    data = Uri.parse("package:$appPackage")
                },
                // Настройки батареи Центра безопасности (общий экран, последний резерв).
                explicit(
                    "Настройки батареи",
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings",
                ),
            )

            Skin.EMUI -> listOf(
                explicit(
                    "Запуск приложений",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
                explicit(
                    "Защищённые приложения",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )

            Skin.MAGICOS -> listOf(
                explicit(
                    "Запуск приложений",
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            )

            Skin.COLOROS -> listOf(
                explicit(
                    "Фоновая активность",
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
                explicit(
                    "Фоновая активность",
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
            )

            Skin.VIVO -> listOf(
                explicit(
                    "Белый список энергосбережения",
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            )

            Skin.FLYME -> listOf(
                explicit(
                    "Фоновые разрешения",
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity",
                ),
            )

            Skin.TRANSSION -> emptyList()

            Skin.NONE -> emptyList()
        }

        fun resolvable(candidate: Candidate): Boolean {
            return runCatching {
                // Осторожно: Intent.resolveActivity(pm) для явного компонента возвращает
                // его НЕ проверяя существование — валидировать нужно через PackageManager.
                val flags = if (candidate.intent.component != null) 0 else PackageManager.MATCH_DEFAULT_ONLY
                val info = packageManager.resolveActivity(candidate.intent, flags) ?: return false
                info.activityInfo?.exported == true
            }.getOrDefault(false)
        }

        return ResolvedCandidates(
            autoStart = autoStartCandidates.filter(::resolvable),
            background = backgroundCandidates.filter(::resolvable),
        )
    }
}
