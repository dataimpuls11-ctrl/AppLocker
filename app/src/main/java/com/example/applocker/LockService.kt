package com.example.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LockService : AccessibilityService() {

    private val installers = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )
    private val uninstallWords = listOf("uninstall", "force stop", "deactivate", "disable", "clear data")

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        // 1) Block uninstall / app-info / force-stop screens for locked apps and AppLocker itself
        if (pkg in installers || pkg == "com.android.settings") {
            if (showsProtectedApp()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // 2) Ask for PIN when a locked app comes to the front
        if (pkg in Prefs.lockedApps(this) && !Prefs.isInGrace(this, pkg)) {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .putExtra("pkg", pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private fun showsProtectedApp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val text = StringBuilder()
        collect(root, text)
        val t = text.toString().lowercase()
        if (uninstallWords.none { t.contains(it) }) return false
        val names = Prefs.lockedApps(this).mapNotNull {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString().lowercase() }
            catch (e: Exception) { null }
        } + "applocker"
        return names.any { t.contains(it) }
    }

    private fun collect(n: AccessibilityNodeInfo, sb: StringBuilder) {
        n.text?.let { sb.append(it).append('\n') }
        n.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, sb) }
    }

    override fun onInterrupt() {}
}
