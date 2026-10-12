package com.example.nova

/**
 * Display order for available VPN exit countries.
 *
 * Orders country codes such that preferred exit countries appear first, followed by
 * remaining countries grouped by region (Europe, America, Asia, Other) and sorted
 * alphabetically within each group.
 */
object CountryDisplayOrder {

    enum class CountryRegion { EUROPE, AMERICA, ASIA, OTHER }

    /**
     * Порядок по умолчанию — по задержке из России, ближние первыми.
     *
     * Замер 2026-10-12, TCP-443, медиана по живому списку узлов Proton: NL 92, NO 112,
     * PL 137, RO 148, CA 162, MX 187, US 189, SG 224, JP 316 мс; CH не ответил.
     * Тот же список стоит в Nova PC (`resources/nova_vpn_slots.py`), меняются вместе.
     */
    val PREFERRED: List<String> = listOf("NL", "NO", "PL", "RO", "CA", "MX", "US", "SG", "JP", "CH")

    private val EUROPE_CODES: Set<String> = setOf(
        "AL", "AD", "AT", "BA", "BE", "BG", "BY", "CH", "CY", "CZ",
        "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR", "HU",
        "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MC", "MD", "ME",
        "MK", "MT", "NL", "NO", "PL", "PT", "RO", "RS", "RU", "SE",
        "SI", "SK", "SM", "UA", "VA"
    )

    private val AMERICA_CODES: Set<String> = setOf(
        "AR", "BO", "BR", "CA", "CL", "CO", "CR", "CU", "DO", "EC",
        "GT", "HN", "JM", "MX", "NI", "PA", "PE", "PR", "PY", "SV",
        "US", "UY", "VE"
    )

    private val ASIA_CODES: Set<String> = setOf(
        "AE", "AM", "AZ", "BD", "BH", "BN", "CN", "GE", "HK", "ID",
        "IL", "IN", "IQ", "IR", "JO", "JP", "KG", "KH", "KP", "KR",
        "KW", "KZ", "LA", "LB", "LK", "MM", "MN", "MO", "MY", "NP",
        "OM", "PH", "PK", "QA", "SA", "SG", "SY", "TH", "TJ", "TM",
        "TR", "TW", "UZ", "VN", "YE"
    )

    fun regionOf(code: String): CountryRegion {
        val normalized = code.trim().uppercase()
        return when {
            normalized in EUROPE_CODES -> CountryRegion.EUROPE
            normalized in AMERICA_CODES -> CountryRegion.AMERICA
            normalized in ASIA_CODES -> CountryRegion.ASIA
            else -> CountryRegion.OTHER
        }
    }

    fun order(available: Collection<String>): List<String> {
        val normalized = LinkedHashSet<String>()
        for (raw in available) {
            val trimmed = raw.trim().uppercase()
            if (trimmed.length == 2 && trimmed[0] in 'A'..'Z' && trimmed[1] in 'A'..'Z') {
                normalized.add(trimmed)
            }
        }

        if (normalized.isEmpty()) {
            return emptyList()
        }

        val preferredPresent = PREFERRED.filter { it in normalized }
        val remaining = normalized.filterNot { it in PREFERRED }

        val europe = remaining.filter { regionOf(it) == CountryRegion.EUROPE }.sorted()
        val america = remaining.filter { regionOf(it) == CountryRegion.AMERICA }.sorted()
        val asia = remaining.filter { regionOf(it) == CountryRegion.ASIA }.sorted()
        val other = remaining.filter { regionOf(it) == CountryRegion.OTHER }.sorted()

        return preferredPresent + europe + america + asia + other
    }
    /**
     * Порядок по замерам этого устройства: страны с измеренной задержкой — по ней,
     * от ближней; остальные — за ними в порядке [order].
     *
     * Своя сеть важнее общего замера: задержка из Владивостока и из Калининграда
     * ставит страны по-разному, а умолчание снято из одной точки.
     *
     * @param pingByCountry медиана задержки по стране, мс; нет ключа или ≤ 0 — не мерено.
     */
    fun orderByPing(available: Collection<String>, pingByCountry: Map<String, Int>): List<String> {
        val base = order(available)
        val measured = base.filter { (pingByCountry[it] ?: 0) > 0 }
        if (measured.size < 2) return base
        val byPing = measured.sortedWith(compareBy({ pingByCountry.getValue(it) }, { base.indexOf(it) }))
        return byPing + base.filterNot { it in measured }
    }
}
