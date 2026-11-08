package com.careclose.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.careclose.core.database.dao.CareEventDao
import com.careclose.core.database.dao.MediaCacheDao
import com.careclose.core.database.dao.PlanDao
import com.careclose.core.database.dao.PresenceDao
import com.careclose.core.database.entity.CareEventEntity
import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.entity.PlanEntity
import com.careclose.core.database.entity.PresenceEntity
import com.careclose.core.database.entity.SyncState

class CareCloseTypeConverters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = SyncState.valueOf(value)
}

@Database(
    entities = [
        PlanEntity::class,
        CareEventEntity::class,
        PresenceEntity::class,
        MediaCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(CareCloseTypeConverters::class)
abstract class CareCloseDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun careEventDao(): CareEventDao
    abstract fun presenceDao(): PresenceDao
    abstract fun mediaCacheDao(): MediaCacheDao

    companion object {
        private const val DB_NAME = "careclose.db"

        fun build(context: Context, dbName: String = DB_NAME): CareCloseDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CareCloseDatabase::class.java,
                dbName,
            ).build()
        }
    }
}

