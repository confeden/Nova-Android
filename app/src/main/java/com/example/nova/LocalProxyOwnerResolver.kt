package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetSocketAddress
import java.util.Locale

data class LocalProxyOwnerInfo(
    val uid: Int? = null,
    val packageName: String = "",
    val appLabel: String = "",
)

object LocalProxyOwnerResolver {

    fun resolveTcpOwner(
        context: Context,
        clientAddress: InetSocketAddress,
        serverAddress: InetSocketAddress,
    ): LocalProxyOwnerInfo? {
        val uid = resolveUidModern(context, clientAddress, serverAddress)
            ?: resolveUidFromProc(clientAddress.port, serverAddress.port)
            ?: return null
        return buildOwnerInfo(context, uid)
    }

    private fun resolveUidModern(
        context: Context,
        clientAddress: InetSocketAddress,
        serverAddress: InetSocketAddress,
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            connectivityManager.getConnectionOwnerUid(
                OsConstants.IPPROTO_TCP,
                clientAddress,
                serverAddress,
            )
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun resolveUidFromProc(clientPort: Int, serverPort: Int): Int? {
        return parseProcTcpFile("/proc/net/tcp", clientPort, serverPort)
            ?: parseProcTcpFile("/proc/net/tcp6", clientPort, serverPort)
    }

    private fun parseProcTcpFile(path: String, clientPort: Int, serverPort: Int): Int? {
        val lines = runCatching { java.io.File(path).readLines() }.getOrElse { return null }
        for (line in lines.drop(1)) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 8) continue
            val localPort = parseProcPort(columns[1])
            val remotePort = parseProcPort(columns[2])
            if (localPort != clientPort || remotePort != serverPort) continue
            return columns[7].toIntOrNull()?.takeIf { it >= 0 }
        }
        return null
    }

    private fun parseProcPort(addressField: String): Int? {
        val hexPort = addressField.substringAfter(':', "").trim()
        if (hexPort.isBlank()) return null
        return hexPort.toIntOrNull(16)
    }

    private fun buildOwnerInfo(context: Context, uid: Int): LocalProxyOwnerInfo {
        val packageManager = context.packageManager
        val packageName = packageManager.getPackagesForUid(uid)
            ?.firstOrNull()
            .orEmpty()
        val label = if (packageName.isBlank()) {
            ""
        } else {
            runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName.substringAfterLast('.').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            })
        }
        return LocalProxyOwnerInfo(
            uid = uid,
            packageName = packageName,
            appLabel = label,
        )
    }
}
