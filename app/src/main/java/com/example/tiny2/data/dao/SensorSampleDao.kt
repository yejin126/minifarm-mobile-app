package com.example.tiny2.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tiny2.data.entities.SensorSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorSampleDao {
    @Insert
    fun insert(e: SensorSampleEntity)

    // 최근 N개 (기본 20개) — 차트용
    @Query("""
        SELECT * FROM sensor_samples
        WHERE ae = :ae AND remote = :remote
        ORDER BY ts DESC
        LIMIT :limit
    """)
    suspend fun getRecent(ae: String, remote: String, limit: Int = 20): List<SensorSampleEntity>

    // 실시간 관찰이 필요하면 Flow 버전도
    @Query("""
        SELECT * FROM sensor_samples
        WHERE ae = :ae AND remote = :remote
        ORDER BY ts DESC
        LIMIT :limit
    """)
    fun observeRecent(ae: String, remote: String, limit: Int = 20): Flow<List<SensorSampleEntity>>

    // 오래된 샘플 정리 (선택)
    @Query("DELETE FROM sensor_samples WHERE ae = :ae AND remote = :remote AND ts < :olderThan")
    suspend fun deleteOlderThan(ae: String, remote: String, olderThan: Long)
}