package com.ilya.dzshka.mascot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ilya.dzshka.R
import com.ilya.dzshka.data.Store
import kotlin.math.abs
import kotlin.random.Random

/**
 * «Выпустить кота»: кот гуляет по экрану поверх всего.
 * Нажал — гладишь (реплика), тащишь пальцем — едет с тобой, двойное нажатие — домой (в виджет).
 */
class CatService : Service() {
    companion object {
        const val ACTION_OUT = "com.ilya.dzshka.CAT_OUT"
        const val ACTION_HOME = "com.ilya.dzshka.CAT_HOME"
        private const val CH = "cat"
        private const val NID = 0xCA7
        @Volatile var running = false
            private set

        fun out(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startForegroundService(Intent(ctx, CatService::class.java).setAction(ACTION_OUT))
        }

        fun home(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CatService::class.java).setAction(ACTION_HOME))
        }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private lateinit var img: ImageView
    private lateinit var bubble: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private val rnd = Random.Default

    private enum class St { SIT, WALK, HAPPY, DRAG, SLEEP, LEAVING }
    private var st = St.SIT
    private var targetX = 0
    private var stateUntil = 0L
    private var frame = 0
    private var facingLeft = false
    private var nextTalk = 0L
    private var bubbleUntil = 0L
    private val taps = mutableListOf<Long>()
    private var lastTap = 0L

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private val catSize by lazy { dp(84f) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        when (intent?.action) {
            ACTION_HOME -> { goHome(); return START_NOT_STICKY }
            else -> {
                startFg()
                if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
                if (root == null) show()
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Кот гуляет по экрану", NotificationManager.IMPORTANCE_MIN))
        val homePi = PendingIntent.getService(this, 1, Intent(this, CatService::class.java).setAction(ACTION_HOME), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${Store.d.mascot.name.ifBlank { "Кот" }} гуляет по экрану 🐾")
            .setContentText("Двойное нажатие на кота или кнопка ниже — домой")
            .addAction(0, "🏠 Домой", homePi)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        wm = getSystemService(WindowManager::class.java)
        bubble = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF3E2723.toInt())
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            maxWidth = dp(220f)
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dp(1f), 0x66C7741F) }
            elevation = dp(3f).toFloat()
            visibility = View.INVISIBLE
        }
        img = ImageView(this).apply { setImageResource(R.drawable.cat_wave) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2f) })
            addView(img, LinearLayout.LayoutParams(catSize, catSize))
        }
        root = FrameLayout(this).apply { addView(col) }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW() / 2 - catSize / 2
            y = (screenH() * 0.62).toInt()
        }
        root!!.setOnTouchListener(touch)
        wm.addView(root, lp)
        running = true
        Store.update { it.copy(mascot = it.mascot.copy(out = true)) }
        Store.saveNow()
        say(Mascot.react(Store.d, Phrases.released, Mascot.Mood.WAVE))
        st = St.HAPPY; stateUntil = now() + 2500
        nextTalk = now() + 40_000
        h.post(tick)
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun screenW(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
    private fun screenH(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    private fun say(line: Mascot.Line, ms: Long = 4500) {
        bubble.text = line.text
        bubble.visibility = View.VISIBLE
        bubbleUntil = now() + ms
        when (line.mood) {
            Mascot.Mood.HAPPY -> img.setImageResource(R.drawable.cat_happy)
            Mascot.Mood.WOW -> img.setImageResource(R.drawable.cat_wow)
            Mascot.Mood.WAVE -> img.setImageResource(R.drawable.cat_wave)
            Mascot.Mood.SLEEP -> img.setImageResource(R.drawable.cat_sleep)
            Mascot.Mood.SIT -> {}
        }
        img.scaleX = 1f
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = root ?: return
            val t = now()
            if (bubble.visibility == View.VISIBLE && t > bubbleUntil) bubble.visibility = View.INVISIBLE
            frame++
            when (st) {
                St.DRAG, St.LEAVING -> {}
                St.HAPPY -> if (t > stateUntil) { st = St.SIT; stateUntil = t + 2000 }
                St.SLEEP -> {
                    img.setImageResource(R.drawable.cat_sleep); img.scaleX = 1f
                    if (!Mascot.isNight()) { st = St.SIT; stateUntil = t + 1000 }
                }
                St.SIT -> {
                    img.scaleX = 1f
                    // моргает и иногда машет хвостом
                    val ph = frame % 80
                    img.setImageResource(when { ph in 0..3 -> R.drawable.cat_blink; ph in 40..55 -> R.drawable.cat_tail; else -> R.drawable.cat_sit })
                    if (Mascot.isNight()) { st = St.SLEEP }
                    else if (t > stateUntil) {
                        st = St.WALK
                        val w = screenW() - catSize
                        targetX = rnd.nextInt(0, w.coerceAtLeast(1))
                    }
                }
                St.WALK -> {
                    val step = dp(2.2f)
                    val dx = targetX - lp.x
                    if (abs(dx) <= step) {
                        st = St.SIT; stateUntil = t + rnd.nextLong(3000, 9000)
                    } else {
                        facingLeft = dx < 0
                        lp.x += if (facingLeft) -step else step
                        img.setImageResource(if ((frame / 5) % 2 == 0) R.drawable.cat_walk1 else R.drawable.cat_walk2)
                        img.scaleX = if (facingLeft) -1f else 1f
                        runCatching { wm.updateViewLayout(r, lp) }
                    }
                }
            }
            // сам по себе иногда что-то говорит
            if (t > nextTalk && st != St.DRAG && st != St.LEAVING) {
                nextTalk = t + rnd.nextLong(45_000, 90_000)
                if (st != St.SLEEP) { say(Mascot.another(Store.d, bubble.text?.toString())); if (st == St.WALK) img.scaleX = if (facingLeft) -1f else 1f }
            }
            h.postDelayed(this, 50)
        }
    }

    private val touch = object : View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop by lazy { ViewConfiguration.get(this@CatService).scaledTouchSlop }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true; st = St.DRAG
                        img.setImageResource(R.drawable.cat_wow); img.scaleX = 1f
                        say(Mascot.react(Store.d, Phrases.drag, Mascot.Mood.WOW))
                    }
                    if (dragging) {
                        lp.x = (startX + dx).toInt().coerceIn(0, (screenW() - catSize).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (screenH() - catSize - dp(40f)).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        say(Mascot.react(Store.d, Phrases.dropped, Mascot.Mood.SIT)); img.setImageResource(R.drawable.cat_sit)
                        st = St.SIT; stateUntil = now() + 4000
                    } else onTap()
                }
            }
            return true
        }
    }

    private fun onTap() {
        val t = now()
        if (t - lastTap < 320) { goHome(); return }
        lastTap = t
        taps.removeAll { t - it > 8000 }
        taps += t
        val d = Store.d
        val line = when {
            Mascot.isNight() -> Mascot.react(d, Phrases.sleepyTap, Mascot.Mood.SLEEP)
            taps.size >= 6 -> Mascot.react(d, Phrases.petSpam, Mascot.Mood.WOW)
            else -> Mascot.react(d, Phrases.pet, Mascot.Mood.HAPPY)
        }
        // чуть подождать: вдруг это двойное нажатие
        h.postDelayed({ if (lastTap == t && st != St.LEAVING) { say(line); st = if (line.mood == Mascot.Mood.SLEEP) St.SLEEP else St.HAPPY; stateUntil = now() + 2500 } }, 330)
    }

    private fun goHome() {
        if (root == null) { finish(); return }
        st = St.LEAVING
        say(Mascot.react(Store.d, Phrases.calledHome, Mascot.Mood.WAVE), 1800)
        h.postDelayed({ finish() }, 1600)
    }

    private fun finish() {
        h.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        running = false
        Store.update { it.copy(mascot = it.mascot.copy(out = false)) }
        Store.saveNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        running = false
        super.onDestroy()
    }
}
