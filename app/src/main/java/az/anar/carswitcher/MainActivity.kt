package az.anar.carswitcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.accBtn).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.usageBtn).setOnClickListener {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.showBtn).setOnClickListener {
            if (CarAccessibilityService.instance == null) {
                Toast.makeText(this, R.string.step_accessibility, Toast.LENGTH_SHORT).show()
            } else {
                CarAccessibilityService.refreshButton()
            }
        }

        val group = findViewById<RadioGroup>(R.id.sizeGroup)
        group.check(
            when (Prefs.buttonSizeDp(this)) {
                Prefs.SIZE_S -> R.id.sizeS
                Prefs.SIZE_L -> R.id.sizeL
                else -> R.id.sizeM
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            val size = when (id) {
                R.id.sizeS -> Prefs.SIZE_S
                R.id.sizeL -> Prefs.SIZE_L
                else -> Prefs.SIZE_M
            }
            Prefs.setButtonSizeDp(this, size)
            CarAccessibilityService.refreshButton()
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val accOk = CarAccessibilityService.instance != null || isAccessibilityEnabled()
        val usageOk = AppTracker.hasUsageAccess(this)
        setStatus(R.id.overlayStatus, R.id.overlayBtn, overlayOk)
        setStatus(R.id.accStatus, R.id.accBtn, accOk)
        setStatus(R.id.usageStatus, R.id.usageBtn, usageOk)
        // если разрешение на показ только что выдали, а служба уже работает: показать кнопку
        if (overlayOk && accOk) CarAccessibilityService.refreshButton()
    }

    private fun setStatus(textId: Int, btnId: Int, ok: Boolean) {
        findViewById<TextView>(textId).apply {
            text = getString(if (ok) R.string.status_ok else R.string.status_off)
            setTextColor(getColor(if (ok) R.color.ok else R.color.bad))
        }
        findViewById<Button>(btnId).isEnabled = !ok
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, CarAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "error", Toast.LENGTH_SHORT).show()
        }
    }
}
