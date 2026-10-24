package com.example.applocker

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)

    // true while the AppLocker settings screen has been unlocked this session
    @Volatile var mainUnlocked = false

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

    fun lockedApps(c: Context): Set<String> = sp(c).getStringSet("locked", emptySet()) ?: emptySet()

    fun setLocked(c: Context, pkg: String, locked: Boolean) {
        val set = lockedApps(c).toMutableSet()
        if (locked) set.add(pkg) else set.remove(pkg)
        sp(c).edit().putStringSet("locked", set).apply()
    }

    // Timer: minutes an app stays unlocked after a correct PIN
    fun graceMinutes(c: Context) = sp(c).getInt("grace", 5)
    fun setGraceMinutes(c: Context, m: Int) = sp(c).edit().putInt("grace", m).apply()

    fun grantGrace(c: Context, pkg: String) {
        val until = System.currentTimeMillis() + graceMinutes(c) * 60_000L
        sp(c).edit().putLong("until_$pkg", until).apply()
    }

    fun isInGrace(c: Context, pkg: String) =
        System.currentTimeMillis() < sp(c).getLong("until_$pkg", 0L)
}
