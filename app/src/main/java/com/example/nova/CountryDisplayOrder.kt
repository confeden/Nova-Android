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

    val PREFERRED: List<String> = listOf("NL", "NO", "PL", "RO", "CH", "US", "CA", "MX", "JP", "SG")

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
}
