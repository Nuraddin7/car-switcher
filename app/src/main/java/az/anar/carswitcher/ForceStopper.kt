package az.anar.carswitcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.TimeUnit

/**
 * Полностью закрывает приложение.
 * 1) Если есть root: `am force-stop <pkg>`.
 * 2) Иначе открывает экран «О приложении» и сама нажимает «Остановить» → «ОК».
 */
class ForceStopper(private val service: CarAccessibilityService, private val tracker: AppTracker) {

    private val handler = Handler(Looper.getMainLooper())
    private var target: AppEntry? = null
    private var stage = Stage.IDLE
    private var deadline = 0L

    private enum class Stage { IDLE, FIND_BUTTON, FIND_CONFIRM }

    // Кнопка «Остановить» на экране «О приложении» (EN / RU / AZ / TR)
    private val forceStopRegex = Regex(
        "(force\\s*stop|остановить|принудительн|məcburi|dayandır|zorla|durdur)",
        RegexOption.IGNORE_CASE
    )
    // Кнопка подтверждения в диалоге
    private val confirmRegex = Regex(
        "^\\s*(ok|ок|да|yes|force\\s*stop|остановить|bəli|tamam|dayandır|durdur)\\s*$",
        RegexOption.IGNORE_CASE
    )

    val isBusy get() = stage != Stage.IDLE

    fun forceStop(app: AppEntry) {
        if (isBusy) return
        stage = Stage.FIND_BUTTON
        Thread {
            val rooted = tryRoot(app.pkg)
            handler.post { if (rooted) finish(app, true) else viaSettings(app) }
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

    private fun viaSettings(app: AppEntry) {
        target = app
        stage = Stage.FIND_BUTTON
        deadline = SystemClock.uptimeMillis() + 10_000
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
            )
        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            finish(app, false); return
        }
        handler.postDelayed(step, 500)
    }

    private val step = object : Runnable {
        override fun run() {
            val app = target ?: return
            if (SystemClock.uptimeMillis() > deadline) {
                finish(app, false); return
            }
            val root = service.rootInActiveWindow
            if (root != null) {
                when (stage) {
                    Stage.FIND_BUTTON -> {
                        val btn = find(root) { n ->
                            idContains(n, "force_stop") || idContains(n, "right_button") || textMatches(n, forceStopRegex)
                        }
                        if (btn != null) {
                            if (!btn.isEnabled) {
                                // приложение уже остановлено
                                finish(app, true); return
                            }
                            if (click(btn)) stage = Stage.FIND_CONFIRM
                        }
                    }
                    Stage.FIND_CONFIRM -> {
                        val ok = find(root) { n -> idContains(n, "android:id/button1") }
                            ?: find(root) { n -> n.isClickable && textMatches(n, confirmRegex) }
                        if (ok != null && click(ok)) {
                            finish(app, true); return
                        }
                    }
                    Stage.IDLE -> return
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun finish(app: AppEntry, success: Boolean) {
        handler.removeCallbacks(step)
        stage = Stage.IDLE
        target = null
        if (success) tracker.forget(app.pkg)
        Toast.makeText(
            service,
            service.getString(if (success) R.string.closed else R.string.close_failed, app.label),
            Toast.LENGTH_SHORT
        ).show()
        // уходим с экрана настроек (или с места закрытого приложения) на главный экран
        handler.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }, 400)
    }

    // ------------------------------------------------------------ поиск узлов

    private fun idContains(n: AccessibilityNodeInfo, s: String): Boolean =
        n.viewIdResourceName?.contains(s) == true

    private fun textMatches(n: AccessibilityNodeInfo, r: Regex): Boolean {
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        return (t != null && r.containsMatchIn(t)) || (d != null && r.containsMatchIn(d))
    }

    private fun find(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val r = find(child, pred)
            if (r != null) return r
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        var depth = 0
        while (node != null && depth < 6) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        // последняя попытка: кликнуть сам узел, даже если не помечен clickable
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
