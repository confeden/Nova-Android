package com.example.nova

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Загрузка подписки по HTTP.
 *
 * Ключевые решения:
 *  - условный запрос по ETag/Last-Modified: неизменившаяся подписка стоит ~0.5 КБ
 *    вместо мегабайтов, поэтому обновление можно делать часто и быстро;
 *  - `Accept-Encoding` не выставляется вручную — OkHttp сам добавляет gzip и
 *    прозрачно распаковывает; ручная установка ломает чтение строк;
 *  - тело читается построчно с ограничениями на строку и общий размер, чтобы
 *    подписка на десятки тысяч узлов не съела память.
 */
object VlessSubscriptionFetcher {

    /** UA, на который панели отдают простой список ссылок, а не Clash YAML. */
    const val DEFAULT_USER_AGENT = "v2rayNG/1.10.6"

    // Живые агрегаторы отдают под сотню мегабайт. Читать столько целиком не
    // нужно — разбор останавливается по достижении лимита профилей, а этот
    // порог остаётся защитой от бесконечного потока.
    private const val MAX_BODY_BYTES = 256L * 1024 * 1024
    private const val MAX_LINE_BYTES = 64L * 1024
    private const val METADATA_SCAN_LINES = 10

    data class Validators(val etag: String = "", val lastModified: String = "")

    sealed class Result {
        /** Сервер ответил 304 — подписка не менялась, парсить нечего. */
        data class NotModified(val validators: Validators) : Result()

        data class Ok(
            val configs: List<VlessConfig>,
            val metadata: VlessSubscription.Metadata,
            val validators: Validators,
            val bytesRead: Long,
            val stats: VlessSubscription.ImportStats,
        ) : Result()

        data class Failed(val httpCode: Int, val message: String) : Result()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // readTimeout ловит зависший сокет; общий лимит намеренно большой,
            // потому что честная большая подписка на медленной сети идёт долго.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    /**
     * Ход загрузки: сколько профилей уже разобрано и сколько символов прочитано.
     *
     * Живая подписка идёт десятками секунд, и без этого экран импорта выглядел
     * зависшим: пользователь нажимал «Загрузить» и до самого конца не видел ничего.
     */
    fun interface Progress {
        fun onProgress(kept: Int, charsRead: Long)
    }

    /** Как часто дёргать [Progress]: чаще смысла нет, экран всё равно не успеет. */
    private const val PROGRESS_STEP = 25

    /**
     * Блокирующий вызов — запускать на IO-потоке.
     */
    fun fetch(
        url: String,
        validators: Validators = Validators(),
        userAgent: String = DEFAULT_USER_AGENT,
        limit: Int = 20_000,
        progress: Progress? = null,
    ): Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .apply {
                when {
                    validators.etag.isNotBlank() -> header("If-None-Match", validators.etag)
                    validators.lastModified.isNotBlank() ->
                        header("If-Modified-Since", validators.lastModified)
                }
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    return@use Result.NotModified(validators)
                }
                if (!response.isSuccessful) {
                    return@use Result.Failed(response.code, "HTTP ${response.code}")
                }
                val body = response.body
                    ?: return@use Result.Failed(response.code, "пустой ответ")

                val headerMetadata = VlessSubscription.parseMetadata(
                    response.headers.names().associateWith { name -> response.header(name).orEmpty() }
                )

                // Считаем объём поверх распакованного потока: Content-Length при
                // прозрачном gzip равен -1, а 100 КБ архива разворачиваются в гигабайты.
                val counting = CountingReader(body.charStream(), MAX_BODY_BYTES, MAX_LINE_BYTES)
                val reader = counting.buffered()

                val configs = ArrayList<VlessConfig>(minOf(limit, 4096))
                var stats = VlessSubscription.parseStreaming(
                    reader,
                    limit = limit,
                    stopWhenFull = true,
                ) { config ->
                    configs.add(config)
                    if (progress != null && configs.size % PROGRESS_STEP == 0) {
                        progress.onProgress(configs.size, counting.charsRead)
                    }
                }

                // Тело целиком в base64 нельзя разобрать построчно — если ни одной
                // ссылки не нашлось, дочитываем остаток и пробуем как base64.
                var inlineMetadata = VlessSubscription.Metadata()
                if (configs.isEmpty()) {
                    val decoded = VlessSubscription.parseWithMetadata(reader, limit = limit)
                    configs.addAll(decoded.first)
                    inlineMetadata = decoded.second
                    stats = stats.copy(kept = configs.size, parsed = configs.size)
                }
                progress?.onProgress(configs.size, counting.charsRead)

                Result.Ok(
                    configs = configs,
                    metadata = mergeMetadata(headerMetadata, inlineMetadata),
                    validators = Validators(
                        etag = response.header("ETag").orEmpty(),
                        lastModified = response.header("Last-Modified").orEmpty(),
                    ),
                    bytesRead = counting.charsRead,
                    stats = stats,
                )
            }
        } catch (e: IOException) {
            Result.Failed(-1, e.message ?: "сеть недоступна")
        } catch (e: IllegalArgumentException) {
            Result.Failed(-1, "некорректный адрес подписки")
        }
    }

    /**
     * Считает прочитанное и обрывает чтение, если подписка выходит за лимиты.
     * Исключение ловится в [fetch] как обычная сетевая ошибка.
     */
    private class CountingReader(
        private val delegate: java.io.Reader,
        private val maxChars: Long,
        private val maxLineChars: Long,
    ) : java.io.Reader() {
        var charsRead: Long = 0
            private set
        private var currentLineChars: Long = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val count = delegate.read(cbuf, off, len)
            if (count <= 0) return count
            charsRead += count
            if (charsRead > maxChars) throw IOException("подписка больше 32 МБ")
            for (index in off until off + count) {
                if (cbuf[index] == '\n') {
                    currentLineChars = 0
                } else {
                    currentLineChars++
                    if (currentLineChars > maxLineChars) {
                        throw IOException("слишком длинная строка в подписке")
                    }
                }
            }
            return count
        }

        override fun close() = delegate.close()
    }

    /** Заголовки главнее, комментарии в теле — запасной источник тех же полей. */
    private fun mergeMetadata(
        headers: VlessSubscription.Metadata,
        inline: VlessSubscription.Metadata,
    ): VlessSubscription.Metadata = VlessSubscription.Metadata(
        title = headers.title.ifBlank { inline.title },
        updateIntervalHours = headers.updateIntervalHours.takeIf { it > 0 }
            ?: inline.updateIntervalHours,
        uploadBytes = headers.uploadBytes.takeIf { it >= 0 } ?: inline.uploadBytes,
        downloadBytes = headers.downloadBytes.takeIf { it >= 0 } ?: inline.downloadBytes,
        totalBytes = headers.totalBytes.takeIf { it >= 0 } ?: inline.totalBytes,
        expireAtSeconds = headers.expireAtSeconds.takeIf { it >= 0 } ?: inline.expireAtSeconds,
        webPageUrl = headers.webPageUrl.ifBlank { inline.webPageUrl },
    )
}
