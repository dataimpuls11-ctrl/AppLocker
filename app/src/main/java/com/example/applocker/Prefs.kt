package com.example.applocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)

    // true while the AppLocker settings screen has been unlocked this session
    @Volatile var mainUnlocked = false

    // Updated every second by the blocking service; lets the screen show whether the service is alive
    @Volatile var lastBeat = 0L

    // Small in-memory log (latest first) shown on the AppLocker screen, to see what the blocker is doing
    private val logLines = java.util.ArrayDeque<String>()
    fun log(msg: String) {
        synchronized(logLines) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logLines.addFirst("$t $msg")
            while (logLines.size > 25) logLines.removeLast()
        }
    }
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    // ---------------- Trusted time (ignores the phone clock once synced) ----------------
    private fun bootCount(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun timeSynced(c: Context) = sp(c).getLong("sync_wall", 0L) != 0L

    fun now(c: Context): Long {
        val s = sp(c)
        val wall = s.getLong("sync_wall", 0L)
        if (wall == 0L) return System.currentTimeMillis()
        // Same boot as the last sync: use the unchangeable monotonic clock, ignore phone clock
        if (bootCount(c) == s.getInt("sync_boot", -2)) {
            val el = SystemClock.elapsedRealtime() - s.getLong("sync_elapsed", 0L)
            if (el >= 0) return wall + el
        }
        // After a reboot: phone clock + the offset measured at the last sync
        return System.currentTimeMillis() + s.getLong("sync_offset", 0L)
    }

    // Reads the real time from the internet (HTTP Date header). Tries several sites. Runs in background.
    fun syncNow(c: Context) {
        val app = c.applicationContext
        Thread {
            val sites = listOf("https://www.google.com", "https://www.cloudflare.com", "https://www.microsoft.com", "https://www.wikipedia.org")
            for (site in sites) {
                try {
                    val conn = URL(site).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.connect()
                    val t = conn.date
                    conn.disconnect()
                    if (t > 0) {
                        sp(app).edit()
                            .putLong("sync_wall", t)
                            .putLong("sync_elapsed", SystemClock.elapsedRealtime())
                            .putLong("sync_offset", t - System.currentTimeMillis())
                            .putInt("sync_boot", bootCount(app))
                            .apply()
                        return@Thread
                    }
                } catch (e: Exception) { /* try next site */ }
            }
        }.start()
    }

    // ---------------- PIN ----------------
    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = sp(c).contains("hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("salt", salt).putString("hash", hash(pin, salt)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val s = sp(c)
        return s.getString("hash", null) == hash(pin, s.getString("salt", "") ?: "")
    }

    // ---------------- Locked apps ----------------
    fun lockedApps(c: Context): Set<String> = sp(c).getStringSet("locked", emptySet()) ?: emptySet()

    fun setLocked(c: Context, pkg: String, locked: Boolean) {
        val set = lockedApps(c).toMutableSet()
        if (locked) set.add(pkg) else set.remove(pkg)
        sp(c).edit().putStringSet("locked", set).apply()
        if (!locked) Enforcer.release(c, pkg)
    }

    // ---------------- PIN grace ----------------
    fun graceMinutes(c: Context) = sp(c).getInt("grace", 5)
    fun setGraceMinutes(c: Context, m: Int) = sp(c).edit().putInt("grace", m).apply()

    fun grantGrace(c: Context, pkg: String) {
        sp(c).edit().putLong("until_$pkg", now(c) + graceMinutes(c) * 60_000L).apply()
    }

    fun isInGrace(c: Context, pkg: String) = now(c) < sp(c).getLong("until_$pkg", 0L)

    // ---------------- Per-app protection ----------------
    // Every app has its OWN periods:
    //  - prot_<pkg>  : daily-schedule protection (uninstall blocked + blocked during the daily window)
    //  - timer_<pkg> : one-off block timer (blocked the whole time)
    fun protUntil(c: Context, p: String) = sp(c).getLong("prot_$p", 0L)
    // Blocks stay ON if EITHER the internet time or the phone clock says the time is not over yet,
    // and start as soon as EITHER clock says it has begun. So a lagging clock can never delay a block.
    private fun before(c: Context, t: Long) = now(c) < t || System.currentTimeMillis() < t
    fun isProtected(c: Context, p: String) = before(c, protUntil(c, p))
    fun timerUntil(c: Context, p: String) = sp(c).getLong("timer_$p", 0L)
    fun isTimerBlocked(c: Context, p: String) = before(c, timerUntil(c, p))

    // App cannot be unticked or uninstalled right now
    fun isLockedHard(c: Context, p: String) = isProtected(c, p) || isTimerBlocked(c, p) || manualBlocked(c, p)

    // Manual block: stays blocked until unblocked with the PIN inside AppLocker
    fun manualBlocked(c: Context, p: String) = sp(c).getBoolean("manual_$p", false)
    fun blockManual(c: Context): Int {
        val fresh = lockedApps(c).filter { !manualBlocked(c, it) }
        val e = sp(c).edit()
        for (p in fresh) e.putBoolean("manual_$p", true)
        e.apply()
        return fresh.size
    }
    fun unblockManual(c: Context) {
        val e = sp(c).edit()
        for (p in lockedApps(c)) e.putBoolean("manual_$p", false)
        e.apply()
    }
    fun hardApps(c: Context): List<String> = lockedApps(c).filter { isLockedHard(c, it) }
    fun protectedApps(c: Context): List<String> = lockedApps(c).filter { isProtected(c, it) }
    fun schedActive(c: Context) = lockedApps(c).any { isProtected(c, it) }
    fun uninstallProtected(c: Context) = hardApps(c).isNotEmpty()
    fun daysLeft(c: Context, p: String): Long = (protUntil(c, p) - now(c) + 86_399_999L) / 86_400_000L

    // One-off timer for every currently ticked app
    fun startBlock(c: Context, hours: Double) {
        val until = now(c) + (hours * 3_600_000L).toLong()
        val e = sp(c).edit()
        for (p in lockedApps(c)) e.putLong("timer_$p", maxOf(until, timerUntil(c, p)))
        e.apply()
    }

    // Daily schedule: only ticked apps that are NOT already protected get a fresh period of `days`.
    // Already-protected apps keep their own remaining days. The window can only be set when no app is protected.
    fun startSchedule(c: Context, startMin: Int, endMin: Int, days: Int, tz: String): Int {
        val fresh = lockedApps(c).filter { !isProtected(c, it) }
        if (fresh.isEmpty()) return 0
        val e = sp(c).edit()
        val until = now(c) + days * 86_400_000L
        for (p in fresh) {
            e.putLong("prot_$p", until).putInt("ps_$p", startMin).putInt("pe_$p", endMin).putString("ptz_$p", tz)
        }
        e.apply()
        return fresh.size
    }

    // Days can only be added (to every protected app), never reduced
    fun addDays(c: Context, days: Int) {
        if (days <= 0) return
        val e = sp(c).edit()
        for (p in protectedApps(c)) e.putLong("prot_$p", protUntil(c, p) + days * 86_400_000L)
        e.apply()
    }

    // ---------------- Daily window (each app keeps its own) ----------------
    fun pStart(c: Context, p: String) = sp(c).getInt("ps_$p", 21 * 60)
    fun pEnd(c: Context, p: String) = sp(c).getInt("pe_$p", 6 * 60)
    fun pTz(c: Context, p: String): String = sp(c).getString("ptz_$p", null) ?: TimeZone.getDefault().id

    private fun cal(c: Context, p: String, t: Long = now(c)): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(pTz(c, p))).also { it.timeInMillis = t }

    // 00:00 - 24:00 means "all day"
    fun inWindow(c: Context, p: String): Boolean =
        inWindowAt(c, p, now(c)) || inWindowAt(c, p, System.currentTimeMillis())

    private fun inWindowAt(c: Context, p: String, t: Long): Boolean {
        val k = cal(c, p, t)
        val m = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)
        val s = pStart(c, p); val e = pEnd(c, p)
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    private fun windowEndMillis(c: Context, p: String): Long {
        val e = pEnd(c, p)
        val k = cal(c, p)
        k.set(Calendar.HOUR_OF_DAY, e / 60); k.set(Calendar.MINUTE, e % 60)
        k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0)
        if (k.timeInMillis <= now(c)) k.add(Calendar.DAY_OF_YEAR, 1)
        return k.timeInMillis
    }

    // This app is blocked right now (its one-off timer OR its protection + inside the daily window)
    fun isBlockedNow(c: Context, p: String) = manualBlocked(c, p) || isTimerBlocked(c, p) || (isProtected(c, p) && inWindow(c, p))
    // Timed blocks only (manual blocks must NOT lock the AppLocker screen, or you could never unblock)
    fun anyBlockedNow(c: Context) = lockedApps(c).any { isTimerBlocked(c, it) || (isProtected(c, it) && inWindow(c, it)) }

    // When the block of this app ends (0 if not blocked)
    fun blockEndsAt(c: Context, p: String): Long {
        var t = 0L
        if (isTimerBlocked(c, p)) t = timerUntil(c, p)
        if (isProtected(c, p) && inWindow(c, p)) t = maxOf(t, windowEndMillis(c, p))
        return t
    }
    fun blockEndsAtAny(c: Context): Long = lockedApps(c).maxOfOrNull { blockEndsAt(c, it) } ?: 0L

    // ---------------- Message shown on the blocked screen ----------------
    fun blockMsg(c: Context): String = sp(c).getString("block_msg", "") ?: ""
    fun setBlockMsg(c: Context, t: String) = sp(c).edit().putString("block_msg", t).apply()

    // ---------------- Ask PIN when not blocked? ----------------
    fun askPin(c: Context) = sp(c).getBoolean("ask_pin", true)
    fun setAskPin(c: Context, on: Boolean) = sp(c).edit().putBoolean("ask_pin", on).apply()
}


