package az.anar.carswitcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * Главная служба: держит плавающую кнопку, следит за текущим приложением
 * и выполняет принудительную остановку через экран «О приложении».
 */
class CarAccessibilityService : AccessibilityService() {

    lateinit var tracker: AppTracker
        private set
    lateinit var overlay: OverlayController
        private set
    lateinit var stopper: ForceStopper
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tracker = AppTracker(this)
        stopper = ForceStopper(this, tracker)
        overlay = OverlayController(this, tracker, stopper)
        overlay.showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName) return
        // Учитываем только настоящие Activity (не клавиатуру, не диалоги, не тосты)
        if (isActivity(pkg, cls)) tracker.onForeground(pkg)
    }

    private fun isActivity(pkg: String, cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.onScreenChanged()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (::overlay.isInitialized) overlay.removeAll()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        var instance: CarAccessibilityService? = null
            private set

        /** Пересоздать кнопку (после смены размера или выдачи разрешения). */
        fun refreshButton() {
            instance?.overlay?.let {
                it.removeAll()
                it.showButton()
            }
        }
    }
}
