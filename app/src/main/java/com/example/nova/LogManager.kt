package com.example.nova

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

enum class DiagnosticLogLevel(val priority: Int) {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3);

    companion object {
        /**
         * Незнакомое значение — это INFO, а не ERROR. Прежнее умолчание ERROR и было
         * главной причиной пустого журнала: переключатель в настройках включал
         * запись, уровень оставался ERROR, а 976 из 982 записей в коде — INFO.
         */
        fun fromValue(value: String?): DiagnosticLogLevel {
            return when (value?.trim()?.lowercase(Locale.US)) {
                "debug" -> DEBUG
                "warn", "warning" -> WARN
                "error" -> ERROR
                else -> INFO
            }
        }
    }
}

/**
 * Журнал для разбора отказов: человек включает его в настройках, повторяет
 * проблему и присылает файл.
 *
 * Как он устроен и почему:
 *
 * - **Настройка лежит в файле, и оба процесса её перечитывают.** Раньше она жила в
 *   `SharedPreferences`, а `:vpn` кэширует их на всю жизнь процесса (I2): журнал
 *   включали на экране, а служба — то есть весь туннель — продолжала не писать до
 *   своей смерти. Теперь `stat` файла раз в пару секунд, как у `NovaLanguage`.
 * - **Каждая строка несёт процесс** (`[main]`/`[vpn]`): оба пишут в один файл, и без
 *   метки их строки не разделить.
 * - **Шапка и снимок окружения пишутся в начале каждого отрезка записи** — при
 *   включении и после очистки, в каждом процессе; `:vpn` добавляет снимок на старте
 *   каждого сеанса. После обрезки по размеру или возрасту их заново не пишем: на
 *   долгом журнале это повторялось бы каждые десять минут, а свежий снимок и так
 *   стоит в шапке отправляемого отчёта (`DiagnosticSnapshot.buildReport`).
 * - **Запись идёт отдельным потоком пачками.** Прежде каждая строка открывала файл,
 *   брала блокировку и писала — в том числе из главного потока (I13).
 * - **Падение процесса попадает в журнал со стеком**, до того как процесс умрёт.
 * - **Болтовня уровня DEBUG** (строки на каждый запрос и каждое соединение) идёт
 *   только в logcat. На Mi A1 за 53 минуты Opera это было 3800 строк из 4200, и они
 *   за час вытесняли из файла всё, ради чего его включали.
 */
object LogManager {
    private const val DEFAULT_TAG = "NovaApp"
    const val LOG_FILE = "nova_diagnostic_log.txt"
    private const val SETTINGS_FILE = "diagnostic_log_settings.json"
    private const val LEGACY_PREFS = "nova_warp_config"
    private const val LEGACY_PREFS_KEY = "diagnostic_log_settings_json"

    private const val MAX_FILE_BYTES = 2_000_000L
    private const val RETAIN_FILE_BYTES = 1_400_000L
    private const val RETENTION_MS = 48 * 60 * 60 * 1000L
    private const val RETENTION_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    private const val SETTINGS_CHECK_INTERVAL_MS = 2_000L
    private const val MAX_PREVIEW_CHARS = 160_000
    private const val MAX_PENDING_ENTRIES = 5_000

    // `SimpleDateFormat` не потокобезопасен, а пишут в журнал из десятков потоков:
    // общий экземпляр изредка выдавал битые метки времени.
    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var settings = DiagnosticLogSettingsConfig()

    @Volatile
    private var settingsStamp = Long.MIN_VALUE

    @Volatile
    private var nextSettingsCheckAtMs = 0L

    /** Шапка этого процесса уже стоит в текущем отрезке файла. */
    @Volatile
    private var captureAnnounced = false

    @Volatile
    private var systemHeaderWritten = false

    @Volatile
    private var crashHandlerInstalled = false

