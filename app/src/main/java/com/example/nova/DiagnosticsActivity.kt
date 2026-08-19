package com.example.nova

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Экран самодиагностики для сборок с `-PnovaDiagnostics`.
 *
 * Зачем: на устройстве (ТВ-приставка) приложение показывает белый экран, ADB к нему
 * не подключить, и единственный канал наружу — фотография экрана. Страница с
 * крупным текстом для человека, страница с QR — для разбора без ошибок чтения.
 *
 * Экран построен кодом на голой [Activity] с системной темой: он не зависит ни от
 * `Theme.Nova`, ни от AppCompat, ни от наших разметок — то есть работает тогда,
 * когда ломаются именно они.
 *
 * **Порядок здесь — часть лечения.** В первой диагностической сборке проверки шли
 * в `onCreate` до того, как в окно попадал текст: любая зависшая проверка (загрузка
 * 35-мегабайтной libnovaxray, надувание разметки главного экрана) оставляла ровно
 * тот же пустой экран, который мы и приехали изучать. Поэтому теперь:
 *
 *  * текст рисуется сразу, ещё до первой проверки;
 *  * проверки идут в фоновом потоке и дописываются по мере готовности;
 *  * у каждой свой срок, и не уложившаяся отмечается как «ЗАВИС» — это уже ответ;
 *  * проверка, которой нужен главный поток (надувание разметки), идёт последней и
 *    ждёт его с тем же сроком: залипший главный поток тоже становится строкой
 *    отчёта, а не пустым экраном.
 */
class DiagnosticsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val results = LinkedHashMap<String, String>()
    private lateinit var root: FrameLayout
    private lateinit var reportView: TextView
    private lateinit var hintView: TextView
    private var qrPage: View? = null
    private var showingQr = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Thread { collectAll() }.apply { isDaemon = true }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        togglePage()
        return true
    }

    // --- экран ------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { togglePage() }
        }
        setContentView(root)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        column.addView(
            TextView(this).apply {
                text = "NOVA — ДИАГНОСТИКА  " + BuildConfig.VERSION_NAME
                setTextColor(Color.parseColor("#7CFF7C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
            },
        )
        reportView = TextView(this).apply {
            text = "собираю данные..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.1f)
        }
        column.addView(reportView)
        hintView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FFD166"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
        }
        column.addView(hintView)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
        root.requestFocus()
    }

    private fun render() {
        val text = results.entries.joinToString("\n") { (key, value) -> "$key: $value" }
        ui.post { reportView.text = text }
    }

    private fun togglePage() {
        val qr = qrPage ?: return
        showingQr = !showingQr
        qr.visibility = if (showingQr) View.VISIBLE else View.GONE
    }

    // --- проверки ---------------------------------------------------------

    /**
     * Одна проверка со своим сроком. Зависшая не блокирует остальные и попадает в
     * отчёт строкой «ЗАВИС»: для белого экрана это и есть искомый ответ.
     */
    private fun probe(name: String, timeoutMs: Long = 6000L, block: () -> String) {
        results[name] = "..."
        render()
        var value = "ЗАВИС (>${timeoutMs / 1000} с)"
        val worker = Thread {
            val outcome = runCatching(block).getOrElse { shortError(it) }
            synchronized(results) { value = outcome }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        results[name] = synchronized(results) { value }
        render()
    }

    /** Проверка, которой нужен главный поток. Залипший поток тоже станет строкой. */
    private fun probeOnMain(name: String, timeoutMs: Long = 6000L, block: () -> String) {
        results[name] = "..."
        render()
        var value = "ЗАВИС — главный поток занят (>${timeoutMs / 1000} с)"
        val done = CountDownLatch(1)
        ui.post {
            value = runCatching(block).getOrElse { shortError(it) }
            done.countDown()
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        results[name] = value
        render()
    }

    private fun collectAll() {
        probe("версия", 2000L) { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
        probe("устройство", 2000L) { "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}" }
        probe("Android", 2000L) { "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
        probe("ABI", 2000L) { Build.SUPPORTED_ABIS.joinToString(",") }
        probe("режим ТВ", 2000L) {
            val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION).toString()
        }
        probe("экран", 2000L) {
            val m = resources.displayMetrics
            "${m.widthPixels}x${m.heightPixels}@${m.densityDpi}dpi"
        }
        probe("прошлое падение", 3000L) {
            val file = File(filesDir, DiagnosticsCrashCatcher.CRASH_FILE)
            if (file.isFile) file.readText().replace('\n', ' ').take(400) else "нет"
        }
        probe("лог приложения", 3000L) {
            val file = File(filesDir, LOG_FILE)
            if (file.isFile) file.readText().takeLast(240).replace('\n', ' ') else "нет"
        }
        probe("согласие VPN", 5000L) {
            val intent = VpnService.prepare(this)
            when {
                intent == null -> "уже выдано"
                packageManager.resolveActivity(intent, 0) == null ->
                    "НЕТ ОКНА СОГЛАСИЯ в прошивке"
                else -> "окно есть: ${packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName}"
            }
        }
        probe("файлы библиотек", 4000L) {
            File(applicationInfo.nativeLibraryDir).listFiles()
                ?.joinToString(",") { it.name.removePrefix("lib").removeSuffix(".so") }
                ?: "каталог не читается"
        }
        // Загрузка нативных библиотек — главный подозреваемый на слабом железе:
        // libnovaxray весит 35 МБ, и её распаковка может занять минуты.
        probe("загрузка gojni", 20000L) { System.loadLibrary("gojni"); "ok" }
        probe("загрузка tun2proxy_jni", 20000L) { System.loadLibrary("tun2proxy_jni"); "ok" }
        probe("загрузка novaxrayjni", 30000L) { System.loadLibrary("novaxrayjni"); "ok" }
        // Последними — те, что трогают главный поток и наши ресурсы.
        probeOnMain("тема Theme.Nova", 6000L) {
            android.view.ContextThemeWrapper(this, R.style.Theme_Nova)
                .theme.resolveAttribute(android.R.attr.colorBackground, TypedValue(), true)
                .let { if (it) "ok" else "атрибут не разрешился" }
        }
        probeOnMain("разметка главного экрана", 15000L) {
            val themed = android.view.ContextThemeWrapper(this, R.style.Theme_Nova)
            val view = android.view.LayoutInflater.from(themed)
                .inflate(R.layout.activity_main, null, false)
            "ok, корень=${view.javaClass.simpleName}"
        }
        finish(buildPayload())
    }

    private fun buildPayload(): String = buildString {
        append("NOVA-DIAG3 ")
        results.forEach { (key, value) ->
            append(key).append('=').append(value.replace('\n', ' ').take(160)).append('\n')
        }
    }

    private fun finish(payload: String) {
        runCatching { File(filesDir, REPORT_FILE).writeText(payload) }
        runCatching { File(getExternalFilesDir(null), REPORT_FILE).writeText(payload) }
        val bitmap = encodeQr(payload.take(1200), dp(320))
        ui.post {
            if (bitmap != null) {
                qrPage = buildQrPage(bitmap).also {
                    it.visibility = View.GONE
                    root.addView(it)
                }
                hintView.text = "\nГотово. Нажми ОК — покажу QR с этим же отчётом"
            } else {
                hintView.text = "\nГотово. QR собрать не удалось — сфотографируй текст"
            }
        }
    }

    private fun buildQrPage(bitmap: Bitmap): View {
        val size = dp(320)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        column.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
        )
        column.addView(
            TextView(this).apply {
                text = "Сфотографируй код целиком, с белой рамкой"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
            },
        )
        return FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    /**
     * QR через zxing. Библиотека подключается только к диагностическим сборкам,
     * поэтому вызов через рефлексию: обычная сборка не должна её требовать вовсе.
     */
    private fun encodeQr(text: String, size: Int): Bitmap? = runCatching {
        val writerClass = Class.forName("com.google.zxing.qrcode.QRCodeWriter")
        val formatClass = Class.forName("com.google.zxing.BarcodeFormat")
        val hintClass = Class.forName("com.google.zxing.EncodeHintType")
        val correctionClass = Class.forName("com.google.zxing.qrcode.decoder.ErrorCorrectionLevel")
        val hints = hashMapOf<Any, Any>(
            enumValue(hintClass, "ERROR_CORRECTION") to enumValue(correctionClass, "M"),
            enumValue(hintClass, "CHARACTER_SET") to "UTF-8",
            enumValue(hintClass, "MARGIN") to 2,
        )
        val writer = writerClass.getDeclaredConstructor().newInstance()
        val encode = writerClass.getMethod(
            "encode",
            String::class.java,
            formatClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Map::class.java,
        )
        val matrix = encode.invoke(writer, text, enumValue(formatClass, "QR_CODE"), size, size, hints)!!
        val matrixClass = matrix.javaClass
        val width = matrixClass.getMethod("getWidth").invoke(matrix) as Int
        val height = matrixClass.getMethod("getHeight").invoke(matrix) as Int
        val get = matrixClass.getMethod("get", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] =
                    if (get.invoke(matrix, x, y) as Boolean) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** У любого Java-перечисления есть статический `valueOf(String)` — этого хватает. */
    private fun enumValue(type: Class<*>, name: String): Any =
        type.getMethod("valueOf", String::class.java).invoke(null, name)!!

    private fun shortError(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.example.nova") }
            ?: error.stackTrace.firstOrNull()
        val where = frame?.let {
            " @${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }.orEmpty()
        return "${error.javaClass.simpleName}: ${error.message ?: "-"}$where"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REPORT_FILE = "nova_diag_report.txt"
        const val LOG_FILE = "nova_diagnostic_log.txt"
    }
}
