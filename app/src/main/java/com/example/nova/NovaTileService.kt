package com.example.nova

import android.app.ActivityManager
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class NovaTileService : TileService() {
    private var tileReceiverRegistered = false
    private val recentManualStopWindowMs = 8_000L

    private val vpnStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == NovaVpnService.ACTION_VPN_STATE) {
                updateTile(intent.getStringExtra(NovaVpnService.EXTRA_STATE))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        ensureReceiverRegistered()
        updateTile()
    }

    override fun onStopListening() {
        unregisterTileReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val clientData = ClientData(this)
        val currentState = resolveEffectiveState(clientData)

        if (currentState != NovaVpnService.STATE_STOPPED) {
            // Stop
            clientData.clearTransientConnectingPending()
            clientData.clearSoftReapplyPending()
            clientData.clearRestartSession()
            clientData.saveServiceState(NovaVpnService.STATE_STOPPED, NovaVpnService.BACKEND_WARP)
            val intent = Intent(this, NovaVpnService::class.java)
            intent.action = "STOP_VPN"
            startService(intent)
            
            val tile = qsTile
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Nova"
            tile.updateTile()
        } else {
            val intent = Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_CONNECT_SMART
                putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())
                putExtra(NovaVpnService.EXTRA_REAPPLY_SPLIT_MODE, clientData.getSplitMode())
                putStringArrayListExtra(
                    NovaVpnService.EXTRA_REAPPLY_SPLIT_APPS,
                    ArrayList(clientData.getSplitApps())
                )
                putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED, clientData.getTrafficMaskEnabled())
                putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_MODE, clientData.getTrafficMaskMode())
                putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_HOST, clientData.getTrafficMaskHost())
            }
            ContextCompat.startForegroundService(this, intent)

            val tile = qsTile
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Nova"
            tile.updateTile()
        }
    }

    private fun updateTile(stateOverride: String? = null) {
        val tile = qsTile ?: return
        val clientData = ClientData(this)
        val effectiveState = resolveEffectiveState(clientData)
        val recentlyStopped = isRecentLocalStop(clientData)
        val hasNovaSystemVpn = hasActiveNovaSystemVpn(clientData)
        val serviceRunning = isNovaVpnServiceRunning()
        val hasRestartSession = clientData.getRestartSession() != null
        val pendingState = clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending()
        val resolvedState = when {
            recentlyStopped -> NovaVpnService.STATE_STOPPED
            stateOverride.isNullOrBlank() -> effectiveState
            stateOverride == NovaVpnService.STATE_STOPPED && hasNovaSystemVpn -> effectiveState
            stateOverride == NovaVpnService.STATE_CONNECTING && recentlyStopped -> NovaVpnService.STATE_STOPPED
            stateOverride == NovaVpnService.STATE_CONNECTED && recentlyStopped -> NovaVpnService.STATE_STOPPED
            stateOverride == NovaVpnService.STATE_CONNECTED && !hasNovaSystemVpn -> effectiveState
            stateOverride == NovaVpnService.STATE_CONNECTING &&
                !hasNovaSystemVpn &&
                !serviceRunning &&
                !(hasRestartSession && pendingState) -> effectiveState
            else -> stateOverride
        }
        LogManager.log(
            "QS tile refresh: override=${stateOverride.orEmpty().ifBlank { "<none>" }}, " +
                "resolved=$resolvedState, persisted=${clientData.getServiceState()}, " +
                "vpn=$hasNovaSystemVpn, service=$serviceRunning"
        )
        tile.label = "Nova"
        tile.state = when (resolvedState) {
            NovaVpnService.STATE_CONNECTED -> Tile.STATE_ACTIVE
            NovaVpnService.STATE_CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun resolveEffectiveState(clientData: ClientData): String {
        val persistedState = clientData.getServiceState()
        if (isRecentLocalStop(clientData)) {
            return NovaVpnService.STATE_STOPPED
        }
        val hasNovaSystemVpn = hasActiveNovaSystemVpn(clientData)
        val serviceRunning = isNovaVpnServiceRunning()
        val hasRestartSession = clientData.getRestartSession() != null
        val pendingState = clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending()
        if (hasNovaSystemVpn) {
            val syncedState = if (persistedState == NovaVpnService.STATE_CONNECTED) {
                NovaVpnService.STATE_CONNECTED
            } else if (
                persistedState == NovaVpnService.STATE_CONNECTING ||
                pendingState
            ) {
                NovaVpnService.STATE_CONNECTING
            } else {
                NovaVpnService.STATE_CONNECTED
            }
            if (syncedState == NovaVpnService.STATE_CONNECTED && pendingState) {
                clientData.clearTransientConnectingPending()
                clientData.clearSoftReapplyPending()
            }
            if (persistedState != syncedState) {
                LogManager.log(
                    "QS tile sync: системный Nova VPN активен, persisted=$persistedState -> $syncedState"
                )
                clientData.saveServiceState(
                    syncedState,
                    clientData.getServiceBackend().ifBlank { NovaVpnService.BACKEND_WARP },
                )
            }
            return syncedState
        }

        // Сессия перезапуска плюс живая служба раньше означали «туннель поднимается».
        // Теперь служба может работать только ради раздачи, поэтому дополнительно
        // требуем, чтобы сохранённое состояние не было «остановлено».
        if (serviceRunning && hasRestartSession && persistedState != NovaVpnService.STATE_STOPPED) {
            return if (persistedState == NovaVpnService.STATE_CONNECTING || pendingState) {
                NovaVpnService.STATE_CONNECTING
            } else {
                NovaVpnService.STATE_CONNECTED
            }
        }

        if (!serviceRunning) {
            val ageMs = (System.currentTimeMillis() - clientData.getServiceStateUpdatedAt()).coerceAtLeast(0L)
            if (
                persistedState == NovaVpnService.STATE_CONNECTING &&
                hasRestartSession &&
                pendingState &&
                ageMs <= 1_500L
            ) {
                return NovaVpnService.STATE_CONNECTING
            }
            if (persistedState != NovaVpnService.STATE_STOPPED) {
                clientData.clearTransientConnectingPending()
                clientData.clearSoftReapplyPending()
                clientData.saveServiceState(NovaVpnService.STATE_STOPPED, NovaVpnService.BACKEND_WARP)
            }
            return NovaVpnService.STATE_STOPPED
        }

        if (persistedState == NovaVpnService.STATE_CONNECTING) {
            val ageMs = (System.currentTimeMillis() - clientData.getServiceStateUpdatedAt()).coerceAtLeast(0L)
            if (clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending() || ageMs <= 15_000L) {
                return NovaVpnService.STATE_CONNECTING
            }
        }

        if (persistedState != NovaVpnService.STATE_STOPPED) {
            clientData.saveServiceState(NovaVpnService.STATE_STOPPED, NovaVpnService.BACKEND_WARP)
        }
        return NovaVpnService.STATE_STOPPED
    }

    private fun isRecentLocalStop(clientData: ClientData): Boolean {
        if (clientData.getServiceState() != NovaVpnService.STATE_STOPPED) return false
        val updatedAt = clientData.getServiceStateUpdatedAt()
        if (updatedAt <= 0L) return false
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        return ageMs <= recentManualStopWindowMs
    }

    private fun isNovaVpnServiceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE) ?: emptyList())
            .any { service -> service.service?.className == NovaVpnService::class.java.name }
    }

    /**
     * Живая служба сама по себе больше не доказывает живой туннель.
     *
     * NovaVpnService остаётся в foreground, пока включена раздача, даже когда VPN
     * остановлен. Без этой оговорки плитка в шторке горела «подключено» при
     * выключенном VPN — достаточно было, чтобы в системе оказалась активна любая
     * VPN-сеть, хоть чужого приложения.
     */
    private fun tunnelIntended(clientData: ClientData): Boolean {
        val persisted = clientData.getServiceState()
        return persisted == NovaVpnService.STATE_CONNECTED ||
            persisted == NovaVpnService.STATE_CONNECTING
    }

    private fun hasActiveNovaSystemVpn(clientData: ClientData): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val serviceRunning = isNovaVpnServiceRunning() && tunnelIntended(clientData)
        val hasRestartSession = clientData.getRestartSession() != null
        fun isLikelyNovaVpnNetwork(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            if (isNovaVpnOwner(caps)) return true
            val transportLabel = extractVpnTransportLabel(caps)
            return transportLabel.contains("NovaVPN", ignoreCase = true) ||
                transportLabel.contains("NovaOperaVPN", ignoreCase = true)
        }

        val active = cm.activeNetwork
        if (active != null && isLikelyNovaVpnNetwork(active)) return true

        val anyVpnActive = cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (!anyVpnActive) return false
        if (serviceRunning) {
            return true
        }

        return cm.allNetworks.any(::isLikelyNovaVpnNetwork)
    }

    private fun extractVpnTransportLabel(caps: NetworkCapabilities?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val transportInfo = NetworkCapabilitiesCompat.getTransportInfo(caps) ?: return ""
        try {
            val sessionId = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getSessionId" && it.parameterCount == 0 }
                ?.invoke(transportInfo) as? String
            if (!sessionId.isNullOrBlank()) {
                return sessionId
            }
        } catch (_: Throwable) {
        }
        return transportInfo.toString().orEmpty()
    }

    private fun isNovaVpnOwner(caps: NetworkCapabilities?): Boolean {
        return extractVpnOwnerUid(caps) == applicationInfo.uid
    }

    private fun extractVpnOwnerUid(caps: NetworkCapabilities?): Int? {
        if (caps == null) return null
        val reflectedOwnerUid = try {
            val ownerUid = caps.javaClass.methods
                .firstOrNull { it.name == "getOwnerUid" && it.parameterCount == 0 }
                ?.invoke(caps) as? Int
            ownerUid?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
        if (reflectedOwnerUid != null) return reflectedOwnerUid

        val capsDump = caps.toString()
        val legacyOwnerUid = Regex("(?:OwnerUid|EstablishingAppUid):\\s*(\\d+)")
            .find(capsDump)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
        return legacyOwnerUid
    }

    private fun ensureReceiverRegistered() {
        if (tileReceiverRegistered) return
        val filter = IntentFilter(NovaVpnService.ACTION_VPN_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
        tileReceiverRegistered = true
    }

    private fun unregisterTileReceiver() {
        if (!tileReceiverRegistered) return
        try {
            unregisterReceiver(vpnStateReceiver)
        } catch (_: Exception) {
        }
        tileReceiverRegistered = false
    }
}
