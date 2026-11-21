package com.novaplay.app.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.novaplay.app.R
import com.novaplay.app.data.Library

/** The search box with the user's recent searches. Pasting a link of the site opens it directly. */
class SearchScreen(activity: MainActivity, private val initial: String = "") : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val clear = Ui.iconButton(ctx, R.drawable.ic_close, "Clear", Ui.textDim(ctx), 40, 10)
    private val history = LinearLayout(ctx)

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        val back = Ui.iconButton(ctx, R.drawable.ic_back, "Back", Ui.text(ctx))
        back.setOnClickListener { activity.pop() }
        bar.addView(back)

        val field = LinearLayout(ctx)
        field.orientation = LinearLayout.HORIZONTAL
        field.gravity = Gravity.CENTER_VERTICAL
        field.background = Ui.rounded(Ui.surfaceVariant(ctx), Ui.dpf(ctx, 20f))
        field.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 4), 0)
        input.hint = "Search or paste a link"
        input.setHintTextColor(Ui.textDim(ctx))
        input.setTextColor(Ui.text(ctx))
        input.textSize = 16f
        input.background = null
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit(input.text.toString())
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                showHistory()
            }
        })
        field.addView(input, Ui.lp(0, Ui.dp(ctx, 40)).also { it.weight = 1f })
        clear.visibility = View.GONE
        clear.setOnClickListener { input.setText("") }
        field.addView(clear)
        bar.addView(field, Ui.lp(0, Ui.WRAP).also { it.weight = 1f })

        val go = Ui.iconButton(ctx, R.drawable.ic_search, "Search", Ui.text(ctx))
        go.setOnClickListener { submit(input.text.toString()) }
        bar.addView(go)
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        if (initial.isNotEmpty()) {
            input.setText(initial)
            input.setSelection(initial.length)
        }

        history.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ctx)
        scroll.addView(history, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
    }

    private fun showHistory() {
        history.removeAllViews()
        val typed = input.text.toString().trim()
        val all = Library.searchList()
        val shown = if (typed.isEmpty()) all else all.filter { it.contains(typed, true) }
        for (q in shown) history.addView(row(q))
        if (typed.isEmpty() && all.isNotEmpty()) {
            val clearAll = Ui.textView(ctx, 14f, Ui.accent(ctx), true)
            clearAll.text = "Clear search history"
            clearAll.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 16), Ui.dp(ctx, 20), Ui.dp(ctx, 16))
            clearAll.setOnClickListener {
                Dialogs.confirm(ctx, "Clear search history?", "Your recent searches will be removed.", "Clear") {
                    Library.clearSearches()
                    showHistory()
                }
            }
            history.addView(clearAll, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
    }

    private fun row(q: String): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.ripple(ctx, null)
        row.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 4), 0)
        val icon = ImageView(ctx)
        icon.setImageResource(R.drawable.ic_history)
        icon.setColorFilter(Ui.textDim(ctx))
        row.addView(icon, Ui.lp(Ui.dp(ctx, 22), Ui.dp(ctx, 22)))
        val text = Ui.textView(ctx, 16f, Ui.text(ctx), false, 1)
        text.text = q
        row.addView(text, Ui.lp(0, Ui.WRAP).also {
            it.weight = 1f
            it.leftMargin = Ui.dp(ctx, 16)
        })
        val remove = Ui.iconButton(ctx, R.drawable.ic_close, "Remove", Ui.textDim(ctx), 44, 12)
        remove.setOnClickListener {
            Library.removeSearch(q)
            showHistory()
        }
        row.addView(remove)
        row.setOnClickListener { submit(q) }
        row.minimumHeight = Ui.dp(ctx, 48)
        return row
    }

    private fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        activity.search(q)
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onShow() {
        showHistory()
        input.requestFocus()
        input.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, 0)
        }
    }

    override fun onHide() {
        hideKeyboard()
    }
}
