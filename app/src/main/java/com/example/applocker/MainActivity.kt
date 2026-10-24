package com.example.applocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onResume() {
        super.onResume()
        if (Prefs.hasPin(this) && !Prefs.mainUnlocked) {
            startActivity(Intent(this, LockActivity::class.java).putExtra("pkg", packageName))
            return
        }
        build()
    }

    // Re-lock the settings screen whenever the user leaves it
    override fun onStop() { super.onStop(); Prefs.mainUnlocked = false }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply { text = "AppLocker"; textSize = 26f })

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

        // Timer
        val mins = EditText(this).apply {
            hint = "Minutes an app stays unlocked after PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.graceMinutes(this@MainActivity).toString())
        }
        col.addView(mins)
        col.addView(Button(this).apply {
            text = "Save timer"
            setOnClickListener {
                Prefs.setGraceMinutes(this@MainActivity, mins.text.toString().toIntOrNull() ?: 0)
                toast("Timer saved")
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
