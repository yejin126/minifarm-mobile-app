package com.example.tiny2.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tiny2.data.entities.CntDefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CntDefDao {
    @Query("SELECT * FROM cnt_def WHERE ae = :ae ORDER BY type, remote")
    fun observeByAe(ae: String): Flow<List<CntDefEntity>>

    @Query("SELECT * FROM cnt_def WHERE ae = :ae AND type = 'sensor' ORDER BY remote")
    fun observeSensors(ae: String): Flow<List<CntDefEntity>>

    @Query("""
    SELECT * FROM cnt_def 
    WHERE ae = :ae AND type IN ('act','actuator') 
    ORDER BY remote
""")
    fun observeActuators(ae: String): Flow<List<CntDefEntity>>

    @Query("DELETE FROM cnt_def WHERE ae = :ae")
    suspend fun deleteByAe(ae: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<CntDefEntity>)
}