package com.lagradost.cloudstream3.utils.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Accessible from Settings > General in both legacy and Compose settings. */
object DiagnosticDialog {
    fun show(context: Context) {
        val report = DiagnosticLog.report()
        AlertDialog.Builder(context)
            .setTitle("CloudStream Diagnostic")
            .setMessage(report)
            .setPositiveButton("Copy report") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CloudStream diagnostic", report))
                Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear") { _, _ ->
                DiagnosticLog.clear()
                Toast.makeText(context, "Diagnostic cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
