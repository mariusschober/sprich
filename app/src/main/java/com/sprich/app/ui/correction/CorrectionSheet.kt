package com.sprich.app.ui.correction

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sprich.app.R
import com.sprich.app.input.correction.SpellingProposal

/**
 * U02 correction sheet (§9.5). Stateless view over IME-held state: the IME
 * owns target/undo/proposal (they must survive UI refreshes and diffuse with
 * the editor), this sheet only renders and forwards taps. Every mutation
 * goes through CorrectionPlan revalidation at tap time — the sheet never
 * writes to the editor itself.
 *
 * Classic Views on purpose: the sheet lives in a Dialog window owned by the
 * IME service, which has no Activity lifecycle — Compose content there needs
 * hand-wired view-tree owners and crashes without them. Plain Views need
 * none of that.
 */
class CorrectionSheetController {
    private fun emitChange() {
        try { onChange?.invoke() } catch (_: Exception) {}
    }

    /** Refresh callback, invoked on every state change (any thread). */
    var onChange: (() -> Unit)? = null

    var targetWord: String = ""
        set(value) { field = value; emitChange() }
    var replacement: String = ""
        set(value) { field = value; emitChange() }
    var listening: Boolean = false
        set(value) { field = value; emitChange() }
    var undoAvailable: Boolean = false
        set(value) { field = value; emitChange() }
    var proposal: SpellingProposal? = null
        set(value) { field = value; emitChange() }
    var rememberAvailable: Boolean = false
        set(value) { field = value; emitChange() }
    var notice: String? = null
        set(value) { field = value; emitChange() }
}

class CorrectionSheetActions(
    val onReplacementChange: (String) -> Unit,
    val onProposalTap: () -> Unit,
    val onDictate: () -> Unit,
    val onApply: () -> Unit,
    val onUndo: () -> Unit,
    val onRemember: () -> Unit,
    val onDismiss: () -> Unit,
)

class CorrectionSheetView(
    val root: View,
    val refresh: (CorrectionSheetController) -> Unit,
)

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

/** Builds the sheet and returns it with a refresh function the IME drives. */
fun buildCorrectionSheetView(
    context: Context,
    controller: CorrectionSheetController,
    actions: CorrectionSheetActions,
): CorrectionSheetView {
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = context.dp(20)
        setPadding(pad, pad, pad, pad)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = context.dp(28).toFloat()
            setColor(0xFF242529.toInt())
        }
    }

    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val title = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 18f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val close = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = context.getString(R.string.correction_close)
        setTextColor(0xFFB9B9C0.toInt())
        setOnClickListener { actions.onDismiss() }
    }
    titleRow.addView(title)
    titleRow.addView(close)
    panel.addView(titleRow)

    val listeningView = TextView(context).apply {
        setTextColor(0xFF8ECAE6.toInt())
        textSize = 13f
        text = context.getString(R.string.correction_listening)
    }
    panel.addView(listeningView)

    var suppressWatcher = false
    val replacementField = EditText(context).apply {
        hint = context.getString(R.string.correction_replace_label)
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF88888F.toInt())
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(4) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) actions.onReplacementChange(s?.toString().orEmpty())
            }
        })
    }
    panel.addView(replacementField)

    val proposalButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        setTextColor(0xFF8ECAE6.toInt())
        setOnClickListener { actions.onProposalTap() }
    }
    panel.addView(proposalButton)

    val noticeView = TextView(context).apply {
        setTextColor(0xFFB9B9C0.toInt())
        textSize = 13f
    }
    panel.addView(noticeView)

    val actionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val m = context.dp(8)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = m }
    }
    val dictateButton = Button(context).apply {
        text = context.getString(R.string.correction_dictate)
        contentDescription = context.getString(R.string.correction_dictate)
        setOnClickListener { actions.onDictate() }
    }
    val applyButton = Button(context).apply {
        text = context.getString(R.string.correction_apply)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
        }
        setOnClickListener { actions.onApply() }
    }
    val undoButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = context.getString(R.string.correction_undo)
        setTextColor(0xFFB9B9C0.toInt())
        setOnClickListener { actions.onUndo() }
    }
    actionRow.addView(dictateButton)
    actionRow.addView(applyButton)
    actionRow.addView(undoButton)
    panel.addView(actionRow)

    val rememberButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = context.getString(R.string.correction_remember)
        setTextColor(0xFF8ECAE6.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { actions.onRemember() }
    }
    panel.addView(rememberButton)

    fun refresh(c: CorrectionSheetController) {
        title.text = context.getString(R.string.correction_title, c.targetWord)
        listeningView.visibility = if (c.listening) View.VISIBLE else View.GONE
        if (replacementField.text.toString() != c.replacement) {
            suppressWatcher = true
            try {
                replacementField.setText(c.replacement)
                try { replacementField.setSelection(c.replacement.length) } catch (_: Exception) {}
            } finally {
                suppressWatcher = false
            }
        }
        val proposal = c.proposal
        if (proposal != null) {
            proposalButton.visibility = View.VISIBLE
            proposalButton.text = proposal.newForm
        } else {
            proposalButton.visibility = View.GONE
        }
        val notice = c.notice
        if (notice != null) {
            noticeView.visibility = View.VISIBLE
            noticeView.text = notice
        } else {
            noticeView.visibility = View.GONE
        }
        applyButton.isEnabled = c.replacement.isNotBlank()
        undoButton.isEnabled = c.undoAvailable
        rememberButton.visibility = if (c.rememberAvailable) View.VISIBLE else View.GONE
    }
    refresh(controller)
    return CorrectionSheetView(panel, ::refresh)
}
