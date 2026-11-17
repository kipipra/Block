package com.focuslock.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var picker: NumberPicker
    private lateinit var lockButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        statusText = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }
        picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 720
            value = 25
            wrapSelectorWheel = false
        }
        lockButton = Button(this).apply {
            text = getString(R.string.lock_button)
            setOnClickListener { onStartClicked() }
        }
        root.addView(statusText)
        root.addView(picker)
        root.addView(lockButton)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun onStartClicked() {
        if (LockAccessibilityService.isLocked()) return
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.enable_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val minutes = picker.value
        LockAccessibilityService.lockEndTime =
            System.currentTimeMillis() + minutes * 60_000L
        startForegroundService(Intent(this, LockService::class.java))
        refreshUi()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/${LockAccessibilityService::class.java.canonicalName}")
    }

    private fun refreshUi() {
        val locked = LockAccessibilityService.isLocked()
        picker.isEnabled = !locked
        lockButton.isEnabled = !locked
        lockButton.text = if (locked) getString(R.string.locked) else getString(R.string.lock_button)
        statusText.text = if (locked) {
            val ms = LockAccessibilityService.lockEndTime - System.currentTimeMillis()
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            String.format(Locale.US, getString(R.string.status_locked), min, sec)
        } else {
            getString(R.string.status_free)
        }
    }
}
