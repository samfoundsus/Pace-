package com.example.data.local

import com.example.data.models.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository abstracting Room history storage from ViewModels and UI.
 * All database and mapping work runs strictly off the main thread on Dispatchers.IO.
 */
class HistoryRepository(
    private val dao: TestResultDao,
) {
    val allResults: Flow<List<TestResult>> = dao.getAllResults()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(Dispatchers.IO)

    fun observeResultById(id: Long): Flow<TestResult?> = dao.getResultById(id)
        .map { entity -> entity?.toDomain() }
        .flowOn(Dispatchers.IO)

    suspend fun saveResult(result: TestResult): Long = withContext(Dispatchers.IO) {
        dao.insertResult(result.toEntity())
    }

    suspend fun deleteResult(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteResultById(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAllResults()
    }
}
