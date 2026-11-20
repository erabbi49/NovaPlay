package com.novaplay.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.novaplay.app.data.Prefs

/**
 * Keeps playback alive in the background and gives the notification / lock screen controls.
 * The player itself lives in [Playback]; this only wraps it in a media session.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Playback.init(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, QueuePlayer(Playback.player))
            .setId("nova")
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = Playback.player
        val keepGoing = Prefs.backgroundPlay && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        if (!keepGoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** Makes Next / Previous in the notification walk the app's queue instead of ExoPlayer's one-item list. */
class QueuePlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = Playback.hasNext()
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = Playback.next()
    override fun seekToNextMediaItem() = Playback.next()
    override fun seekToPrevious() = Playback.previous()
    override fun seekToPreviousMediaItem() = Playback.previous()
}
