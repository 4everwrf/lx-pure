package com.lxpure.music.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class MusicSource(val code: String, val displayName: String, val shortLabel: String) {
    WY("wy", "网易云音乐", "网易"),
    TX("tx", "QQ 音乐", "QQ"),
    KW("kw", "酷我音乐", "酷我"),
    KG("kg", "酷狗音乐", "酷狗"),
    MG("mg", "咪咕音乐", "咪咕"),
    SODA("soda", "汽水音乐", "汽水"),
    LOCAL("local", "本地音乐", "本地");

    companion object {
        fun fromCode(code: String?): MusicSource =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: WY
    }
}

@Serializable
enum class AudioQuality(val lxType: String, val label: String, val badge: String, val priority: Int) {
    Q128K("128k", "标准 128k", "128K", 1),
    Q320K("320k", "高品质 320k", "HQ", 2),
    FLAC("flac", "无损 FLAC", "SQ", 3),
    FLAC24("flac24bit", "Hi-Res 无损", "HR", 4);

    companion object {
        fun fromLxType(type: String?): AudioQuality =
            entries.firstOrNull { it.lxType.equals(type, ignoreCase = true) } ?: Q320K

        fun fallbackChain(preferred: AudioQuality): List<AudioQuality> {
            return entries.filter { it.priority <= preferred.priority }.sortedByDescending { it.priority }
        }
    }
}

@Serializable
data class QualityInfo(
    val type: String,
    val size: String = "",
    val hash: String? = null
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val source: MusicSource = MusicSource.WY,
    val songmid: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val albumId: String? = null,
    val albumMid: String? = null,
    val hash: String? = null,
    val strMediaMid: String? = null,
    val songId: String? = null,
    val qualities: List<QualityInfo> = emptyList(),
    val localUri: String? = null,
    val cachedFilePath: String? = null,
    @Transient val alternates: List<Track> = emptyList(),
    val artistNames: List<String> = emptyList()
) {
    val isLocal: Boolean get() = source == MusicSource.LOCAL || !localUri.isNullOrBlank()

    val formattedDuration: String
        get() {
            if (durationMs <= 0L) return ""
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }

    val bestQualityBadge: String?
        get() = when {
            isLocal -> "LOCAL"
            !cachedFilePath.isNullOrBlank() -> "离线"
            qualities.any { it.type == "flac24bit" } -> "HR"
            qualities.any { it.type == "flac" } -> "SQ"
            qualities.any { it.type == "320k" } -> "HQ"
            else -> null
        }

    /**
     * Produces the exact LX Music `musicInfo` JSON object expected by community `.js` custom source scripts.
     */
    fun toLxMusicInfoJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", title)
        put("singer", artist)
        put("albumName", album)
        put("source", source.code)
        put("interval", formattedDuration.ifBlank { null })
        // Keep numeric songmid where NetEase expects number or string
        if (source == MusicSource.WY && songmid.toLongOrNull() != null) {
            put("songmid", songmid.toLong())
        } else {
            put("songmid", songmid)
        }
        put("img", coverUrl ?: "")
        if (!albumId.isNullOrBlank()) put("albumId", albumId)
        if (!albumMid.isNullOrBlank()) put("albumMid", albumMid)
        if (!hash.isNullOrBlank()) put("hash", hash)
        if (!strMediaMid.isNullOrBlank()) put("strMediaMid", strMediaMid)
        if (!songId.isNullOrBlank()) {
            songId.toLongOrNull()?.let { put("songId", it) } ?: put("songId", songId)
        }

        val effectiveQualities = qualities.ifEmpty {
            listOf(
                QualityInfo("128k"),
                QualityInfo("320k"),
                QualityInfo("flac")
            )
        }

        put("types", buildJsonArray {
            effectiveQualities.forEach { q ->
                add(buildJsonObject {
                    put("type", q.type)
                    put("size", q.size)
                    if (!q.hash.isNullOrBlank()) put("hash", q.hash)
                })
            }
        })

        put("_types", buildJsonObject {
            effectiveQualities.forEach { q ->
                put(q.type, buildJsonObject {
                    put("size", q.size)
                    if (!q.hash.isNullOrBlank()) put("hash", q.hash)
                })
            }
        })

        // LX Music v2 meta envelope (used by newer scripts)
        put("meta", buildJsonObject {
            put("songId", songmid)
            put("albumName", album)
            put("picUrl", coverUrl ?: "")
            if (!hash.isNullOrBlank()) put("hash", hash)
            if (!strMediaMid.isNullOrBlank()) put("strMediaMid", strMediaMid)
            if (!albumMid.isNullOrBlank()) put("albumMid", albumMid)
            put("qualitys", buildJsonArray {
                effectiveQualities.forEach { q ->
                    add(buildJsonObject {
                        put("type", q.type)
                        put("size", q.size)
                        if (!q.hash.isNullOrBlank()) put("hash", q.hash)
                    })
                }
            })
            put("_qualitys", buildJsonObject {
                effectiveQualities.forEach { q ->
                    put(q.type, buildJsonObject {
                        put("size", q.size)
                        if (!q.hash.isNullOrBlank()) put("hash", q.hash)
                    })
                }
            })
        })
    }
}

