package com.lagradost.cloudstream3.utils.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import kotlin.math.min

/** A dedicated, scrollable provider trace panel. Never changes the application's Logcat. */
object DiagnosticDialog {
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudStream provider diagnostic", text))
        Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context) {
        var full = false
        var selected = 0
        val padding = dp(context, 12)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(context, 8), padding, dp(context, 8))
        }
        val title = TextView(context).apply {
            text = "Provider Diagnostic"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 4))
        }
        panel.addView(title)

        val selectionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mode = Button(context).apply { text = "Important"; isAllCaps = false }
        selectionRow.addView(mode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sections = Spinner(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, ProviderTrace.sections).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sections.adapter = adapter
        selectionRow.addView(sections, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        panel.addView(selectionRow)

        val body = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 12))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = Button(context).apply { text = "Copy"; isAllCaps = false }
        val clear = Button(context).apply { text = "Clear"; isAllCaps = false }
        val close = Button(context).apply { text = "Close"; isAllCaps = false }
        listOf(copy, clear, close).forEach {
            actions.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(actions)

        val dialog = AlertDialog.Builder(context).setView(panel).create()
        val handler = Handler(Looper.getMainLooper())
        var lastContent = ""
        fun refresh() {
            val text = ProviderTrace.report(ProviderTrace.sections[selected], !full)
            if (text != lastContent) {
                val position = scroll.scrollY
                body.text = text
                lastContent = text
                scroll.post { scroll.scrollTo(0, position) }
            }
        }
        mode.setOnClickListener {
            full = !full
            mode.text = if (full) "Full trace" else "Important"
            refresh()
        }
        sections.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = position
                scroll.scrollTo(0, 0)
                refresh()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        copy.setOnClickListener { copy(context, ProviderTrace.report(ProviderTrace.sections[selected], !full)) }
        clear.setOnClickListener { ProviderTrace.clear(); scroll.scrollTo(0, 0); refresh() }
        close.setOnClickListener { dialog.dismiss() }

        val update = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                refresh()
                handler.postDelayed(this, 1500)
            }
        }
        dialog.setOnShowListener {
            // Occupy the app's content area, not the status-bar safe area or bottom navigation.
            val activity = context as? Activity
            val visible = Rect()
            activity?.window?.decorView?.getWindowVisibleDisplayFrame(visible)
            val metrics = context.resources.displayMetrics
            val availableTop = if (visible.height() > 0) visible.top else dp(context, 24)
            var availableBottom = if (visible.height() > 0) visible.bottom else metrics.heightPixels
            val bottomNav = activity?.findViewById<View>(R.id.nav_view)
            if (bottomNav != null && bottomNav.visibility == View.VISIBLE && bottomNav.height > 0) {
                val location = IntArray(2)
                bottomNav.getLocationOnScreen(location)
                if (location[1] > availableTop) availableBottom = min(availableBottom, location[1])
            }
            val panelHeight = (availableBottom - availableTop - dp(context, 16)).coerceAtLeast(dp(context, 180))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(resolveBackground(context)))
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f) // Keep the original bottom bar visible.
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { y = dp(context, 8) }
                setLayout(metrics.widthPixels - dp(context, 16), panelHeight)
            }
            handler.post(update)
        }
        dialog.setOnDismissListener { handler.removeCallbacks(update) }
        dialog.show()
    }

    private fun resolveBackground(context: Context): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
