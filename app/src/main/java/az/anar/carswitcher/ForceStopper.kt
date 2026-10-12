package az.anar.carswitcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.TimeUnit

/**
 * Закрывает приложение так же, как смахивание в «Недавних»:
 * 1) если приложение на экране: уходим на главный экран;
 * 2) root есть: `am force-stop <pkg>`; root нет: ActivityManager.killBackgroundProcesses.
 */
class ForceStopper(private val service: CarAccessibilityService, private val tracker: AppTracker) {

    private val handler = Handler(Looper.getMainLooper())
    private val am = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun kill(app: AppEntry, onDone: () -> Unit = {}) {
        val wasForeground = tracker.foregroundPackage == app.pkg
        if (wasForeground) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        tracker.forget(app.pkg)
        Thread {
            if (wasForeground) Thread.sleep(700)   // даём системе убрать приложение с экрана
            val rooted = tryRoot(app.pkg)
            if (!rooted) {
                try {
                    am.killBackgroundProcesses(app.pkg)
                } catch (e: Exception) {
                    // нет разрешения или ограничение прошивки: просто оставляем в фоне
                }
            }
            handler.post {
                Toast.makeText(service, service.getString(R.string.closed, app.label), Toast.LENGTH_SHORT).show()
                onDone()
            }
        }.start()
    }

    private fun tryRoot(pkg: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", "am force-stop $pkg").redirectErrorStream(true).start()
        val done = p.waitFor(3, TimeUnit.SECONDS)
        if (!done) p.destroy()
        done && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}
