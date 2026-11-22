package com.lxpure.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        HistoryEntity::class,
        UserApiEntity::class,
        DownloadEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun userApiDao(): UserApiDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "lx_pure.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
