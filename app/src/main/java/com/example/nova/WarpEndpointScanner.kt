package com.example.nova

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object WarpEndpointScanner {
    private const val TAG = "WarpEndpointScanner"

    // Subnets and Ports exactly as requested from telegra.ph / task.md
    private val subnets = listOf(
        "8.6.112.", "8.34.70.", "8.34.146.", "8.35.211.",
        "8.39.125.", "8.39.204.", "8.39.214.", "8.47.69.",
        "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.",
        "162.159.195.", "162.159.192."
    )

    private val ports = listOf(
        500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934,
        939, 942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014,
        1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2408, 2506, 3138,
        3476, 3581, 3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152,
        7156, 7281, 7559, 8319, 8742, 8854, 8886
    )

    fun findWorkingEndpoint(): Pair<String, Int>? {
        val pool = Executors.newFixedThreadPool(8)
        try {
            // First, try the main official endpoint
            val officialHost = "engage.cloudflareclient.com"
            for (port in listOf(2408, 500, 4500, 1701)) {
                if (testConnectivity(officialHost, port, 1500)) {
                    Log.i(TAG, "Official endpoint reachable: $officialHost:$port")
                    return Pair(officialHost, port)
                }
            }

            Log.i(TAG, "Official endpoint failed, starting scanner...")

            // If official fails, start scanning random combinations
            val tasks = mutableListOf<Callable<Pair<String, Int>?>>()
            for (i in 0 until 40) {
                val subnetPrefix = subnets.random()
                val randomSuffix = Random.nextInt(0, 256)
                val ip = "$subnetPrefix$randomSuffix"
                val port = ports.random()

                tasks.add(Callable {
                    if (testConnectivity(ip, port, 1500)) Pair(ip, port) else null
                })
            }

            // Execute tasks, return the first one that succeeds
            val futures = pool.invokeAll(tasks, 10, TimeUnit.SECONDS)
            for (future in futures) {
                try {
                    val result = future.get()
                    if (result != null) {
                        Log.i(TAG, "Scanner found working endpoint: ${result.first}:${result.second}")
                        return result
                    }
                } catch (e: Exception) {
                    // Ignore exceptions from cancelled tasks
                }
            }
        } finally {
            pool.shutdownNow()
        }
        
        Log.w(TAG, "Scanner failed to find any working endpoint.")
        return null
    }

    private fun testConnectivity(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