    private val announceLock = Any()
    private val settingsLock = Any()
    private val pendingLock = Any()
    private val fileLock = Any()
    private val pending = ArrayDeque<String>()
    private var drainScheduled = false // под pendingLock
    private var droppedEntries = 0 // под pendingLock
    private var nextRetentionCheckAtMs = 0L // под fileLock
    private var writeFailureReported = false // под fileLock

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nova-log-writer").apply { isDaemon = true }
    }

    /** `main`, `vpn`, `diag` — метка процесса в каждой строке. */
    val processLabel: String by lazy { readProcessSuffix().ifBlank { "main" } }

    fun setAppContext(context: Context?) {
        val app = context?.applicationContext ?: return
        if (appContext == null) {
            appContext = app
        }
        installCrashHandler()
        reloadSettings()
    }

    /** Перечитать настройку сейчас, не дожидаясь очередной проверки файла. */
    fun reloadSettings() {
        val context = appContext ?: return
        nextSettingsCheckAtMs = SystemClock.elapsedRealtime() + SETTINGS_CHECK_INTERVAL_MS
        refreshSettings(context, force = true)
    }

    fun isCapturing(level: DiagnosticLogLevel = DiagnosticLogLevel.INFO): Boolean {
        if (appContext == null) return false
        val snapshot = currentSettings()
        return snapshot.enabled && level.priority <= DiagnosticLogLevel.fromValue(snapshot.level).priority
    }

    fun log(message: String) {
        record(DiagnosticLogLevel.INFO, DEFAULT_TAG, message)
    }

    fun d(message: String, tag: String = DEFAULT_TAG) {
        record(DiagnosticLogLevel.DEBUG, tag, message)
    }

    fun i(message: String, tag: String = DEFAULT_TAG) {
        record(DiagnosticLogLevel.INFO, tag, message)
    }

    fun w(message: String, tag: String = DEFAULT_TAG) {
        record(DiagnosticLogLevel.WARN, tag, message)
    }

    fun e(message: String, tag: String = DEFAULT_TAG, error: Throwable? = null) {
        val fullMessage = if (error == null) {
            message
        } else {
            "$message\n${describeThrowable(error, maxFrames = 6, maxCauses = 3)}"
        }
        record(DiagnosticLogLevel.ERROR, tag, fullMessage)
    }

    // ---- настройка ----

    /**
     * Чтение и запись — не через `AtomicFile`. Его чтение на API 24-29 «чинит» файл,
     * возвращая `.bak` на место, и если в эту минуту другой процесс пишет, свежая
     * запись стирается, а на диске остаётся прежнее «выключено». Здесь пишущий кладёт
     * временный файл своего процесса и переименовывает его поверх: `rename` в одной
     * папке атомарен, и читающий видит либо старое содержимое, либо новое.
     */
    fun readSettings(context: Context): DiagnosticLogSettingsConfig {
        val app = context.applicationContext ?: context
        val file = settingsFile(app)
        if (!file.exists()) {
            return readLegacySettings(app)
        }
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        return parseSettings(raw)
    }

    fun saveSettings(context: Context, config: DiagnosticLogSettingsConfig): Boolean {
        val app = context.applicationContext ?: context
        val raw = JSONObject().apply {
            put("enabled", config.enabled)
            put("level", DiagnosticLogLevel.fromValue(config.level).name.lowercase(Locale.US))
        }.toString()
        val file = settingsFile(app)
        val temp = File(file.path + ".tmp-$processLabel")
        val saved = runCatching {
            FileOutputStream(temp).use { stream ->
                stream.write(raw.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            temp.renameTo(file)
        }.getOrElse { error ->
            Log.w(DEFAULT_TAG, "Настройка журнала не сохранилась: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        if (!saved) {
            runCatching { temp.delete() }
            Log.w(DEFAULT_TAG, "Настройка журнала не сохранилась: временный файл не встал на место.")
        }
        return saved
    }

    private fun parseSettings(raw: String?): DiagnosticLogSettingsConfig {
        if (raw.isNullOrBlank()) return DiagnosticLogSettingsConfig()
        return runCatching {
            val json = JSONObject(raw)
            DiagnosticLogSettingsConfig(
                enabled = json.optBoolean("enabled", false),
                level = DiagnosticLogLevel.fromValue(json.optString("level")).name.lowercase(Locale.US),
            )
        }.getOrDefault(DiagnosticLogSettingsConfig())
    }

    /**
     * Перенос из `SharedPreferences`, пока файла нет. Уровень оттуда не берётся:
     * экрана выбора уровня пользователь не видел, и сохранённый там ERROR — это
     * умолчание переключателя, а не выбор. Сохраняется только DEBUG, он шире.
     */
    private fun readLegacySettings(context: Context): DiagnosticLogSettingsConfig {
        return runCatching {
            val raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_PREFS_KEY, null)
            if (raw.isNullOrBlank()) return DiagnosticLogSettingsConfig()
            val json = JSONObject(raw)
            val level = if (DiagnosticLogLevel.fromValue(json.optString("level")) == DiagnosticLogLevel.DEBUG) {
                "debug"
            } else {
                "info"
            }
            DiagnosticLogSettingsConfig(enabled = json.optBoolean("enabled", false), level = level)
        }.getOrDefault(DiagnosticLogSettingsConfig())
    }

    private fun currentSettings(): DiagnosticLogSettingsConfig {
        val context = appContext ?: return settings
        val now = SystemClock.elapsedRealtime()
        if (now >= nextSettingsCheckAtMs) {
            nextSettingsCheckAtMs = now + SETTINGS_CHECK_INTERVAL_MS
            refreshSettings(context, force = false)
        }
        return settings
    }

    /**
     * Под монитором: иначе поток, начавший чтение до сохранения, мог записать старое
     * значение поверх уже перечитанного нового — с новой отметкой, то есть навсегда.
     */
    private fun refreshSettings(context: Context, force: Boolean) {
        synchronized(settingsLock) {
            val file = settingsFile(context)
            val stamp = runCatching { file.lastModified() xor file.length() }.getOrDefault(0L)
            if (!force && stamp == settingsStamp) return
            settingsStamp = stamp
            val fresh = runCatching { readSettings(context) }.getOrDefault(DiagnosticLogSettingsConfig())
            settings = fresh
            if (!fresh.enabled) {
                // Следующее включение — новый отрезок журнала, и шапка ему нужна своя.
                captureAnnounced = false
            }
        }
    }

    private fun settingsFile(context: Context) = File(context.filesDir, SETTINGS_FILE)

    // ---- запись ----

    private fun record(level: DiagnosticLogLevel, tag: String, message: String) {
        val safeTag = tag.trim().ifBlank { DEFAULT_TAG }
        val sanitizedMessage = DiagnosticLogSanitizer.sanitize(message)
        writeSystemHeaderIfNeeded()
        writeToSystemLog(level, safeTag, sanitizedMessage)
        val context = appContext ?: return
        val snapshot = currentSettings()
        if (!snapshot.enabled) return
        if (level.priority > DiagnosticLogLevel.fromValue(snapshot.level).priority) return
        announceCaptureIfNeeded(context)
        enqueue(formatEntry(level, safeTag, sanitizedMessage))
    }

    private fun formatEntry(level: DiagnosticLogLevel, tag: String, message: String): String {
        val timestamp = timestampFormat.get()!!.format(Date())
        val source = if (tag == DEFAULT_TAG) processLabel else "$processLabel/$tag"
        return "[$timestamp] [${level.name}] [$source] $message"
    }

    /**
     * Начало отрезка записи в этом процессе: шапка сразу, снимок настроек и
     * окружения — фоном (он зовёт системные службы и читает настройки).
     */
    private fun announceCaptureIfNeeded(context: Context) {
        if (captureAnnounced) return
        synchronized(announceLock) {
            if (captureAnnounced) return
            captureAnnounced = true
        }
        enqueue(formatEntry(DiagnosticLogLevel.INFO, DEFAULT_TAG, sessionHeader()))
        runCatching {
            writer.execute {
                runCatching { DiagnosticSnapshot.logCaptureContext(context) }
                    .onFailure { error -> w("Снимок окружения для журнала не собрался: ${error.javaClass.simpleName}: ${error.message}") }
            }
        }
    }

    /**
     * Шапка сеанса: какая сборка, какой Android, какое устройство, какой процесс.
     *
     * Журнал приходит владельцу без сопроводительного письма, а первые вопросы к
     * нему всегда одни и те же. Часовой пояс нужен, потому что метки времени в
     * UTC, а человек пишет «отвалилось в 14:30» по своим часам.
     */
    private fun sessionHeader(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val process = if (processLabel == "main") "основной" else processLabel
        val offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes < 0) "-" else "+"
        val offset = String.format(Locale.US, "%s%02d:%02d", sign, Math.abs(offsetMinutes) / 60, Math.abs(offsetMinutes) % 60)
        return "Nova ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR}) - " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, $abi, процесс $process, " +
            "часовой пояс UTC$offset, язык системы ${Locale.getDefault().toLanguageTag()}"
    }

    /** В logcat шапка идёт один раз на процесс — независимо от того, включён ли файл. */
    private fun writeSystemHeaderIfNeeded() {
        if (systemHeaderWritten) return
        systemHeaderWritten = true
        runCatching { Log.i(DEFAULT_TAG, sessionHeader()) }
    }

    private fun enqueue(entry: String) {
        val schedule: Boolean
        synchronized(pendingLock) {
            if (pending.size >= MAX_PENDING_ENTRIES) {
                pending.removeFirst()
                droppedEntries++
            }
            pending.addLast(entry)
            schedule = !drainScheduled
            drainScheduled = true
        }
        if (!schedule) return
        val accepted = runCatching { writer.execute { drainPending() } }.isSuccess
        if (!accepted) {
            synchronized(pendingLock) { drainScheduled = false }
        }
    }

    /** Дописать всё, что ждёт очереди, в вызывающем потоке. */
    fun flush() {
        drainPending()
    }

    /**
     * Пачка забирается под файловым монитором, а не до него: иначе сброс из потока
     * падения мог бы записать свою пачку раньше пачки, которую уже взял писатель.
     */
    private fun drainPending() {
        val context = appContext ?: return
        synchronized(fileLock) {
            val batch: List<String>
            val dropped: Int
            synchronized(pendingLock) {
                batch = ArrayList(pending)
                pending.clear()
                drainScheduled = false
                dropped = droppedEntries
                droppedEntries = 0
            }
            if (batch.isEmpty()) return
            writeBatch(context, batch, dropped)
        }
    }

    private fun writeBatch(context: Context, batch: List<String>, dropped: Int) {
        runCatching {
            RandomAccessFile(logFile(context), "rw").use { raf ->
                raf.channel.lock().use {
                    applyRetentionIfDue(raf)
                    trimIfNeeded(raf)
                    val text = buildString {
                        if (dropped > 0) {
                            append(formatEntry(DiagnosticLogLevel.WARN, DEFAULT_TAG, "Очередь журнала переполнилась: потеряно строк $dropped."))
                            append('\n')
                        }
                        batch.forEach { append(it).append('\n') }
                    }
                    raf.seek(raf.length())
                    raf.write(text.toByteArray(Charsets.UTF_8))
                }
            }
            writeFailureReported = false
        }.onFailure { error ->
            // В сам журнал об этом не написать — только в logcat, и один раз на серию.
            if (!writeFailureReported) {
                writeFailureReported = true
                Log.w(DEFAULT_TAG, "Журнал не записался (строк ${batch.size}): ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    /**
     * Старше 48 часов — долой. Сначала смотрим только первую строку: в обычном
     * случае она свежая, и читать весь файл незачем.
     */
    private fun applyRetentionIfDue(raf: RandomAccessFile) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextRetentionCheckAtMs) return
        nextRetentionCheckAtMs = now + RETENTION_CHECK_INTERVAL_MS
        val length = raf.length()
        if (length <= 0L) return
        val limit = System.currentTimeMillis() - RETENTION_MS
        val head = ByteArray(minOf(length, 64L).toInt())
        raf.seek(0L)
        raf.readFully(head)
        val headTime = parseEntryTime(String(head, Charsets.UTF_8), 0)
        if (headTime >= limit) return
        val bytes = ByteArray(length.toInt())
        raf.seek(0L)
        raf.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        var cut = 0
        var index = 0
        while (index < text.length) {
            val newline = text.indexOf('\n', index)
            val end = if (newline < 0) text.length else newline + 1
            val time = parseEntryTime(text, index)
            if (time >= 0L) {
                if (time >= limit) break
                cut = end
            } else if (cut == index) {
                // Продолжение удалённой записи (стек, выгрузка конфига) уходит с ней.
                cut = end
            }
            index = end
        }
        if (cut <= 0) return
        val kept = text.substring(cut).toByteArray(Charsets.UTF_8)
        raf.setLength(0L)
        raf.seek(0L)
        raf.write(kept)
    }

    private fun parseEntryTime(text: String, lineStart: Int): Long {
        if (lineStart >= text.length || text[lineStart] != '[') return -1L
        val end = text.indexOf(']', lineStart)
        if (end < 0 || end - lineStart > 32) return -1L
        return runCatching {
            timestampFormat.get()!!.parse(text.substring(lineStart + 1, end))?.time ?: -1L
        }.getOrDefault(-1L)
    }

    /** Обрезка по размеру — по границе строки, чтобы первая строка не была огрызком. */
    private fun trimIfNeeded(raf: RandomAccessFile) {
        val currentLength = raf.length()
        if (currentLength <= MAX_FILE_BYTES) return
        val retainedBytes = RETAIN_FILE_BYTES.coerceAtMost(currentLength)
        val buffer = ByteArray(retainedBytes.toInt())
        raf.seek(currentLength - retainedBytes)
        raf.readFully(buffer)
        val newline = buffer.indexOf('\n'.code.toByte())
        val from = if (newline in 0 until buffer.size - 1) newline + 1 else 0
        raf.setLength(0L)
        raf.seek(0L)
        raf.write(buffer, from, buffer.size - from)
        raf.write(
            (formatEntry(DiagnosticLogLevel.INFO, DEFAULT_TAG, "Журнал обрезан по размеру: самые старые строки удалены.") + "\n")
                .toByteArray(Charsets.UTF_8)
        )
    }

    // ---- чтение и очистка ----

    /**
     * Хвост журнала. Для отправки зовут с `Int.MAX_VALUE`: прежний предел в 160 тысяч
     * знаков отрезал от отправляемого файла всё, кроме последних минут.
     */
    fun getPersistedLogs(maxChars: Int = MAX_PREVIEW_CHARS): String {
        val context = appContext ?: return ""
        flush()
        val file = logFile(context)
        if (!file.exists()) return ""
        // Под блокировкой: другой процесс мог как раз обрезать файл, и чтение без неё
        // вернуло бы пустоту или половину.
        val content = synchronized(fileLock) {
            runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.channel.lock().use {
                        val bytes = ByteArray(raf.length().toInt())
                        raf.seek(0L)
                        raf.readFully(bytes)
                        String(bytes, Charsets.UTF_8)
                    }
                }
            }.getOrElse { error ->
                "Журнал не прочитался: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        return if (content.length <= maxChars) {
            content
        } else {
            content.takeLast(maxChars).substringAfter('\n')
        }
    }

    fun clearCapturedLogs() {
        val context = appContext ?: return
        synchronized(fileLock) {
            synchronized(pendingLock) {
                pending.clear()
                droppedEntries = 0
            }
            runCatching {
                RandomAccessFile(logFile(context), "rw").use { raf ->
                    raf.channel.lock().use {
                        raf.setLength(0L)
                    }
                }
            }
            captureAnnounced = false
        }
        // Очищенный журнал сразу начинается с шапки и снимка, а не с пустоты.
        if (isCapturing()) {
            log("Журнал очищен.")
        }
    }

    private fun logFile(context: Context) = File(context.filesDir, LOG_FILE)

    // ---- падения ----

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(announceLock) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                e("Процесс $processLabel падает: необработанное исключение в потоке «${thread.name}».\n" +
                    describeThrowable(error, maxFrames = 14, maxCauses = 4))
                flush()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Класс, сообщение и верхние кадры стека — с причинами. */
    fun describeThrowable(error: Throwable, maxFrames: Int, maxCauses: Int): String = buildString {
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < maxCauses && seen.add(current)) {
            if (depth > 0) append('\n').append("причина: ")
            append(current.javaClass.name).append(": ").append(current.message ?: "-")
            val frames = current.stackTrace
            frames.take(maxFrames).forEach { frame ->
                append("\n  at ").append(frame.className).append('.').append(frame.methodName)
                    .append(':').append(frame.lineNumber)
            }
            if (frames.size > maxFrames) {
                append("\n  … ещё кадров: ").append(frames.size - maxFrames)
            }
            current = current.cause
            depth++
        }
    }

    // ---- служебное ----

    /**
     * Имя процесса — из procfs, а не из `Application.getProcessName()`: тот
     * появился в API 28, а у нас minSdk 24, и подпись `:vpn` нужна как раз на
     * старых устройствах, где расходятся два процесса.
     */
    private fun readProcessSuffix(): String {
        return try {
            File("/proc/self/cmdline").readText()
                .filter { it.code != 0 }
                .trim()
                .let { name -> if (name.contains(':')) name.substringAfterLast(':') else "" }
        } catch (_: Throwable) {
            "?"
        }
    }

    private fun writeToSystemLog(level: DiagnosticLogLevel, tag: String, message: String) {
        when (level) {
            DiagnosticLogLevel.ERROR -> Log.e(tag, message)
            DiagnosticLogLevel.WARN -> Log.w(tag, message)
            DiagnosticLogLevel.INFO -> Log.i(tag, message)
            DiagnosticLogLevel.DEBUG -> Log.d(tag, message)
        }
    }
}
