package com.talalqasem.netcard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), PortalLogin.Callback {

    enum class Mode { COPY, MANUAL, AUTO, SAVE }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var detectedText: TextView
    private lateinit var hintText: TextView
    private lateinit var flashLabel: TextView
    private lateinit var advLabel: TextView
    private lateinit var advSwitch: SwitchCompat
    private lateinit var mainContent: View
    private lateinit var portalPanel: View
    private lateinit var successOverlay: View
    private lateinit var webView: WebView
    private lateinit var tabs: Map<Mode, TextView>
    private lateinit var portal: PortalLogin

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var camera: Camera? = null
    private var torchOn = false
    private var mode = Mode.AUTO
    private var currentCard: CardParser.CardPair? = null
    private var lastPair: CardParser.CardPair? = null
    private var stableCount = 0
    private var portalShown = false

    @Volatile private var scanning = true
    @Volatile private var lastAnalysis = 0L

    private val hideSuccess = Runnable { successOverlay.visibility = View.GONE }

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else setStatus("الكاميرا غير مفعّلة — اضغط هنا لمنح الإذن")
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanImage(uri)
    }

    private val savedCards = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val u = d.getStringExtra("user")
            val p = d.getStringExtra("pass")
            if (u != null && p != null) startLogin(CardParser.CardPair(u, p))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        statusText = findViewById(R.id.statusText)
        detectedText = findViewById(R.id.detectedText)
        hintText = findViewById(R.id.hintText)
        flashLabel = findViewById(R.id.flashLabel)
        advLabel = findViewById(R.id.advLabel)
        advSwitch = findViewById(R.id.advSwitch)
        mainContent = findViewById(R.id.mainContent)
        portalPanel = findViewById(R.id.portalPanel)
        successOverlay = findViewById(R.id.successOverlay)
        webView = findViewById(R.id.webView)

        setupWebView()
        portal = PortalLogin(this, webView, this)

        tabs = mapOf(
            Mode.COPY to findViewById<TextView>(R.id.tabCopy),
            Mode.MANUAL to findViewById<TextView>(R.id.tabManual),
            Mode.AUTO to findViewById<TextView>(R.id.tabAuto),
            Mode.SAVE to findViewById<TextView>(R.id.tabSave)
        )
        tabs.forEach { (m, v) -> v.setOnClickListener { setMode(m) } }
        setMode(Mode.AUTO)

        torchOn = AppSettings.flashDefault(this)
        updateFlashLabel()

        findViewById<View>(R.id.btnFlash).setOnClickListener { toggleTorch() }
        findViewById<View>(R.id.btnLogout).setOnClickListener { portal.logout() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnGallery).setOnClickListener { gallery.launch("image/*") }
        findViewById<View>(R.id.btnSaved).setOnClickListener { openSaved() }
        findViewById<View>(R.id.btnClosePortal).setOnClickListener { hidePortal() }
        findViewById<View>(R.id.btnAdvanced).setOnClickListener { showAdvancedDialog() }
        successOverlay.setOnClickListener {
            handler.removeCallbacks(hideSuccess)
            successOverlay.visibility = View.GONE
        }
        statusText.setOnClickListener {
            if (!hasCameraPermission()) ensureCamera()
        }

        val adv = AppSettings.advanced(this)
        advSwitch.isChecked = adv
        updateAdvLabel(adv)
        advSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAdvanced(this, checked)
            updateAdvLabel(checked)
            if (checked) showAdvancedDialog()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (portalShown) {
                hidePortal()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        hidePortal()
        ensureCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        portal.cancel()
        recognizer.close()
        analysisExecutor.shutdown()
    }

    // ================== الكاميرا ==================
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureCamera() {
        if (hasCameraPermission()) startCamera() else camPerm.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        setStatus("وجّه الكاميرا نحو البطاقة")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Exception) {
                setStatus("تعذّر تشغيل الكاميرا")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            toast("الفلاش غير متاح")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        updateFlashLabel()
    }

    private fun updateFlashLabel() {
        flashLabel.text = if (torchOn) "الفلاش يعمل" else "الفلاش مطفأ"
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = SystemClock.elapsedRealtime()
        if (media == null || !scanning || now - lastAnalysis < 400) {
            proxy.close()
            return
        }
        lastAnalysis = now
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(img)
            .addOnSuccessListener { t -> if (scanning) handleText(t, false) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun scanImage(uri: Uri) {
        if (portal.isRunning) {
            toast("انتظر انتهاء العملية الحالية")
            return
        }
        try {
            val img = InputImage.fromFilePath(this, uri)
            recognizer.process(img)
                .addOnSuccessListener { t -> handleText(t, true) }
                .addOnFailureListener { toast("تعذّر قراءة الصورة") }
        } catch (e: Exception) {
            toast("تعذّر فتح الصورة")
        }
    }

    // ================== معالجة النص المقروء ==================
    private fun handleText(text: Text, immediate: Boolean) {
        val p = CardParser.parse(text, AppSettings.minUser(this), AppSettings.maxPass(this))
        if (p == null) {
            stableCount = 0
            lastPair = null
            if (immediate) toast("لم أجد الرقمين في الصورة")
            return
        }
        showDetected(p)
        if (!immediate) {
            if (p == lastPair) stableCount++ else {
                lastPair = p
                stableCount = 1
            }
            if (stableCount < 2) return
        }
        stableCount = 0
        lastPair = null
        onCardDetected(p, immediate)
    }

    private fun onCardDetected(p: CardParser.CardPair, fromGallery: Boolean) {
        when (mode) {
            Mode.AUTO -> {
                CardStore.save(this, p.user, p.pass)
                startLogin(p)
            }
            Mode.SAVE -> {
                val added = CardStore.save(this, p.user, p.pass)
                toast(if (added) "تم حفظ البطاقة ✓" else "البطاقة محفوظة مسبقاً")
                setStatus("تم حفظ البطاقة")
                pauseScan(2500)
            }
            Mode.COPY -> {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("card", "${p.user}\n${p.pass}"))
                CardStore.save(this, p.user, p.pass)
                toast("تم نسخ الرقمين ✓")
                setStatus("تم نسخ الرقمين")
                pauseScan(2500)
            }
            Mode.MANUAL -> {
                if (fromGallery) showManualDialog(p)
            }
        }
    }

    private fun showDetected(p: CardParser.CardPair) {
        detectedText.text = "اليوزر: ${p.user}\nكلمة السر: ${p.pass}"
    }

    private fun pauseScan(ms: Long) {
        scanning = false
        resumeScan(ms)
    }

    private fun resumeScan(ms: Long) {
        handler.postDelayed({
            lastPair = null
            stableCount = 0
            if (mode != Mode.MANUAL) scanning = true
        }, ms)
    }

    // ================== الأوضاع ==================
    private fun setMode(m: Mode) {
        mode = m
        tabs.forEach { (k, v) ->
            val sel = k == m
            v.setBackgroundResource(if (sel) R.drawable.bg_tab_selected else 0)
            v.setTextColor(if (sel) Color.WHITE else ContextCompat.getColor(this, R.color.ink))
        }
        hintText.text = when (m) {
            Mode.AUTO -> "ضع البطاقة داخل الإطار وسيتم تسجيل الدخول تلقائياً"
            Mode.COPY -> "ضع البطاقة داخل الإطار وسيتم نسخ الرقمين"
            Mode.SAVE -> "ضع البطاقة داخل الإطار وسيتم حفظها فقط"
            Mode.MANUAL -> "أدخل الرقمين يدوياً ثم اضغط دخول"
        }
        lastPair = null
        stableCount = 0
        scanning = m != Mode.MANUAL
        if (m == Mode.MANUAL && ::portal.isInitialized) showManualDialog(null)
    }

    private fun showManualDialog(prefill: CardParser.CardPair?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(56, 24, 56, 0)
        }
        val u = EditText(this).apply {
            hint = "الرقم المتسلسل (اليوزر)"
            inputType = InputType.TYPE_CLASS_NUMBER
            prefill?.let { setText(it.user) }
        }
        val p = EditText(this).apply {
            hint = "الرقم السفلي (كلمة السر)"
            inputType = InputType.TYPE_CLASS_NUMBER
            prefill?.let { setText(it.pass) }
        }
        box.addView(u)
        box.addView(p)
        AlertDialog.Builder(this)
            .setTitle("إدخال يدوي")
            .setView(box)
            .setPositiveButton("دخول") { _, _ ->
                val uu = CardParser.normalize(u.text.toString()).filter { it.isDigit() }
                val pp = CardParser.normalize(p.text.toString()).filter { it.isDigit() }
                if (uu.isEmpty() || pp.isEmpty()) {
                    toast("أدخل الرقمين")
                } else {
                    CardStore.save(this, uu, pp)
                    startLogin(CardParser.CardPair(uu, pp))
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ================== تسجيل الدخول ==================
    private fun startLogin(p: CardParser.CardPair) {
        if (portal.isRunning) return
        scanning = false
        currentCard = p
        showDetected(p)
        setStatus("جارٍ تسجيل الدخول...")
        portal.start(p.user, p.pass)
    }

    override fun onStatus(msg: String) = setStatus(msg)

    override fun onSuccess() {
        currentCard?.let { CardStore.recordUse(this, it.user, "نجح") }
        setStatus("تم الدخول بنجاح ✓")
        showSuccess()
        resumeScan(4000)
    }

    override fun onAlreadyOnline() {
        setStatus("أنت متصل بالإنترنت بالفعل ✓")
        toast("أنت متصل بالإنترنت بالفعل")
        resumeScan(3000)
    }

    override fun onFailure(msg: String, showPage: Boolean) {
        currentCard?.let { CardStore.recordUse(this, it.user, "فشل") }
        setStatus(msg)
        if (showPage) showPortal()
        resumeScan(3000)
    }

    // ================== الواجهة ==================
    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showSuccess() {
        hidePortal()
        successOverlay.visibility = View.VISIBLE
        successOverlay.bringToFront()
        handler.removeCallbacks(hideSuccess)
        handler.postDelayed(hideSuccess, 7000)
    }

    private fun showPortal() {
        portalShown = true
        portalPanel.bringToFront()
    }

    private fun hidePortal() {
        portalShown = false
        mainContent.bringToFront()
        if (successOverlay.visibility == View.VISIBLE) successOverlay.bringToFront()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "البطاقات المحفوظة")
        pm.menu.add(0, 2, 1, "إظهار صفحة الشبكة")
        pm.menu.add(0, 3, 2, "حول التطبيق")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openSaved()
                2 -> showPortal()
                3 -> AlertDialog.Builder(this)
                    .setTitle("قارئ بطاقات النت")
                    .setMessage("يقرأ رقمي البطاقة بالكاميرا ويسجّل الدخول تلقائياً على شبكة الواي فاي.\n\nتصميم طلال قاسم 0599347848")
                    .setPositiveButton("حسناً", null)
                    .show()
            }
            true
        }
        pm.show()
    }

    private fun openSaved() {
        savedCards.launch(android.content.Intent(this, SavedCardsActivity::class.java))
    }

    // ================== الإعدادات المتقدمة ==================
    private fun updateAdvLabel(on: Boolean) {
        advLabel.text = if (on) "الإعدادات المتقدمة مفعّلة" else "الإعدادات المتقدمة غير مفعّلة"
    }

    private fun showAdvancedDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(56, 24, 56, 0)
        }
        val minU = EditText(this).apply {
            hint = "أقل عدد أرقام لليوزر (الافتراضي 8)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppSettings.savedMinUser(this@MainActivity).toString())
        }
        val maxP = EditText(this).apply {
            hint = "أقصى عدد أرقام لكلمة السر (الافتراضي 8)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppSettings.savedMaxPass(this@MainActivity).toString())
        }
        val flash = CheckBox(this).apply {
            text = "تشغيل الفلاش افتراضياً عند فتح التطبيق"
            isChecked = AppSettings.savedFlash(this@MainActivity)
        }
        box.addView(minU)
        box.addView(maxP)
        box.addView(flash)
        AlertDialog.Builder(this)
            .setTitle("الإعدادات المتقدمة")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val a = minU.text.toString().toIntOrNull()?.coerceIn(4, 30) ?: 8
                val b = maxP.text.toString().toIntOrNull()?.coerceIn(3, 20) ?: 8
                AppSettings.save(this, a, b, flash.isChecked)
                AppSettings.setAdvanced(this, true)
                advSwitch.isChecked = true
                updateAdvLabel(true)
                toast("تم حفظ الإعدادات")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
