package com.example.data.server

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.constants.SpeedTestConstants
import com.example.engine.server.LibreSpeedServerParser
import com.example.engine.server.SameHostHttpsRedirectInterceptor
import com.example.engine.server.SafeServerDns
import com.example.engine.server.ServerHealthChecker
import com.example.engine.server.ServerProbeOutcome
import com.example.engine.server.ServerUrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface CustomServerResult {
    data class Success(val profile: ServerProfile, val pingMs: Double) : CustomServerResult
    data object InvalidUrl : CustomServerResult
    data object Unreachable : CustomServerResult
}

data class ResolvedTestServer(
    val server: ServerProfile,
    val fellBackFromServerName: String? = null,
)

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pace_servers",
)

/**
 * Manages built-in Cloudflare, cached community LibreSpeed servers (24h TTL, fetched on demand only),
 * user-added custom servers in DataStore, and session-cached health probe rankings.
 * Performs zero background network requests on startup.
 */
class ServerRepository(
    context: Context,
    private val healthChecker: ServerHealthChecker = ServerHealthChecker(),
    private val feedClient: OkHttpClient = createFeedClient(),
) {
    private val dataStore = context.applicationContext.serverDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        val CUSTOM_SERVERS_JSON = stringPreferencesKey("custom_servers_json")
        val COMMUNITY_CACHE_JSON = stringPreferencesKey("community_cache_json")
        val COMMUNITY_CACHE_TIME_MS = longPreferencesKey("community_cache_time_ms")
    }

    private val _selectorState = MutableStateFlow(ServerSelectorUiState())
    val selectorState: StateFlow<ServerSelectorUiState> = _selectorState.asStateFlow()

    @Volatile
    private var cloudflareProfile: ServerProfile = ServerProfile.CLOUDFLARE

    @Volatile
    private var lastProbedIncludeCommunity: Boolean? = null

    private var activeProbeJob: Job? = null

    init {
        // Read persisted selection and custom servers from local DataStore only (no network I/O).
        scope.launch {
            dataStore.data
                .catch { ex ->
                    if (ex is IOException) emit(emptyPreferences()) else throw ex
                }
                .collect { prefs ->
                    val selectedId = prefs[Keys.SELECTED_SERVER_ID] ?: ServerProfile.AUTO_SERVER_ID
                    val customServers = parseCustomServersJson(prefs[Keys.CUSTOM_SERVERS_JSON])
                    val cachedCommunity = LibreSpeedServerParser.parseCommunityServers(
                        prefs[Keys.COMMUNITY_CACHE_JSON].orEmpty(),
                    )
                    mergeLocalInventory(
                        selectedId = selectedId,
                        customServers = customServers,
                        cachedCommunity = cachedCommunity,
                    )
                }
        }
    }

    /**
     * Called when the user opens the server selector bottom sheet.
     * Fetches community servers (using the 24h cache if fresh) and probes candidates if not yet
     * probed this session for the current community-servers setting.
     */
    fun onSelectorOpened(includeCommunityServers: Boolean) {
        val state = _selectorState.value
        val needsProbe = !state.hasProbedThisSession ||
            lastProbedIncludeCommunity != includeCommunityServers
        if (needsProbe && !state.isProbing) {
            refreshServers(
                includeCommunityServers = includeCommunityServers,
                forceFetchCommunity = false,
            )
        }
    }

    /**
     * Refreshes candidates and probes them progressively in parallel (up to 8 at a time).
     */
    fun refreshServers(
        includeCommunityServers: Boolean,
        forceFetchCommunity: Boolean = true,
    ) {
        activeProbeJob?.cancel()
        activeProbeJob = scope.launch {
            runCandidateFetchAndProbe(
                includeCommunityServers = includeCommunityServers,
                forceFetchCommunity = forceFetchCommunity,
            )
        }
    }

    suspend fun selectServer(serverId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED_SERVER_ID] = serverId
        }
        _selectorState.update { it.copy(selectedServerId = serverId) }
    }

    /**
     * Validates a custom LibreSpeed server URL (HTTPS-only, public host) and verifies reachability
     * with a live ping before saving to DataStore.
     */
    suspend fun validateAndAddCustomServer(
        name: String,
        baseUrl: String,
        downloadPath: String,
        uploadPath: String,
        pingPath: String,
        getIpPath: String,
    ): CustomServerResult = withContext(Dispatchers.IO) {
        val validBase = ServerUrlValidator.validateHttpsUrl(baseUrl)
            ?: return@withContext CustomServerResult.InvalidUrl

        val dlRel = downloadPath.trim().ifEmpty { ServerProfile.DEFAULT_LIBRESPEED_DL_PATH }
        val ulRel = uploadPath.trim().ifEmpty { ServerProfile.DEFAULT_LIBRESPEED_UL_PATH }
        val pingRel = pingPath.trim().ifEmpty { ServerProfile.DEFAULT_LIBRESPEED_PING_PATH }
        val getIpRel = getIpPath.trim().ifEmpty { ServerProfile.DEFAULT_LIBRESPEED_GET_IP_PATH }

        val dlUrl = ServerUrlValidator.resolveEndpointUrl(validBase, dlRel)
            ?: return@withContext CustomServerResult.InvalidUrl
        val ulUrl = ServerUrlValidator.resolveEndpointUrl(validBase, ulRel)
            ?: return@withContext CustomServerResult.InvalidUrl
        val pingUrl = ServerUrlValidator.resolveEndpointUrl(validBase, pingRel)
            ?: return@withContext CustomServerResult.InvalidUrl
        val getIpUrl = ServerUrlValidator.resolveEndpointUrl(validBase, getIpRel)

        val fallbackHost = runCatching { java.net.URI(validBase).host }.getOrNull().orEmpty()
        val displayName = name.trim().ifEmpty { fallbackHost.ifEmpty { "Custom server" } }
        val profile = ServerProfile(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            location = fallbackHost.takeIf { !it.equals(displayName, ignoreCase = true) }.orEmpty(),
            kind = ServerKind.CUSTOM,
            baseUrl = validBase,
            downloadBaseUrl = dlUrl,
            uploadBaseUrl = ulUrl,
            pingBaseUrl = pingUrl,
            getIpBaseUrl = getIpUrl,
        )

        val outcome = healthChecker.probeServer(
            server = profile,
            probeCount = SpeedTestConstants.SERVER_HEALTH_PROBES,
            timeoutMs = SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS,
        )
        if (!outcome.isHealthy || outcome.medianPingMs == null) {
            return@withContext CustomServerResult.Unreachable
        }

        // Persist in DataStore
        dataStore.edit { prefs ->
            val existing = parseCustomServersJson(prefs[Keys.CUSTOM_SERVERS_JSON])
            val updated = existing + profile
            prefs[Keys.CUSTOM_SERVERS_JSON] = serializeCustomServers(updated)
            prefs[Keys.SELECTED_SERVER_ID] = profile.id
        }

        _selectorState.update { state ->
            val updatedList = state.candidates.filterNot { it.profile.id == profile.id } +
                ServerCandidateState(
                    profile = profile,
                    status = ServerProbeStatus.HEALTHY,
                    medianPingMs = outcome.medianPingMs,
                )
            val sorted = sortCandidateStates(updatedList)
            val fastest = sorted.firstOrNull { it.status == ServerProbeStatus.HEALTHY }
            state.copy(
                selectedServerId = profile.id,
                candidates = sorted,
                autoFastestServer = fastest?.profile ?: state.autoFastestServer,
                autoFastestPingMs = fastest?.medianPingMs ?: state.autoFastestPingMs,
            )
        }

        CustomServerResult.Success(profile = profile, pingMs = outcome.medianPingMs)
    }

    suspend fun deleteCustomServer(serverId: String) {
        dataStore.edit { prefs ->
            val existing = parseCustomServersJson(prefs[Keys.CUSTOM_SERVERS_JSON])
            val remaining = existing.filterNot { it.id == serverId }
            prefs[Keys.CUSTOM_SERVERS_JSON] = serializeCustomServers(remaining)
            if (prefs[Keys.SELECTED_SERVER_ID] == serverId) {
                prefs[Keys.SELECTED_SERVER_ID] = ServerProfile.AUTO_SERVER_ID
            }
        }

        _selectorState.update { state ->
            val remaining = state.candidates.filterNot { it.profile.id == serverId }
            val nextSelected = if (state.selectedServerId == serverId) {
                ServerProfile.AUTO_SERVER_ID
            } else {
                state.selectedServerId
            }
            val fastest = remaining.firstOrNull { it.status == ServerProbeStatus.HEALTHY }
            state.copy(
                selectedServerId = nextSelected,
                candidates = remaining,
                autoFastestServer = fastest?.profile ?: cloudflareProfile,
                autoFastestPingMs = fastest?.medianPingMs,
            )
        }
    }

    /**
     * Resolves the server to use right as a speed test begins:
     * - Auto: uses session-cached ranking if already probed for the current settings, otherwise
     *   fetches (24h cached) community servers if enabled, probes candidates, caches ranking for the
     *   session, and picks the lowest-ping healthy server (Cloudflare always a candidate).
     * - Saved specific server: validates reachability if non-Cloudflare; if unreachable, falls back
     *   to Cloudflare and reports [ResolvedTestServer.fellBackFromServerName] for a Snackbar.
     */
    suspend fun resolveServerForTest(
        includeCommunityServers: Boolean,
    ): ResolvedTestServer = withContext(Dispatchers.IO) {
        val prefs = readPrefsSafely()
        val selectedId = prefs[Keys.SELECTED_SERVER_ID] ?: ServerProfile.AUTO_SERVER_ID

        if (selectedId == ServerProfile.AUTO_SERVER_ID) {
            val state = _selectorState.value
            val cachedFastest = state.autoFastestServer
            val canReuseSession = state.hasProbedThisSession &&
                lastProbedIncludeCommunity == includeCommunityServers &&
                cachedFastest != null &&
                !state.isProbing

            val fastest = if (canReuseSession) {
                cachedFastest
            } else {
                runCandidateFetchAndProbe(
                    includeCommunityServers = includeCommunityServers,
                    forceFetchCommunity = false,
                )
                _selectorState.value.autoFastestServer ?: cloudflareProfile
            }
            return@withContext ResolvedTestServer(server = fastest)
        }

        if (selectedId == ServerProfile.CLOUDFLARE_ID) {
            return@withContext ResolvedTestServer(server = cloudflareProfile)
        }

        // Look up the saved manual server from session list, custom servers, or cached community list.
        val savedProfile = findProfileById(selectedId, prefs, includeCommunityServers)
        if (savedProfile == null) {
            return@withContext ResolvedTestServer(server = cloudflareProfile)
        }

        // Quick reachability probe (1 ping, 2s timeout) to ensure saved server is reachable now.
        val reachability = healthChecker.probeServer(
            server = savedProfile,
            probeCount = 1,
            timeoutMs = SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS,
        )
        if (reachability.isHealthy) {
            ResolvedTestServer(server = savedProfile)
        } else {
            ResolvedTestServer(
                server = cloudflareProfile,
                fellBackFromServerName = savedProfile.name,
            )
        }
    }

    /**
     * Records the discovered Cloudflare edge location from a live `cf-ray` header.
     */
    fun updateCloudflareLocation(location: String?) {
        val clean = location?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (cloudflareProfile.location == clean) return
        val updated = cloudflareProfile.copy(location = clean)
        cloudflareProfile = updated
        _selectorState.update { state ->
            val updatedCandidates = state.candidates.map { item ->
                if (item.profile.kind == ServerKind.CLOUDFLARE) {
                    item.copy(profile = updated)
                } else {
                    item
                }
            }
            val updatedAuto = if (state.autoFastestServer?.kind == ServerKind.CLOUDFLARE) {
                updated
            } else {
                state.autoFastestServer
            }
            state.copy(
                candidates = updatedCandidates,
                autoFastestServer = updatedAuto,
            )
        }
    }

    /**
     * Called when the "Include community servers" setting changes so the next selector open or
     * Auto test re-evaluates the candidate pool cleanly.
     */
    fun onIncludeCommunitySettingChanged(includeCommunityServers: Boolean) {
        if (lastProbedIncludeCommunity != null && lastProbedIncludeCommunity != includeCommunityServers) {
            lastProbedIncludeCommunity = null
            _selectorState.update { state ->
                val filtered = if (includeCommunityServers) {
                    state.candidates
                } else {
                    state.candidates.filterNot { it.profile.kind == ServerKind.COMMUNITY }
                }
                val fastest = filtered.firstOrNull { it.status == ServerProbeStatus.HEALTHY }
                state.copy(
                    candidates = filtered,
                    autoFastestServer = fastest?.profile,
                    autoFastestPingMs = fastest?.medianPingMs,
                    hasProbedThisSession = false,
                    communityLoadError = false,
                )
            }
        }
    }

    private suspend fun runCandidateFetchAndProbe(
        includeCommunityServers: Boolean,
        forceFetchCommunity: Boolean,
    ) {
        val prefs = readPrefsSafely()
        val customServers = parseCustomServersJson(prefs[Keys.CUSTOM_SERVERS_JSON])

        _selectorState.update { state ->
            state.copy(
                isProbing = true,
                communityLoadError = false,
            )
        }

        var communityLoadError = false
        val communityServers: List<ServerProfile> = if (includeCommunityServers) {
            val (servers, hadError) = loadCommunityServers(
                prefs = prefs,
                forceFetch = forceFetchCommunity,
            )
            communityLoadError = hadError
            servers
        } else {
            emptyList()
        }

        val allCandidates = buildList {
            add(cloudflareProfile)
            addAll(customServers)
            addAll(communityServers)
        }.distinctBy { it.id }

        // Initialize progressive list showing all candidates as probing.
        val workingMap = LinkedHashMap<String, ServerCandidateState>(allCandidates.size)
        allCandidates.forEach { profile ->
            workingMap[profile.id] = ServerCandidateState(
                profile = profile,
                status = ServerProbeStatus.PROBING,
                medianPingMs = null,
            )
        }

        _selectorState.update { state ->
            state.copy(
                candidates = sortCandidateStates(workingMap.values.toList()),
                isProbing = true,
                communityLoadError = communityLoadError,
            )
        }

        val outcomes = ArrayList<ServerProbeOutcome>(allCandidates.size)
        try {
            healthChecker.probeCandidatesProgressive(
                candidates = allCandidates,
                maxConcurrency = SpeedTestConstants.SERVER_HEALTH_CONCURRENCY,
                probeCount = SpeedTestConstants.SERVER_HEALTH_PROBES,
                timeoutMs = SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS,
            ).collect { outcome ->
                outcomes.add(outcome)
                if (outcome.profile.kind == ServerKind.CLOUDFLARE && outcome.profile.location.isNotBlank()) {
                    cloudflareProfile = outcome.profile
                }
                workingMap[outcome.profile.id] = ServerCandidateState(
                    profile = outcome.profile,
                    status = if (outcome.isHealthy) ServerProbeStatus.HEALTHY else ServerProbeStatus.UNAVAILABLE,
                    medianPingMs = outcome.medianPingMs,
                )

                val sorted = sortCandidateStates(workingMap.values.toList())
                val best = sorted.firstOrNull { it.status == ServerProbeStatus.HEALTHY }
                _selectorState.update { state ->
                    state.copy(
                        candidates = sorted,
                        autoFastestServer = best?.profile ?: cloudflareProfile,
                        autoFastestPingMs = best?.medianPingMs,
                        communityLoadError = communityLoadError,
                    )
                }
            }

            val fastestProfile = ServerHealthChecker.selectAutoFastest(
                outcomes = outcomes,
                fallbackCloudflare = cloudflareProfile,
            )
            val fastestPing = outcomes.firstOrNull { it.profile.id == fastestProfile.id }?.medianPingMs
            lastProbedIncludeCommunity = includeCommunityServers
            _selectorState.update { state ->
                state.copy(
                    candidates = sortCandidateStates(workingMap.values.toList()),
                    autoFastestServer = fastestProfile,
                    autoFastestPingMs = fastestPing,
                    isProbing = false,
                    hasProbedThisSession = true,
                    communityLoadError = communityLoadError,
                )
            }
        } catch (ce: CancellationException) {
            _selectorState.update { it.copy(isProbing = false) }
            throw ce
        } catch (_: Throwable) {
            _selectorState.update { state ->
                state.copy(
                    isProbing = false,
                    autoFastestServer = state.autoFastestServer ?: cloudflareProfile,
                )
            }
        }
    }

    private suspend fun loadCommunityServers(
        prefs: Preferences,
        forceFetch: Boolean,
    ): Pair<List<ServerProfile>, Boolean> {
        val cachedJson = prefs[Keys.COMMUNITY_CACHE_JSON].orEmpty()
        val cachedTimestamp = prefs[Keys.COMMUNITY_CACHE_TIME_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        val isCacheFresh = cachedJson.isNotBlank() &&
            (nowMs - cachedTimestamp) in 0L..SpeedTestConstants.COMMUNITY_CACHE_TTL_MS

        if (!forceFetch && isCacheFresh) {
            val cachedParsed = LibreSpeedServerParser.parseCommunityServers(cachedJson)
            if (cachedParsed.isNotEmpty()) {
                return cachedParsed to false
            }
        }

        val fetchedJson = fetchCommunityFeedJson()
        if (fetchedJson != null) {
            val parsed = LibreSpeedServerParser.parseCommunityServers(fetchedJson)
            if (parsed.isNotEmpty()) {
                runCatching {
                    dataStore.edit { editPrefs ->
                        editPrefs[Keys.COMMUNITY_CACHE_JSON] = fetchedJson
                        editPrefs[Keys.COMMUNITY_CACHE_TIME_MS] = System.currentTimeMillis()
                    }
                }
                return parsed to false
            }
        }

        // Fall back to stale cache if available, otherwise report load error (Cloudflare still works)
        val staleParsed = LibreSpeedServerParser.parseCommunityServers(cachedJson)
        return if (staleParsed.isNotEmpty()) {
            staleParsed to false
        } else {
            emptyList<ServerProfile>() to true
        }
    }

    private suspend fun fetchCommunityFeedJson(): String? = withContext(Dispatchers.IO) {
        val validatedUrl = ServerUrlValidator.validateHttpsUrl(SpeedTestConstants.COMMUNITY_SERVERS_URL)
            ?: return@withContext null
        val request = Request.Builder()
            .url(validatedUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            feedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                ServerUrlValidator.readBoundedUtf8(body, ServerUrlValidator.MAX_COMMUNITY_LIST_BYTES)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun findProfileById(
        id: String,
        prefs: Preferences,
        includeCommunityServers: Boolean,
    ): ServerProfile? {
        _selectorState.value.candidates.firstOrNull { it.profile.id == id }?.profile?.let {
            return it
        }
        parseCustomServersJson(prefs[Keys.CUSTOM_SERVERS_JSON])
            .firstOrNull { it.id == id }
            ?.let { return it }
        val cachedCommunity = LibreSpeedServerParser.parseCommunityServers(
            prefs[Keys.COMMUNITY_CACHE_JSON].orEmpty(),
        )
        cachedCommunity.firstOrNull { it.id == id }?.let { return it }

        if (includeCommunityServers) {
            val (fetched, _) = loadCommunityServers(prefs = prefs, forceFetch = true)
            fetched.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    private fun mergeLocalInventory(
        selectedId: String,
        customServers: List<ServerProfile>,
        cachedCommunity: List<ServerProfile>,
    ) {
        _selectorState.update { state ->
            val existingById = state.candidates.associateBy { it.profile.id }
            val merged = LinkedHashMap<String, ServerCandidateState>()
            merged[cloudflareProfile.id] = existingById[cloudflareProfile.id]?.copy(
                profile = cloudflareProfile,
            ) ?: ServerCandidateState(profile = cloudflareProfile)

            customServers.forEach { custom ->
                merged[custom.id] = existingById[custom.id]?.copy(profile = custom)
                    ?: ServerCandidateState(profile = custom)
            }
            // Keep existing community entries if already probed, or add cached ones silently without probing
            val existingCommunity = state.candidates.filter { it.profile.kind == ServerKind.COMMUNITY }
            if (existingCommunity.isNotEmpty()) {
                existingCommunity.forEach { merged[it.profile.id] = it }
            } else {
                cachedCommunity.forEach { comm ->
                    merged[comm.id] = ServerCandidateState(profile = comm)
                }
            }
            state.copy(
                selectedServerId = selectedId,
                candidates = sortCandidateStates(merged.values.toList()),
            )
        }
    }

    private suspend fun readPrefsSafely(): Preferences = dataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .first()

    companion object {
        private fun createFeedClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(SafeServerDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SameHostHttpsRedirectInterceptor())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cache(null)
            .build()

        internal fun sortCandidateStates(
            items: List<ServerCandidateState>,
        ): List<ServerCandidateState> = items.sortedWith(
            compareBy<ServerCandidateState> {
                when (it.status) {
                    ServerProbeStatus.HEALTHY -> 0
                    ServerProbeStatus.PROBING -> 1
                    ServerProbeStatus.PENDING -> 2
                    ServerProbeStatus.UNAVAILABLE -> 3
                }
            }
                .thenBy { it.medianPingMs ?: Double.MAX_VALUE }
                .thenBy { if (it.profile.kind == ServerKind.CLOUDFLARE) 0 else 1 }
                .thenBy { it.profile.name.lowercase() },
        )

        internal fun serializeCustomServers(servers: List<ServerProfile>): String {
            val array = JSONArray()
            servers.forEach { server ->
                val obj = JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("location", server.location)
                    .put("baseUrl", server.baseUrl)
                    .put("downloadBaseUrl", server.downloadBaseUrl)
                    .put("uploadBaseUrl", server.uploadBaseUrl)
                    .put("pingBaseUrl", server.pingBaseUrl)
                if (!server.getIpBaseUrl.isNullOrBlank()) {
                    obj.put("getIpBaseUrl", server.getIpBaseUrl)
                }
                array.put(obj)
            }
            return array.toString()
        }

        internal fun parseCustomServersJson(rawJson: String?): List<ServerProfile> {
            val text = rawJson?.trim().orEmpty()
            if (text.isEmpty()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            val out = ArrayList<ServerProfile>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
                val name = obj.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
                val location = obj.optString("location").trim()
                val baseUrl = ServerUrlValidator.validateHttpsUrl(obj.optString("baseUrl")) ?: continue
                val dlUrl = ServerUrlValidator.validateHttpsUrl(obj.optString("downloadBaseUrl")) ?: continue
                val ulUrl = ServerUrlValidator.validateHttpsUrl(obj.optString("uploadBaseUrl")) ?: continue
                val pingUrl = ServerUrlValidator.validateHttpsUrl(obj.optString("pingBaseUrl")) ?: continue
                val getIpUrl = obj.optString("getIpBaseUrl").trim().takeIf { it.isNotEmpty() }
                    ?.let { ServerUrlValidator.validateHttpsUrl(it) }

                out.add(
                    ServerProfile(
                        id = id,
                        name = name,
                        location = location,
                        kind = ServerKind.CUSTOM,
                        baseUrl = baseUrl,
                        downloadBaseUrl = dlUrl,
                        uploadBaseUrl = ulUrl,
                        pingBaseUrl = pingUrl,
                        getIpBaseUrl = getIpUrl,
                    ),
                )
            }
            return out
        }
    }
}
