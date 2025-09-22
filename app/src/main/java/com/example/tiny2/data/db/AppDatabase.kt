package com.example.tiny2.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.tiny2.data.dao.CntDefDao
import com.example.tiny2.data.entities.CntDefEntity
import com.example.tiny2.data.dao.SensorSampleDao
import com.example.tiny2.data.entities.SensorSampleEntity

@Database(entities = [CntDefEntity::class, SensorSampleEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cntDefDao(): CntDefDao
    abstract fun sensorSampleDao(): SensorSampleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tiny.db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}