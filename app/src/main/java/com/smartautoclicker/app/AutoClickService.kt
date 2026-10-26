package com.smartautoclicker.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class AutoClickService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var running = false
    private var lastClickTime = 0L
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val markers = mutableListOf<TextView>()
    private val markerParams = mutableListOf<WindowManager.LayoutParams>()
    private val markerActive = mutableListOf<Boolean>()
    private val markerLastTap = mutableListOf<Long>()
    private var markerSize = 56

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "$packageName.ADD_MARKER" -> addMarker()
                "$packageName.REMOVE_MARKER" -> removeLastMarker()
                "$packageName.HIDE_MARKERS" -> markers.forEach { it.visibility = View.GONE }
                "$packageName.SHOW_MARKERS" -> markers.forEach { it.visibility = View.VISIBLE }
                "$packageName.MARKERS_LARGER" -> resizeMarkers(8)
                "$packageName.MARKERS_SMALLER" -> resizeMarkers(-8)
                "$packageName.SET_MARKER_SIZE" -> setMarkerSizePercent(intent.getIntExtra("value", 50))
                "$packageName.STOP_ALL" -> { for (i in markerActive.indices) markerActive[i] = false; running = false; handler.removeCallbacks(clickRunnable) }
                "$packageName.SHOW_CLICK_MARKER" -> { /* legacy floating control intentionally disabled */ }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            val f = IntentFilter()
            listOf("ADD_MARKER", "REMOVE_MARKER", "HIDE_MARKERS", "SHOW_MARKERS", "MARKERS_LARGER", "MARKERS_SMALLER", "SET_MARKER_SIZE", "STOP_ALL", "SHOW_CLICK_MARKER").forEach { f.addAction("$packageName.$it") }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, f)
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoClickService", "Failed to register marker receiver", e)
        }
        restoreMarkers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (markers.isNotEmpty()) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val word = prefs.getString("word", "") ?: ""
        if (word.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastClickTime < prefs.getLong("interval_ms", 1000L).coerceIn(0L, 86_400_000L).coerceAtLeast(1L)) return
        val root = rootInActiveWindow ?: return
        if (searchAndClick(root, word)) lastClickTime = now
    }

    private fun overlayType() = if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    private fun lp(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(w, h, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x=x; this.y=y }
    private fun bg(color: Int, radius: Float = 18f) = GradientDrawable().apply { setColor(color); cornerRadius=radius }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showFloating() {
        if (bubble != null) { bubble?.visibility = View.VISIBLE; return }
        bubble = TextView(this).apply {
            text = "SC"; textSize=16f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); background=bg(0xFF6750A4.toInt(), 100f)
            contentDescription = "لوحة التحكم العائمة"
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        bubbleParams = lp(dp(52), dp(52), dp(12), dp(160))
        makeDraggable(bubble!!, bubbleParams!!, false)
        try { wm.addView(bubble, bubbleParams) } catch (_: Exception) { bubble=null; bubbleParams=null; return }
        restoreMarkers()
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams, marker: Boolean) {
        var sx=0f; var sy=0f; var px=0; var py=0; var moved=false
        view.setOnTouchListener { v, e ->
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; px=params.x; py=params.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if(abs(dx)>dp(4)||abs(dy)>dp(4)) moved=true; params.x=px+dx; params.y=py+dy; try { wm.updateViewLayout(v,params) } catch (_:Exception) {}; true }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (marker) {
                            val idx = markers.indexOf(v as TextView)
                            if (idx >= 0) {
                                val now = System.currentTimeMillis()
                                if (now - markerLastTap.getOrElse(idx) { 0L } < 450L) {
                                    markerActive[idx] = !markerActive[idx]
                                    markerLastTap[idx] = 0L
                                    (v as TextView).alpha = if (markerActive[idx]) 1f else 0.55f
                                    if (markerActive.any { it } && !running) { running = true; handler.post(clickRunnable) }
                                    if (!markerActive.any { it }) { running = false; handler.removeCallbacks(clickRunnable) }
                                } else markerLastTap[idx] = now
                            }
                        } else v.performClick()
                    } else if (marker) saveMarkers()
                    true
                }
                else -> true
            }
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)); background=bg(0xEE202127.toInt(), dp(16).toFloat()) }
        fun button(label: String, action: () -> Unit) { box.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, dp(44))) }
        val heading = TextView(this).apply { text="Smart Auto Clicker"; textSize=18f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0,0,0,dp(6)) }
        box.addView(heading)
        val intervalLabel = TextView(this).apply { setTextColor(Color.WHITE); gravity=Gravity.CENTER; textSize=14f }
        fun refreshSpeed() { intervalLabel.text="الفاصل: ${getSharedPreferences("settings", MODE_PRIVATE).getLong("interval_ms",1000L)} ms" }
        box.addView(intervalLabel); refreshSpeed()
        button("السرعة − 100 ms") { changeInterval(-100); refreshSpeed() }
        button("السرعة + 100 ms") { changeInterval(100); refreshSpeed() }
        button("تحديد السرعة / الوحدة") { editInterval { refreshSpeed() } }
        button("＋ إضافة نقطة ضغط") { addMarker(); }
        button("－ حذف آخر نقطة") { removeLastMarker() }
        button(if (running) "Ⅱ إيقاف الضغط" else "▶ بدء الضغط") { toggleRunning(); if (panel != null) { /* update next open */ } }
        button("إخفاء / إظهار نقاط الضغط") { markers.forEach { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
        button("تصغير للشريط") { showCompactStrip() }
        button("إغلاق اللوحة والرجوع للأيقونة") { hidePanel(); bubble?.visibility=View.VISIBLE }
        button("إخفاء الأيقونة العائمة") { hidePanel(); bubble?.visibility=View.GONE }
        panel=box; panelParams=lp(dp(220), -2, dp(70), dp(160))
        try { wm.addView(box,panelParams) } catch (_:Exception) { panel=null; panelParams=null }
    }

    private fun showCompactStrip() {
        hidePanel()
        val strip=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(dp(5),dp(4),dp(5),dp(4)); background=bg(0xEE202127.toInt(), dp(14).toFloat()) }
        fun small(label:String, action:()->Unit) { strip.addView(Button(this).apply { text=label; isAllCaps=false; setPadding(dp(4),0,dp(4),0); setOnClickListener { action() } }, LinearLayout.LayoutParams(-2,dp(42))) }
        small(if(running) "Ⅱ" else "▶") { toggleRunning(); showCompactStrip() }
        small("＋") { addMarker() }
        small("−") { removeLastMarker() }
        small("سرعة") { editInterval {} }
        small("☰") { hidePanel(); showPanel() }
        panel=strip; panelParams=lp(-2,-2,dp(70),dp(160))
        try { wm.addView(strip,panelParams) } catch (_:Exception) { panel=null; panelParams=null }
    }
    private fun hidePanel() { try { panel?.let { wm.removeView(it) } } catch (_:Exception) {}; panel=null; panelParams=null }
    private fun changeInterval(delta: Long) {
        val p=getSharedPreferences("settings",MODE_PRIVATE); val value=(p.getLong("interval_ms",1000L)+delta).coerceIn(0L,86_400_000L); p.edit().putLong("interval_ms",value).apply()
    }
    private fun editInterval(done: () -> Unit) {
        val input=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER; setText(getSharedPreferences("settings",MODE_PRIVATE).getLong("interval_ms",1000L).toString()); hint="المللي ثانية (0 إلى 86400000)" }
        AlertDialog.Builder(this).setTitle("الفاصل بين الضغطات بالمللي ثانية").setView(input).setNegativeButton("إلغاء",null).setPositiveButton("حفظ") { _,_ ->
            val n=input.text.toString().toLongOrNull(); if(n != null && n in 0L..86_400_000L) getSharedPreferences("settings",MODE_PRIVATE).edit().putLong("interval_ms",n).apply(); done()
        }.create().also { it.window?.setType(overlayType()); it.show() }
    }

    private fun addMarker() {
        val percent = getSharedPreferences("settings", MODE_PRIVATE).getInt("marker_size_percent", 50).coerceIn(1, 100)
        markerSize = (24 + ((120 - 24) * (percent - 1) / 99.0)).toInt()
        val size=dp(markerSize)
        val view=TextView(this).apply { text="●"; textSize=24f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(0xAAFF453A.toInt()); setStroke(dp(2),Color.WHITE) }; contentDescription="نقطة ضغط قابلة للسحب" }
        val p=lp(size,size,dp(120+markers.size*28),dp(320+markers.size*28))
        makeDraggable(view,p,true)
        try { wm.addView(view,p); markers.add(view); markerParams.add(p); markerActive.add(false); markerLastTap.add(0L); saveMarkers() } catch (e:Exception) { android.util.Log.e("AutoClickService", "Unable to add marker overlay", e) }
    }
    private fun setMarkerSizePercent(percent: Int) {
        val p = percent.coerceIn(1, 100)
        markerSize = (24 + ((120 - 24) * (p - 1) / 99.0)).toInt()
        markers.forEachIndexed { i, view ->
            val params = markerParams.getOrNull(i) ?: return@forEachIndexed
            params.width = dp(markerSize)
            params.height = dp(markerSize)
            view.textSize = (markerSize * 0.43f).coerceIn(10f, 48f)
            try { wm.updateViewLayout(view, params) } catch (e: Exception) { android.util.Log.e("AutoClickService", "Unable to resize marker", e) }
        }
        saveMarkers()
    }

    private fun resizeMarkers(deltaDp: Int) {
        markers.forEachIndexed { i, view ->
            val params = markerParams.getOrNull(i) ?: return@forEachIndexed
            val newSize = (params.width + dp(deltaDp)).coerceIn(dp(24), dp(120))
            params.width = newSize
            params.height = newSize
            view.textSize = (newSize / resources.displayMetrics.density * 0.43f).coerceIn(12f, 48f)
            try { wm.updateViewLayout(view, params) } catch (e: Exception) { android.util.Log.e("AutoClickService", "Unable to resize marker", e) }
        }
        saveMarkers()
    }

    private fun removeLastMarker() {
        if(markers.isEmpty()) return
        val v=markers.removeAt(markers.lastIndex); markerParams.removeAt(markerParams.lastIndex)
        markerActive.removeAt(markerActive.lastIndex); markerLastTap.removeAt(markerLastTap.lastIndex)
        try { wm.removeView(v) } catch (_:Exception) {}; saveMarkers()
    }
    private fun toggleRunning() { running=!running; handler.removeCallbacks(clickRunnable); if(running) handler.post(clickRunnable) }
    private val clickRunnable=object:Runnable { override fun run() {
        if(!running) return
        markers.forEachIndexed { i, v -> if(v.visibility==View.VISIBLE && markerActive.getOrElse(i) { false }) { val p=markerParams.getOrNull(i); if(p!=null) clickAt(p.x+p.width/2f,p.y+p.height/2f) } }
        val delay=getSharedPreferences("settings",MODE_PRIVATE).getLong("interval_ms",1000L).coerceIn(0L,86_400_000L).coerceAtLeast(1L)
        handler.postDelayed(this,delay)
    } }
    private fun clickAt(x:Float,y:Float) { val path=Path().apply { moveTo(x,y) }; val gesture=android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path,0,50)).build(); dispatchGesture(gesture,null,null) }

    private fun saveMarkers() {
        val a=JSONArray(); markerParams.forEach { p -> a.put(JSONObject().put("x",p.x).put("y",p.y).put("size",p.width)) }
        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("markers",a.toString()).apply()
    }
    private fun restoreMarkers() {
        val s=getSharedPreferences("settings",MODE_PRIVATE).getString("markers",null) ?: return
        try { val a=JSONArray(s); for(i in 0 until a.length()) { val o=a.getJSONObject(i); addMarkerAt(o.optInt("x",120),o.optInt("y",320),o.optInt("size",dp(markerSize))) } } catch (_:Exception) {}
    }
    private fun addMarkerAt(x:Int,y:Int,size:Int) {
        val view=TextView(this).apply { text="●"; textSize=24f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(0xAAFF453A.toInt()); setStroke(dp(2),Color.WHITE) } }
        val p=lp(size,size,x,y); makeDraggable(view,p,true); try { wm.addView(view,p); markers.add(view); markerParams.add(p); markerActive.add(false); markerLastTap.add(0L) } catch (e:Exception) { android.util.Log.e("AutoClickService", "Unable to restore marker overlay", e) }
    }
    private fun searchAndClick(node: AccessibilityNodeInfo, word: String): Boolean {
        if(node.text?.toString()?.contains(word,true)==true || node.contentDescription?.toString()?.contains(word,true)==true) { var t:AccessibilityNodeInfo?=node; var d=0; while(t!=null&&d<10) { if(t.isClickable&&t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; t=t.parent; d++ } }
        for(i in 0 until node.childCount) { val child=node.getChild(i) ?: continue; if(searchAndClick(child,word)) return true }
        return false
    }
    override fun onInterrupt() { running=false; handler.removeCallbacks(clickRunnable) }
    override fun onDestroy() { handler.removeCallbacks(clickRunnable); try { unregisterReceiver(receiver) } catch (_:Exception) {}; hidePanel(); try { bubble?.let{wm.removeView(it)} } catch (_:Exception) {}; markers.toList().forEach { try{wm.removeView(it)}catch(_:Exception){} }; markers.clear(); markerParams.clear(); markerActive.clear(); markerLastTap.clear(); super.onDestroy() }
}
