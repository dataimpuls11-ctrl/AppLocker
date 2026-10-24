package com.example.applocker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class LockActivity : Activity() {
    private var target: String = ""
    private var forSelf = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        target = intent.getStringExtra("pkg") ?: ""
        forSelf = target == packageName
        val blocked = intent.getBooleanExtra("blocked", false)

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString()
        } catch (e: Exception) { "AppLocker" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.WHITE)
        }

        if (blocked) {
            // Block timer screen: no PIN, just a live countdown
            root.addView(TextView(this).apply { text = "$label is blocked"; textSize = 22f; gravity = Gravity.CENTER })
            val clock = TextView(this).apply { textSize = 40f; gravity = Gravity.CENTER; setPadding(0, 48, 0, 48) }
            root.addView(clock)
            root.addView(Button(this).apply { text = "Go home"; setOnClickListener { goHome() } })
            setContentView(root)
            val tick = object : Runnable {
                override fun run() {
                    val left = Prefs.blockUntil(this@LockActivity) - System.currentTimeMillis()
                    if (left <= 0) { finish(); return }
                    val s = left / 1000
                    clock.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(tick)
            return
        }

        root.addView(TextView(this).apply { text = "$label is locked"; textSize = 22f; gravity = Gravity.CENTER })
        val pin = EditText(this).apply {
            hint = "Enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        val err = TextView(this).apply { setTextColor(Color.RED); gravity = Gravity.CENTER }
        val btn = Button(this).apply { text = "Unlock" }
        btn.setOnClickListener {
            if (Prefs.checkPin(this, pin.text.toString())) {
                if (forSelf) Prefs.mainUnlocked = true else Prefs.grantGrace(this, target)
                finish()
            } else { err.text = "Wrong PIN"; pin.text.clear() }
        }
        root.addView(pin, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(err)
        root.addView(btn)
        setContentView(root)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); recreate() }

    // Back = go home, so the locked app is never revealed.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goHome() }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }
}
