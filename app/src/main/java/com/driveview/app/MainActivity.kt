package com.driveview.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: StereoRenderer
    private lateinit var statusView: TextView

    private var stream: UdpStream? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var ledOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen awake the whole time the app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        settings = Settings(this)
        renderer = StereoRenderer()
        renderer.cfg = settings.config()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        statusView = TextView(this).apply {
            text = "Connecting… (join the car's Wi-Fi first)"
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val statusLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        )
        statusLp.bottomMargin = dp(24)
        root.addView(statusView, statusLp)
        setContentView(root)

        // Tap the black center band = flash LED. Long-press = settings.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap(e.x)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                showSettings()
            }
        })
        glView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        hideSystemUi()
        acquireWifiLock()
        connectNetwork()
    }

    override fun onPause() {
        stream?.stop()
        stream = null
        releaseNetwork()
        releaseWifiLock()
        glView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------------- Network: stick to the Wi-Fi even if it has no internet ----------------
    private fun connectNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { cm.bindProcessToNetwork(network) } catch (_: Exception) {}
                runOnUiThread { startStream() }
            }
            override fun onLost(network: Network) {
                setStatus("Wi-Fi lost – join the DriveView network")
            }
        }
        netCallback = cb
        try {
            cm.requestNetwork(req, cb)
        } catch (e: Exception) {
            startStream()   // fall back to the default network
        }
    }

    private fun releaseNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
    }

    private fun startStream() {
        stream?.stop()
        stream = UdpStream(
            settings.host,
            onFrame = { bmp ->
                renderer.submit(bmp)
                glView.requestRender()
            },
            onStatus = { msg -> setStatus(msg) }
        ).also {
            it.start()
            it.setLight(if (ledOn) settings.ledLevel else 0)
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread {
            statusView.text = msg
            statusView.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------------- Wi-Fi lock: stops the phone's Wi-Fi from power saving (which made the video lag) ----------------
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val lock = wm.createWifiLock(mode, "DriveView")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    // ---------------- LED ----------------
    private fun onTap(x: Float) {
        val w = glView.width.toFloat()
        val h = glView.height.toFloat()
        val g = Geo.tiles(w, h, renderer.cfg)
        val half = max((g[1] - (g[0] + g[3])) / 2f, w * 0.03f)
        if (abs(x - w / 2f) < half) toggleLed()
    }

    private fun toggleLed() {
        ledOn = !ledOn
        stream?.setLight(if (ledOn) settings.ledLevel else 0)
    }

    // ---------------- Settings dialog (long-press) ----------------
    private fun showSettings() {
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val fields = LinkedHashMap<String, EditText>()

        fun add(key: String, label: String, value: String, number: Boolean = true) {
            box.addView(TextView(this).apply { text = label })
            val et = EditText(this).apply {
                setText(value)
                setSingleLine()
                inputType = if (number) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }
            fields[key] = et
            box.addView(et)
        }

        add("host", "Car address (ESP32 IP)", settings.host, false)
        add("k1", "Lens correction k1 (0 = off)", settings.k1.toString())
        add("k2", "Lens correction k2", settings.k2.toString())
        add("zoom", "Zoom (bigger = fills the tile more)", settings.zoom.toString())
        add("sep", "Eye separation (fraction of screen width)", settings.sep.toString())
        add("size", "Tile size (fraction of screen height)", settings.size.toString())
        add("radius", "Corner roundness (0 – 0.5)", settings.radius.toString())
        add("sharp", "Sharpening (0 – 1)", settings.sharp.toString())
        add("led", "LED level (0 – 255)", settings.ledLevel.toString())

        val flipX = CheckBox(this).apply { text = "Mirror left/right"; isChecked = settings.flipX }
        val flipY = CheckBox(this).apply { text = "Flip upside down"; isChecked = settings.flipY }
        box.addView(flipX)
        box.addView(flipY)

        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("DriveView settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val oldHost = settings.host
                fields["host"]?.text?.toString()?.takeIf { it.isNotBlank() }?.let { settings.host = it }
                fields["k1"]?.text?.toString()?.toFloatOrNull()?.let { settings.k1 = it }
                fields["k2"]?.text?.toString()?.toFloatOrNull()?.let { settings.k2 = it }
                fields["zoom"]?.text?.toString()?.toFloatOrNull()?.let { if (it > 0.1f) settings.zoom = it }
                fields["sep"]?.text?.toString()?.toFloatOrNull()?.let { if (it > 0.1f) settings.sep = it }
                fields["size"]?.text?.toString()?.toFloatOrNull()?.let { if (it > 0.1f) settings.size = it }
                fields["radius"]?.text?.toString()?.toFloatOrNull()?.let { settings.radius = it.coerceIn(0f, 0.5f) }
                fields["sharp"]?.text?.toString()?.toFloatOrNull()?.let { settings.sharp = it.coerceIn(0f, 2f) }
                fields["led"]?.text?.toString()?.toIntOrNull()?.let { settings.ledLevel = it }
                settings.flipX = flipX.isChecked
                settings.flipY = flipY.isChecked
                applySettings(oldHost != settings.host)
            }
            .setNeutralButton("Reset") { _, _ ->
                settings.reset()
                applySettings(true)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { hideSystemUi() }
            .show()
    }

    private fun applySettings(restartStream: Boolean) {
        renderer.cfg = settings.config()
        glView.requestRender()
        if (restartStream) startStream()
    }

    // ---------------- Helpers ----------------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
