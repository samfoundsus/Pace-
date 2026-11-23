package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppContainer
import com.example.data.local.HistoryRepository
import com.example.data.models.TestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the History list and History Detail screens.
 */
class HistoryViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<TestResult>> = historyRepository.allResults
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    fun observeResult(id: Long): Flow<TestResult?> = historyRepository.observeResultById(id)

    fun deleteResult(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteResult(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = AppContainer.getInstance(context.applicationContext)
                return HistoryViewModel(container.historyRepository) as T
            }
        }
    }
}
