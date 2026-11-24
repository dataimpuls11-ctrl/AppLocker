package com.example.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class LockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg = ""
    private var lastCls = ""
    private var ticks = 0

    private val installers = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    // System screens from where an app can be uninstalled / stopped / switched off
    private val surfaceHints = listOf(
        "settings", "packageinstaller", "installer", "vending", "launcher", "home",
        "safecenter", "securitycenter", "appmanager", "oplus", "coloros", "miui", "samsung.android.app", "systemui"
    )

    // "Uninstall / Force stop / Deactivate / Turn off" in many languages
    private val words = listOf(
        "uninstall", "delete app", "remove app", "force stop", "deactivate", "disable", "clear data", "turn off", "use applocker",
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

    private var lastEnforce = 0L

    // Twice a second: if a locked app that is blocked right now is on screen, close it at once.
    // Every ~15 min: re-read the real time from the internet.
    private val periodic = object : Runnable {
        override fun run() {
            try {
                Prefs.lastBeat = SystemClock.elapsedRealtime()
                if (overlay != null) updateOverlayClock()
                if (ticks++ % 1800 == 0) Prefs.syncNow(this@LockService)
                Enforcer.apply(this@LockService)
                enforce()
            } catch (e: Exception) { /* keep the loop alive */ }
            handler.postDelayed(this, 500)
        }
    }

    private fun enforce() {
        val locked = Prefs.lockedApps(this)
        if (locked.none { Prefs.isBlockedNow(this, it) }) return
        val p = rootInActiveWindow?.packageName?.toString() ?: return
        if (p in locked && Prefs.isBlockedNow(this, p)) closeAndBlock(p)
    }

    private var overlay: View? = null
    private var overlayClock: TextView? = null
    private var overlayPkg = ""

    // Instant block: a full-screen message drawn by this service itself (needs no extra permission and
    // is not delayed by phone-maker restrictions). It covers the app at once; 2.5 s later the app is sent home.
    private fun closeAndBlock(p: String) {
        Prefs.log("BLOCK $p")
        showOverlay(p)
        if (overlay == null) {            // overlay not possible: old method
            performGlobalAction(GLOBAL_ACTION_HOME)
            showBlocked(p)
        }
    }

    private fun showOverlay(pkg: String) {
        if (overlay != null) return
        try {
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                        catch (e: Exception) { pkg }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                setBackgroundColor(Color.WHITE)
            }
            root.addView(TextView(this).apply {
                text = "$label is blocked"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            })
            val clock = TextView(this).apply {
                textSize = 36f; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40); setTextColor(Color.BLACK)
            }
            root.addView(clock)
            val note = Prefs.blockMsg(this)
            if (note.isNotEmpty()) root.addView(TextView(this).apply {
                text = note; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            })
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root; overlayClock = clock; overlayPkg = pkg
            updateOverlayClock()
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 2500)
            handler.postDelayed({ hideOverlay() }, 3000)
        } catch (e: Exception) {
            Prefs.log("overlay failed: ${e.javaClass.simpleName}")
            overlay = null
        }
    }

    private fun hideOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        overlay = null; overlayClock = null
    }

    private fun updateOverlayClock() {
        val c = overlayClock ?: return
        if (Prefs.manualBlocked(this, overlayPkg)) { c.textSize = 20f; c.text = "Blocked until you unblock it in AppLocker"; return }
        val left = maxOf(0L, Prefs.blockEndsAt(this, overlayPkg) - Prefs.now(this)) / 1000
        c.text = "%02d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        if (info != null) {
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            serviceInfo = info
        }
        handler.post(periodic)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); hideOverlay(); super.onDestroy() }

    private fun showBlocked(pkg: String) {
        startActivity(
            Intent(this, LockActivity::class.java)
                .putExtra("pkg", pkg)
                .putExtra("blocked", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun isSurface(pkg: String) = surfaceHints.any { pkg.contains(it) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        try { handle(e) } catch (x: Exception) { /* ignore */ }
    }

    private fun handle(e: AccessibilityEvent) {
        // Activity changes / content changes inside an already open blocked app
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val cp = e.packageName?.toString() ?: return
            val t = SystemClock.uptimeMillis()
            if (t - lastEnforce > 500 && cp in Prefs.lockedApps(this) && Prefs.isBlockedNow(this, cp)) {
                lastEnforce = t
                closeAndBlock(cp)
            }
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString()?.lowercase() ?: ""
        if (pkg == packageName) { lastPkg = ""; return }
        lastPkg = pkg
        lastCls = cls

        // While any app is protected: close every uninstall / app-info / device-admin / accessibility-off screen.
        // Screens load their text slowly, so check right away and again a little later.
        if (Prefs.uninstallProtected(this) && isSurface(pkg)) {
            guard()
            for (d in longArrayOf(300, 900, 2000)) handler.postDelayed({ guard() }, d)
        }

        if (pkg in Prefs.lockedApps(this)) Prefs.log("open $pkg blockedNow=${Prefs.isBlockedNow(this, pkg)}")
        if (pkg !in Prefs.lockedApps(this)) return

        // Blocked now (timer or daily window): no PIN, show live countdown
        if (Prefs.isBlockedNow(this, pkg)) { closeAndBlock(pkg); return }

        // Not blocked: ask for PIN only if a PIN exists, the option is on, and not inside the PIN grace period
        if (Prefs.hasPin(this) && Prefs.askPin(this) && !Prefs.isInGrace(this, pkg)) {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .putExtra("pkg", pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private fun guard() {
        if (!Prefs.uninstallProtected(this)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName || !isSurface(pkg)) return
        val isInstaller = pkg in installers || pkg.contains("packageinstaller")
        if (showsProtectedApp(root, isInstaller, lastCls)) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun showsProtectedApp(root: AccessibilityNodeInfo, isInstaller: Boolean, cls: String): Boolean {
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
