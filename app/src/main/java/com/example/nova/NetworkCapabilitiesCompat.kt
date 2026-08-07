package com.example.nova

import android.net.NetworkCapabilities

internal object NetworkCapabilitiesCompat {
    fun getTransportInfo(caps: NetworkCapabilities?): Any? {
        if (caps == null) return null
        return try {
            val method = caps.javaClass.methods
                .firstOrNull { it.name == "getTransportInfo" && it.parameterCount == 0 }
                ?: return null
            method.invoke(caps)
        } catch (_: Throwable) {
            null
        }
    }
}
