package com.novaplay.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.novaplay.app.R
import com.novaplay.app.data.DlState
import com.novaplay.app.data.Library
import com.novaplay.app.data.Video
import com.novaplay.app.download.DownloadCenter
import com.novaplay.app.player.Playback

/** The "more" menu of a video: queue, save, download, share ... */
object VideoMenu {
    /** An extra, screen-specific entry such as "Remove from history". */
    class Extra(val icon: Int, val label: String, val action: () -> Unit)

    fun show(
        ctx: Context,
        video: Video,
        extras: List<Extra> = emptyList(),
        openListing: ((url: String, title: String) -> Unit)? = null
    ) {
        val s = BottomSheet(ctx, video.title)
        s.add(R.drawable.ic_next, "Play next") { Playback.playNext(video) }
        s.add(R.drawable.ic_queue, "Add to queue") { Playback.addToQueue(video) }

        val later = Library.isWatchLater(video.id)
        s.add(R.drawable.ic_watch_later, if (later) "Remove from Watch later" else "Save to Watch later") {
            val now = Library.toggleWatchLater(video)
            Dialogs.toast(ctx, if (now) "Saved to Watch later" else "Removed from Watch later")
        }
        s.add(R.drawable.ic_playlist_add, "Save to playlist") { Dialogs.saveToPlaylist(ctx, video) }

        val liked = Library.isLiked(video.id)
        s.add(R.drawable.ic_thumb_up, if (liked) "Remove like" else "Like") {
            val now = Library.toggleLike(video)
            Dialogs.toast(ctx, if (now) "Added to Liked videos" else "Removed from Liked videos")
        }

        val dl = Library.downloadFor(video.id)
        when {
            dl == null -> s.add(R.drawable.ic_download, "Download") { Dialogs.download(ctx, video) }
            dl.state == DlState.DONE -> s.add(R.drawable.ic_delete, "Delete download") {
                Dialogs.confirm(ctx, "Delete download?", video.title, "Delete") { DownloadCenter.remove(dl.id) }
            }
            dl.state == DlState.RUNNING || dl.state == DlState.QUEUED -> {
                s.add(R.drawable.ic_pause, "Pause download") { DownloadCenter.pause(dl.id) }
                s.add(R.drawable.ic_close, "Cancel download") { DownloadCenter.remove(dl.id) }
            }
            else -> {
                s.add(R.drawable.ic_download, "Resume download") { DownloadCenter.resume(dl.id) }
                s.add(R.drawable.ic_close, "Cancel download") { DownloadCenter.remove(dl.id) }
            }
        }

        if (video.uploaderUrl.isNotEmpty() && openListing != null) {
            val name = video.uploader.ifEmpty { "channel" }
            s.add(R.drawable.ic_subs, "Go to $name") { openListing(video.uploaderUrl, video.uploader) }
        }
        s.add(R.drawable.ic_share, "Share") { share(ctx, video) }
        s.add(R.drawable.ic_copy, "Copy link") { copy(ctx, video.url, "Link copied") }
        s.add(R.drawable.ic_copy, "Copy title") { copy(ctx, video.title, "Title copied") }
        s.add(R.drawable.ic_browser, "Open in browser") { BrowserActivity.open(ctx, video.url) }
        for (e in extras) s.add(e.icon, e.label, e.action)
        s.show()
    }

    /** Puts [text] on the clipboard and says so with [message]. */
    fun copy(ctx: Context, text: String, message: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NovaPlay", text))
        Dialogs.toast(ctx, message)
    }

    fun share(ctx: Context, video: Video) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, video.title)
        send.putExtra(Intent.EXTRA_TEXT, video.url)
        ctx.startActivity(Intent.createChooser(send, "Share"))
    }
}
