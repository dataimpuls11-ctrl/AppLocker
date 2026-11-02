package com.example.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg = ""
    private var ticks = 0

    private val installers = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    // "Uninstall / Force stop / Deactivate / Turn off" in many languages
    private val words = listOf(
        "uninstall", "force stop", "deactivate", "disable", "clear data", "turn off", "use applocker",
        "ان انسٹال", "زبردستی", "غیر فعال", "بند کریں",
        "إلغاء التثبيت", "إيقاف إجباري", "إلغاء التنشيط", "إيقاف",
        "अनइंस्टॉल", "ज़बरदस्ती", "निष्क्रिय", "बंद करें",
        "desinstalar", "forzar", "desactivar", "désinstaller", "forcer", "désactiver", "desativar", "forçar",
        "copot", "paksa", "nonaktifkan", "kaldır", "durmaya zorla", "devre dışı",
        "удалить", "остановить", "отключить", "আনইনস্টল", "জোর করে"
    )

    // Language-independent: names of the system screens themselves
    private val classHints = listOf(
        "installedappdetails", "appinfo", "appdetail", "applicationinfo", "uninstall",
        "deviceadmin", "accessibility"
    )

    // Every 15 s: if a locked app is already on screen when a block starts, block it.
    // Every ~15 min: re-read the real time from the internet.
    private val periodic = object : Runnable {
        override fun run() {
            if (ticks++ % 60 == 0) Prefs.syncNow(this@LockService)
            val p = lastPkg
            if (p.isNotEmpty() && p in Prefs.lockedApps(this@LockService) && Prefs.isBlockedNow(this@LockService, p)) showBlocked(p)
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); handler.post(periodic) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun showBlocked(pkg: String) {
        startActivity(
            Intent(this, LockActivity::class.java)
                .putExtra("pkg", pkg)
                .putExtra("blocked", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString()?.lowercase() ?: ""
        if (pkg == packageName) { lastPkg = ""; return }
        lastPkg = pkg

        if (Prefs.uninstallProtected(this)) {
            // Uninstall / app-info / device-admin / accessibility screens of protected apps
            if ((pkg in installers || pkg == "com.android.settings" || pkg.contains("settings")) &&
                showsProtectedApp(pkg in installers, cls)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        if (pkg !in Prefs.lockedApps(this)) return

        // Blocked now (timer or daily window): no PIN, show live countdown
        if (Prefs.isBlockedNow(this, pkg)) { showBlocked(pkg); return }

        // Not blocked: ask for PIN only if the option is on, and not inside the PIN grace period
        if (Prefs.hasPin(this) && Prefs.askPin(this) && !Prefs.isInGrace(this, pkg)) {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .putExtra("pkg", pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private fun showsProtectedApp(isInstaller: Boolean, cls: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val sb = StringBuilder()
        collect(root, sb)
        val t = sb.toString().lowercase()
        // App labels come from the phone itself, so they are in the phone's own language
        val names = Prefs.hardApps(this).mapNotNull {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString().lowercase() }
            catch (e: Exception) { null }
        } + "applocker"
        if (names.none { t.contains(it) }) return false
        if (isInstaller) return true                               // uninstall dialog shows the app name
        return words.any { t.contains(it) } || classHints.any { cls.contains(it) }
    }

    private fun collect(n: AccessibilityNodeInfo, sb: StringBuilder) {
        n.text?.let { sb.append(it).append('\n') }
        n.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, sb) }
    }

    override fun onInterrupt() {}
}