// Works only when AppLocker is set up as "device owner" (one-time ADB command).
// Then blocked apps are really HIDDEN from the phone and protected apps cannot be uninstalled by anyone.
object Enforcer {
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context): Boolean =
        try { dpm(c).isDeviceOwnerApp(c.packageName) } catch (e: Exception) { false }

    // Hide apps that are blocked right now, show the rest, block uninstall for protected apps
    fun apply(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c); val a = admin(c)
        for (p in Prefs.lockedApps(c)) {
            try {
                val hide = Prefs.isBlockedNow(c, p)
                if (d.isApplicationHidden(a, p) != hide) d.setApplicationHidden(a, p, hide)
                val ub = Prefs.isLockedHard(c, p)
                if (d.isUninstallBlocked(a, p) != ub) d.setUninstallBlocked(a, p, ub)
            } catch (e: Exception) { }
        }
    }

    fun release(c: Context, p: String) {
        if (!isOwner(c)) return
        try {
            val d = dpm(c); val a = admin(c)
            d.setApplicationHidden(a, p, false)
            d.setUninstallBlocked(a, p, false)
        } catch (e: Exception) { }
    }

    fun releaseAll(c: Context) {
        if (!isOwner(c)) return
        for (p in Prefs.lockedApps(c)) release(c, p)
        try { dpm(c).clearDeviceOwnerApp(c.packageName) } catch (e: Exception) { }
    }
}
