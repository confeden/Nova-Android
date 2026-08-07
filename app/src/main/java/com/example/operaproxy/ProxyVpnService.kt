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

        @JvmStatic
        fun haltTun2proxy(): Int {
            if (!nativeLoaded) return 0
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
