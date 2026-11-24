package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.models.ConnectionType
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.speedtest.NetworkSnapshot
import com.example.engine.speedtest.WifiBand
import com.example.engine.speedtest.WifiSignalQuality
import com.example.engine.tools.InternetInfoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class NetworkInfoUiState(
    val connectionType: ConnectionType = ConnectionType.NONE,
    val isValidated: Boolean = false,
    val networkName: String? = null,
    val cellularNetworkTag: String? = null,
    val hasWifiLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val wifiPermissionDenied: Boolean = false,
    val wifiPermissionPermanentlyDenied: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val phonePermissionDenied: Boolean = false,
    val wifiBand: WifiBand? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val wifiRssiDbm: Int? = null,
    val wifiSignalQuality: WifiSignalQuality? = null,
    val isLoadingInternetInfo: Boolean = false,
    val hasLoadedInternetInfo: Boolean = false,
    val publicIp: String? = null,
    val isp: String? = null,
    val ipv4Supported: Boolean? = null,
    val ipv6Supported: Boolean? = null,
)

/**
 * ViewModel scoped strictly to the Network info screen.
 * Starts observing network state and fetching external IP/ISP/IPv4/IPv6 info only when opened,
 * cancels lookups and unregisters listeners safely when cleared, and ignores late/cancelled updates.
 */
class NetworkInfoViewModel(
    private val networkMonitor: NetworkMonitor,
    private val internetInfoClient: InternetInfoClient,
) : ViewModel() {

    private val cleared = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(
        initialState(networkMonitor.currentSnapshot()),
    )
    val uiState: StateFlow<NetworkInfoUiState> = _uiState.asStateFlow()

    private var internetLookupJob: Job? = null

    init {
        networkMonitor.startObserving()
        viewModelScope.launch {
            networkMonitor.networkState.collect { snapshot ->
                if (!cleared.get()) {
                    _uiState.update { state -> state.applySnapshot(snapshot) }
                }
            }
        }
        refreshInternetInfo()
    }

    fun onRefreshAll() {
        if (cleared.get()) return
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = true)
        if (!cleared.get()) {
            _uiState.update { it.applySnapshot(snapshot) }
            refreshInternetInfo()
        }
    }

    fun onResumeRefresh() {
        if (cleared.get()) return
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = true)
        if (!cleared.get()) {
            _uiState.update { state ->
                state.applySnapshot(snapshot).let { updated ->
                    updated.copy(
                        wifiPermissionDenied = if (updated.hasWifiLocationPermission) false else updated.wifiPermissionDenied,
                        wifiPermissionPermanentlyDenied = if (updated.hasWifiLocationPermission) false else updated.wifiPermissionPermanentlyDenied,
                        phonePermissionDenied = if (updated.hasPhoneStatePermission) false else updated.phonePermissionDenied,
                    )
                }
            }
        }
    }

    fun onWifiPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (cleared.get()) return
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        if (!cleared.get()) {
            _uiState.update { state ->
                state.applySnapshot(snapshot).copy(
                    wifiPermissionDenied = !granted,
                    wifiPermissionPermanentlyDenied = !granted && permanentlyDenied,
                )
            }
        }
    }

    fun onPhonePermissionResult(granted: Boolean) {
        if (cleared.get()) return
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        if (!cleared.get()) {
            _uiState.update { state ->
                state.applySnapshot(snapshot).copy(
                    phonePermissionDenied = !granted,
                )
            }
        }
    }

    fun refreshInternetInfo() {
        if (cleared.get()) return
        internetLookupJob?.cancel()
        val currentConnection = networkMonitor.currentSnapshot().connectionType
        if (currentConnection == ConnectionType.NONE) {
            if (!cleared.get()) {
                _uiState.update {
                    it.copy(
                        isLoadingInternetInfo = false,
                        hasLoadedInternetInfo = true,
                        publicIp = null,
                        isp = null,
                        ipv4Supported = false,
                        ipv6Supported = false,
                    )
                }
            }
            return
        }

        _uiState.update { it.copy(isLoadingInternetInfo = true) }
        internetLookupJob = viewModelScope.launch {
            try {
                val result = internetInfoClient.fetchInternetInfo()
                if (!isActive || cleared.get()) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingInternetInfo = false,
                        hasLoadedInternetInfo = true,
                        publicIp = result.publicIp,
                        isp = result.isp,
                        ipv4Supported = result.ipv4Supported,
                        ipv6Supported = result.ipv6Supported,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (!isActive || cleared.get()) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingInternetInfo = false,
                        hasLoadedInternetInfo = true,
                    )
                }
            }
        }
    }

    fun cancelPendingInternetLookup() {
        internetLookupJob?.cancel()
        internetLookupJob = null
    }

    internal fun clearForTest() {
        onCleared()
    }

    private fun NetworkInfoUiState.applySnapshot(snapshot: NetworkSnapshot): NetworkInfoUiState = copy(
        connectionType = snapshot.connectionType,
        isValidated = snapshot.isValidated,
        networkName = snapshot.networkName,
        cellularNetworkTag = snapshot.cellularNetworkTag,
        hasWifiLocationPermission = snapshot.hasWifiLocationPermission,
        isLocationEnabled = snapshot.isLocationEnabled,
        hasPhoneStatePermission = snapshot.hasPhoneStatePermission,
        phonePermissionDenied = if (snapshot.hasPhoneStatePermission) false else phonePermissionDenied,
        wifiBand = snapshot.wifiBand,
        wifiLinkSpeedMbps = snapshot.wifiLinkSpeedMbps,
        wifiRssiDbm = snapshot.wifiRssiDbm,
        wifiSignalQuality = snapshot.wifiSignalQuality,
    )

    override fun onCleared() {
        val firstClear = cleared.compareAndSet(false, true)
        internetLookupJob?.cancel()
        internetLookupJob = null
        networkMonitor.stopObserving()
        internetInfoClient.close()
        if (firstClear) {
            super.onCleared()
        }
    }

    companion object {
        private fun initialState(snapshot: NetworkSnapshot): NetworkInfoUiState = NetworkInfoUiState(
            connectionType = snapshot.connectionType,
            isValidated = snapshot.isValidated,
            networkName = snapshot.networkName,
            cellularNetworkTag = snapshot.cellularNetworkTag,
            hasWifiLocationPermission = snapshot.hasWifiLocationPermission,
            isLocationEnabled = snapshot.isLocationEnabled,
            hasPhoneStatePermission = snapshot.hasPhoneStatePermission,
            wifiBand = snapshot.wifiBand,
            wifiLinkSpeedMbps = snapshot.wifiLinkSpeedMbps,
            wifiRssiDbm = snapshot.wifiRssiDbm,
            wifiSignalQuality = snapshot.wifiSignalQuality,
        )

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return NetworkInfoViewModel(
                    networkMonitor = NetworkMonitor(appContext),
                    internetInfoClient = InternetInfoClient(),
                ) as T
            }
        }
    }
}
