package az.anar.carswitcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process

data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

/**
 * Ведёт список недавно использованных приложений (LRU) по событиям службы
 * специальных возможностей и, если дан доступ, по статистике использования.
 */
class AppTracker(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val recent = ArrayDeque<String>().apply { addAll(Prefs.recentPackages(ctx)) }

    /** Пакет приложения, которое сейчас на экране (кроме лаунчера, настроек и нас самих). */
    @Volatile
    var foregroundPackage: String? = null
        private set

    private val excluded: Set<String> by lazy {
        val set = mutableSetOf(ctx.packageName, "com.android.systemui", "com.android.settings", "android")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { set += it }
        set
    }

    fun isLaunchable(pkg: String): Boolean = pm.getLaunchIntentForPackage(pkg) != null

    fun onForeground(pkg: String) {
        if (pkg in excluded || !isLaunchable(pkg)) return
        foregroundPackage = pkg
        synchronized(recent) {
            recent.remove(pkg)
            recent.addFirst(pkg)
            while (recent.size > MAX) recent.removeLast()
            Prefs.saveRecentPackages(ctx, recent.toList())
        }
    }

    fun forget(pkg: String) {
        synchronized(recent) {
            recent.remove(pkg)
            Prefs.saveRecentPackages(ctx, recent.toList())
        }
        if (foregroundPackage == pkg) foregroundPackage = null
    }

    fun recentApps(): List<AppEntry> {
        val ordered = LinkedHashMap<String, Long>()
        synchronized(recent) { recent.forEachIndexed { i, p -> ordered[p] = Long.MAX_VALUE - i } }
        if (hasUsageAccess(ctx)) {
            fromUsageStats().forEach { (p, t) -> if (p !in ordered) ordered[p] = t }
        }
        return ordered.entries
            .sortedByDescending { it.value }
            .mapNotNull { toEntry(it.key) }
            .take(MAX)
    }

    private fun fromUsageStats(): List<Pair<String, Long>> = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 12 * 3600_000L, now)
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val p = e.packageName ?: continue
                if (p !in excluded && isLaunchable(p)) last[p] = e.timeStamp
            }
        }
        last.entries.sortedByDescending { it.value }.map { it.key to it.value }
    } catch (ex: Exception) {
        emptyList()
    }

    fun toEntry(pkg: String): AppEntry? = try {
        val ai = pm.getApplicationInfo(pkg, 0)
        AppEntry(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        const val MAX = 12

        fun hasUsageAccess(c: Context): Boolean {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
