package com.lagradost.cloudstream3.utils.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** A completely separate screen in Settings, not a replacement for Logcat. */
object DiagnosticDialog {
    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudStream provider diagnostic", text))
        Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context) = open(context, false)

    private fun open(context: Context, full: Boolean) {
        val body = TextView(context).apply {
            text = if (full) ProviderTrace.full() else ProviderTrace.important()
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 20, 24, 20)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (full) "Diagnostic — Full trace" else "Diagnostic — Important")
            .setView(scroll)
            .setPositiveButton(if (full) "Important" else "Full trace") { _, _ -> open(context, !full) }
            .setNeutralButton("Copy") { _, _ -> copy(context, if (full) ProviderTrace.full() else ProviderTrace.important()) }
            .setNegativeButton("Close", null)
            .create()
        val handler = Handler(Looper.getMainLooper())
        val update = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                body.text = if (full) ProviderTrace.full() else ProviderTrace.important()
                handler.postDelayed(this, 1500)
            }
        }
        dialog.setOnShowListener { handler.post(update) }
        dialog.setOnDismissListener { handler.removeCallbacks(update) }
        dialog.show()
    }
}
