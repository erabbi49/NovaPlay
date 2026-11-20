package com.novaplay.app.ui

import android.content.Context
import com.novaplay.app.data.Video
import com.novaplay.app.player.PlayerActivity

/** "Play all" / "Shuffle" for any list of videos: it becomes the queue. */
object PlayAll {
    fun start(ctx: Context, videos: List<Video>, shuffle: Boolean) {
        if (videos.isEmpty()) return
        val queue = if (shuffle) videos.shuffled() else ArrayList(videos)
        PlayerActivity.playAll(ctx, queue, 0)
    }
}
