package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_tracks")
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val path: String,
    val isLocal: Boolean,
    val albumArtSeed: Int
)

@Entity(tableName = "synced_lyrics")
data class SyncedLyricsEntity(
    @PrimaryKey val trackId: String, // id трека
    val lyricsJson: String // текст песни
)

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

fun parseLrc(lrcText: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)")
    lrcText.lines().forEach { line ->
        val match = regex.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.isNotEmpty()) {
                val parsed = msPart.toLong()
                when (msPart.length) {
                    1 -> parsed * 100
                    2 -> parsed * 10
                    else -> parsed
                }
            } else 0L
            val text = match.groupValues[4].trim()
            val timestamp = (min * 60 + sec) * 1000 + ms
            lines.add(LyricLine(timestamp, text))
        }
    }
    return lines.sortedBy { it.timestampMs }
}
