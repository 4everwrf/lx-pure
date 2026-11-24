package com.lxpure.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add CASCADE ForeignKey(playlistId -> playlists.id) and trackId index while preserving all valid rows
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (
                        `playlistId` INTEGER NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `trackJson` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`, `trackId`),
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_tracks_new` (`playlistId`, `trackId`, `trackJson`, `position`, `addedAt`)
                    SELECT pt.`playlistId`, pt.`trackId`, pt.`trackJson`, pt.`position`, pt.`addedAt`
                    FROM `playlist_tracks` pt
                    WHERE pt.`playlistId` IN (SELECT `id` FROM `playlists`)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `playlist_tracks`")
                db.execSQL("ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "lx_pure.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
