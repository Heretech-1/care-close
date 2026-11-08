package com.careclose.feature.elder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.careclose.core.model.FloatingButtonSize
import kotlin.math.hypot

class ElderFloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingBubbleView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null

    private var currentSize: FloatingButtonSize = FloatingButtonSize.LARGE
    private var isHighContrast: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sizeName = intent?.getStringExtra(EXTRA_BUTTON_SIZE)
        val newSize = sizeName?.let { runCatching { FloatingButtonSize.valueOf(it) }.getOrNull() }
            ?: currentSize
        val newContrast = intent?.getBooleanExtra(EXTRA_HIGH_CONTRAST, isHighContrast) ?: isHighContrast

        if (newSize != currentSize || newContrast != isHighContrast || floatingBubbleView == null) {
            currentSize = newSize
            isHighContrast = newContrast
            setupOrUpdateBubble()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            stopSelf()
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        currentParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 350
        }

        setupOrUpdateBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOrUpdateBubble() {
        val wm = windowManager ?: return
        val params = currentParams ?: return

        floatingBubbleView?.let { oldView ->
            runCatching { wm.removeView(oldView) }
        }

        val bubble = createBubbleView(currentSize, isHighContrast)
        floatingBubbleView = bubble

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (hypot(dx.toDouble(), dy.toDouble()) > 14.0) {
                        isClick = false
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { wm.updateViewLayout(bubble, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (isClick) {
                        launchApp()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    true
                }

                else -> false
            }
        }

        runCatching {
            wm.addView(bubble, params)
        }.onFailure {
            stopSelf()
        }
    }

    private fun createBubbleView(size: FloatingButtonSize, highContrast: Boolean): View {
        val density = resources.displayMetrics.density
        val sizePx = (size.dpSize * density).toInt()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (highContrast) {
                setColor(Color.BLACK)
                setStroke((4.5f * density).toInt(), Color.parseColor("#FFEA00")) // Neon Yellow
            } else {
                setColor(Color.parseColor("#1B5E20")) // Deep Vibrant CareClose Green
                setStroke((3.5f * density).toInt(), Color.parseColor("#A7FFEB")) // Bright mint accent ring
            }
        }
        root.background = circleBg
        root.elevation = 16 * density

        val contentLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val iconSp = when (size) {
            FloatingButtonSize.STANDARD -> 28f
            FloatingButtonSize.LARGE -> 36f
            FloatingButtonSize.EXTRA_LARGE -> 44f
        }

        val labelSp = when (size) {
            FloatingButtonSize.STANDARD -> 11f
            FloatingButtonSize.LARGE -> 13f
            FloatingButtonSize.EXTRA_LARGE -> 15f
        }

        val iconText = TextView(this).apply {
            text = "❤️"
            textSize = iconSp
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        val label = TextView(this).apply {
            text = "ДІМ"
            textSize = labelSp
            typeface = Typeface.DEFAULT_BOLD
            if (highContrast) {
                setTextColor(Color.parseColor("#FFEA00"))
            } else {
                setTextColor(Color.WHITE)
            }
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }

        contentLayout.addView(iconText)
        contentLayout.addView(label)
        root.addView(contentLayout)

        return root
    }

    private fun launchApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingBubbleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        floatingBubbleView = null
    }

    companion object {
        const val EXTRA_BUTTON_SIZE = "extra_button_size"
        const val EXTRA_HIGH_CONTRAST = "extra_high_contrast"

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun start(
            context: Context,
            buttonSize: FloatingButtonSize = FloatingButtonSize.LARGE,
            isHighContrast: Boolean = false,
        ) {
            if (!canDrawOverlays(context)) return
            runCatching {
                val intent = Intent(context, ElderFloatingBubbleService::class.java).apply {
                    putExtra(EXTRA_BUTTON_SIZE, buttonSize.name)
                    putExtra(EXTRA_HIGH_CONTRAST, isHighContrast)
                }
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ElderFloatingBubbleService::class.java))
            }
        }
    }
}

