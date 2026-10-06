package com.netscope.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface DiagnosisDao {

    /** 新增一条诊断记录。同 id 重复插入会被忽略——这是防双写保护。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: DiagnosisRecord): Long

    /** 取最近一条记录。UI 启动时加载，用于 V4 验收。 */
    @Query("SELECT * FROM diagnosis_records ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(): DiagnosisRecord?

    
    @Query("SELECT COUNT(*) FROM diagnosis_records")
    suspend fun count(): Int
}