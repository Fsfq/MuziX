package com.example.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.LocalContext
import android.content.Intent
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.MainActivity
import com.example.R
import com.example.audio.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// обновление виджета
object WidgetBridge {
    private const val PREFS = "muzix_widget"

    suspend fun publish(context: Context, title: String, artist: String, playing: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .putBoolean("playing", playing)
            .apply()
        MuzixWidget().updateAll(context)
    }

    fun read(context: Context): Triple<String, String, Boolean> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(
            p.getString("title", null) ?: "Muzix",
            p.getString("artist", null) ?: "Ничего не играет",
            p.getBoolean("playing", false)
        )
    }
}

class MuzixWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, artist, playing) = WidgetBridge.read(context)
        provideContent {
            GlanceTheme { WidgetContent(title, artist, playing) }
        }
    }
}

@Composable
private fun WidgetContent(title: String, artist: String, playing: Boolean) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clickable(actionStartActivity(android.content.ComponentName(LocalContext.current, MainActivity::class.java))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = artist,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
            )
        }
        WidgetButton(R.drawable.ic_notification_prev, actionRunCallback<PrevAction>())
        WidgetButton(
            if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
            actionRunCallback<PlayPauseAction>()
        )
        WidgetButton(R.drawable.ic_notification_next, actionRunCallback<NextAction>())
    }
}

@Composable
private fun WidgetButton(icon: Int, action: androidx.glance.action.Action) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        modifier = GlanceModifier
            .size(40.dp)
            .padding(8.dp)
            .clickable(action)
    )
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { PlayerHub.commandHandler?.onPlayPause() }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { PlayerHub.commandHandler?.onNext() }
    }
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { PlayerHub.commandHandler?.onPrevious() }
    }
}

class MuzixWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MuzixWidget()
}
