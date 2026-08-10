package com.example.operaproxy

import android.content.Context
import android.net.VpnService
import java.io.File

open class ProxyVpnService : VpnService() {

    open fun onTun2ProxyLog(message: String) {}

    companion object {
        private enum class WrapperMode {
            MODERN,
            LEGACY,
        }

        @Volatile
        private var nativeLoaded = false
        @Volatile
        private var wrapperMode = WrapperMode.MODERN

        @Synchronized
        private fun ensureNativeLoaded(context: Context) {
            if (nativeLoaded) return
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir.orEmpty())
            val hasModernWrapper = File(nativeLibDir, "libtun2proxy_jni.so").exists()
            val hasLegacyWrapper = File(nativeLibDir, "libnative-lib.so").exists()
            System.loadLibrary("tun2proxy")
            wrapperMode = when {
                hasModernWrapper -> {
                    System.loadLibrary("tun2proxy_jni")
                    WrapperMode.MODERN
                }
                hasLegacyWrapper -> {
                    System.loadLibrary("native-lib")
                    WrapperMode.LEGACY
                }
                else -> WrapperMode.MODERN
            }
            nativeLoaded = true
        }

        @JvmStatic
        fun isNativeRuntimeAvailable(context: Context): Boolean {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir.orEmpty())
            if (!nativeLibDir.exists()) return false
            val hasTun2proxy = File(nativeLibDir, "libtun2proxy.so").exists()
            val hasWrapper =
                File(nativeLibDir, "libtun2proxy_jni.so").exists() ||
                    File(nativeLibDir, "libnative-lib.so").exists()
            return hasTun2proxy && hasWrapper
        }

        @JvmStatic
        fun runTun2proxy(
            service: ProxyVpnService,
            proxyUrl: String,
            tunFd: Int,
            closeFdOnDrop: Boolean,
            tunMtu: Char,
            dnsStrategy: Int,
            verbosity: Int,
        ): Int {
            ensureNativeLoaded(service)
            return try {
                when (wrapperMode) {
                    WrapperMode.MODERN -> nativeStartTun2proxy(
                        service,
                        proxyUrl,
                        tunFd,
                        closeFdOnDrop,
                        tunMtu,
                        dnsStrategy,
                        verbosity,
                    )
                    WrapperMode.LEGACY -> {
                        service.onTun2ProxyLog("Используем legacy JNI-обёртку tun2proxy для текущего ABI.")
                        startTun2proxy(
                            service,
                            proxyUrl,
                            tunFd,
                            closeFdOnDrop,
                            tunMtu,
                            dnsStrategy,
                            verbosity,
                        )
                    }
                }
            } catch (_: UnsatisfiedLinkError) {
                try {
                    if (wrapperMode != WrapperMode.LEGACY) {
                        wrapperMode = WrapperMode.LEGACY
                    }
                    service.onTun2ProxyLog("Переходим на legacy JNI-обёртку tun2proxy для arm64.")
                    startTun2proxy(
                        service,
                        proxyUrl,
                        tunFd,
                        closeFdOnDrop,
                        tunMtu,
                        dnsStrategy,
                        verbosity,
                    )
                } catch (t: UnsatisfiedLinkError) {
                    nativeLoaded = false
                    service.onTun2ProxyLog(
                        "JNI-обёртка tun2proxy недоступна для текущего ABI: ${t.message}"
                    )
                    -1
                }
            }
        }

        /**
         * Когда в последний раз звали `tun2proxy_stop()`.
         *
         * Библиотека после этого вызова безусловно поднимает отсоединённый поток
         * «поспать 2 секунды → `exit(-1)`». Между сном и выходом она не проверяет
         * ничего: ни вернулся ли рабочий цикл, ни запущен ли новый экземпляр. Отменить
         * этот фитиль нечем, поэтому вызов равносилен приговору процессу.
         */
        @Volatile
        var lastForceStopAtMs: Long = 0L
            private set

        @JvmStatic
        fun haltTun2proxy(log: (String) -> Unit = {}): Int {
            if (!nativeLoaded) {
                log("tun2proxy_stop пропущен: нативная библиотека не загружена.")
                return 0
            }
            lastForceStopAtMs = android.os.SystemClock.elapsedRealtime()
            log("Зовём tun2proxy_stop. Через 2 секунды библиотека выполнит exit(-1).")
            return try {
                when (wrapperMode) {
                    WrapperMode.MODERN -> nativeStopTun2proxy()
                    WrapperMode.LEGACY -> stopTun2proxy()
                }
            } catch (_: UnsatisfiedLinkError) {
                try {
                    wrapperMode = WrapperMode.LEGACY
                    stopTun2proxy()
                } catch (_: UnsatisfiedLinkError) {
                    nativeLoaded = false
                    0
                }
            }
        }

        @JvmStatic
        private external fun nativeStartTun2proxy(
            service: ProxyVpnService,
            proxyUrl: String,
            tunFd: Int,
            closeFdOnDrop: Boolean,
            tunMtu: Char,
            dnsStrategy: Int,
            verbosity: Int,
        ): Int

        @JvmStatic
        private external fun nativeStopTun2proxy(): Int

        @JvmStatic
        private external fun startTun2proxy(
            service: ProxyVpnService,
            proxyUrl: String,
            tunFd: Int,
            closeFdOnDrop: Boolean,
            tunMtu: Char,
            dnsStrategy: Int,
            verbosity: Int,
        ): Int

        @JvmStatic
        private external fun stopTun2proxy(): Int
    }
}
