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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * and cleans up callbacks and network calls immediately when closed.
 */
class NetworkInfoViewModel(
    private val networkMonitor: NetworkMonitor,
    private val internetInfoClient: InternetInfoClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        initialState(networkMonitor.currentSnapshot()),
    )
    val uiState: StateFlow<NetworkInfoUiState> = _uiState.asStateFlow()

    private var internetLookupJob: Job? = null

    init {
        networkMonitor.startObserving()
        viewModelScope.launch {
            networkMonitor.networkState.collect { snapshot ->
                _uiState.update { state -> state.applySnapshot(snapshot) }
            }
        }
        refreshInternetInfo()
    }

    fun onRefreshAll() {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = true)
        _uiState.update { it.applySnapshot(snapshot) }
        refreshInternetInfo()
    }

    fun onResumeRefresh() {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = true)
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

    fun onWifiPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        _uiState.update { state ->
            state.applySnapshot(snapshot).copy(
                wifiPermissionDenied = !granted,
                wifiPermissionPermanentlyDenied = !granted && permanentlyDenied,
            )
        }
    }

    fun onPhonePermissionResult(granted: Boolean) {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        _uiState.update { state ->
            state.applySnapshot(snapshot).copy(
                phonePermissionDenied = !granted,
            )
        }
    }

    fun refreshInternetInfo() {
        internetLookupJob?.cancel()
        val currentConnection = networkMonitor.currentSnapshot().connectionType
        if (currentConnection == ConnectionType.NONE) {
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
            return
        }

        _uiState.update { it.copy(isLoadingInternetInfo = true) }
        internetLookupJob = viewModelScope.launch {
            val result = internetInfoClient.fetchInternetInfo()
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
        }
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
        internetLookupJob?.cancel()
        internetLookupJob = null
        networkMonitor.stopObserving()
        internetInfoClient.close()
        super.onCleared()
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
