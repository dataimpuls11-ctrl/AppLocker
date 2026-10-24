package com.example.applocker

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onResume() {
        super.onResume()
        if (Prefs.hasPin(this) && !Prefs.mainUnlocked) {
            startActivity(Intent(this, LockActivity::class.java).putExtra("pkg", packageName))
            return
        }
        if (Prefs.isBlocking(this)) buildBlocked() else build()
    }

    // Re-lock the settings screen whenever the user leaves it
    override fun onStop() { super.onStop(); Prefs.mainUnlocked = false }

    private fun accessOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(packageName)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }

    private fun buildBlocked() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = "Block timer is ON"; textSize = 26f })
        col.addView(TextView(this).apply {
            text = "\nLocked apps cannot be opened or uninstalled until the timer ends. Settings cannot be changed until then.\n"
            textSize = 16f
        })
        val clock = TextView(this).apply { textSize = 44f }
        col.addView(clock)
        setContentView(col)
        val tick = object : Runnable {
            override fun run() {
                val left = Prefs.blockUntil(this@MainActivity) - System.currentTimeMillis()
                if (left <= 0) { build(); return }
                val s = left / 1000
                clock.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply { text = "AppLocker"; textSize = 26f })
        col.addView(TextView(this).apply {
            text = if (accessOn()) "Accessibility: ON" else "Accessibility: OFF (locking will not work!)"
            textSize = 16f
        })

        // PIN
        val pin = EditText(this).apply {
            hint = if (Prefs.hasPin(this@MainActivity)) "New PIN (to change)" else "Set a PIN (4+ digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        col.addView(pin)
        col.addView(Button(this).apply {
            text = "Save PIN"
            setOnClickListener {
                if (pin.text.length < 4) toast("PIN must be at least 4 digits")
                else { Prefs.setPin(this@MainActivity, pin.text.toString()); Prefs.mainUnlocked = true; pin.text.clear(); toast("PIN saved") }
            }
        })

        // Block timer
        col.addView(TextView(this).apply { text = "\nBlock timer: locked apps can't be opened or uninstalled at all"; textSize = 16f })
        val hours = EditText(this).apply {
            hint = "Hours (e.g. 2 or 1.5)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        col.addView(hours)
        col.addView(Button(this).apply {
            text = "Start block timer"
            setOnClickListener {
                val hrs = hours.text.toString().toDoubleOrNull() ?: 0.0
                when {
                    hrs <= 0 -> toast("Enter hours")
                    Prefs.lockedApps(this@MainActivity).isEmpty() -> toast("Tick at least one app first")
                    !accessOn() -> toast("Turn on Accessibility first")
                    else -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Start block for $hrs hour(s)?")
                        .setMessage("This cannot be cancelled until the time is over.")
                        .setPositiveButton("Start") { _, _ -> Prefs.startBlock(this@MainActivity, hrs); buildBlocked() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })

        // PIN grace timer
        val mins = EditText(this).apply {
            hint = "Minutes an app stays open after correct PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.graceMinutes(this@MainActivity).toString())
        }
        col.addView(mins)
        col.addView(Button(this).apply {
            text = "Save PIN-grace minutes"
            setOnClickListener {
                Prefs.setGraceMinutes(this@MainActivity, mins.text.toString().toIntOrNull() ?: 0)
                toast("Saved")
            }
        })

        // Permissions
        col.addView(Button(this).apply {
            text = "1. Enable Accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "2. Activate Device Admin (blocks uninstall of AppLocker)"
            setOnClickListener {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java)))
            }
        })

        col.addView(TextView(this).apply { text = "\nTick the apps to lock:"; textSize = 16f })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        fillApps()

        setContentView(ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) })
    }

    private fun fillApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val locked = Prefs.lockedApps(this)
        for (a in apps) {
            list.addView(CheckBox(this).apply {
                text = pm.getApplicationLabel(a)
                isChecked = a.packageName in locked
                setCompoundDrawablesWithIntrinsicBounds(pm.getApplicationIcon(a), null, null, null)
                compoundDrawablePadding = 24
                setOnCheckedChangeListener { _, on ->
                    if (!Prefs.hasPin(this@MainActivity)) { isChecked = false; toast("Set a PIN first") }
                    else Prefs.setLocked(this@MainActivity, a.packageName, on)
                }
            })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
