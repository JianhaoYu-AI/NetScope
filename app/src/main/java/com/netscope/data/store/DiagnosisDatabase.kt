package com.netscope.data.store

import androidx.room.Database
import androidx.room.RoomDatabase


@Database(
    entities = [DiagnosisRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class DiagnosisDatabase : RoomDatabase() {
    abstract fun diagnosisDao(): DiagnosisDao

    companion object {
        const val DATABASE_NAME = "netscope-diagnosis.db"
    }
}