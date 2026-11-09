package com.example.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.audio.PlayerHub

// плитка управления
class PlayPauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val handler = PlayerHub.commandHandler
        if (handler != null) {
            handler.onPlayPause()
            refreshTile()
        } else {
            openApp()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val player = PlayerHub.peek()
        tile.state = when {
            player == null -> Tile.STATE_INACTIVE
            player.playWhenReady -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Muzix"
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
