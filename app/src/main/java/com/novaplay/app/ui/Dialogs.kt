package com.novaplay.app.ui

import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Video
import com.novaplay.app.download.DownloadCenter

/** Small dialogs shared by several screens. */
object Dialogs {
    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun confirm(ctx: Context, title: String, message: String, positive: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One-line text input. Empty input is ignored. */
    fun prompt(ctx: Context, title: String, hint: String, initial: String, positive: String, onOk: (String) -> Unit) {
        val edit = EditText(ctx)
        edit.hint = hint
        edit.setText(initial)
        edit.setSelection(initial.length)
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT
        edit.imeOptions = EditorInfo.IME_ACTION_DONE
        val box = FrameLayout(ctx)
        val pad = Ui.dp(ctx, 20)
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0)
        box.addView(edit, Ui.frame(Ui.MATCH, Ui.WRAP))
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    /** Check the playlists the video should be in; "New playlist" creates one on the spot. */
    fun saveToPlaylist(ctx: Context, video: Video) {
        val createAndAdd = {
            prompt(ctx, "New playlist", "Playlist name", "", "Create") { name ->
                val p = Library.createPlaylist(name)
                Library.addToPlaylist(p.id, video)
                toast(ctx, "Saved to ${p.name}")
            }
        }
        val lists = Library.playlistList()
        if (lists.isEmpty()) {
            createAndAdd()
            return
        }
        val names = Array<CharSequence>(lists.size) { lists[it].name }
        val had = BooleanArray(lists.size) { i -> lists[i].videos.any { it.id == video.id } }
        val checked = had.copyOf()
        AlertDialog.Builder(ctx)
            .setTitle("Save to playlist")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                for (i in lists.indices) {
                    if (checked[i] && !had[i]) Library.addToPlaylist(lists[i].id, video)
                    if (!checked[i] && had[i]) Library.removeFromPlaylist(lists[i].id, video.id)
                }
            }
            .setNeutralButton("New playlist") { _, _ -> createAndAdd() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val heights = intArrayOf(0, 1080, 720, 480, 360, 240)

    fun qualityLabel(height: Int): String = when {
        height < 0 -> "Ask every time"
        height == 0 -> "Best available"
        else -> "${height}p"
    }

    /** Starts a download, asking for the quality first when the setting says so. */
    fun download(ctx: Context, video: Video) {
        val pref = Prefs.downloadQuality
        if (pref >= 0) {
            DownloadCenter.enqueue(video, pref)
            return
        }
        val labels = Array<CharSequence>(heights.size) { qualityLabel(heights[it]) }
        var selected = 0
        AlertDialog.Builder(ctx)
            .setTitle("Download quality")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("Download") { _, _ -> DownloadCenter.enqueue(video, heights[selected]) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
