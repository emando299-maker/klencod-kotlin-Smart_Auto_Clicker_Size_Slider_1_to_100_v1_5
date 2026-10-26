package com.smartautoclicker.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var intervalInput: EditText
    private lateinit var wordInput: EditText
    private var pointsVisible = true

    private val navy = Color.rgb(13, 20, 38)
    private val card = Color.rgb(25, 37, 63)
    private val blue = Color.rgb(66, 112, 255)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointsVisible = prefs.getBoolean("points_visible", true)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundColor(navy)
        }
        fun label(text: String, size: Float, color: Int = white, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(7))
        }
        fun section(title: String): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(card, dp(18).toFloat()) }
            box.addView(label(title, 17f, white, true))
            root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return box
        }
        fun button(parent: LinearLayout, text: String, action: () -> Unit, primary: Boolean = false) {
            val b = Button(this).apply {
                this.text = text; isAllCaps = false; textSize = 15f; setTextColor(white)
                background = rounded(if (primary) blue else Color.rgb(46, 61, 91), dp(12).toFloat())
                setOnClickListener { action() }
            }
            parent.addView(b, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        }

        root.addView(label("Smart Auto Clicker", 27f, white, true))
        root.addView(label("تحكم بسيط وحديث في نقاط النقر", 15f, Color.rgb(174, 190, 220)))

        val points = section("نقاط النقر")
        points.addView(label("أضف نقطة، ثم اسحبها إلى المكان المطلوب. اضغط عليها مرتين لتشغيلها أو إيقافها بشكل مستقل.", 14f, Color.LTGRAY))
        button(points, "＋  إضافة نقطة نقر", {
            if (!ensureOverlayPermission()) return@button
            if (!isServiceEnabled()) { status.text = "فعّل خدمة إمكانية الوصول أولًا"; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@button }
            sendServiceAction("ADD_MARKER"); status.text = "تم طلب إضافة نقطة نقر"
        }, true)
        val markerSizeLabel = label("حجم زر النقر: ${prefs.getInt("marker_size_percent", 50)} / 100", 14f, Color.LTGRAY)
        points.addView(markerSizeLabel)
        val markerSizeSlider = SeekBar(this).apply {
            max = 99
            progress = (prefs.getInt("marker_size_percent", 50).coerceIn(1, 100) - 1)
            contentDescription = "حجم نقطة النقر من 1 إلى 100"
        }
        markerSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1
                markerSizeLabel.text = "حجم زر النقر: $value / 100"
                if (fromUser) {
                    prefs.edit().putInt("marker_size_percent", value).apply()
                    sendServiceAction("SET_MARKER_SIZE", value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        points.addView(markerSizeSlider, LinearLayout.LayoutParams(-1, dp(44)))
        button(points, if (pointsVisible) "إخفاء نقاط النقر" else "إظهار نقاط النقر", {
            if (!ensureOverlayPermission()) return@button
            pointsVisible = !pointsVisible
            prefs.edit().putBoolean("points_visible", pointsVisible).apply()
            sendServiceAction(if (pointsVisible) "SHOW_MARKERS" else "HIDE_MARKERS")
            status.text = if (pointsVisible) "نقاط النقر ظاهرة" else "تم إخفاء نقاط النقر"
            // Update the visible label after state change.
            recreate()
        })
        button(points, "حذف آخر نقطة", { sendServiceAction("REMOVE_MARKER"); status.text = "تم طلب حذف آخر نقطة" })

        val settings = section("إعدادات النقر")
        settings.addView(label("الفاصل بين الضغطات (مللي ثانية)", 14f, Color.LTGRAY))
        intervalInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true); setTextColor(white); setHintTextColor(Color.LTGRAY)
            setText(prefs.getLong("interval_ms", 1000L).toString()); hint = "من 0 إلى 86400000"
            background = rounded(Color.rgb(37, 52, 79), dp(10).toFloat()); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        settings.addView(intervalInput, LinearLayout.LayoutParams(-1, dp(48)))
        button(settings, "حفظ سرعة النقر", {
            val n = intervalInput.text.toString().toLongOrNull()
            if (n == null || n !in 0L..86_400_000L) intervalInput.error = "أدخل رقمًا من 0 إلى 86400000"
            else { prefs.edit().putLong("interval_ms", n).apply(); status.text = "تم حفظ الفاصل: $n مللي ثانية" }
        })

        val search = section("البحث عن كلمة (اختياري)")
        wordInput = EditText(this).apply {
            hint = "اكتب الكلمة المطلوب البحث عنها"; setSingleLine(true); setTextColor(white); setHintTextColor(Color.LTGRAY)
            setText(prefs.getString("word", "")); background = rounded(Color.rgb(37, 52, 79), dp(10).toFloat()); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        search.addView(wordInput, LinearLayout.LayoutParams(-1, dp(48)))
        button(search, "حفظ كلمة البحث", { prefs.edit().putString("word", wordInput.text.toString().trim()).apply(); status.text = "تم حفظ كلمة البحث" })
        button(search, "إعدادات إمكانية الوصول", { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        status = label("الحالة: جاهز", 14f, Color.rgb(143, 210, 177))
        status.gravity = Gravity.CENTER
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        status.text = "اسمح بعرض نقاط النقر فوق التطبيقات ثم ارجع للتطبيق"
        return false
    }
    private fun sendServiceAction(action: String, value: Int? = null) {
        val intent = Intent("$packageName.$action").setPackage(packageName)
        if (value != null) intent.putExtra("value", value)
        sendBroadcast(intent)
    }
    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name.endsWith("AutoClickService")
        }
    }
}