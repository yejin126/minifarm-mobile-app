package com.example.tiny2.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_samples")
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,   // 자동 증가 PK
    val ae: String,                                     // AE 이름 (예: TinyFarm)
    val remote: String,                                 // 센서 key (예: Temperature / Humidity ...)
    val ts: Long,                                       // 타임스탬프 (ms)
    val value: Float                                    // 센서 값
)