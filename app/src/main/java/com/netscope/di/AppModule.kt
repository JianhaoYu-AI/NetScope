package com.netscope.di

import android.content.Context
import androidx.room.Room
import com.netscope.core.util.Clock
import com.netscope.core.util.EvidenceIdGenerator
import com.netscope.core.util.WallClock
import com.netscope.data.store.DiagnosisDao
import com.netscope.data.store.DiagnosisDatabase
import com.netscope.data.store.DiagnosisRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 时间源。全局唯一，保证证据时间戳与实验归档时间线可对齐。 */
    @Provides
    @Singleton
    fun provideClock(): Clock = WallClock()

    


    @Provides
    @Singleton
    fun provideEvidenceIdGenerator(): EvidenceIdGenerator = EvidenceIdGenerator()

    /**
     * JSON 实例。落库、实验归档、模型输出解析三处共用同一份配置。
     *
     * - `ignoreUnknownKeys = true`：允许「只追加字段」的演进策略成立——
     *   旧版本读新数据不会崩，这是冻结模型后仍能安全迭代的前提。
     * - `encodeDefaults = true`：默认值也要落盘，避免「字段缺失」与「值为默认」
     *   在归档数据里无法区分。
     * - `prettyPrint = true`：实验归档的 JSON 需要人工审阅与粘贴进设计文档。
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
    }

    


    @Provides
    @Singleton
    fun provideDiagnosisDatabase(
        @ApplicationContext context: Context,
    ): DiagnosisDatabase = Room.databaseBuilder(
        context,
        DiagnosisDatabase::class.java,
        DiagnosisDatabase.DATABASE_NAME,
    ).fallbackToDestructiveMigration().build()

    @Provides
    @Singleton
    fun provideDiagnosisDao(database: DiagnosisDatabase): DiagnosisDao = database.diagnosisDao()
}
