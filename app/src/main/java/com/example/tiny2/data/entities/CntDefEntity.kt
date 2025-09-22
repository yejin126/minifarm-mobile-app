package com.example.tiny2.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cnt_def")
data class CntDefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ae: String,          // AE 이름 (예: TinyFarm)
    val remote: String,      // 서버에서 내려주는 실제 이름
    val canonical: String,   // UI에서 보여줄 이름
    val type: String,        // "sensor" or "actuator"
    val intervalMs: Long? = null
)