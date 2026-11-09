package com.example.data

import kotlinx.coroutines.flow.Flow

class MusicRepository(private val musicDao: MusicDao) {
    val allPlaylists: Flow<List<PlaylistEntity>> = musicDao.getAllPlaylists()

    fun getTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>> {
        return musicDao.getTracksForPlaylist(playlistId)
    }

    suspend fun createPlaylist(name: String): Long {
        return musicDao.insertPlaylist(PlaylistEntity(name = name))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deleteTracksForPlaylist(playlistId)
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        val entity = PlaylistTrackEntity(
            playlistId = playlistId,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            duration = track.duration,
            path = track.path,
            isLocal = track.isLocal,
            albumArtSeed = track.albumArtSeed
        )
        musicDao.insertPlaylistTrack(entity)
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        musicDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun getSyncedLyrics(trackId: String): SyncedLyricsEntity? {
        return musicDao.getSyncedLyrics(trackId)
    }

    suspend fun saveSyncedLyrics(trackId: String, lyricsText: String) {
        musicDao.insertSyncedLyrics(SyncedLyricsEntity(trackId, lyricsText))
    }
}
