package com.example.nova

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WarpScanner {
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

    fun scanBestIp(callback: (Pair<String, Int>?) -> Unit) {
        val executor = Executors.newFixedThreadPool(20)
        val results = mutableListOf<Pair<Pair<String, Int>, Long>>()
        
        Log.i("NovaScanner", "Starting endpoint scan with telegra.ph subnets and ports...")

        val candidates = mutableListOf<Pair<String, Int>>()
        repeat(60) {
            val subnet = subnets.random()
            val ip = subnet + Random.nextInt(0, 256)
            val port = ports.random()
            candidates.add(Pair(ip, port))
        }

        candidates.forEach { (ip, port) ->
            executor.execute {
                val start = System.currentTimeMillis()
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), 1500)
                    val latency = System.currentTimeMillis() - start
                    socket.close()
                    synchronized(results) {
                        results.add(Pair(ip, port) to latency)
                    }
                } catch (e: Exception) {
                    // Fail silently
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(6, TimeUnit.SECONDS)

        val best = results.minByOrNull { it.second }?.first
        if (best != null) {
            Log.i("NovaScanner", "Scan finished. Best IP: ${best.first}:${best.second}")
        } else {
            Log.i("NovaScanner", "Scan finished. No working IP found.")
        }
        callback(best)
    }
}
