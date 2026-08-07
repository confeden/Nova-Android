package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import nova.Nova
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.Locale
import org.bouncycastle.math.ec.rfc7748.X25519

class WarpClient(
    private val context: Context,
    private val logger: (String) -> Unit,
    private val shouldAbort: () -> Boolean = { false },
) {

    companion object {
        @Volatile
        private var optionalTrafficCamouflageSetterAvailable = true
    }

    private val REG_URL = "https://api.cloudflareclient.com/v0a4471/reg"

    private enum class CountryOrderStrategy {
        PREFERRED,
        REVERSED,
        EU_FIRST,
        AM_FIRST,
    }

    private enum class DirectStageOrder {
        BEFORE_FAST_PROXY,
        AFTER_FAST_PROXY,
    }

    private data class RegistrationProfile(
        val id: String,
        val label: String,
        val primaryCountryStrategy: CountryOrderStrategy,
        val retryCountryStrategy: CountryOrderStrategy,
        val allowFastProxyOnRestrictedMobile: Boolean = false,
        val fastMaskHostPolicy: OperaProxyManager.MaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_FIRST,
        val fastSkipHostlessCandidate: Boolean = false,
        val fastMaskHostAttempts: Int = 2,
        val fastMaskRotation: Int = 0,
        val fastReadyTimeoutMs: Long = 8_000L,
        val fastMaxLaunchPlans: Int? = null,
        val retryMaskHostPolicy: OperaProxyManager.MaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_FIRST,
        val retrySkipHostlessCandidate: Boolean = true,
        val retryMaskHostAttempts: Int = 4,
        val retryMaskRotation: Int = 2,
        val retryReadyTimeoutMs: Long = 12_000L,
        val retryMaxLaunchPlans: Int? = null,
        val directStageOrder: DirectStageOrder = DirectStageOrder.AFTER_FAST_PROXY,
        val directMaskHostPolicy: OperaProxyManager.MaskHostPolicy? = null,
        val directTimeoutSeconds: Long = 35L,
        val retryDirectTimeoutSeconds: Long = 55L,
    )

    private data class DirectRegistrationResult(
        val config: WarpConfig? = null,
        val lastException: Exception? = null,
    )

    fun register(
        onProgress: (Int) -> Unit = {},
        attemptVariant: Int = 0,
    ): WarpConfig? {
        val clientData = ClientData(context)
        val operaProxySupported = OperaProxyManager.isRegistrationSupportedOnDevice(context)
        val normalizedVariant = attemptVariant.coerceAtLeast(0)
        val forceDirectRegistrationOnce = clientData.consumeWarpDebugSkipFastProxyOnce()
        val forcedDirectCamouflageHosts = if (forceDirectRegistrationOnce) 3 else 1
        var stopManagedOperaProxy = false
        var progressValue = 0
        fun reportProgress(value: Int, logMessage: String? = null) {
            if (value > progressValue) {
                progressValue = value
                onProgress(value)
            }
            if (!logMessage.isNullOrBlank()) {
                logger(logMessage)
            }
        }
        try {
            if (shouldAbort()) return null
            reportProgress(5, "Подготавливаем регистрацию WARP...")
            reportProgress(10, "Генерация ключей...")
            // Генерация ключей Curve25519 на Kotlin (без Go), чтобы обойти
            // SELinux краш Go runtime при чтении /proc/cap_last_cap
            val keyPair = generateWireGuardKeyPair()
            val privateKey = keyPair.first
            val publicKey = keyPair.second
            val locale = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
            val modelName = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"

            // Формат отметки о принятии условий берём у клиента WARP один в один:
            // миллисекунды и числовое смещение, а не «Z».
            //
            // Было `yyyy-MM-dd'T'HH:mm:ss'Z'` — то есть `2026-08-07T08:13:20Z`. Эталон
            // (usque, `TimeAsCfString`) шлёт `2006-01-02T15:04:05.000-07:00`, то есть
            // `2026-08-07T08:13:20.000+00:00`. Регистрация проходит и так, но устройство
            // остаётся зарегистрированным без обслуживания, а расхождения с эталоном в
            // полях регистрации — первое, что стоит исключить: разбираться, какое из них
            // важно серверу, можно только не имея своих.
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val tosTime = sdf.format(java.util.Date())
            val bodyString = buildRegistrationBody(publicKey, tosTime, locale, modelName)
            reportProgress(18, "Ключи готовы. Формируем запрос регистрации...")

            var lastException: Exception? = null
            val baseCountries = clientData.getPreferredRegistrationCountries()
            val restrictedMobileNetwork = isRestrictedMobileNetwork()
            val registrationProfiles = buildRegistrationProfiles(
                clientData = clientData,
                baseCountries = baseCountries,
                operaProxySupported = operaProxySupported,
                restrictedMobileNetwork = restrictedMobileNetwork,
            )
            logger(
                "Registration profile order: " +
                    registrationProfiles.joinToString(" -> ") { it.id }
            )
            val registrationProfile = registrationProfiles
                .getOrElse(normalizedVariant) { registrationProfiles.last() }
            val profileStartedAtMs = System.currentTimeMillis()
            var profileOutcomeRecorded = false
            val preferredCountries = buildRegistrationCountries(baseCountries, registrationProfile.primaryCountryStrategy)
            val retryCountries = buildRegistrationCountries(baseCountries, registrationProfile.retryCountryStrategy)
            val canUseFastProxyPath = !forceDirectRegistrationOnce &&
                operaProxySupported &&
                (!restrictedMobileNetwork || registrationProfile.allowFastProxyOnRestrictedMobile)

            fun recordProfileOutcome(success: Boolean) {
                if (profileOutcomeRecorded) return
                profileOutcomeRecorded = true
                clientData.recordRegistrationProfileOutcome(
                    profileId = registrationProfile.id,
                    success = success,
                    durationMs = System.currentTimeMillis() - profileStartedAtMs,
                )
            }

            logger(
                "Registration profile: ${registrationProfile.id} " +
                    "[${registrationProfile.label}] primary=${preferredCountries.joinToString("->")} " +
                    "retry=${retryCountries.joinToString("->")} restrictedMobile=$restrictedMobileNetwork"
            )
            if (forceDirectRegistrationOnce) {
                logger(
                    "ADB debug: быстрый proxy registration принудительно пропущен для этой " +
                        "попытки. Проверяем прямой obfuscated этап на нескольких camouflage-host."
                )
            }

            fun attemptDirectRegistrationStage(
                progressStart: Int,
                progressMessage: String,
                stageMessage: String,
                timeoutSeconds: Long,
                sourceLabel: String,
                maxCamouflageHosts: Int = 1,
            ): WarpConfig? {
                if (shouldAbort()) return null
                reportProgress(progressStart, progressMessage)
                val directResult = tryDirectRegistration(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    locale = locale,
                    modelName = modelName,
                    timeoutSeconds = timeoutSeconds,
                    profile = registrationProfile,
                    sourceLabel = sourceLabel,
                    maxCamouflageHosts = maxCamouflageHosts,
                    onStageCheckpoint = { hostIndex, totalHosts ->
                        if (totalHosts <= 1) return@tryDirectRegistration
                        val progressEndExclusive = (progressStart + 14).coerceAtMost(99)
                        val spread = (progressEndExclusive - progressStart).coerceAtLeast(1)
                        val checkpoint = progressStart +
                            (((hostIndex + 1) * spread) / (totalHosts + 1).coerceAtLeast(1))
                        reportProgress(checkpoint.coerceIn(progressStart, progressEndExclusive))
                    },
                )
                directResult.config?.let {
                    reportProgress(100)
                    return it
                }
                directResult.lastException?.let {
                    lastException = it
                    logger("$stageMessage: ${it.message}")
                }
                return null
            }

            if (registrationProfile.directStageOrder == DirectStageOrder.BEFORE_FAST_PROXY) {
                attemptDirectRegistrationStage(
                    progressStart = 25,
                    progressMessage = "Пробуем прямую obfuscated регистрацию до proxy path...",
                    stageMessage = "Прямая obfuscated регистрация до proxy path завершилась ошибкой",
                    timeoutSeconds = registrationProfile.directTimeoutSeconds,
                    sourceLabel = "nova-core-obfs-${registrationProfile.id}-direct-first",
                )?.let {
                    recordProfileOutcome(success = true)
                    return it
                }
            }

            if (canUseFastProxyPath) {
                if (shouldAbort()) return null
                reportProgress(
                    25,
                    "Пробуем быстрый proxy registration через встроенный Opera Proxy " +
                        "(${preferredCountries.joinToString(" -> ")}, ${registrationProfile.label})" +
                        when {
                            registrationProfile.fastSkipHostlessCandidate -> ", сразу с маскировкой..."
                            normalizedVariant > 0 -> ", с альтернативным порядком..."
                            else -> "..."
                        }
                )

                val proxyResult = tryOperaProxyRegistration(
                    bodyString = bodyString,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    countries = preferredCountries,
                    loggerLabel = "основной быстрый registration через встроенный Opera Proxy (${registrationProfile.id})",
                    httpClient = buildOperaProxyHttpClient(retryMode = false),
                    readyTimeoutMs = registrationProfile.fastReadyTimeoutMs,
                    maxMaskHostAttempts = registrationProfile.fastMaskHostAttempts,
                    maxLaunchPlans = registrationProfile.fastMaxLaunchPlans,
                    maskHostPolicy = registrationProfile.fastMaskHostPolicy,
                    skipHostlessCandidate = registrationProfile.fastSkipHostlessCandidate,
                    maskHostRotation = registrationProfile.fastMaskRotation,
                    onCountryProgress = { _, index, total ->
                        val start = 25
                        val end = 45
                        val spread = (end - start).coerceAtLeast(1)
                        val step = if (total <= 1) spread else (spread * index / (total - 1).coerceAtLeast(1))
                        reportProgress((start + step).coerceAtMost(end - 1))
                    },
                )
                stopManagedOperaProxy = stopManagedOperaProxy || proxyResult.stopManaged
                if (proxyResult.config != null) {
                    recordProfileOutcome(success = true)
                    reportProgress(100)
                    return proxyResult.config
                }
                val adaptiveDirectHosts = if (proxyResult.blockSignalObserved) 3 else 1
                proxyResult.lastException?.let {
                    lastException = it
                }
                if (shouldAbort()) return null
                reportProgress(
                    45,
                    if (proxyResult.blockSignalObserved) {
                        "Proxy path дал признаки блокировки/сброса. Переходим к adaptive direct obfuscated fallback..."
                    } else {
                        "Быстрый proxy path не дал ответа. Переходим к прямой obfuscated регистрации..."
                    }
                )
                if (registrationProfile.directStageOrder == DirectStageOrder.AFTER_FAST_PROXY) {
                    attemptDirectRegistrationStage(
                        progressStart = 55,
                        progressMessage = if (proxyResult.blockSignalObserved) {
                            "Пробуем adaptive direct obfuscated регистрацию с несколькими camouflage-hosts..."
                        } else {
                            "Пробуем прямую obfuscated регистрацию (${registrationProfile.label})..."
                        },
                        stageMessage = "Прямая obfuscated регистрация завершилась ошибкой",
                        timeoutSeconds = registrationProfile.directTimeoutSeconds,
                        sourceLabel = "nova-core-obfs-${registrationProfile.id}",
                        maxCamouflageHosts = adaptiveDirectHosts,
                    )?.let {
                        recordProfileOutcome(success = true)
                        return it
                    }
                    reportProgress(70, "Прямая obfuscated регистрация не дала ответа. Переходим к дополнительному proxy retry...")
                }
            } else if (operaProxySupported) {
                reportProgress(
                    25,
                    "На мобильной сети обнаружен режим белых списков. " +
                        "Для профиля ${registrationProfile.label} быстрый proxy path пропускаем, " +
                        "идём в прямую obfuscated регистрацию..."
                )
            } else {
                reportProgress(
                    25,
                    "Встроенный Opera proxy недоступен для ABI ${Build.SUPPORTED_ABIS.joinToString()}. " +
                        "Сразу пробуем прямую obfuscated регистрацию..."
                )
            }

            if (!canUseFastProxyPath) {
                attemptDirectRegistrationStage(
                    progressStart = 55,
                    progressMessage = if (canUseFastProxyPath) {
                        "Пробуем прямую obfuscated регистрацию (${registrationProfile.label})..."
                    } else {
                        "Пробуем прямую obfuscated регистрацию..."
                    },
                    stageMessage = "Прямая obfuscated регистрация завершилась ошибкой",
                    timeoutSeconds = registrationProfile.directTimeoutSeconds,
                    sourceLabel = "nova-core-obfs-${registrationProfile.id}",
                    maxCamouflageHosts = forcedDirectCamouflageHosts,
                )?.let {
                    recordProfileOutcome(success = true)
                    return it
                }
                reportProgress(70, "Прямая obfuscated регистрация не дала ответа. Переходим к дополнительному proxy retry...")
            }
            if (operaProxySupported) {
                if (shouldAbort()) return null
                reportProgress(
                    85,
                    "Повторяем регистрацию через встроенный Opera Proxy с другими условиями и другим набором доменов " +
                        "(${retryCountries.joinToString(" -> ")}, ${registrationProfile.label})..."
                )

                val retryProxyResult = tryOperaProxyRegistration(
                    bodyString = bodyString,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    countries = retryCountries,
                    loggerLabel = "дополнительный obfuscated registration retry через встроенный Opera Proxy (${registrationProfile.id})",
                    httpClient = buildOperaProxyHttpClient(retryMode = true),
                    readyTimeoutMs = registrationProfile.retryReadyTimeoutMs,
                    maxMaskHostAttempts = registrationProfile.retryMaskHostAttempts,
                    maxLaunchPlans = registrationProfile.retryMaxLaunchPlans,
                    maskHostPolicy = registrationProfile.retryMaskHostPolicy,
                    skipHostlessCandidate = registrationProfile.retrySkipHostlessCandidate,
                    maskHostRotation = registrationProfile.retryMaskRotation,
                    onCountryProgress = { _, index, total ->
                        val start = 85
                        val end = 98
                        val spread = (end - start).coerceAtLeast(1)
                        val step = if (total <= 1) spread else (spread * index / (total - 1).coerceAtLeast(1))
                        reportProgress((start + step).coerceAtMost(end - 1))
                    },
                )
                stopManagedOperaProxy = stopManagedOperaProxy || retryProxyResult.stopManaged
                if (retryProxyResult.config != null) {
                    recordProfileOutcome(success = true)
                    reportProgress(100)
                    return retryProxyResult.config
                }
                retryProxyResult.lastException?.let {
                    lastException = it
                }
            } else {
                if (shouldAbort()) return null
                reportProgress(85, "Для текущего ABI proxy retry недоступен. Повторяем прямую obfuscated регистрацию...")
                attemptDirectRegistrationStage(
                    progressStart = 85,
                    progressMessage = "Повторяем прямую obfuscated регистрацию...",
                    stageMessage = "Повторная obfuscated регистрация завершилась ошибкой",
                    timeoutSeconds = registrationProfile.retryDirectTimeoutSeconds,
                    sourceLabel = "nova-core-obfs-retry-${registrationProfile.id}",
                )?.let {
                    recordProfileOutcome(success = true)
                    return it
                }
                if (!operaProxySupported && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    if (shouldAbort()) return null
                    reportProgress(
                        92,
                        "Повторяем прямую obfuscated регистрацию с расширенным таймаутом для Android 9..."
                    )
                    attemptDirectRegistrationStage(
                        progressStart = 92,
                        progressMessage = "Повторяем прямую obfuscated регистрацию с расширенным таймаутом...",
                        stageMessage = "Медленный retry obfuscated регистрации завершился ошибкой",
                        timeoutSeconds = 90L,
                        sourceLabel = "nova-core-obfs-android9-slow-retry-${registrationProfile.id}",
                    )?.let {
                        recordProfileOutcome(success = true)
                        return it
                    }
                }
            }

            logger("Все obfuscated/proxy способы регистрации исчерпаны. Прямой plain HTTPS fallback отключён.")
            recordProfileOutcome(success = false)
            lastException?.let { logger("Ошибка сети/регистрации: ${it.message}") }

        } catch (e: Exception) {
            logger("Глобальная ошибка: ${e.message}")
            Log.e("WarpClient", "Global Exception during registration", e)
            LogManager.e("Global Exception during registration", tag = "WarpClient", error = e)
        } finally {
            if (stopManagedOperaProxy) {
                OperaProxyManager.stopManaged(logger)
                ClientData(context).setTrafficMaskActiveHost(null)
            }
        }
        return null
    }

    private fun buildRegistrationCountries(
        baseCountries: List<String>,
        strategy: CountryOrderStrategy,
    ): List<String> {
        val normalized = normalizeRegistrationCountries(baseCountries)
        return when (strategy) {
            CountryOrderStrategy.PREFERRED -> normalized
            CountryOrderStrategy.REVERSED -> normalized.reversed()
            CountryOrderStrategy.EU_FIRST -> (
                listOf("EU", "AM").filter { it in normalized } +
                    normalized.filterNot { it == "EU" || it == "AM" }
                ).distinct()
            CountryOrderStrategy.AM_FIRST -> (
                listOf("AM", "EU").filter { it in normalized } +
                    normalized.filterNot { it == "EU" || it == "AM" }
                ).distinct()
        }
    }

    private fun buildRegistrationProfiles(
        clientData: ClientData,
        baseCountries: List<String>,
        operaProxySupported: Boolean,
        restrictedMobileNetwork: Boolean,
    ): List<RegistrationProfile> {
        val normalizedCountries = normalizeRegistrationCountries(baseCountries)
        val hasAm = normalizedCountries.contains("AM")
        val hasEu = normalizedCountries.contains("EU")
        val baseDirectTimeout = when {
            !operaProxySupported && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> 55L
            !operaProxySupported -> 45L
            restrictedMobileNetwork -> 45L
            else -> 35L
        }
        val extendedDirectTimeout = when {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> 70L
            restrictedMobileNetwork -> 55L
            else -> 45L
        }
        val preferredGlobalFirst = !restrictedMobileNetwork && clientData.getTrafficMaskMode() != "custom"
        val defaultProfiles = listOf(
            RegistrationProfile(
                id = "balanced-proxy",
                label = "proxy-balanced",
                primaryCountryStrategy = CountryOrderStrategy.PREFERRED,
                retryCountryStrategy = CountryOrderStrategy.REVERSED,
                allowFastProxyOnRestrictedMobile = false,
                fastMaskHostPolicy = if (preferredGlobalFirst) {
                    OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST
                } else {
                    OperaProxyManager.MaskHostPolicy.WHITE_FIRST
                },
                fastSkipHostlessCandidate = restrictedMobileNetwork,
                fastMaskHostAttempts = if (restrictedMobileNetwork) 3 else 2,
                fastMaskRotation = 0,
                fastReadyTimeoutMs = 8_000L,
                fastMaxLaunchPlans = if (restrictedMobileNetwork) 5 else 6,
                retryMaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_FIRST,
                retrySkipHostlessCandidate = true,
                retryMaskHostAttempts = 4,
                retryMaskRotation = 2,
                retryReadyTimeoutMs = 11_000L,
                retryMaxLaunchPlans = 8,
                directStageOrder = DirectStageOrder.AFTER_FAST_PROXY,
                directMaskHostPolicy = if (restrictedMobileNetwork) {
                    OperaProxyManager.MaskHostPolicy.WHITE_FIRST
                } else {
                    OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST
                },
                directTimeoutSeconds = baseDirectTimeout,
                retryDirectTimeoutSeconds = extendedDirectTimeout,
            ),
            RegistrationProfile(
                id = "white-masked",
                label = "white.sni-masked",
                primaryCountryStrategy = if (hasAm) CountryOrderStrategy.AM_FIRST else CountryOrderStrategy.PREFERRED,
                retryCountryStrategy = if (hasEu) CountryOrderStrategy.EU_FIRST else CountryOrderStrategy.REVERSED,
                allowFastProxyOnRestrictedMobile = true,
                fastMaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_ONLY,
                fastSkipHostlessCandidate = true,
                fastMaskHostAttempts = 5,
                fastMaskRotation = 2,
                fastReadyTimeoutMs = 9_000L,
                fastMaxLaunchPlans = 10,
                retryMaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_FIRST,
                retrySkipHostlessCandidate = true,
                retryMaskHostAttempts = 6,
                retryMaskRotation = 5,
                retryReadyTimeoutMs = 12_000L,
                retryMaxLaunchPlans = 12,
                directStageOrder = DirectStageOrder.BEFORE_FAST_PROXY,
                directMaskHostPolicy = OperaProxyManager.MaskHostPolicy.WHITE_FIRST,
                directTimeoutSeconds = extendedDirectTimeout,
                retryDirectTimeoutSeconds = extendedDirectTimeout + 10L,
            ),
            RegistrationProfile(
                id = "direct-bootstrap",
                label = "direct-obfs-first",
                primaryCountryStrategy = if (hasEu) CountryOrderStrategy.EU_FIRST else CountryOrderStrategy.PREFERRED,
                retryCountryStrategy = if (hasAm) CountryOrderStrategy.AM_FIRST else CountryOrderStrategy.REVERSED,
                allowFastProxyOnRestrictedMobile = true,
                fastMaskHostPolicy = if (restrictedMobileNetwork) {
                    OperaProxyManager.MaskHostPolicy.WHITE_FIRST
                } else {
                    OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST
                },
                fastSkipHostlessCandidate = true,
                fastMaskHostAttempts = 4,
                fastMaskRotation = 1,
                fastReadyTimeoutMs = 8_500L,
                fastMaxLaunchPlans = 8,
                retryMaskHostPolicy = if (restrictedMobileNetwork) {
                    OperaProxyManager.MaskHostPolicy.WHITE_ONLY
                } else {
                    OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST
                },
                retrySkipHostlessCandidate = true,
                retryMaskHostAttempts = 6,
                retryMaskRotation = 6,
                retryReadyTimeoutMs = 12_000L,
                retryMaxLaunchPlans = 10,
                directStageOrder = DirectStageOrder.BEFORE_FAST_PROXY,
                directMaskHostPolicy = if (restrictedMobileNetwork) {
                    OperaProxyManager.MaskHostPolicy.WHITE_ONLY
                } else {
                    OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST
                },
                directTimeoutSeconds = extendedDirectTimeout,
                retryDirectTimeoutSeconds = extendedDirectTimeout + 10L,
            ),
        )
        val orderedIds = clientData.getPreferredRegistrationProfiles(defaultProfiles.map { it.id })
        return orderedIds.mapNotNull { orderedId ->
            defaultProfiles.firstOrNull { it.id == orderedId }
        }.ifEmpty { defaultProfiles }
    }

    private fun normalizeRegistrationCountries(countries: List<String>): List<String> {
        val normalized = countries
            .map { it.trim().uppercase(Locale.US) }
            .map { if (it == "US") "AM" else it }
            .filter { it == "EU" || it == "AM" }
            .distinct()
        return if (normalized.isEmpty()) listOf("EU", "AM") else normalized
    }

    private data class ProxyRegistrationResult(
        val config: WarpConfig? = null,
        val lastException: Exception? = null,
        val stopManaged: Boolean = false,
        val blockSignalObserved: Boolean = false,
    )

    private fun tryOperaProxyRegistration(
        bodyString: String,
        privateKey: String,
        publicKey: String,
        countries: List<String>,
        loggerLabel: String,
        httpClient: OkHttpClient,
        readyTimeoutMs: Long,
        maxMaskHostAttempts: Int,
        maxLaunchPlans: Int?,
        maskHostPolicy: OperaProxyManager.MaskHostPolicy,
        skipHostlessCandidate: Boolean,
        maskHostRotation: Int,
        onCountryProgress: ((country: String, index: Int, total: Int) -> Unit)? = null,
    ): ProxyRegistrationResult {
        val clientData = ClientData(context)
        var lastException: Exception? = null
        var stopManagedOperaProxy = false
        var blockSignalObserved = false
        for ((index, country) in countries.withIndex()) {
            if (shouldAbort()) break
            onCountryProgress?.invoke(country, index, countries.size)
            logger("Пробуем $loggerLabel: $country...")
            val proxyReadyState = OperaProxyManager.ensureReady(
                context = context,
                logger = logger,
                purposeLabel = "регистрации WARP ($country)",
                country = country,
                preferGlobalMaskHosts = true,
                maskHostPolicy = maskHostPolicy,
                readyTimeoutMs = readyTimeoutMs,
                maxMaskHostAttempts = maxMaskHostAttempts,
                maxLaunchPlans = maxLaunchPlans,
                skipHostlessCandidate = skipHostlessCandidate,
                maskHostRotation = maskHostRotation,
            )
            if (proxyReadyState == OperaProxyManager.ReadyState.STARTED_INTERNAL) {
                stopManagedOperaProxy = true
            }
            if (proxyReadyState == OperaProxyManager.ReadyState.FAILED || !hasOperaLocalProxy()) {
                val apiCode = OperaProxyManager.getLastFailureApiCode()
                if (apiCode == 801 || apiCode == 500 || apiCode == 502) {
                    blockSignalObserved = true
                }
                logger("Встроенный Opera Proxy для $country не готов. Переходим к следующему варианту регистрации.")
                clientData.recordRegistrationRouteOutcome(country, success = false)
                continue
            }
            val startedAt = System.currentTimeMillis()
            try {
                val responseText = executeRegistrationRequest(httpClient, bodyString)
                if (shouldAbort()) return ProxyRegistrationResult(stopManaged = stopManagedOperaProxy)
                val config = parseRegistrationResponse(
                    responseText = responseText,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    sourceLabel = "opera-proxy-internal-$country"
                )
                OperaProxyManager.markCurrentMaskHostSuccessful(context)
                clientData.recordRegistrationRouteOutcome(
                    country = country,
                    success = true,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                clientData.recordOperaRegistrationPlanOutcome(
                    country = country,
                    fakeSni = OperaProxyManager.getCurrentFakeSni(),
                    endpoint = OperaProxyManager.getCurrentEndpoint(),
                    apiProfileId = OperaProxyManager.getCurrentApiProfileId(),
                    success = true,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                return ProxyRegistrationResult(config = config, stopManaged = stopManagedOperaProxy)
            } catch (e: Exception) {
                lastException = e
                val apiCode = OperaProxyManager.getLastFailureApiCode()
                if (apiCode == 801 || apiCode == 500 || apiCode == 502 || looksLikeRegistrationBlockSignal(e)) {
                    blockSignalObserved = true
                }
                clientData.recordTrafficMaskAttempt(clientData.getTrafficMaskActiveHost(), success = false)
                clientData.recordRegistrationRouteOutcome(
                    country = country,
                    success = false,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                clientData.recordOperaRegistrationPlanOutcome(
                    country = country,
                    fakeSni = OperaProxyManager.getCurrentFakeSni(),
                    endpoint = OperaProxyManager.getCurrentEndpoint(),
                    apiProfileId = OperaProxyManager.getCurrentApiProfileId(),
                    success = false,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                Log.e("WarpClient", "Registration via internal Opera proxy failed for $country", e)
                LogManager.e("Registration via internal Opera proxy failed for $country", tag = "WarpClient", error = e)
            }
        }
        return ProxyRegistrationResult(
            lastException = lastException,
            stopManaged = stopManagedOperaProxy,
            blockSignalObserved = blockSignalObserved,
        )
    }

    private fun tryDirectRegistration(
        privateKey: String,
        publicKey: String,
        locale: String,
        modelName: String,
        timeoutSeconds: Long,
        profile: RegistrationProfile,
        sourceLabel: String,
        maxCamouflageHosts: Int,
        onStageCheckpoint: ((hostIndex: Int, totalHosts: Int) -> Unit)? = null,
    ): DirectRegistrationResult {
        val clientData = ClientData(context)
        val camouflageHosts = pickDirectTrafficCamouflageHosts(
            clientData = clientData,
            policy = profile.directMaskHostPolicy,
            limit = maxCamouflageHosts,
        ).ifEmpty { listOf("") }
        var lastException: Exception? = null
        val stageStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val minPerHostTimeoutSeconds = when {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> 12L
            camouflageHosts.size > 1 -> 8L
            else -> 10L
        }
        val stageBudgetMs = timeoutSeconds
            .coerceAtLeast(minPerHostTimeoutSeconds)
            .times(1000L)
        for ((hostIndex, selectedCamouflageHost) in camouflageHosts.withIndex()) {
            if (shouldAbort()) return DirectRegistrationResult(lastException = lastException)
            onStageCheckpoint?.invoke(hostIndex, camouflageHosts.size)
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - stageStartedAtMs
            val remainingMs = stageBudgetMs - elapsedMs
            if (remainingMs < minPerHostTimeoutSeconds * 1000L) {
                logger(
                    "Прямая obfuscated регистрация исчерпала общий budget этапа " +
                        "(${timeoutSeconds}s). Переходим к следующему способу."
                )
                break
            }
            val remainingHosts = (camouflageHosts.size - hostIndex).coerceAtLeast(1)
            val perHostBudgetMs = if (remainingHosts == 1) {
                remainingMs
            } else {
                maxOf(minPerHostTimeoutSeconds * 1000L, remainingMs / remainingHosts.toLong())
            }
            val hostTimeoutSeconds = ((perHostBudgetMs + 999L) / 1000L)
                .coerceAtLeast(minPerHostTimeoutSeconds)
            if (selectedCamouflageHost.isNullOrBlank()) {
                logger(
                    "Прямая obfuscated регистрация: без дополнительного camouflage host, " +
                        "budget=${hostTimeoutSeconds}s (${hostIndex + 1}/${camouflageHosts.size})."
                )
            } else {
                logger(
                    "Прямая obfuscated регистрация: camouflage host = $selectedCamouflageHost " +
                        "(${hostIndex + 1}/${camouflageHosts.size}), budget=${hostTimeoutSeconds}s"
                )
            }
            setTrafficCamouflageHostCompat(selectedCamouflageHost)
            try {
                val responseText = executeNovaCoreRegistration(
                    publicKey = publicKey,
                    locale = locale,
                    modelName = modelName,
                    timeoutSeconds = hostTimeoutSeconds,
                ).orEmpty()
                if (shouldAbort()) return DirectRegistrationResult()
                if (responseText.isBlank()) {
                    continue
                }
                return DirectRegistrationResult(
                    config = parseRegistrationResponse(
                        responseText = responseText,
                        privateKey = privateKey,
                        publicKey = publicKey,
                        sourceLabel = sourceLabel,
                    )
                )
            } catch (e: Exception) {
                lastException = e
                Log.e("WarpClient", "Obfuscated registration via nova-core failed", e)
                LogManager.e("Obfuscated registration via nova-core failed", tag = "WarpClient", error = e)
            } finally {
                if (!selectedCamouflageHost.isNullOrBlank()) {
                    setTrafficCamouflageHostCompat(null)
                }
            }
        }
        return DirectRegistrationResult(lastException = lastException)
    }

    private fun pickDirectTrafficCamouflageHosts(
        clientData: ClientData,
        policy: OperaProxyManager.MaskHostPolicy?,
        limit: Int,
    ): List<String> {
        if (policy == null || !clientData.getTrafficMaskEnabled()) return emptyList()
        if (clientData.getTrafficMaskMode() == "custom") {
            return listOfNotNull(clientData.getTrafficMaskHost().takeIf { it.isNotBlank() })
                .take(limit.coerceAtLeast(1))
        }
        val whiteHosts = clientData.getPreferredTrafficMaskHosts(TrafficMaskCatalog.getWhiteHosts(context), limit = 8)
        val globalHosts = clientData.getPreferredTrafficMaskHosts(TrafficMaskCatalog.getGlobalHosts(context), limit = 12)
        val orderedHosts = linkedSetOf<String>()
        when (policy) {
            OperaProxyManager.MaskHostPolicy.WHITE_FIRST -> {
                whiteHosts.forEach(orderedHosts::add)
                globalHosts.forEach(orderedHosts::add)
            }
            OperaProxyManager.MaskHostPolicy.GLOBAL_FIRST -> {
                globalHosts.forEach(orderedHosts::add)
                whiteHosts.forEach(orderedHosts::add)
            }
            OperaProxyManager.MaskHostPolicy.WHITE_ONLY -> {
                whiteHosts.forEach(orderedHosts::add)
            }
            OperaProxyManager.MaskHostPolicy.GLOBAL_ONLY -> {
                globalHosts.forEach(orderedHosts::add)
            }
        }
        if (clientData.shouldPreferMessengerWarpProfiles()) {
            orderedHosts.add(
                MessengerObfsPolicy.pickCamouflageHost(
                    context = context,
                    seed = "warp-register-${policy.name.lowercase()}",
                )
            )
        }
        return orderedHosts.filter { it.isNotBlank() }.take(limit.coerceAtLeast(1))
    }

    private fun looksLikeRegistrationBlockSignal(error: Exception): Boolean {
        val message = error.message?.trim()?.lowercase(Locale.US).orEmpty()
        if (message.isBlank()) return false
        return listOf(
            "timeout",
            "timed out",
            "connection reset",
            "unexpected end of stream",
            "stream was reset",
            "connection refused",
            "software caused connection abort",
        ).any(message::contains)
    }

    private fun setTrafficCamouflageHostCompat(host: String?) {
        if (!optionalTrafficCamouflageSetterAvailable) return
        try {
            Nova.setTrafficCamouflageHost(host.orEmpty())
        } catch (t: Throwable) {
            optionalTrafficCamouflageSetterAvailable = false
            logger("Optional JNI setTrafficCamouflageHost недоступен для registration path: ${t.message}")
        }
    }

    private fun isRestrictedMobileNetwork(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false

        val probeTargets = listOf(
            "1.1.1.1" to 443,
            "1.0.0.1" to 443,
            "8.8.8.8" to 443,
        )
        val reachable = probeTargets.any { (host, port) ->
            tcpProbe(activeNetwork, host, port, 350)
        }
        if (!reachable) {
            logger(
                "Обнаружен режим белых списков на мобильной сети. " +
                    "Для регистрации WARP сразу смещаем приоритет к прямой obfuscated попытке."
            )
        }
        return !reachable
    }

    private fun tcpProbe(network: Network, host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun executeNovaCoreRegistration(
        publicKey: String,
        locale: String,
        modelName: String,
        timeoutSeconds: Long = 35L,
    ): String? {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nova-warp-register").apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<String?> {
                Nova.registerWarp(publicKey, locale, modelName).orEmpty().takeIf { it.isNotBlank() }
            }
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            try {
                Thread({
                    try {
                        Nova.cancelRegisterWarp()
                    } catch (_: Throwable) {
                    }
                }, "NovaCancelRegisterWarp").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
            }
            logger("Прямая obfuscated registration слишком долго не отвечает. Переходим к proxy fallback...")
            null
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: Exception(e.cause)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Регистрация одним чистым запросом через уже поднятый туннель.
     *
     * Обычный [register] борется с блокировкой провайдера: перебирает страны,
     * подставляет fake SNI, поднимает Opera-прокси. Внутри туннеля всё это не
     * нужно и вредно — провайдер трафика не видит, а лишние попытки стоят
     * времени. Поэтому здесь один запрос без прокси и маскировки.
     *
     * Сокет намеренно не защищается через `VpnService.protect`: защищённый
     * сокет ушёл бы мимо туннеля, прямо в заблокированный `api.cloudflareclient.com`.
     */
    fun registerThroughActiveTunnel(timeoutSeconds: Long = 25L): WarpConfig? {
        if (shouldAbort()) return null
        val keyPair = generateWireGuardKeyPair()
        val privateKey = keyPair.first
        val publicKey = keyPair.second
        val locale = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        val modelName = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val bodyString = buildRegistrationBody(
            publicKey,
            sdf.format(java.util.Date()),
            locale,
            modelName,
        )
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(timeoutSeconds / 3 + 1, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return try {
            val responseText = executeRegistrationRequest(client, bodyString)
            if (shouldAbort()) return null
            parseRegistrationResponse(responseText, privateKey, publicKey, "через туннель")
        } catch (e: Exception) {
            logger("Регистрация через туннель не удалась: ${e.message}")
            null
        }
    }

    private fun buildRegistrationBody(publicKey: String, tosTime: String, locale: String, modelName: String): String {
        return JSONObject().apply {
            put("key", publicKey)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tosTime)
            put("model", modelName)
            put("serial_number", randomSerialHex())
            put("os_version", "")
            put("key_type", "curve25519")
            put("tunnel_type", "wireguard")
            put("locale", locale)
        }.toString()
    }

    private fun executeRegistrationRequest(client: OkHttpClient, bodyString: String): String {
        val request = Request.Builder()
            .url(REG_URL)
            .post(bodyString.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "WARP for Android")
            .header("CF-Client-Version", "a-6.35-4471")
            .header("Connection", "Keep-Alive")
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (response.code != 200) {
                throw IllegalStateException("HTTP ${response.code}: ${responseText.take(160)}")
            }
            if (responseText.isBlank()) {
                throw IllegalStateException("Empty registration response body")
            }
            return responseText
        }
    }

    private fun hasOperaLocalProxy(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(OperaProxyManager.getLoopbackProxyAddress(context), 400)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildOperaProxyHttpClient(retryMode: Boolean): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, OperaProxyManager.getLoopbackProxyAddress(context)))
            .connectTimeout(if (retryMode) 8L else 6L, TimeUnit.SECONDS)
            .readTimeout(if (retryMode) 18L else 12L, TimeUnit.SECONDS)
            .callTimeout(if (retryMode) 24L else 16L, TimeUnit.SECONDS)
            .build()
    }

    private fun parseRegistrationResponse(
        responseText: String,
        privateKey: String,
        publicKey: String,
        sourceLabel: String,
    ): WarpConfig {
        val root = JSONObject(responseText)
        val resultObj = if (root.has("result")) root.getJSONObject("result") else root
        val config = resultObj.getJSONObject("config")
        val account = resultObj.optJSONObject("account")

        val peer0 = config.getJSONArray("peers").getJSONObject(0)
        val endpoint = peer0.getJSONObject("endpoint")
        val peerEndpoint = when {
            endpoint.optString("v4").isNotBlank() -> endpoint.getString("v4")
            endpoint.optString("host").isNotBlank() -> endpoint.getString("host")
            else -> endpoint.getString("v6")
        }

        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")

        var reserved: String? = null
        if (config.has("client_id")) {
            val clientId = config.getString("client_id")
            try {
                val bytes = Base64.decode(clientId, Base64.DEFAULT)
                if (bytes.size >= 3) {
                    reserved = "${bytes[0].toInt() and 0xFF},${bytes[1].toInt() and 0xFF},${bytes[2].toInt() and 0xFF}"
                    logger("Reserved байты получены: $reserved")
                }
            } catch (e: Exception) {
                logger("Ошибка декодирования client_id: ${e.message}")
            }
        }

        logger("Регистрация успешна! ($sourceLabel)")
        return WarpConfig(
            privateKey = privateKey,
            publicKey = publicKey,
            ipv4 = addresses.getString("v4"),
            ipv6 = addresses.getString("v6"),
            peerPublicKey = peer0.getString("public_key"),
            peerEndpoint = peerEndpoint,
            reserved = reserved,
            accessToken = resultObj.optString("token", root.optString("token", "")),
            deviceId = resultObj.optString("id", root.optString("id", "")),
            license = account?.optString("license")?.takeIf { it.isNotBlank() },
            masqueConfigJson = null,
        )
    }

    private fun randomSerialHex(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }
    /**
     * Генерация WireGuard-совместимой ключевой пары X25519.
     * Пробуем несколько способов, т.к. Android Conscrypt не поддерживает NamedParameterSpec.
     */
    private fun generateWireGuardKeyPair(): Pair<String, String> {
        // Способ 1: KeyPairGenerator "X25519" напрямую (некоторые устройства)
        try {
            val kpg = java.security.KeyPairGenerator.getInstance("X25519")
            val pair = kpg.generateKeyPair()
            val privRaw = extractRawFromPkcs8(pair.private.encoded)
            val pubRaw = extractRawFromSpki(pair.public.encoded)
            return Pair(
                Base64.encodeToString(privRaw, Base64.NO_WRAP),
                Base64.encodeToString(pubRaw, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            android.util.Log.d("WarpClient", "X25519 direct not available: ${e.message}")
        }

        // Способ 2: KeyPairGenerator "XDH" без initialize (Conscrypt default = X25519)
        try {
            val kpg = java.security.KeyPairGenerator.getInstance("XDH")
            val pair = kpg.generateKeyPair()
            val privRaw = extractRawFromPkcs8(pair.private.encoded)
            val pubRaw = extractRawFromSpki(pair.public.encoded)
            return Pair(
                Base64.encodeToString(privRaw, Base64.NO_WRAP),
                Base64.encodeToString(pubRaw, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            android.util.Log.d("WarpClient", "XDH default not available: ${e.message}")
        }

        // Способ 3: полностью совместимый pure-Java fallback через BouncyCastle.
        val secureRandom = SecureRandom()
        val privBytes = ByteArray(32)
        val pubBytes = ByteArray(32)
        X25519.generatePrivateKey(secureRandom, privBytes)
        X25519.generatePublicKey(privBytes, 0, pubBytes, 0)
        return Pair(
            Base64.encodeToString(privBytes, Base64.NO_WRAP),
            Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        )
    }

    private fun extractRawFromPkcs8(pkcs8: ByteArray): ByteArray {
        // Ищем внутренний OCTET_STRING (0x04, 0x20) за которым идут 32 байта ключа
        for (i in 0 until pkcs8.size - 33) {
            if (pkcs8[i] == 0x04.toByte() && pkcs8[i + 1] == 0x20.toByte()) {
                return pkcs8.copyOfRange(i + 2, i + 34)
            }
        }
        // Fallback: последние 32 байта
        return pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
    }

    private fun extractRawFromSpki(spki: ByteArray): ByteArray {
        // Публичный ключ X25519 — последние 32 байта SPKI
        return spki.copyOfRange(spki.size - 32, spki.size)
    }
}
