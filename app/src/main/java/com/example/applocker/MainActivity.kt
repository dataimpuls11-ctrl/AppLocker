package com.example.applocker

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var startMin = 21 * 60
    private var endMin = 6 * 60
    private var tzIndex = 0

    private val zones = listOf(
        "Pakistan" to "Asia/Karachi", "India" to "Asia/Kolkata", "Bangladesh" to "Asia/Dhaka",
        "Saudi Arabia" to "Asia/Riyadh", "UAE" to "Asia/Dubai", "Qatar" to "Asia/Qatar",
        "Turkey" to "Europe/Istanbul", "United Kingdom" to "Europe/London", "Germany" to "Europe/Berlin",
        "USA - New York" to "America/New_York", "USA - Chicago" to "America/Chicago",
        "USA - Los Angeles" to "America/Los_Angeles", "Canada - Toronto" to "America/Toronto",
        "Malaysia" to "Asia/Kuala_Lumpur", "Indonesia - Jakarta" to "Asia/Jakarta",
        "Australia - Sydney" to "Australia/Sydney", "Egypt" to "Africa/Cairo",
        "Nigeria" to "Africa/Lagos", "South Africa" to "Africa/Johannesburg"
    )

    override fun onResume() {
        super.onResume()
        Prefs.syncNow(this)
        if (Prefs.hasPin(this) && !Prefs.mainUnlocked) {
            startActivity(Intent(this, LockActivity::class.java).putExtra("pkg", packageName))
            return
        }
        show()
    }

    private fun show() { Enforcer.apply(this); if (Prefs.anyBlockedNow(this)) buildBlocked() else build() }

    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }

    // Re-lock the settings screen whenever the user leaves it
    override fun onStop() { super.onStop(); Prefs.mainUnlocked = false }

    private fun accessOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(packageName)
    }

    private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    // Live clock of the chosen country, from the trusted (internet-checked) time
    private fun startClock(tv: TextView, tz: () -> String) {
        val f = SimpleDateFormat("EEE dd MMM, hh:mm:ss a", Locale.getDefault())
        val r = object : Runnable {
            override fun run() {
                f.timeZone = TimeZone.getTimeZone(tz())
                tv.text = "Time there: " + f.format(Date(Prefs.now(this@MainActivity)))
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(r)
    }

    private fun buildBlocked() {
        handler.removeCallbacksAndMessages(null)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = "Block is ON"; textSize = 26f })
        val extra = ""
        col.addView(TextView(this).apply {
            text = "\nLocked apps cannot be opened or uninstalled right now. Settings cannot be changed until the block ends.$extra\n"
            textSize = 16f
        })
        val clock = TextView(this).apply { textSize = 44f }
        col.addView(clock)
        setContentView(col)
        val tick = object : Runnable {
            override fun run() {
                val left = maxOf(0L, Prefs.blockEndsAtAny(this@MainActivity) - Prefs.now(this@MainActivity))
                if (!Prefs.anyBlockedNow(this@MainActivity)) { build(); return }
                val s = left / 1000
                clock.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
    }

    private fun build() {
        handler.removeCallbacksAndMessages(null)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply { text = "AppLocker"; textSize = 26f })
        col.addView(TextView(this).apply {
            text = if (accessOn()) "Accessibility: ON" else "Accessibility: OFF (locking will not work!)"
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            val ago = (SystemClock.elapsedRealtime() - Prefs.lastBeat) / 1000
            text = if (Prefs.lastBeat != 0L && ago < 5) "Block service: RUNNING"
                   else "Block service: NOT RUNNING - turn Accessibility OFF and ON again, and set Battery to Unrestricted"
            textSize = 14f
        })
        col.addView(TextView(this).apply {
            val diff = (Prefs.now(this@MainActivity) - System.currentTimeMillis()) / 1000
            text = if (Prefs.timeSynced(this@MainActivity)) "Clock check: internet time differs from phone clock by $diff s"
                   else "Clock check: not checked online yet (phone clock is used)"
            textSize = 14f
        })
        col.addView(TextView(this).apply {
            text = if (Enforcer.isOwner(this@MainActivity)) "Device owner: ON (blocked apps are hidden)"
                   else "Device owner: OFF (blocked apps are only closed, not hidden)"
            textSize = 14f
        })

        // PIN
        val pin = EditText(this).apply {
            hint = if (Prefs.hasPin(this@MainActivity)) "New PIN (to change)" else "PIN (optional, 4+ digits)"
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

        // Message shown under "blocked" on the block screen
        val msg = EditText(this).apply {
            hint = "Message shown on the blocked screen (optional)"
            setText(Prefs.blockMsg(this@MainActivity))
        }
        col.addView(TextView(this).apply { text = "\nBlock screen message"; textSize = 16f })
        col.addView(msg)
        col.addView(Button(this).apply {
            text = "Save message"
            setOnClickListener { Prefs.setBlockMsg(this@MainActivity, msg.text.toString().trim()); toast("Saved") }
        })

        // ---- Manual block (PIN needed to unblock) ----
        col.addView(TextView(this).apply { text = "\nManual block (stays blocked until you unblock it with your PIN)"; textSize = 16f })
        if (!Prefs.hasPin(this)) {
            col.addView(TextView(this).apply { text = "Set a PIN above first, so only you can unblock." })
        } else {
            col.addView(Button(this).apply {
                text = "Block ticked apps now"
                setOnClickListener {
                    val n = Prefs.blockManual(this@MainActivity)
                    if (n == 0) toast("Tick apps first (or they are already blocked)") else { toast("$n app(s) blocked"); Enforcer.apply(this@MainActivity); build() }
                }
            })
            col.addView(Button(this).apply {
                text = "Unblock manually blocked apps (PIN)"
                setOnClickListener {
                    val et = EditText(this@MainActivity).apply {
                        hint = "PIN"
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Enter PIN to unblock")
                        .setView(et)
                        .setPositiveButton("Unblock") { _, _ ->
                            if (Prefs.checkPin(this@MainActivity, et.text.toString())) {
                                Prefs.unblockManual(this@MainActivity); Enforcer.apply(this@MainActivity); toast("Unblocked"); build()
                            } else toast("Wrong PIN")
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        }

        // ---- Daily schedule (every app keeps its OWN time window and days) ----
        if (Prefs.schedActive(this)) {
            val pm0 = packageManager
            val lines = Prefs.protectedApps(this).joinToString("\n") { p ->
                val n = try { pm0.getApplicationLabel(pm0.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
                "- $n: ${fmt(Prefs.pStart(this, p))} - ${fmt(Prefs.pEnd(this, p))}, ${Prefs.daysLeft(this, p)} day(s) left"
            }
            col.addView(TextView(this).apply {
                text = "\nProtected apps:\n$lines\n\nEach app keeps its own time and days. Uninstall stays blocked for an app until its own days finish."
                textSize = 16f
            })
            val add = EditText(this).apply { hint = "Add days to ALL protected apps"; inputType = InputType.TYPE_CLASS_NUMBER }
            col.addView(add)
            col.addView(Button(this).apply {
                text = "Add days"
                setOnClickListener {
                    val d = add.text.toString().toIntOrNull() ?: 0
                    if (d <= 0) toast("Enter days") else { Prefs.addDays(this@MainActivity, d); toast("Added"); build() }
                }
            })
        }

        col.addView(TextView(this).apply { text = "\nNEW block for the apps you tick below"; textSize = 16f })
        col.addView(TextView(this).apply { text = "Country / time zone:" })
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, zones.map { it.first })
        spin.setSelection(tzIndex)
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { tzIndex = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(spin)
        val clock = TextView(this)
        col.addView(clock)
        startClock(clock) { zones[tzIndex].second }

        val allDay = CheckBox(this).apply { text = "Block all day (24 hours), no time window" }
        col.addView(allDay)
        val b1 = Button(this); val b2 = Button(this)
        fun refresh() { b1.text = "Block from: ${fmt(startMin)}"; b2.text = "Block until: ${fmt(endMin)}" }
        refresh()
        b1.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> startMin = h * 60 + m; refresh() }, startMin / 60, startMin % 60, true).show()
        }
        b2.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> endMin = h * 60 + m; refresh() }, endMin / 60, endMin % 60, true).show()
        }
        col.addView(b1); col.addView(b2)
        val days = EditText(this).apply { hint = "For how many days (e.g. 21)"; inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(days)
        col.addView(Button(this).apply {
            text = "Start block for newly ticked apps"
            setOnClickListener {
                val d = days.text.toString().toIntOrNull() ?: 0
                val s = if (allDay.isChecked) 0 else startMin
                val e = if (allDay.isChecked) 1440 else endMin
                val fresh = Prefs.lockedApps(this@MainActivity).filter { !Prefs.isProtected(this@MainActivity, it) }
                when {
                    d <= 0 -> toast("Enter number of days")
                    s == e -> toast("Start and end time cannot be the same")
                    fresh.isEmpty() -> toast("Tick a new app first")
                    !accessOn() -> toast("Turn on Accessibility first")
                    else -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Protect ${fresh.size} app(s) for $d day(s)?")
                        .setMessage((if (allDay.isChecked) "Blocked all day. " else "Blocked ${fmt(s)} - ${fmt(e)} every day. ") +
                            "Cannot be cancelled. These apps cannot be untick-ed or uninstalled for $d days from now. Other apps keep their own time and days.")
                        .setPositiveButton("Start") { _, _ ->
                            Prefs.startSchedule(this@MainActivity, s, e, d, zones[tzIndex].second)
                            show()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })

        // ---- One-off block timer ----
        col.addView(TextView(this).apply { text = "\nOne-time block timer (starts right now)"; textSize = 16f })
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
                        .setPositiveButton("Start") { _, _ -> Prefs.startBlock(this@MainActivity, hrs); show() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })

        // Ask PIN when not blocked?
        col.addView(CheckBox(this).apply {
            text = "Ask PIN when apps are not blocked (untick = apps open freely outside block time)"
            isChecked = Prefs.askPin(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAskPin(this@MainActivity, on) }
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

        if (Enforcer.isOwner(this)) {
            col.addView(Button(this).apply {
                text = "Remove device-owner mode (only when nothing is blocked)"
                setOnClickListener {
                    if (Prefs.uninstallProtected(this@MainActivity)) toast("Not possible while a block or protection is active")
                    else { Enforcer.releaseAll(this@MainActivity); toast("Device-owner mode removed"); build() }
                }
            })
        }

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

        col.addView(TextView(this).apply {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            text = "\nVersion $v - diagnostics (latest first):\n" + Prefs.logText().ifEmpty { "(nothing yet)" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        col.addView(Button(this).apply { text = "Refresh diagnostics"; setOnClickListener { build() } })

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
                text = pm.getApplicationLabel(a).toString() + when {
                    Prefs.manualBlocked(this@MainActivity, a.packageName) -> "  [BLOCKED]"
                    Prefs.isProtected(this@MainActivity, a.packageName) ->
                        "  [${Prefs.daysLeft(this@MainActivity, a.packageName)} day(s) left, ${fmt(Prefs.pStart(this@MainActivity, a.packageName))}-${fmt(Prefs.pEnd(this@MainActivity, a.packageName))}" +
                        (if (Prefs.isBlockedNow(this@MainActivity, a.packageName)) ", BLOCKED NOW" else "") + "]"
                    Prefs.isTimerBlocked(this@MainActivity, a.packageName) -> "  [timer running]"
                    a.packageName in locked -> "  [ticked, NOT blocking yet - press Start]"
                    else -> ""
                }
                isChecked = a.packageName in locked
                setCompoundDrawablesWithIntrinsicBounds(pm.getApplicationIcon(a), null, null, null)
                compoundDrawablePadding = 24
                setOnCheckedChangeListener { _, on ->
                    when {
                        !on && Prefs.isLockedHard(this@MainActivity, a.packageName) -> { isChecked = true; toast("This app is blocked/protected. Unblock it with your PIN or wait until its days finish") }
                        else -> Prefs.setLocked(this@MainActivity, a.packageName, on)
                    }
                }
            })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
