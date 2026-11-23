package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TestResultDao {

    @Query("SELECT * FROM test_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<TestResultEntity>>

    @Query("SELECT * FROM test_results WHERE id = :id LIMIT 1")
    fun getResultById(id: Long): Flow<TestResultEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResult(entity: TestResultEntity): Long

    @Query("DELETE FROM test_results WHERE id = :id")
    suspend fun deleteResultById(id: Long)

    @Query("DELETE FROM test_results")
    suspend fun clearAllResults()
}