@Serializable
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val author: String = "",
    val playCount: String = "",
    val description: String = "",
    val trackCount: Int = 0,
    val source: MusicSource = MusicSource.WY
)

@Serializable
data class LeaderboardItem(
    val id: String,
    val bangId: String,
    val name: String,
    val subtitle: String = "",
    val source: MusicSource,
    val accentHex: Long = 0xFF38BDF8
)

data class UserPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val description: String,
    val sourceUrl: String?,
    val trackCount: Int,
    val createdAt: Long
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

data class ParsedLyrics(
    val lines: List<LyricLine> = emptyList(),
    val hasTranslation: Boolean = false
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    fun activeIndexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val targetMs = if (positionMs > Long.MAX_VALUE - 120L) Long.MAX_VALUE else positionMs + 120L
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= targetMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        while (result > 0 && lines[result - 1].timeMs == lines[result].timeMs) {
            result--
        }
        return result
    }
}

enum class RepeatMode(val label: String) {
    LIST_LOOP("列表循环"),
    SEQUENTIAL("顺序播放"),
    SHUFFLE("随机播放"),
    SINGLE_LOOP("单曲循环");

    fun next(): RepeatMode = when (this) {
        LIST_LOOP -> SEQUENTIAL
        SEQUENTIAL -> SHUFFLE
        SHUFFLE -> SINGLE_LOOP
        SINGLE_LOOP -> LIST_LOOP
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    DARK("深曜暗黑"),
    LIGHT("纯净瓷白")
}

enum class UserApiRuntimeStatus {
    IDLE,
    INITIALIZING,
    READY,
    ERROR
}

data class UserApiSource(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
    val script: String,
    val sourceUrl: String?,
    val enabled: Boolean,
    val priority: Int,
    val status: UserApiRuntimeStatus = UserApiRuntimeStatus.IDLE,
    val supportedPlatforms: Map<String, List<String>> = emptyMap(),
    val latencyMs: Long? = null,
    val errorMessage: String? = null
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadRecord(
    val trackId: String,
    val track: Track,
    val quality: AudioQuality,
    val status: DownloadStatus,
    val progress: Float,
    val filePath: String?,
    val totalBytes: Long,
    val errorMessage: String?,
    val updatedAt: Long
)

data class AppSettings(
    val preferredQuality: AudioQuality = AudioQuality.FLAC,
    val autoFallbackSource: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fluidAmbientBackground: Boolean = true,
    val showTranslationLyrics: Boolean = true,
    val wifiOnlyHighQuality: Boolean = true
)

@Serializable
enum class QueueSourceType {
    UNKNOWN,
    FAVORITES,
    LOCAL,
    DOWNLOADS,
    HISTORY,
    USER_PLAYLIST,
    ONLINE_PLAYLIST,
    LEADERBOARD,
    SEARCH
}

@Serializable
data class PlaybackQueueSource(
    val type: QueueSourceType = QueueSourceType.UNKNOWN,
    val title: String = "",
    val userPlaylistId: Long? = null,
    val onlinePlaylist: OnlinePlaylist? = null,
    val leaderboard: LeaderboardItem? = null
)

@Serializable
data class PlaybackQueueSnapshot(
    val version: Int = 1,
    val queue: List<Track> = emptyList(),
    val queueSource: PlaybackQueueSource? = null,
    val savedAt: Long = 0L
)

@Serializable
data class PlaybackCursorSnapshot(
    val version: Int = 1,
    val currentIndex: Int = -1,
    val currentTrackId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: String = RepeatMode.LIST_LOOP.name,
    val activeQualityBadge: String? = null,
    val activeProviderName: String? = null,
    val updatedAt: Long = 0L
)
