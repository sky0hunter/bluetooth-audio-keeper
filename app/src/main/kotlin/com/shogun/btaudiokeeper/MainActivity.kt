package com.shogun.btaudiokeeper

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        toggle = Button(this).apply {
            setOnClickListener { onToggle() }
        }

        root.addView(status)
        root.addView(toggle)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onToggle() {
        val intent = Intent(this, KeeperService::class.java)
        if (isServiceRunning()) {
            intent.action = KeeperService.ACTION_STOP
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        toggle.postDelayed({ refresh() }, 200)
    }

    private fun refresh() {
        val running = isServiceRunning()
        status.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        toggle.text = getString(if (running) R.string.action_stop else R.string.action_start)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KeeperService::class.java.name }
    }
}
