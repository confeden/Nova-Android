package com.example.nova

import java.net.IDN

/**
 * Утилиты для разбора и проверки правил обхода по доменам и зонам.
 *
 * Объект намеренно не содержит зависимостей от Android SDK, чтобы его можно
 * было тестировать обычными JVM-тестами без эмулятора или Robolectric.
 */
object DomainBypassRules {

    /**
     * Псевдозона «любая кириллическая» в сохранённой строке зон.
     *
     * Токен хранится вперемешку с настоящими зонами, но зоной не является: в ядре
     * под него отдельный булев параметр. Константа общая, чтобы экран, служба и
     * тесты писали и вычитали одно и то же слово — разъехавшийся литерал здесь
     * означал бы галочку, которая сохраняется и ничего не включает.
     */
    const val CYRILLIC_TOKEN = "cyrillic"

    /** Набор правил для проверки хоста. */
    data class Rules(
        val zones: Set<String>,
        val domains: Set<String>,
        val cyrillicZones: Boolean,
    )

    /**
     * Разбирает строку зон: разделители — запятые, пробелы, переводы строк.
     * Каждый токен приводится к нижнему регистру; ведущая точка отбрасывается.
     * Пустые токены игнорируются.
     */
    fun parseZones(raw: String): Set<String> =
        raw.split(',', ' ', '\n', '\r', '\t')
            .map { it.trim().lowercase().trimStart('.') }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Разбирает строку доменов: разделители — запятые, пробелы, переводы строк.
     * Для каждого токена:
     *   1. Отбрасывается схема (всё до `://` включительно).
     *   2. Отбрасывается всё после первого `/`.
     *   3. Строка приводится к нижнему регистру и обрезается.
     *   4. Ведущая точка и префикс `www.` отбрасываются.
     * Результаты без точки (одиночные метки) и пустые строки не включаются.
     */
    fun parseDomains(raw: String): Set<String> =
        raw.split(',', ' ', '\n', '\r', '\t')
            .map { token ->
                var s = token.trim()
                val schemeEnd = s.indexOf("://")
                if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
                val slashIdx = s.indexOf('/')
                if (slashIdx >= 0) s = s.substring(0, slashIdx)
                s = s.lowercase().trim()
                s = s.trimStart('.')
                if (s.startsWith("www.")) s = s.removePrefix("www.")
                s
            }
            .filter { it.isNotEmpty() && it.contains('.') }
            .toSet()

    /** Собирает [Rules] из сырых строк. */
    fun rulesFrom(zonesRaw: String, domainsRaw: String, cyrillicZones: Boolean): Rules =
        Rules(
            zones = parseZones(zonesRaw),
            domains = parseDomains(domainsRaw),
            cyrillicZones = cyrillicZones,
        )

    /**
     * Проверяет, должен ли хост [host] быть направлен в обход по заданным [rules].
     *
     * Хост нормализуется: обрезаются пробелы, строка переводится в нижний регистр,
     * убирается завершающая точка, а IDN punycode-метки вида `xn--...` декодируются
     * в Unicode через [IDN.toUnicode] (при ошибке сохраняется исходный вид).
     *
     * Совпадение по домену проверяется как `host == rule || host.endsWith("." + rule)`:
     * такая граница по метке исключает ложные срабатывания вида `67ozon.ru` при
     * правиле `ozon.ru`, поскольку требует точку непосредственно перед правилом.
     * Без этого суффиксного якоря любое имя, заканчивающееся на `ozon.ru`, считалось
     * бы совпадением.
     *
     * **Боевых вызовов у этой функции нет и быть не должно.** Сопоставление имён
     * целиком переехало в ядро (`nova-core/dnsname/zone.go`): решение принимается
     * там, где перехватывается DNS-ответ. Здесь она осталась как исполняемое
     * описание правила — её тесты фиксируют семантику, на которую ядро обязано
     * походить. Если правило меняется, менять надо обе стороны сразу; тест,
     * прошедший только здесь, ничего о туннеле не говорит.
     */
    fun matches(host: String, rules: Rules): Boolean {
        if (host.isBlank()) return false
        if (rules.zones.isEmpty() && rules.domains.isEmpty() && !rules.cyrillicZones) return false

        val normalized = run {
            val trimmed = host.trim().lowercase().trimEnd('.')
            runCatching { IDN.toUnicode(trimmed) }.getOrDefault(trimmed)
        }

        if (normalized.isEmpty()) return false

        val labels = normalized.split('.')
        if (labels.size < 2) return false

        val tld = labels.last()

        if (tld in rules.zones) return true

        if (rules.cyrillicZones && tld.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }) {
            return true
        }

        return rules.domains.any { rule ->
            normalized == rule || normalized.endsWith(".$rule")
        }
    }
}
