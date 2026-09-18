package com.example.nova

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Цвета наших уведомлений по теме оформления.
 *
 * Зачем. Кнопка «Отключить» в строке VPN и карточка «обновление готово» были
 * прибиты к зелёному Aurora mint: какую тему ни выбери, в шторке оставалась мята.
 * Теперь их заливка идёт от акцента выбранной темы.
 *
 * Почему цвета считаются, а не берутся из темы как есть. Макет уведомления
 * раздувает SystemUI в своей теме, `?attr/nova*` там нет (I21, G192), поэтому цвет
 * доезжает литералом через `RemoteViews`. А сам акцент заливкой быть не может: у
 * Matrix он люминофорный `#00FF41`, у Graphene — жёлтый, и светлая подпись по ним
 * не читается. Поэтому заливка — акцент, затемнённый ровно до контраста
 * [MIN_CONTRAST] со светлой подписью, а чистый акцент остаётся обводкой. Заливка
 * сплошная по той же причине, что и раньше (G192): контраст с подписью один и тот
 * же на тёмной шторке и на светлой.
 *
 * Где тема. Её выбирает основной процесс и держит в `SharedPreferences`, а строку
 * VPN строит `:vpn`, и чужую запись в настройки он не видит (I2). Поэтому выбор
 * дублируется в файл [MIRROR_FILE]; `:vpn` проверяет его не чаще раза в пару секунд,
 * а живую службу вдобавок будит [EXTRA_UI_THEME] в `ACTION_REFRESH_NOTIFICATION`
 * (I19) — так же, как язык в [NovaLanguage]. Нет файла — зелёный Aurora mint,
 * то есть ровно прежний вид.
 */
object NovaNotificationPalette {

    data class Palette(
        /** Акцент темы как есть: цвет шапки уведомления, обводки. */
        val accent: Int,
        val buttonFill: Int,
        val buttonStroke: Int,
        val buttonText: Int,
        val cardBase: Int,
        val cardGlow: Int,
        val cardStroke: Int,
        val cardTitle: Int,
        val cardSubtitle: Int,
        val cardChevron: Int,
    )

    /** Ключ выбранной темы в `ACTION_REFRESH_NOTIFICATION` для процесса `:vpn`. */
    const val EXTRA_UI_THEME = "com.example.nova.extra.UI_THEME"

    /**
     * Контраст подписи с заливкой. WCAG AA для текста мельче 18sp требует 4,5;
     * запас на то, что шрифт в шторке 12sp и местами полупрозрачный край.
     */
    internal const val MIN_CONTRAST = 5.0

    private const val MIRROR_FILE = "ui_theme"
    private const val DISK_CHECK_INTERVAL_MS = 2_000L
    private const val OPAQUE = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Процесс не пишет выбор сам и обязан замечать чужую запись (`:vpn`). */
    @Volatile
    private var followsForeignWrites = false

    /** Тема из файла или из намерения; `null` — выбора ещё не видели. */
    @Volatile
    private var mirrorKey: String? = null

    @Volatile
    private var mirrorStamp = Long.MIN_VALUE

    @Volatile
    private var nextDiskCheckAtMs = 0L

    @Volatile
    private var mirrorSynced = false

    @Volatile
    private var cached: Pair<String, Palette>? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaThemeMirrorWrite").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- `:vpn`

    /** Процессу `:vpn`: тему выбирает основной процесс, а этот должен замечать запись. */
    fun followForeignWrites() {
        followsForeignWrites = true
    }

