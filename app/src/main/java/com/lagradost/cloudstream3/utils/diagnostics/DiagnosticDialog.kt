package com.lagradost.cloudstream3.utils.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Settings > Diagnostic: important summary first, then detailed event history on demand. */
object DiagnosticDialog {
    private fun copy(context: Context, title: String, content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(title, content))
        Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context) {
        val summary = DiagnosticLog.summary()
        AlertDialog.Builder(context)
            .setTitle("Diagnostic v4 - Important")
            .setMessage(summary)
            .setPositiveButton("Full log") { _, _ -> showFull(context) }
            .setNeutralButton("Copy summary") { _, _ -> copy(context, "Diagnostic summary", summary) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFull(context: Context) {
        val report = DiagnosticLog.fullReport()
        AlertDialog.Builder(context)
            .setTitle("Diagnostic v4 - Full log")
            .setMessage(report)
            .setPositiveButton("Copy full log") { _, _ -> copy(context, "Diagnostic full log", report) }
            .setNeutralButton("Clear log") { _, _ ->
                DiagnosticLog.clear()
                Toast.makeText(context, "Diagnostic cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Back") { _, _ -> show(context) }
            .show()
    }
}
