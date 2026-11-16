package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Книга выпусков: под какой версией приложения выпущен каждый личный набор и сколько
 * раз не вышло.
 *
 * Файл, а не `SharedPreferences` (I2): пишет и `:vpn` (фоновый выпуск), и процесс
 * интерфейса (кнопка в настройках), читают оба. Правила — в [ProfileIssuePolicy].
 *
 * Тринадцатый писатель общего замка `warpVerifiedConfigsLock` здесь не появляется
 * (I12): книга лежит в своём файле под своим замком.
 */
class ProfileIssueLedger(context: Context) {

    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    fun read(kind: ProfileIssuePolicy.Kind): ProfileIssuePolicy.Entry =
        synchronized(lock) { parse(readRaw())[kind] ?: ProfileIssuePolicy.Entry() }

    /** Решение для текущей версии приложения. */
    fun decide(
        kind: ProfileIssuePolicy.Kind,
        hasSet: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): ProfileIssuePolicy.Decision =
        ProfileIssuePolicy.decide(hasSet, read(kind), BuildConfig.VERSION_CODE, nowMs)

    /** Выпуск начался по-настоящему (дошёл до сети): считаем попытку, пока не доказано обратное. */
    fun noteAttempt(kind: ProfileIssuePolicy.Kind, nowMs: Long = System.currentTimeMillis()) {
        mutate(kind) { old ->
            val sameVersion = old.attemptVersion == BuildConfig.VERSION_CODE
            old.copy(
                attemptVersion = BuildConfig.VERSION_CODE,
                attempts = if (sameVersion) old.attempts + 1 else 1,
                lastAttemptAt = nowMs,
            )
        }
    }

    /** Набор выпущен целиком: метка версии, счётчик неудач в ноль. */
    fun noteIssued(kind: ProfileIssuePolicy.Kind) {
        mutate(kind) { old ->
            old.copy(
                issuedVersion = BuildConfig.VERSION_CODE,
                attemptVersion = BuildConfig.VERSION_CODE,
                attempts = 0,
            )
        }
    }

    private fun mutate(
        kind: ProfileIssuePolicy.Kind,
        change: (ProfileIssuePolicy.Entry) -> ProfileIssuePolicy.Entry,
    ) {
        synchronized(lock) {
            val all = parse(readRaw()).toMutableMap()
            all[kind] = change(all[kind] ?: ProfileIssuePolicy.Entry())
            val json = JSONObject()
            all.forEach { (k, e) ->
                json.put(
                    k.key,
                    JSONObject()
                        .put("issued", e.issuedVersion)
                        .put("attempt_version", e.attemptVersion)
                        .put("attempts", e.attempts)
                        .put("last_attempt", e.lastAttemptAt)
                )
            }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Книга выпусков профилей не записалась — ${e.message}")
            }
        }
    }

    private fun readRaw(): String? {
        val base = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
        if (!base.isNullOrBlank() && runCatching { JSONObject(base) }.isSuccess) return base
        return runCatching {
            File(file.baseFile.path + ".bak").takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull()?.takeIf { runCatching { JSONObject(it) }.isSuccess }
    }

    private fun parse(raw: String?): Map<ProfileIssuePolicy.Kind, ProfileIssuePolicy.Entry> {
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = LinkedHashMap<ProfileIssuePolicy.Kind, ProfileIssuePolicy.Entry>()
        for (kind in ProfileIssuePolicy.Kind.values()) {
            val item = json.optJSONObject(kind.key) ?: continue
            out[kind] = ProfileIssuePolicy.Entry(
                issuedVersion = item.optInt("issued", 0),
                attemptVersion = item.optInt("attempt_version", 0),
                attempts = item.optInt("attempts", 0),
                lastAttemptAt = item.optLong("last_attempt", 0L),
            )
        }
        return out
    }

    companion object {
        const val FILE_NAME = "profile_issue_ledger.json"
        private val lock = Any()
    }
}
