package com.example.engine.speedtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.data.models.ConnectionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WifiBand(val label: String) {
    BAND_2_4_GHZ("2.4 GHz"),
    BAND_5_GHZ("5 GHz"),
    BAND_6_GHZ("6 GHz"),
}

enum class WifiSignalQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK,
}

data class NetworkSnapshot(
    val connectionType: ConnectionType,
    val networkHandle: Long?,
    val isValidated: Boolean = false,
    val networkName: String? = null,
    val cellularNetworkTag: String? = null,
    val wifiSsidHidden: Boolean = false,
    val hasWifiLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val wifiBand: WifiBand? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val wifiRssiDbm: Int? = null,
    val wifiSignalQuality: WifiSignalQuality? = null,
    val deviceModel: String = Build.MODEL?.trim().orEmpty(),
)

/**
 * Observes active network type, validation status, Wi-Fi details (band, link speed, signal,
 * and SSID when permitted), active data SIM carrier, mobile generation (2G/3G/4G/5G including 5G NSA),
 * and device model.
 *
 * - Android 12+ (API 31+): reads WifiInfo via NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) and
 *   TelephonyDisplayInfo via TelephonyCallback.DisplayInfoListener.
 * - Android 11 (API 30): reads TelephonyDisplayInfo via PhoneStateListener.
 * - Android 10 and below (API <= 29): reads mobile generation without requiring READ_PHONE_STATE.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRegistered = false

    @Volatile
    private var latestTransportWifiInfo: WifiInfo? = null

    @Volatile
    private var latestOverrideNetworkType: Int = OVERRIDE_NETWORK_TYPE_NONE

    @Volatile
    private var latestDisplayNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN

    @Volatile
    private var registeredTelephonySubId: Int = Int.MIN_VALUE

    @Volatile
    private var registeredTelephonyManager: TelephonyManager? = null

    @Volatile
    private var registeredTelephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    @Volatile
    private var registeredPhoneStateListener: PhoneStateListener? = null

    private val _networkState = MutableStateFlow(readCurrentSnapshot())
    val networkState: StateFlow<NetworkSnapshot> = _networkState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback = createNetworkCallback()

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        val baseCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshSnapshot()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val active = runCatching { connectivityManager?.activeNetwork }.getOrNull()
                if (active == null || active == network) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val callbackWifiInfo = networkCapabilities.transportInfo as? WifiInfo
                        if (callbackWifiInfo != null) {
                            latestTransportWifiInfo = callbackWifiInfo
                        }
                    }
                    ensureTelephonyDisplayListener()
                    _networkState.value = buildSnapshot(network, networkCapabilities)
                } else {
                    refreshSnapshot()
                }
            }

            override fun onLost(network: Network) {
                val currentHandle = _networkState.value.networkHandle
                val active = runCatching { connectivityManager?.activeNetwork }.getOrNull()
                if (active == null || active == network || currentHandle == network.networkHandle) {
                    latestTransportWifiInfo = null
                    val updated = readCurrentSnapshot()
                    _networkState.value = if (updated.networkHandle == network.networkHandle) {
                        offlineSnapshot()
                    } else {
                        updated
                    }
                }
            }

            override fun onUnavailable() {
                latestTransportWifiInfo = null
                _networkState.value = offlineSnapshot()
            }
        }

        // On Android 12+ (API 31+), FLAG_INCLUDE_LOCATION_INFO is required to receive unredacted WifiInfo.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = baseCallback.onAvailable(network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    baseCallback.onCapabilitiesChanged(network, networkCapabilities)
                override fun onLost(network: Network) = baseCallback.onLost(network)
                override fun onUnavailable() = baseCallback.onUnavailable()
            }
        } else {
            baseCallback
        }
    }

    fun startObserving() {
        if (isRegistered) return
        val cm = connectivityManager ?: return
        isRegistered = true
        ensureTelephonyDisplayListener()
        refreshSnapshot()
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
        }.onFailure {
            isRegistered = false
        }
    }

    fun stopObserving() {
        if (!isRegistered) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        unregisterTelephonyDisplayListener()
        isRegistered = false
        latestTransportWifiInfo = null
    }

    /**
     * Re-reads active network and SSID/Wi-Fi/carrier/generation info immediately.
     * Pass [reregisterCallback] = true after granting location or phone permission or toggling location
     * services so Android 12+ re-evaluates FLAG_INCLUDE_LOCATION_INFO and delivers fresh WifiInfo,
     * and Android 11+ registers TelephonyDisplayInfo listeners if READ_PHONE_STATE was just granted.
     */
    fun refreshSnapshot(reregisterCallback: Boolean = false): NetworkSnapshot {
        val cm = connectivityManager
        if (reregisterCallback && isRegistered && cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                cm.unregisterNetworkCallback(networkCallback)
            }
            latestTransportWifiInfo = null
            networkCallback = createNetworkCallback()
            runCatching {
                cm.registerDefaultNetworkCallback(networkCallback)
            }
        }
        if (isRegistered) {
            ensureTelephonyDisplayListener()
        }
        val snapshot = readCurrentSnapshot()
        _networkState.value = snapshot
        return snapshot
    }

    fun currentSnapshot(): NetworkSnapshot = refreshSnapshot()

    private fun offlineSnapshot(): NetworkSnapshot = NetworkSnapshot(
        connectionType = ConnectionType.NONE,
        networkHandle = null,
        isValidated = false,
        hasWifiLocationPermission = hasFineLocationPermission(),
        isLocationEnabled = isLocationServicesEnabled(),
        hasPhoneStatePermission = hasPhoneStatePermission(),
    )

    private fun readCurrentSnapshot(): NetworkSnapshot {
        val cm = connectivityManager ?: return offlineSnapshot()
        val activeNetwork = runCatching { cm.activeNetwork }.getOrNull() ?: return offlineSnapshot()
        val capabilities = runCatching { cm.getNetworkCapabilities(activeNetwork) }.getOrNull()
            ?: return offlineSnapshot()
        return buildSnapshot(activeNetwork, capabilities)
    }

    private fun buildSnapshot(
        network: Network,
        capabilities: NetworkCapabilities,
    ): NetworkSnapshot {
        val connectionType = mapCapabilities(capabilities)
        val isValidated = connectionType != ConnectionType.NONE &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasWifiPerm = hasFineLocationPermission()
        val locEnabled = isLocationServicesEnabled()
        val hasPhonePerm = hasPhoneStatePermission()

        var networkName: String? = null
        var cellularTag: String? = null
        var wifiHidden = false
        var wifiBand: WifiBand? = null
        var wifiLinkSpeedMbps: Int? = null
        var wifiRssiDbm: Int? = null
        var wifiSignalQuality: WifiSignalQuality? = null

        when (connectionType) {
            ConnectionType.WIFI -> {
                val primaryWifiInfo = resolvePrimaryWifiInfo(capabilities)
                val fallbackWifiInfo = resolveLegacyWifiInfo()
                val wifiInfo = primaryWifiInfo ?: fallbackWifiInfo

                // Band, link speed, and signal require no location permission; read immediately.
                val frequencyMhz = runCatching { primaryWifiInfo?.frequency }.getOrNull()?.takeIf { it > 0 }
                    ?: runCatching { fallbackWifiInfo?.frequency }.getOrNull()?.takeIf { it > 0 }
                wifiBand = frequencyMhz?.let { mapWifiBand(it) }

                val rawLinkSpeed = runCatching { primaryWifiInfo?.linkSpeed }.getOrNull()?.takeIf { it > 0 }
                    ?: runCatching { fallbackWifiInfo?.linkSpeed }.getOrNull()?.takeIf { it > 0 }
                wifiLinkSpeedMbps = rawLinkSpeed

                val rawRssi = runCatching { wifiInfo?.rssi }.getOrNull()
                    ?.takeIf { isValidRssi(it) }
                    ?: readCapabilitiesSignalStrength(capabilities)
                wifiRssiDbm = rawRssi
                wifiSignalQuality = rawRssi?.let { mapWifiSignalQuality(it) }

                // SSID requires ACCESS_FINE_LOCATION + Location services enabled.
                if (hasWifiPerm && locEnabled) {
                    val ssid = sanitizeSsid(runCatching { primaryWifiInfo?.ssid }.getOrNull())
                        ?: sanitizeSsid(runCatching { fallbackWifiInfo?.ssid }.getOrNull())
                    if (ssid != null) {
                        networkName = ssid
                        wifiHidden = false
                    } else {
                        wifiHidden = true
                    }
                } else {
                    wifiHidden = true
                }
            }
            ConnectionType.MOBILE -> {
                networkName = readActiveDataCarrierName()
                cellularTag = readOptionalCellularTag(hasPhonePerm)
            }
            ConnectionType.ETHERNET, ConnectionType.NONE -> Unit
        }

        return NetworkSnapshot(
            connectionType = connectionType,
            networkHandle = if (connectionType == ConnectionType.NONE) null else network.networkHandle,
            isValidated = isValidated,
            networkName = networkName,
            cellularNetworkTag = cellularTag,
            wifiSsidHidden = wifiHidden,
            hasWifiLocationPermission = hasWifiPerm,
            isLocationEnabled = locEnabled,
            hasPhoneStatePermission = hasPhonePerm,
            wifiBand = wifiBand,
            wifiLinkSpeedMbps = wifiLinkSpeedMbps,
            wifiRssiDbm = wifiRssiDbm,
            wifiSignalQuality = wifiSignalQuality,
        )
    }

    private fun resolvePrimaryWifiInfo(capabilities: NetworkCapabilities): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callbackInfo = latestTransportWifiInfo
            val capInfo = capabilities.transportInfo as? WifiInfo
            if (callbackInfo != null) {
                val callbackSsid = sanitizeSsid(runCatching { callbackInfo.ssid }.getOrNull())
                val capSsid = sanitizeSsid(runCatching { capInfo?.ssid }.getOrNull())
                if (callbackSsid != null || capSsid == null) {
                    return callbackInfo
                }
            }
            if (capInfo != null) {
                return capInfo
            }
        }
        return resolveLegacyWifiInfo()
    }

    private fun resolveLegacyWifiInfo(): WifiInfo? {
        val hasWifiState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_WIFI_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasWifiState) return null

        return runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo
        }.getOrNull()
    }

    private fun readCapabilitiesSignalStrength(capabilities: NetworkCapabilities): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val strength = runCatching { capabilities.signalStrength }.getOrNull() ?: return null
        return strength.takeIf { isValidRssi(it) }
    }

    private fun mapCapabilities(capabilities: NetworkCapabilities): ConnectionType {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ConnectionType.NONE
        }
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.NONE
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPhoneStatePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationServicesEnabled(): Boolean {
        val lm = locationManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }

    private fun resolveActiveSubscriptionTelephony(): Pair<TelephonyManager?, Int> {
        val baseTelephony = telephonyManager ?: return null to SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val dataSubId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val activeTelephony = if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { baseTelephony.createForSubscriptionId(dataSubId) }.getOrDefault(baseTelephony)
        } else {
            baseTelephony
        }
        return activeTelephony to dataSubId
    }

    private fun readActiveDataCarrierName(): String? {
        return runCatching {
            val (activeTelephony, _) = resolveActiveSubscriptionTelephony()
            activeTelephony?.networkOperatorName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun readOptionalCellularTag(hasPhonePerm: Boolean): String? {
        return runCatching {
            val (activeTelephony, _) = resolveActiveSubscriptionTelephony()
            val tm = activeTelephony ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!hasPhonePerm) return null
                ensureTelephonyDisplayListener()
                val rawNetworkType = runCatching { tm.dataNetworkType }
                    .getOrNull()
                    ?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: runCatching {
                        @Suppress("DEPRECATION")
                        tm.networkType
                    }.getOrNull()?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: latestDisplayNetworkType

                mapCellularGenerationTag(
                    networkType = rawNetworkType,
                    overrideNetworkType = latestOverrideNetworkType,
                )
            } else {
                // Android 10 and below: read generation without requiring READ_PHONE_STATE.
                val rawNetworkType = runCatching {
                    @Suppress("DEPRECATION")
                    tm.networkType
                }.getOrNull()?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: runCatching { tm.dataNetworkType }
                        .getOrNull()
                        ?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: runCatching {
                        @Suppress("DEPRECATION")
                        connectivityManager?.activeNetworkInfo?.subtype
                    }.getOrNull()
                    ?: TelephonyManager.NETWORK_TYPE_UNKNOWN

                mapCellularGenerationTag(
                    networkType = rawNetworkType,
                    overrideNetworkType = OVERRIDE_NETWORK_TYPE_NONE,
                )
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun ensureTelephonyDisplayListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!hasPhoneStatePermission()) {
            unregisterTelephonyDisplayListener()
            return
        }

        val (activeTelephony, subId) = resolveActiveSubscriptionTelephony()
        val tm = activeTelephony ?: return

        if (registeredTelephonyManager != null && registeredTelephonySubId == subId &&
            (registeredTelephonyCallback != null || registeredPhoneStateListener != null)
        ) {
            return
        }

        unregisterTelephonyDisplayListener()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerApi31DisplayInfoCallback(tm, subId)
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            registerApi30PhoneStateListener(tm, subId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun registerApi31DisplayInfoCallback(tm: TelephonyManager, subId: Int) {
        runCatching {
            val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    latestOverrideNetworkType = telephonyDisplayInfo.overrideNetworkType
                    latestDisplayNetworkType = telephonyDisplayInfo.networkType
                    _networkState.value = readCurrentSnapshot()
                }
            }
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(appContext), callback)
            registeredTelephonyManager = tm
            registeredTelephonySubId = subId
            registeredTelephonyCallback = callback
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun registerApi30PhoneStateListener(tm: TelephonyManager, subId: Int) {
        val registerAction = {
            runCatching {
                val listener = object : PhoneStateListener() {
                    @RequiresApi(Build.VERSION_CODES.R)
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        latestOverrideNetworkType = telephonyDisplayInfo.overrideNetworkType
                        latestDisplayNetworkType = telephonyDisplayInfo.networkType
                        _networkState.value = readCurrentSnapshot()
                    }
                }
                tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                registeredTelephonyManager = tm
                registeredTelephonySubId = subId
                registeredPhoneStateListener = listener
            }
        }
        if (Looper.myLooper() != null) {
            registerAction()
        } else {
            mainHandler.post { registerAction() }
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterTelephonyDisplayListener() {
        val tm = registeredTelephonyManager
        val callback = registeredTelephonyCallback
        val listener = registeredPhoneStateListener

        if (tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callback is TelephonyCallback) {
                runCatching { tm.unregisterTelephonyCallback(callback) }
            }
            if (listener != null) {
                runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        }

        registeredTelephonyManager = null
        registeredTelephonyCallback = null
        registeredPhoneStateListener = null
        registeredTelephonySubId = Int.MIN_VALUE
        latestOverrideNetworkType = OVERRIDE_NETWORK_TYPE_NONE
        latestDisplayNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN
    }

    companion object {
        internal const val OVERRIDE_NETWORK_TYPE_NONE = 0
        internal const val OVERRIDE_NETWORK_TYPE_NR_NSA = 3
        internal const val OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE = 4
        internal const val OVERRIDE_NETWORK_TYPE_NR_ADVANCED = 5

        internal fun sanitizeSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val stripped = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
            if (stripped.isBlank()) return null
            if (stripped.equals("<unknown ssid>", ignoreCase = true)) return null
            if (stripped.equals("0x", ignoreCase = true)) return null
            return stripped
        }

        internal fun mapWifiBand(frequencyMhz: Int): WifiBand? = when (frequencyMhz) {
            in 2400..2500 -> WifiBand.BAND_2_4_GHZ
            in 4900..5900 -> WifiBand.BAND_5_GHZ
            in 5925..7125 -> WifiBand.BAND_6_GHZ
            else -> null
        }

        internal fun isValidRssi(rssiDbm: Int): Boolean = rssiDbm in -126..-1

        internal fun mapWifiSignalQuality(rssiDbm: Int): WifiSignalQuality = when {
            rssiDbm >= -55 -> WifiSignalQuality.EXCELLENT
            rssiDbm >= -67 -> WifiSignalQuality.GOOD
            rssiDbm >= -75 -> WifiSignalQuality.FAIR
            else -> WifiSignalQuality.WEAK
        }

        internal fun mapCellularGenerationTag(
            networkType: Int,
            overrideNetworkType: Int = OVERRIDE_NETWORK_TYPE_NONE,
        ): String? {
            if (overrideNetworkType == OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideNetworkType == OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
                overrideNetworkType == OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
            ) {
                return "5G"
            }
            return when (networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN,
                TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                else -> null
            }
        }
    }
}
