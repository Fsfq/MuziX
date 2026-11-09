package com.example.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// медиа-сессия
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val exo = PlayerHub.player(this)
        val queueAwarePlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                PlayerHub.commandHandler?.onNext()
            }

            override fun seekToNextMediaItem() {
                PlayerHub.commandHandler?.onNext()
            }

            override fun seekToPrevious() {
                PlayerHub.commandHandler?.onPrevious()
            }

            override fun seekToPreviousMediaItem() {
                PlayerHub.commandHandler?.onPrevious()
            }
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaSession.Builder(this, queueAwarePlayer)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .setCustomLayout(buildLayout(PlayerHub.favoriteState))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build()
                .also { it.setSmallIcon(R.drawable.ic_notification_logo) }
        )

        PlayerHub.layoutRefresh = {
            session?.setCustomLayout(buildLayout(PlayerHub.favoriteState))
        }

        val prefs = getSharedPreferences("musicx_settings", MODE_PRIVATE)
        if (prefs.getBoolean("dynamic_island_enabled", false)) {
            com.example.island.DynamicIslandService.start(this)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlayerHub.layoutRefresh = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun buildLayout(isFavorite: Boolean): ImmutableList<CommandButton> {
        val favorite = CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(if (isFavorite) "В избранном" else "Нравится")
            .setCustomIconResId(
                if (isFavorite) R.drawable.ic_notification_favorite else R.drawable.ic_notification_favorite_border
            )
            .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
            .build()
        val close = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("Закрыть")
            .setCustomIconResId(R.drawable.ic_notification_close)
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .build()
        return ImmutableList.of(favorite, close)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_FAVORITE -> PlayerHub.commandHandler?.onToggleFavorite()
                ACTION_CLOSE -> PlayerHub.commandHandler?.onStop()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return super.onMediaButtonEvent(session, controllerInfo, intent)
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return true
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    PlayerHub.commandHandler?.onNext()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    PlayerHub.commandHandler?.onPrevious()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    PlayerHub.commandHandler?.onPlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    PlayerHub.commandHandler?.onPlayPause()
                    return true
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    companion object {
        const val CHANNEL_ID = "muzix_playback_channel_v6"
        const val ACTION_FAVORITE = "com.example.muzix.FAVORITE"
        const val ACTION_CLOSE = "com.example.muzix.CLOSE"
    }
}
