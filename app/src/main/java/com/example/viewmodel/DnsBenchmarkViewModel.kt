package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.engine.tools.DnsBenchmarkEngine
import com.example.engine.tools.DnsBenchmarkEvent
import com.example.engine.tools.DnsResolverId
import com.example.engine.tools.DnsResolverResult
import com.example.engine.tools.DnsResolverStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DnsBenchmarkUiState(
    val isRunning: Boolean = false,
    val hasCompleted: Boolean = false,
    val activeResolverId: DnsResolverId? = null,
    val activeResolverIndex: Int = 0,
    val totalResolvers: Int = 4,
    val results: List<DnsResolverResult> = emptyList(),
) {
    /**
     * Ranked view: completed reachable resolvers sorted fastest-first, followed by
     * running/pending resolvers in order, and unreachable resolvers at the bottom.
     */
    val rankedResults: List<DnsResolverResult>
        get() = rankDnsResults(results)

    val fastestResolverId: DnsResolverId?
        get() = rankedResults
            .firstOrNull { it.status == DnsResolverStatus.COMPLETED && it.medianMs != null }
            ?.id

    val maxMedianMs: Double
        get() = results
            .mapNotNull { it.medianMs }
            .filter { it.isFinite() && it > 0.0 }
            .maxOrNull() ?: 1.0
}

internal fun rankDnsResults(results: List<DnsResolverResult>): List<DnsResolverResult> {
    val completedReachable = results
        .filter { it.status == DnsResolverStatus.COMPLETED && it.medianMs != null }
        .sortedBy { it.medianMs }
    val runningOrPending = results.filter {
        it.status == DnsResolverStatus.RUNNING || it.status == DnsResolverStatus.PENDING
    }
    val unreachable = results.filter {
        it.status == DnsResolverStatus.UNREACHABLE ||
            (it.status == DnsResolverStatus.COMPLETED && it.medianMs == null)
    }
    return completedReachable + runningOrPending + unreachable
}

/**
 * ViewModel scoped strictly to the DNS benchmark screen.
 * Nothing runs until the user taps Run, and navigating away cancels any active benchmark.
 */
class DnsBenchmarkViewModel(
    private val engine: DnsBenchmarkEngine = DnsBenchmarkEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DnsBenchmarkUiState(
            totalResolvers = engine.resolvers.size,
            results = initialPendingResults(),
        ),
    )
    val uiState: StateFlow<DnsBenchmarkUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    fun onRunClicked() {
        if (_uiState.value.isRunning) return
        activeJob?.cancel()

        _uiState.value = DnsBenchmarkUiState(
            isRunning = true,
            hasCompleted = false,
            activeResolverId = null,
            activeResolverIndex = 0,
            totalResolvers = engine.resolvers.size,
            results = initialPendingResults(),
        )

        activeJob = viewModelScope.launch {
            engine.runBenchmark().collect { event ->
                when (event) {
                    is DnsBenchmarkEvent.ResolverStarted -> {
                        _uiState.update { state ->
                            state.copy(
                                activeResolverId = event.resolverId,
                                activeResolverIndex = event.resolverIndex,
                                totalResolvers = event.totalResolvers,
                                results = state.results.map { item ->
                                    if (item.id == event.resolverId) {
                                        item.copy(status = DnsResolverStatus.RUNNING)
                                    } else {
                                        item
                                    }
                                },
                            )
                        }
                    }

                    is DnsBenchmarkEvent.ResolverCompleted -> {
                        _uiState.update { state ->
                            state.copy(
                                results = state.results.map { item ->
                                    if (item.id == event.result.id) event.result else item
                                },
                            )
                        }
                    }

                    DnsBenchmarkEvent.Finished -> {
                        _uiState.update { state ->
                            state.copy(
                                isRunning = false,
                                hasCompleted = true,
                                activeResolverId = null,
                            )
                        }
                        activeJob = null
                    }
                }
            }
        }
    }

    fun onCancelClicked() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { state ->
            state.copy(
                isRunning = false,
                activeResolverId = null,
                results = state.results.map { item ->
                    if (item.status == DnsResolverStatus.RUNNING) {
                        item.copy(status = DnsResolverStatus.PENDING)
                    } else {
                        item
                    }
                },
            )
        }
    }

    private fun initialPendingResults(): List<DnsResolverResult> = engine.resolvers.map { spec ->
        DnsResolverResult(
            id = spec.id,
            serverIp = spec.serverIp,
            status = DnsResolverStatus.PENDING,
        )
    }

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
        super.onCleared()
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DnsBenchmarkViewModel() as T
            }
        }
    }
}