    /**
     * Тема из `ACTION_REFRESH_NOTIFICATION`. Отметка файла намеренно не
     * сбрасывается — по той же причине, что в [NovaLanguage.applyFromIntent]: запись
     * могла ещё не дойти до диска, и перечитанный старый файл откатил бы выбор.
     */
    fun applyFromIntent(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_UI_THEME) ?: return
        mirrorKey = NovaTheme.optionFor(key).key
    }

    // ---------------------------------------------------------------- цвета

    /** Цвета для уведомления, которое строится прямо сейчас. */
    fun current(context: Context): Palette {
        val key = themeKey(context)
        cached?.let { (cachedKey, palette) -> if (cachedKey == key) return palette }
        val accent = runCatching {
            val themed = ContextThemeWrapper(context.applicationContext, NovaTheme.optionFor(key).styleRes)
            NovaTheme.color(themed, R.attr.novaAccent)
        }.getOrElse { ContextCompat.getColor(context, R.color.nova_aurora_accent) }
        return derive(accent).also { cached = key to it }
    }

    private fun themeKey(context: Context): String {
        // Основной процесс сам пишет настройки, и его кэш настроек и есть правда.
        // Предпросмотр экрана оформления сюда не попадает: шторка показывает
        // сохранённое, а не примеряемое.
        if (!followsForeignWrites) {
            return NovaTheme.optionFor(NovaAppearance.storedThemeKey(context)).key
        }
        refreshFromDiskIfDue(context.applicationContext ?: context)
        return mirrorKey ?: NovaTheme.DEFAULT_KEY
    }

    /** `stat` раз в пару секунд, не чаще: цвета спрашивают на каждое уведомление. */
    private fun refreshFromDiskIfDue(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextDiskCheckAtMs) return
        nextDiskCheckAtMs = now + DISK_CHECK_INTERVAL_MS
        val file = mirrorFile(context)
        val stamp = runCatching { file.lastModified() }.getOrDefault(0L)
        if (stamp == mirrorStamp) return
        mirrorStamp = stamp
        readMirror(file)?.let { mirrorKey = it }
    }

    /**
     * Цвета из акцента. Чистая арифметика над ARGB, без `android.graphics.Color`,
     * чтобы её проверял обычный юнит-тест.
     *
     * Подпись — акцент, почти доведённый до белого: она светлая при любом акценте
     * и всё же в его тоне. Заливка — акцент, затемнённый с шагом 5 % до
     * [MIN_CONTRAST] с подписью; начинаем с 55 %, потому что это ближе всего к
     * прежней заливке `#10664A` при акценте Aurora mint. Подпись минимум на 85 %
     * белая, поэтому к 10 % яркости контраст заведомо набран и цикл конечен.
     */
    internal fun derive(accent: Int): Palette {
        val base = accent or OPAQUE
        val text = mix(base, WHITE, 0.85)
        var factor = 0.55
        var fill = scale(base, factor)
        while (contrast(text, fill) < MIN_CONTRAST && factor > 0.1) {
            factor -= 0.05
            fill = scale(base, factor)
        }
        return Palette(
            accent = base,
            buttonFill = fill,
            buttonStroke = base,
            buttonText = text,
            cardBase = scale(fill, 0.5),
            cardGlow = fill,
            cardStroke = base,
            cardTitle = WHITE,
            cardSubtitle = text,
            cardChevron = mix(base, WHITE, 0.45),
        )
    }

    private fun channel(color: Int, shift: Int) = (color shr shift) and 0xFF

    private fun rgb(r: Int, g: Int, b: Int): Int =
        OPAQUE or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun mix(from: Int, to: Int, amount: Double): Int {
        fun one(shift: Int): Int {
            val a = channel(from, shift)
            return (a + (channel(to, shift) - a) * amount).roundToInt()
        }
        return rgb(one(16), one(8), one(0))
    }

    private fun scale(color: Int, factor: Double): Int =
        rgb(
            (channel(color, 16) * factor).roundToInt(),
            (channel(color, 8) * factor).roundToInt(),
            (channel(color, 0) * factor).roundToInt(),
        )

    /** Относительная яркость sRGB по WCAG 2.x. */
    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = channel(color, shift) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // ---------------------------------------------------------------- основной процесс

    /**
     * Выбор темы сохранён: дублирует его в файл для `:vpn` и перерисовывает живое
     * уведомление. Только основной процесс.
     */
    fun onThemeStored(context: Context, key: String) {
        val app = context.applicationContext ?: context
        writeMirror(app, key)
        runCatching {
            if (ClientData(app).getServiceState() != NovaVpnService.STATE_STOPPED) {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, NovaVpnService::class.java).apply {
                        action = NovaVpnService.ACTION_REFRESH_NOTIFICATION
                        putExtra(EXTRA_UI_THEME, key)
                    },
                )
            }
        }
    }

    /**
     * Раз на процесс, в фоне: установка, обновлённая с версии без файла-зеркала,
     * получает его с первым же экраном, а не с первой сменой темы.
     */
    fun syncMirrorOnce(context: Context) {
        if (mirrorSynced || followsForeignWrites) return
        mirrorSynced = true
        val app = context.applicationContext ?: context
        val key = NovaTheme.optionFor(NovaAppearance.storedThemeKey(app)).key
        writer.execute {
            if (readMirror(mirrorFile(app)) != key) writeMirrorNow(app, key)
        }
    }

    private fun mirrorFile(context: Context) = File(context.filesDir, MIRROR_FILE)

    private fun readMirror(file: File): String? {
        if (!file.exists()) return null
        val key = runCatching { String(AtomicFile(file).readFully(), Charsets.UTF_8).trim() }.getOrNull()
        return key?.takeIf { candidate -> NovaTheme.ORDER.any { it.key == candidate } }
    }

    /** Запись в отдельном потоке: `AtomicFile` делает `fsync`, а зовут это с нажатия (I13). */
    private fun writeMirror(context: Context, key: String) {
        writer.execute { writeMirrorNow(context, key) }
    }

    private fun writeMirrorNow(context: Context, key: String) {
        runCatching {
            val file = AtomicFile(mirrorFile(context))
            val stream = file.startWrite()
            try {
                stream.write(key.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }.onFailure { error ->
            LogManager.log("Тема уведомления: не удалось сохранить выбор $key: ${error.message}")
        }
    }
}
