package com.example.nova

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Process
import java.net.InetAddress

internal object AndroidCompat {

    fun getProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { Application.getProcessName().orEmpty() }.getOrDefault("")
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ""
        val myPid = Process.myPid()
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == myPid }
            ?.processName
            .orEmpty()
    }

    fun isPrivateDnsActive(linkProperties: LinkProperties?): Boolean {
        if (linkProperties == null) return false
        return try {
            val method = linkProperties.javaClass.methods
                .firstOrNull { it.name == "isPrivateDnsActive" && it.parameterCount == 0 }
                ?: return false
            method.invoke(linkProperties) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun getPrivateDnsServerName(linkProperties: LinkProperties?): String {
        if (linkProperties == null) return ""
        return try {
            val method = linkProperties.javaClass.methods
                .firstOrNull { it.name == "getPrivateDnsServerName" && it.parameterCount == 0 }
                ?: return ""
            (method.invoke(linkProperties) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    fun createIpPrefix(address: InetAddress, prefixLength: Int): Any? {
        return try {
            val ipPrefixClass = Class.forName("android.net.IpPrefix")
            val constructor = ipPrefixClass.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            constructor.newInstance(address, prefixLength)
        } catch (_: Throwable) {
            null
        }
    }

    fun setUnderlyingNetworks(
        builder: VpnService.Builder,
        networks: Array<Network>?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return try {
            val method = builder.javaClass.methods
                .firstOrNull { it.name == "setUnderlyingNetworks" && it.parameterCount == 1 }
                ?: return false
            method.invoke(builder, networks)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
