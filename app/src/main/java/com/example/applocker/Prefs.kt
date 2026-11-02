package com.example.applocker

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
    }

    // ---------------- PIN grace ----------------
    fun graceMinutes(c: Context) = sp(c).getInt("grace", 5)
    fun setGraceMinutes(c: Context, m: Int) = sp(c).edit().putInt("grace", m).apply()

    fun grantGrace(c: Context, pkg: String) {
        sp(c).edit().putLong("until_$pkg", now(c) + graceMinutes(c) * 60_000L).apply()
    }

    fun isInGrace(c: Context, pkg: String) = now(c) < sp(c).getLong("until_$pkg", 0L)

    // ---------------- One-off block timer ----------------
    fun blockUntil(c: Context) = sp(c).getLong("block_until", 0L)
    fun isBlocking(c: Context) = now(c) < blockUntil(c)
    fun startBlock(c: Context, hours: Double) {
        sp(c).edit().putLong("block_until", now(c) + (hours * 3_600_000L).toLong()).apply()
    }

    // ---------------- Daily schedule ----------------
    fun schedUntil(c: Context) = sp(c).getLong("sched_until", 0L)
    fun schedActive(c: Context) = now(c) < schedUntil(c)
    fun schedStart(c: Context) = sp(c).getInt("sched_start", 21 * 60)
    fun schedEnd(c: Context) = sp(c).getInt("sched_end", 6 * 60)
    fun schedTz(c: Context): String = sp(c).getString("sched_tz", null) ?: TimeZone.getDefault().id

    fun startSchedule(c: Context, startMin: Int, endMin: Int, days: Int, tz: String) {
        sp(c).edit().putInt("sched_start", startMin).putInt("sched_end", endMin).putString("sched_tz", tz)
            .putLong("sched_until", now(c) + days * 86_400_000L).apply()
    }

    // Days can only be added, never reduced
    fun addDays(c: Context, days: Int) {
        if (days <= 0) return
        sp(c).edit().putLong("sched_until", schedUntil(c) + days * 86_400_000L).apply()
    }

    fun daysLeft(c: Context): Long = (schedUntil(c) - now(c) + 86_399_999L) / 86_400_000L

    private fun cal(c: Context): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(schedTz(c))).also { it.timeInMillis = now(c) }

    fun inWindow(c: Context): Boolean {
        val k = cal(c)
        val m = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)
        val s = schedStart(c); val e = schedEnd(c)
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    private fun windowEndMillis(c: Context): Long {
        val e = schedEnd(c)
        val k = cal(c)
        k.set(Calendar.HOUR_OF_DAY, e / 60); k.set(Calendar.MINUTE, e % 60)
        k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0)
        if (k.timeInMillis <= now(c)) k.add(Calendar.DAY_OF_YEAR, 1)
        return k.timeInMillis
    }

    // Apps are blocked right now (one-off timer OR inside the daily window)
    fun isBlockedNow(c: Context) = isBlocking(c) || (schedActive(c) && inWindow(c))

    // When the current block ends (0 if not blocked)
    fun blockEndsAt(c: Context): Long {
        var t = 0L
        if (isBlocking(c)) t = blockUntil(c)
        if (schedActive(c) && inWindow(c)) t = maxOf(t, windowEndMillis(c))
        return t
    }

    // Uninstall / force-stop / settings tampering is blocked for the whole timer or schedule period
    fun uninstallProtected(c: Context) = isBlocking(c) || schedActive(c)

    // Ask for the PIN when apps are NOT blocked (outside the block window / after the timer)?
    fun askPin(c: Context) = sp(c).getBoolean("ask_pin", true)
    fun setAskPin(c: Context, on: Boolean) = sp(c).edit().putBoolean("ask_pin", on).apply()
}
