package com.shihab.diplay.probe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var reportView: TextView
    private lateinit var eventView: TextView
    private lateinit var version: EditText
    private lateinit var exportButton: Button
    private lateinit var copyButton: Button
    private lateinit var refreshButton: Button
    private var snapshot: JSONObject? = null
    private var pendingExport: String? = null
    private var presentation: Presentation? = null
    private var testedDisplay: Int? = null
    private var testAttempt = 0
    private var answerAttempt: Int? = null
    private var resumed = false
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val closeTest = Runnable { dismissDisplay("timeout") }
    private val eventTick = object : Runnable {
        override fun run() {
            eventView.text = ProbeState.snapshot().takeLast(12).joinToString("\n") { "${it.kind}: ${it.detail}" }
            handler.postDelayed(this, 500)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { ProbeState.record("display", "added id=$displayId; refresh_required") }
        override fun onDisplayChanged(displayId: Int) {
            if (testedDisplay == displayId) dismissDisplay("display_changed")
            ProbeState.record("display", "changed id=$displayId; refresh_required")
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (testedDisplay == displayId) dismissDisplay("display_removed")
            ProbeState.record("display", "removed id=$displayId; refresh_required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingExport")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        // API 35+ edge-to-edge: retain factory bars/controls and avoid placing tests beneath them.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        fun label(id: Int) = TextView(this).apply {
            setText(id); textSize = 17f; setTextColor(Color.rgb(32, 45, 59)); setPadding(0, dp(12), 0, dp(8))
            content.addView(this)
        }
        fun button(id: Int, action: () -> Unit) = Button(this).apply {
            setText(id); isAllCaps = false; minHeight = dp(52)
            setOnClickListener { action() }; content.addView(this)
        }
        label(R.string.app_name).textSize = 26f
        label(R.string.intro)
        version = EditText(this).apply {
            id = View.generateViewId()
            setHint(R.string.map_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(32))
            setSingleLine(true)
            setText(savedInstanceState?.getString("mapVersion").orEmpty())
            content.addView(this)
        }
        refreshButton = button(R.string.refresh, ::refresh)
        button(R.string.permissions, ::askPermissions)
        button(R.string.settings) {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) { toast(R.string.unavailable) }
        }
        label(R.string.media_help)
        button(R.string.media_start) {
            try { startForegroundService(Intent(this, MediaKeyProbeService::class.java)) }
            catch (failure: Exception) { ProbeState.record("media", "launch_failed=${failure.javaClass.simpleName}") }
        }
        button(R.string.media_stop) { stopService(Intent(this, MediaKeyProbeService::class.java)) }
        label(R.string.display_help)
        button(R.string.display_test, ::chooseDisplay)
        button(R.string.close_display) { dismissDisplay("user_stop") }
        label(R.string.events)
        eventView = TextView(this).apply { textSize = 14f; content.addView(this) }
        exportButton = button(R.string.export, ::export)
        copyButton = button(R.string.copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DiPlay probe", report()))
            toast(R.string.copied)
        }
        reportView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); content.addView(this) }
        displayManager.registerDisplayListener(displayListener, handler)
        refresh()
    }

    private fun ownDisplayId(): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.displayId
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        exportButton.isEnabled = false; copyButton.isEnabled = false
        reportView.setText(R.string.loading)
        val ownId = ownDisplayId()
        val bounds = resources.displayMetrics
        val width = bounds.widthPixels; val height = bounds.heightPixels; val density = bounds.densityDpi
        worker.execute {
            val next = HeadUnitCapabilityProbe.collect(applicationContext, ownId).apply {
                put("appMetrics", JSONObject().put("width", width).put("height", height).put("densityDpi", density))
            }
            handler.post {
                if (!isDestroyed) {
                    snapshot = next
                    reportView.text = next.toString(2)
                    refreshButton.isEnabled = true
                    exportButton.isEnabled = true; copyButton.isEnabled = true
                }
            }
        }
    }

    private fun askPermissions() {
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) requested += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) refresh() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ProbeState.record("permissions", "updated; refresh_required")
        if (refreshButton.isEnabled) refresh()
    }

    private fun chooseDisplay() {
        val candidates = displayManager.displays.filter { HeadUnitCapabilityProbe.eligible(it, ownDisplayId()) }
        if (candidates.isEmpty()) { toast(R.string.no_displays); return }
        val names = candidates.map { "#${it.displayId} · ${it.mode.physicalWidth}×${it.mode.physicalHeight} · ${ProbeText.safe(it.name)}" }
        AlertDialog.Builder(this).setTitle(R.string.display_test).setItems(names.toTypedArray()) { _, index ->
            val id = candidates[index].displayId
            AlertDialog.Builder(this).setTitle(R.string.confirm_title).setMessage(R.string.confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.test) { _, _ -> showDisplay(id) }.show()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showDisplay(id: Int) {
        dismissDisplay("replaced", ask = false)
        val display = displayManager.getDisplay(id)
        if (display == null || !HeadUnitCapabilityProbe.eligible(display, ownDisplayId())) {
            ProbeState.record("presentation", "id=$id unavailable_or_private")
            return
        }
        testAttempt++
        answerAttempt = null
        testedDisplay = id
        try {
            val next = Presentation(this, display).apply {
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setContentView(TextView(context).apply {
                    setText(R.string.display_marker); gravity = Gravity.CENTER; textSize = 18f
                    setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(23, 96, 138))
                })
                setOnDismissListener {
                    if (presentation === this) {
                        presentation = null
                        handler.removeCallbacks(closeTest)
                        ProbeState.record("presentation", "attempt=$testAttempt id=$id system_dismissed")
                    }
                }
                window?.setGravity(Gravity.CENTER)
                window?.setLayout(minOf(dp(260), display.mode.physicalWidth / 2), minOf(dp(140), display.mode.physicalHeight / 2))
            }
            presentation = next
            next.show()
            // Window manager acceptance and physical visibility are deliberately separate facts.
            answerAttempt = testAttempt
            ProbeState.record("presentation", "attempt=$testAttempt id=$id window_accepted; visibility_unconfirmed")
            handler.postDelayed(closeTest, 10_000)
        } catch (failure: Exception) {
            dismissDisplay("show_failed", ask = false)
            ProbeState.record("presentation", "attempt=$testAttempt id=$id failed=${failure.javaClass.simpleName}")
        }
    }

    private fun dismissDisplay(reason: String, ask: Boolean = true) {
        handler.removeCallbacks(closeTest)
        val current = presentation ?: return
        presentation = null
        runCatching { current.dismiss() }
        val attempt = answerAttempt
        answerAttempt = null
        ProbeState.record("presentation", "attempt=$testAttempt id=$testedDisplay closed=$reason")
        if (ask && resumed && attempt != null) {
            AlertDialog.Builder(this).setTitle(R.string.display_question)
                .setPositiveButton(R.string.seen) { _, _ -> ProbeState.record("presentation_observation", "attempt=$attempt owner_reported_visible") }
                .setNegativeButton(R.string.not_seen) { _, _ -> ProbeState.record("presentation_observation", "attempt=$attempt not_seen_or_unknown") }.show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val observable = MediaKeyProbeService.mediaCommand(event.keyCode) != null || event.keyCode in setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        )
        if (MediaKeyProbeService.active && currentFocus !is EditText && observable) {
            ProbeState.record("activity_key", "key=${event.keyCode} action=${event.action} repeat=${event.repeatCount}; observed_only")
        }
        // Observation only: do not swallow system volume, driver controls or navigation keys.
        return super.dispatchKeyEvent(event)
    }

    private fun report(): String = JSONObject(snapshot?.toString() ?: "{}").apply {
        put("amapVersion", ProbeText.mapVersion(version.text.toString()))
        put("events", JSONArray().apply { ProbeState.snapshot().forEach {
            put(JSONObject().put("kind", it.kind).put("detail", it.detail).put("elapsedMillis", it.elapsedMillis))
        } })
        put("mediaTestActive", MediaKeyProbeService.active)
        put("eventLifetime", "current application process; last 200 events; no persistent capture")
    }.toString(2)

    @Suppress("DEPRECATION")
    private fun export() {
        pendingExport = report()
        val name = "diplay-c11-probe-${System.currentTimeMillis()}.json"
        if (Build.VERSION.SDK_INT >= 29) {
            val text = checkNotNull(pendingExport)
            worker.execute {
                var uri: Uri? = null
                try {
                    uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/DiPlay"); put(MediaStore.Downloads.IS_PENDING, 1)
                    }) ?: error("create_failed")
                    write(uri, text)
                    check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                    handler.post { if (!isDestroyed) toast(R.string.saved) }
                } catch (_: Exception) {
                    uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                    handler.post { if (!isDestroyed) toast(R.string.save_failed) }
                }
            }
        } else {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, name)
                }, 2)
            } catch (_: Exception) { toast(R.string.save_failed) }
        }
    }

    @Deprecated("Platform activity result is sufficient for the dependency-free probe")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = pendingExport ?: return
        worker.execute {
            val success = runCatching { write(uri, text) }.isSuccess
            handler.post { if (!isDestroyed) toast(if (success) R.string.saved else R.string.save_failed) }
        }
    }

    private fun write(uri: Uri, text: String) {
        (contentResolver.openOutputStream(uri, "wt") ?: error("open_failed"))
            .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExport", pendingExport)
        outState.putString("mapVersion", version.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); resumed = true; handler.post(eventTick) }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(eventTick)
        dismissDisplay("activity_paused", ask = false)
        super.onPause()
    }
    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        dismissDisplay("activity_destroyed", ask = false)
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
