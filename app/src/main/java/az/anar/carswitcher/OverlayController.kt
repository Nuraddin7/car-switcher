package az.anar.carswitcher

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Плавающая кнопка + две полноэкранные панели:
 * переключатель приложений и подтверждение закрытия.
 */
class OverlayController(
    private val service: CarAccessibilityService,
    private val tracker: AppTracker,
    private val stopper: ForceStopper
) {
    private val ctx get() = service
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var button: View? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val autoHide = Runnable { hidePanel() }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        else ctx.resources.displayMetrics.let { Point(it.widthPixels, it.heightPixels) }

    fun canDraw(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    // ---------------------------------------------------------------- кнопка

    fun showButton() {
        if (button != null || !canDraw()) return
        val size = dp(Prefs.buttonSizeDp(ctx))
        val view = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_apps)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = size / 5
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61565C0.toInt())
                setStroke(dp(3), Color.WHITE)
            }
            contentDescription = ctx.getString(R.string.app_name)
        }
        val scr = screenSize()
        val (sx, sy) = Prefs.buttonPos(ctx)
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (sx >= 0) sx.coerceIn(0, scr.x - size) else scr.x - size - dp(12)
            y = if (sy >= 0) sy.coerceIn(0, scr.y - size) else scr.y / 2
        }
        attachTouch(view, lp)
        try {
            wm.addView(view, lp)
            button = view
            buttonLp = lp
        } catch (e: Exception) {
            button = null
        }
    }

    private fun attachTouch(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { showSwitcher(); return true }
        })
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false

        view.setOnTouchListener { v, ev ->
            if (!dragging) detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        detector.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        val scr = screenSize()
                        lp.x = (startX + dx).toInt().coerceIn(0, scr.x - v.width)
                        lp.y = (startY + dy).toInt().coerceIn(0, scr.y - v.height)
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // прилипаем к ближнему боковому краю
                        val scr = screenSize()
                        lp.x = if (lp.x + v.width / 2 < scr.x / 2) dp(12) else scr.x - v.width - dp(12)
                        wm.updateViewLayout(v, lp)
                        Prefs.saveButtonPos(ctx, lp.x, lp.y)
                        dragging = false
                    }
                }
            }
            true
        }
    }

    fun onScreenChanged() {
        val v = button ?: return
        val lp = buttonLp ?: return
        val scr = screenSize()
        lp.x = lp.x.coerceIn(0, maxOf(0, scr.x - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, scr.y - lp.height))
        runCatching { wm.updateViewLayout(v, lp) }
        hidePanel()
    }

    fun removeAll() {
        hidePanel()
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        buttonLp = null
    }

    // ---------------------------------------------------------------- панели

    private fun showPanel(content: View, autoHideMs: Long) {
        hidePanel()
        val wrapper = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) hidePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        wrapper.isFocusableInTouchMode = true
        wrapper.addView(content, FrameLayout.LayoutParams(match, match))
        val lp = WindowManager.LayoutParams(
            match, match, overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(wrapper, lp)
        } catch (e: Exception) {
            return
        }
        wrapper.requestFocus()
        panel = wrapper
        button?.visibility = View.GONE
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, autoHideMs)
    }

    fun hidePanel() {
        handler.removeCallbacks(autoHide)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        button?.visibility = View.VISIBLE
    }

    private fun bigButton(text: String, color: Int, textSp: Float, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(color)
            }
            setOnClickListener { onClick() }
        }

    private fun goHome() {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun toast(resId: Int, arg: String? = null) {
        val msg = if (arg == null) ctx.getString(resId) else ctx.getString(resId, arg)
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------- переключатель приложений

    fun showSwitcher() {
        val apps = tracker.recentApps()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0101820.toInt())
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(16), dp(8))
        }
        header.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.running_apps)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(
            bigButton("✕", 0xFF37474F.toInt(), 36f) { hidePanel() },
            LinearLayout.LayoutParams(dp(88), dp(88))
        )
        root.addView(header, LinearLayout.LayoutParams(match, wrap))

        if (apps.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.no_apps)
                setTextColor(0xFFB0BEC5.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
            }, LinearLayout.LayoutParams(match, 0, 1f))
        } else {
            val grid = GridView(ctx).apply {
                numColumns = 3
                verticalSpacing = dp(12)
                horizontalSpacing = dp(12)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                setPadding(dp(16), dp(8), dp(16), dp(8))
                clipToPadding = false
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                adapter = AppAdapter(apps.toMutableList())
            }
            root.addView(grid, LinearLayout.LayoutParams(match, 0, 1f))
        }

        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        bottom.addView(
            bigButton(ctx.getString(R.string.home), 0xFF455A64.toInt(), 26f) { hidePanel(); goHome() },
            LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = dp(12) }
        )
        bottom.addView(
            bigButton(ctx.getString(R.string.cancel), 0xFF37474F.toInt(), 26f) { hidePanel() },
            LinearLayout.LayoutParams(0, dp(96), 1f)
        )
        root.addView(bottom, LinearLayout.LayoutParams(match, wrap))

        showPanel(root, 20_000)
    }

    private fun launch(app: AppEntry) {
        hidePanel()
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            toast(R.string.launch_failed); return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.launch_failed)
        }
    }

    private inner class AppAdapter(private val apps: MutableList<AppEntry>) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(match, dp(176))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(0xFF263238.toInt())
                }
                // содержимое: иконка + подпись
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    addView(ImageView(ctx), LinearLayout.LayoutParams(dp(96), dp(96)))
                    addView(TextView(ctx).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
                }, FrameLayout.LayoutParams(match, match))
                // крестик закрытия в правом верхнем углу
                addView(TextView(ctx).apply {
                    text = "\u2715"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTypeface(null, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFC62828.toInt())
                        setStroke(dp(2), Color.WHITE)
                    }
                }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6); rightMargin = dp(6)
                })
            }
            val app = apps[position]
            val content = cell.getChildAt(0) as LinearLayout
            (content.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (content.getChildAt(1) as TextView).text = app.label
            cell.setOnClickListener { launch(app) }
            cell.getChildAt(1).setOnClickListener {
                handler.removeCallbacks(autoHide)
                handler.postDelayed(autoHide, 20_000)
                apps.remove(app)
                notifyDataSetChanged()
                stopper.kill(app)
            }
            return cell
        }
    }
}
