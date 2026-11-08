package com.custom.watchdrift

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

// --- BÖLÜM 1 BAŞLANGICI: VERİ YAPILARI, HAVUZLAR VE YAŞAM DÖNGÜSÜ ---

class MainActivity : Activity() {
    private lateinit var gameView: CrownDriftView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = CrownDriftView(this)
        setContentView(gameView)
        gameView.requestFocus()
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 102)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            if (gameView.handleRotaryScroll(ev)) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_1) {
            if (gameView.onSideButtonPressed()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!gameView.onSideButtonPressed()) super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        gameView.onHostPause()
    }

    override fun onResume() {
        super.onResume()
        gameView.onHostResume()
    }

    override fun onDestroy() {
        gameView.cleanup()
        super.onDestroy()
    }
}

data class Vec2(val x: Float, val y: Float)
data class GhostPose(val x: Float, val y: Float, val yaw: Float, val roll: Float = 0f)

data class RemotePlayer(
    var active: Boolean = false,
    var name: String = "Bos",
    var x: Float = 0f,
    var y: Float = 0f,
    var yaw: Float = 0f,
    var colorIdx: Int = 7,
    var modelIdx: Int = 1,
    var lap: Int = 1,
    var wpIdx: Int = 0,
    var finishOrder: Int = 0
)

class BotCar(
    var wpIdx: Int,
    var laneOffset: Float,
    var targetLane: Float,
    val baseSpeedFactor: Float,
    val color: Int,
    val modelIdx: Int
) {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0f
    var vz: Float = 0f
    var yaw: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var smoothedSteering: Float = 0f
    var rollAngle: Float = 0f
    var isDrifting: Boolean = false
    var skidIntensity: Float = 0f
    var completedLaps: Int = 0
    var maxWpInLap: Int = 0
    var totalProgress: Float = 0f
    var finishOrder: Int = 0
    var flipTimer: Float = 0f
    var slowTimer: Float = 0f
    var mistakeTimer: Float = 2.5f
    var mistakeOffset: Float = 0f
    var wheelIdBase: Int = 0
}

// Sabit Engeller: 0=Ağaç, 1=Bina, 2=Sabit Yol Kenarı Bariyeri
data class Obstacle3D(
    val x: Float,
    val y: Float,
    val radius: Float,
    val height: Float,
    val type: Int,
    val yaw: Float = 0f
)

// Yolda Rastgele Doğan Dinamik Engeller (Sabit yeri olmayan engeller)
class DynamicHazard {
    var active = false
    var x = 0f
    var y = 0f
    var yaw = 0f
    var radius = 13f
    var height = 14f
    var kind = 0 // 0=Kırılabilir Bariyer, 1=Kaya/Beton Blok, 2=Varil
    var life = 0f
}

// Yoldaki Rastgele Sürpriz Kutuları
class SurpriseBox {
    var active = false
    var respawnTimer = 0f
    var wpIdx = 0
    var laneOffset = 0f
    var x = 0f
    var y = 0f
    var spinYaw = 0f
}

// Aktif Füze / Araç Önü Silah Mermisi Havuzu
class Projectile3D {
    var active = false
    var isMissile = true
    var x = 0f
    var y = 0f
    var z = 6f
    var vx = 0f
    var vy = 0f
    var yaw = 0f
    var life = 0f
}

// Yola Bırakılan Diken (Spike) Havuzu
class RoadSpike {
    var active = false
    var x = 0f
    var y = 0f
    var yaw = 0f
    var radius = 15f
    var life = 0f
    var ownerIsPlayer = true
}

class DebrisPart {
    var active = false
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var yaw = 0f; var size = 3f; var color = 0; var life = 0f
}

class Particle3D {
    var active = false
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var size = 3f; var alpha = 0; var color = 0
}

// Referans CrownDriftGame.java'daki 32 Parçacıklı Duman/Toz Havuzu
class SmokeParticle {
    var life = 0f
    var lifeTotal = 1f
    var x = 0f
    var y = 0f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var startSize = 3.5f
    var r = 225
    var g = 225
    var b = 230
}

// Referans SkidTrail.java'daki Dairesel Tamponlu Solan Lastik İzi Segmenti
class SkidSegment {
    var active = false
    var ax = 0f; var ay = 0f
    var bx = 0f; var by = 0f
    var cx = 0f; var cy = 0f
    var dx = 0f; var dy = 0f
    var strength0 = 0f
    var strength1 = 0f
    var isGrass = false
}

data class TrackData(
    val name: String,
    val theme: Int,
    val goldScore: Int,
    val controlPoints: List<Vec2>
)

// --- BÖLÜM 1 SONU: VERİ YAPILARI, HAVUZLAR VE YAŞAM DÖNGÜSÜ ---

// --- BÖLÜM 2 BAŞLANGICI: FİZİK, DRİFT MOTORU, SÜRPRİZ KUTULARI, BOTLAR VE CO-OP ---

class CrownDriftView(context: Context) : View(context) {

    companion object {
        // Referans CrownDriftGame.java & Car3D.java Sabitleri (Dünya Ölçeğine Uyarlanmış)
        private const val STEP = 1f / 60f
        private const val BOOST_SECONDS = 3.0f
        private const val BOOST_SPEED = 1.24f
        private const val BOOST_HINT_SECONDS = 2.5f
        private const val COMBO_BREAK_SECONDS = 0.3f
        private const val COMBO_STEP_SECONDS = 3.0f
        private const val MAX_MULTIPLIER = 4
        private const val POINTS_PER_SECOND = 100f
        private const val EDGE_BAND = 16.5f
        private const val EDGE_FACTOR = 1.5f
        private const val GRASS_SPEED = 0.60f
        private const val DEFAULT_GRIP = 0.042f
        private const val ICE_GRIP = 0.025f
        private const val SMOKE_COUNT = 32
        private const val MAX_SKID_SEGS = 220
        private const val WHEEL_CHANNELS = 8 // 0..1 oyuncu, 2..7 botların arka tekerlekleri

        // Sürpriz Kutusu Özellik Tipleri
        private const val POWER_NONE = 0
        private const val POWER_MISSILE = 1     // Rakip görüş açısına girince ateşlenir, devirir
        private const val POWER_SPIKE = 2       // Yola diken bırakır, çarpanı devirir/yavaşlatır
        private const val POWER_SHIELD = 3      // Rakiplerin ve engellerin zararını engeller
        private const val POWER_FRONT_GUN = 4   // Araç önü otomatik silah
        private const val POWER_BAD_SLOW = 5    // Kötü kutu: hasar verir ve yavaşlatır
    }

    private val prefs = context.getSharedPreferences("crowndrift_v7", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val polyPath = Path()
    private val arcPath = Path()
    private val miniPath = Path()
    private val tempRect = RectF()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // 0=MENU, 1=GARAGE, 2=SETTINGS, 3=GAME, 4=WIN, 5=COOP_LOBBY
    @Volatile private var state = 0
    private var isHostPaused = false

    private val carColors = intArrayOf(
        Color.rgb(30, 105, 255),  // Mavi
        Color.rgb(235, 45, 45),   // Kırmızı
        Color.rgb(255, 200, 25),  // Sarı
        Color.rgb(55, 195, 75),   // Yeşil
        Color.rgb(255, 135, 20),  // Turuncu
        Color.rgb(165, 50, 215),  // Mor
        Color.rgb(240, 240, 240), // Beyaz
        Color.rgb(0, 225, 255)    // Turkuaz
    )
    private val carModels = arrayOf("JDM GT", "SUPER V12", "MUSCLE V8", "F1 HYPER")
    private var selectedColor = prefs.getInt("color", 0)
    private var selectedModel = prefs.getInt("model", 0)
    private var garagePreviewYaw = 0.5f

    // Ayarlar
    private var sensLevel = prefs.getInt("sens", 1)
    private var ghostEnabled = prefs.getBoolean("ghost", true)
    private var botsEnabled = prefs.getBoolean("bots", true)
    private var showFps = prefs.getBoolean("fps", false)

    // Virajlı Yarış Pistleri
    private val tracks = listOf(
        TrackData("Classic GP", 0, 8000, listOf(
            Vec2(0f, -1400f), Vec2(850f, -1350f), Vec2(1350f, -850f),
            Vec2(900f, -300f), Vec2(1450f, 200f), Vec2(1250f, 950f),
            Vec2(450f, 700f), Vec2(150f, 1350f), Vec2(-650f, 1400f),
            Vec2(-1250f, 850f), Vec2(-750f, 200f), Vec2(-1400f, -350f),
            Vec2(-1150f, -1100f), Vec2(-500f, -1350f)
        )),
        TrackData("Cyber Touge", 1, 9500, listOf(
            Vec2(-700f, -1450f), Vec2(900f, -1450f), Vec2(1500f, -950f),
            Vec2(750f, -450f), Vec2(1400f, 50f), Vec2(600f, 450f),
            Vec2(1250f, 1050f), Vec2(500f, 1500f), Vec2(-450f, 1100f),
            Vec2(-1100f, 1500f), Vec2(-1550f, 800f), Vec2(-900f, 150f),
            Vec2(-1500f, -500f), Vec2(-1250f, -1200f)
        )),
        TrackData("Tokyo Bay", 2, 10200, listOf(
            Vec2(0f, -1550f), Vec2(1100f, -1350f), Vec2(1550f, -550f),
            Vec2(650f, -150f), Vec2(450f, 450f), Vec2(1450f, 750f),
            Vec2(1150f, 1450f), Vec2(150f, 1550f), Vec2(-450f, 900f),
            Vec2(-1200f, 1350f), Vec2(-1550f, 500f), Vec2(-800f, -150f),
            Vec2(-1500f, -800f), Vec2(-950f, -1450f)
        )),
        TrackData("Glacier S", 3, 8800, listOf(
            Vec2(0f, -1450f), Vec2(1050f, -1200f), Vec2(1500f, -300f),
            Vec2(750f, 250f), Vec2(1350f, 950f), Vec2(600f, 1500f),
            Vec2(-550f, 1450f), Vec2(-1350f, 900f), Vec2(-700f, 200f),
            Vec2(-1500f, -400f), Vec2(-1050f, -1250f)
        )),
        TrackData("Canyon Hairpin", 4, 11500, listOf(
            Vec2(-600f, -1550f), Vec2(950f, -1500f), Vec2(1600f, -800f),
            Vec2(550f, -350f), Vec2(1550f, 250f), Vec2(650f, 750f),
            Vec2(1400f, 1350f), Vec2(200f, 1650f), Vec2(-650f, 1050f),
            Vec2(-1350f, 1500f), Vec2(-1650f, 600f), Vec2(-750f, 0f),
            Vec2(-1600f, -650f), Vec2(-1250f, -1350f)
        ))
    )
    private var currentTrackIdx = 0

    private val trackPts = ArrayList<Vec2>(400)
    private val trackNormals = ArrayList<Vec2>(400)
    private val trackTangents = ArrayList<Float>(400)
    private var zoneMask = BooleanArray(0)
    private val obstacles = ArrayList<Obstacle3D>(64)
    private val roadHalfWidth = 58f
    private val kerbWidth = 9.5f

    // Oyuncu Araç Fiziği (Car3D.java'dan Uyarlanmış Orijinal Drift & Grip Motoru)
    private var carX = 0f
    private var carY = 0f
    private var carZ = 0f
    private var carVZ = 0f
    private var carYaw = 0f
    private var rollAngle = 0f
    private var carVX = 0f
    private var carVY = 0f
    private var speed = 0f
    private var grip = DEFAULT_GRIP
    private var smoothedSteering = 0f
    private var frontWheelAngleDeg = 0f

    // Interpolasyon Değişkenleri (Car3D.beginStep & applyInterpolated)
    private var prevX = 0f
    private var prevY = 0f
    private var prevYaw = 0f
    private var prevRoll = 0f
    private var renderX = 0f
    private var renderY = 0f
    private var renderYaw = 0f
    private var renderRoll = 0f

    // Drift, Kombo, Edge Bonus ve Boost Durumu
    private var isCurrentlyDrifting = false
    private var driftAmount = 0f
    private var skidIntensity = 0f
    private var onGrass = false
    private var edgeActive = false
    private var lastRoadDist = 0f

    private var boostMeter = 0f
    private var boostTime = 0f
    private var boostReady = false
    private var boostRequest = false
    private var boostHintTime = 0f

    // Kamera (CrownDriftGame.updateCamera: %55 Bakış + %45 Hız Harmanlama, Dinamik FOV/Uzaklık, Shake)
    private var camX = 0f
    private var camY = 0f
    private var camZ = 138f
    private var camYaw = 0f
    private var camDist = 98f
    private var camFovScale = 1.48f
    private var shakeTime = 0f
    private var shakeOffsetX = 0f
    private var shakeOffsetY = 0f

    // Kontrol ve Sabit Adım Akümülatörleri
    private var isFullStopped = false
    private var isHoldingStop = false
    private var steerInput = 0f
    @Volatile private var crownAccum = 0f
    private var physicsAccumulator = 0f
    private var stepCounter = 0
    private var hapticTimer = 0f

    // Dokunmatik Çerçeve (Bezel) Açı Takibi
    private var isBezelDragging = false
    private var lastBezelAngle = 0f

    // Turnuva Başlangıç Geri Sayımı (3 - 2 - 1 - GO!)
    private var countdownValue = 3
    private var countdownTimer = 0f

    // Kademeli Hasar ve Güvenli Yolda Yeniden Doğma (3sn Canlanma Süresi + 3sn Koruma)
    private var carDamage = 0f
    private var spoilerBroken = false
    private var bumperBroken = false
    private var isCrashing = false
    private var crashTimer = 0f
    private var crashSpotX = 0f
    private var crashSpotY = 0f
    private var respawnBannerTimer = 0f
    private var respawnShieldTimer = 0f

    // Sürpriz Kutusu ve Aktif Özellik Sistemi
    private var activePower = POWER_NONE
    private var powerTimer = 0f
    private var powerMaxTimer = 1f
    private var frontGunCooldown = 0f
    private var statusToastText = ""
    private var statusToastTimer = 0f

    // Nesne Havuzları (Sıfır GC / Batarya ve Isınma Optimizasyonu)
    private val debrisPool = Array(28) { DebrisPart() }
    private var debrisHead = 0
    private val particlePool = Array(32) { Particle3D() }
    private var particleHead = 0
    private val smokePool = Array(SMOKE_COUNT) { SmokeParticle() }
    private var smokeHead = 0

    // Dairesel Tamponlu SkidTrail (SkidTrail.java Uyarlaması)
    private val skidSegs = Array(MAX_SKID_SEGS) { SkidSegment() }
    private var skidHead = 0
    private var skidCount = 0
    private val wheelActive = BooleanArray(WHEEL_CHANNELS)
    private val wheelEdgeValid = BooleanArray(WHEEL_CHANNELS)
    private val wheelLastX = FloatArray(WHEEL_CHANNELS)
    private val wheelLastY = FloatArray(WHEEL_CHANNELS)
    private val wheelLastS = FloatArray(WHEEL_CHANNELS)
    private val wheelAX = FloatArray(WHEEL_CHANNELS)
    private val wheelAY = FloatArray(WHEEL_CHANNELS)
    private val wheelBX = FloatArray(WHEEL_CHANNELS)
    private val wheelBY = FloatArray(WHEEL_CHANNELS)

    // Sürpriz Kutuları, Dinamik Engeller, Füzeler ve Diken Havuzları
    private val surpriseBoxes = Array(8) { SurpriseBox() }
    private val dynamicHazards = Array(8) { DynamicHazard() }
    private var hazardSpawnTimer = 2.0f
    private val projectiles = Array(12) { Projectile3D() }
    private var projectileHead = 0
    private val roadSpikes = Array(8) { RoadSpike() }
    private var spikeHead = 0

    // Yarış, Tur ve Kesin Sıralama Bilgileri
    private var lap = 1
    private var completedLaps = 0
    private val totalLaps = 3
    private var currentWp = 0
    private var maxWpReached = 0
    private var playerProgress = 0f
    private var playerFinishOrder = 0
    private var nextFinishOrder = 1
    private var racePosition = 1
    private var raceTime = 0f
    private var lapStartTime = 0f
    private var currentLapTime = 0f
    private var lastLapTime = 0f
    private var bestLapTime = 99.99f
    private var lapFlashTimer = 0f
    private var lapWasBest = false

    private var score = 0f
    private var comboTime = 0f
    private var comboGap = 0f
    private var multiplier = 1
    private var isNewRecord = false

    // HUD String Önbellekleri (Her karede String tahsisini önler)
    private var cachedScoreInt = -1
    private var cachedScoreStr = "0"
    private var cachedPosKey = -1
    private var cachedPosStr = "1/4"
    private var cachedLapKey = -1
    private var cachedLapStr = "L1/3"
    private val multLabels = arrayOf("x1", "x1", "x2", "x3", "x4")

    // Ghost ve Botlar (3 Bot + 1 Oyuncu = 4 Yarışçı)
    private val currentLapRecording = ArrayList<GhostPose>(1200)
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
    private val bots = ArrayList<BotCar>(3)

    // Eşleşme Gerektirmeyen Bluetooth Oda Sistemi
    private val btUuid: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    @Volatile private var lobbyRole = 0 // 0=Yok, 1=HOST, 2=CLIENT
    @Volatile private var myAssignedModel = 0
    @Volatile private var lobbyStatusText = "Oda Kur veya Tara"
    @Volatile private var btRunning = false
    private var btServerSocket: BluetoothServerSocket? = null
    private val hostClientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private var clientSocket: BluetoothSocket? = null
    private val triedAddresses = HashSet<String>()
    private val remotePlayers = Array(3) { RemotePlayer(modelIdx = (it + 1) % 4) }

    private val btDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (BluetoothDevice.ACTION_FOUND == action && btRunning && lobbyRole == 2 && clientSocket == null) {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val addr = device.address ?: return
                    if (triedAddresses.add(addr)) {
                        tryConnectToDiscoveredDevice(device)
                    }
                }
            }
        }
    }
    private var receiverRegistered = false

    // 3B Projeksiyon Geçici Değişkenleri
    private var projX = 0f
    private var projY = 0f
    private var projScale = 0f
    private var camCosYaw = 1f
    private var camSinYaw = 0f

    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60
    private var lastVibeWallTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        myAssignedModel = selectedModel
        buildTrackSpline(0)
        registerBtReceiver()
    }

    fun onHostPause() {
        isHostPaused = true
        if (state == 3 && countdownValue < 0 && !isFullStopped) {
            countdownValue = 3
            countdownTimer = 0.55f
            crownAccum = 0f
        }
    }

    fun onHostResume() {
        isHostPaused = false
        lastFrameTime = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun registerBtReceiver() {
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                context.registerReceiver(btDiscoveryReceiver, filter)
                receiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    fun cleanup() {
        stopAllBluetooth()
        if (receiverRegistered) {
            try { context.unregisterReceiver(btDiscoveryReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && width > 0 && height > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    fun onSideButtonPressed(): Boolean {
        return if (state != 0) {
            state = 0
            vibeClick()
            invalidate()
            true
        } else {
            false
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("color", selectedColor)
            .putInt("model", selectedModel)
            .putInt("sens", sensLevel)
            .putBoolean("ghost", ghostEnabled)
            .putBoolean("bots", botsEnabled)
            .putBoolean("fps", showFps)
            .apply()
    }

    // ==================== BLUETOOTH CO-OP ODA MOTORU ====================
    fun stopAllBluetooth() {
        btRunning = false
        lobbyRole = 0
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
        try { btServerSocket?.close() } catch (_: Throwable) {}
        for (s in hostClientSockets) {
            try { s.close() } catch (_: Throwable) {}
        }
        hostClientSockets.clear()
        try { clientSocket?.close() } catch (_: Throwable) {}
        clientSocket = null
        triedAddresses.clear()
        for (rp in remotePlayers) rp.active = false
    }

    private fun startHostLobby() {
        stopAllBluetooth()
        lobbyRole = 1
        myAssignedModel = selectedModel
        btRunning = true
        lobbyStatusText = "Oda Acik (1/4) Bekleniyor"

        try {
            val discIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            context.startActivity(discIntent)
        } catch (_: Throwable) {}

        thread(start = true, isDaemon = true) {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    lobbyStatusText = "BT Kapali!"
                    return@thread
                }
                val server = adapter.listenUsingInsecureRfcommWithServiceRecord("CrownDriftRoom", btUuid)
                btServerSocket = server

                while (btRunning && lobbyRole == 1 && hostClientSockets.size < 3) {
                    val sock = server.accept() ?: break
                    val slot = hostClientSockets.size
                    if (slot < 3) {
                        hostClientSockets.add(sock)
                        remotePlayers[slot].active = true
                        remotePlayers[slot].modelIdx = (selectedModel + slot + 1) % 4
                        remotePlayers[slot].name = try { sock.remoteDevice?.name ?: "Saat ${slot + 2}" } catch (_: SecurityException) { "Saat ${slot + 2}" }
                        lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4 Oyuncu"
                        vibeClick()
                        handleHostClientConnection(sock, slot)
                    } else {
                        sock.close()
                    }
                }
            } catch (_: Throwable) {
                if (lobbyRole == 1 && hostClientSockets.isEmpty()) {
                    lobbyStatusText = "Oda Hazir (1/4)"
                }
            }
        }
    }

    private fun handleHostClientConnection(sock: BluetoothSocket, slot: Int) {
        thread(start = true, isDaemon = true) {
            try {
                val inp = DataInputStream(sock.inputStream)
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    remotePlayers[slot].x = inp.readFloat()
                    remotePlayers[slot].y = inp.readFloat()
                    remotePlayers[slot].yaw = inp.readFloat()
                    remotePlayers[slot].colorIdx = inp.readInt().coerceIn(0, 7)
                    remotePlayers[slot].modelIdx = inp.readInt().coerceIn(0, 3)
                }
            } catch (_: Throwable) {
                remotePlayers[slot].active = false
                hostClientSockets.remove(sock)
                lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4 Oyuncu"
            }
        }

        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream)
                val assignedClientModel = (selectedModel + slot + 1) % 4
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    out.writeInt(state)
                    out.writeInt(currentTrackIdx)
                    out.writeInt(hostClientSockets.size + 1)
                    out.writeInt(assignedClientModel)

                    out.writeFloat(carX)
                    out.writeFloat(carY)
                    out.writeFloat(carYaw)
                    out.writeInt(selectedColor)
                    out.writeInt(selectedModel)

                    for (i in 0..2) {
                        if (i != slot) {
                            val rp = remotePlayers[i]
                            out.writeBoolean(rp.active)
                            out.writeFloat(rp.x)
                            out.writeFloat(rp.y)
                            out.writeFloat(rp.yaw)
                            out.writeInt(rp.colorIdx)
                            out.writeInt(rp.modelIdx)
                        }
                    }
                    out.flush()
                    Thread.sleep(45)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun scanAndJoinLobby() {
        stopAllBluetooth()
        lobbyRole = 2
        btRunning = true
        lobbyStatusText = "Cevre Taraniyor..."

        thread(start = true, isDaemon = true) {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    lobbyStatusText = "BT Kapali!"
                    return@thread
                }

                val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
                if (!bonded.isNullOrEmpty()) {
                    for (dev in bonded) {
                        if (!btRunning || lobbyRole != 2 || clientSocket != null) break
                        triedAddresses.add(dev.address)
                        tryConnectToDiscoveredDevice(dev)
                    }
                }

                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                    adapter.startDiscovery()
                } catch (_: SecurityException) {
                    lobbyStatusText = "Tarama Izni Gerekli"
                }
            } catch (_: Throwable) {
                lobbyStatusText = "Tarama Hatasi"
            }
        }
    }

    private fun tryConnectToDiscoveredDevice(dev: BluetoothDevice) {
        thread(start = true, isDaemon = true) {
            try {
                if (!btRunning || lobbyRole != 2 || clientSocket != null) return@thread
                val sock = dev.createInsecureRfcommSocketToServiceRecord(btUuid)
                sock.connect()
                if (clientSocket != null) {
                    sock.close()
                    return@thread
                }
                clientSocket = sock
                try {
                    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    bm?.adapter?.cancelDiscovery()
                } catch (_: SecurityException) {}

                remotePlayers[0].active = true
                remotePlayers[0].name = try { dev.name ?: "Kurucu" } catch (_: SecurityException) { "Kurucu" }
                lobbyStatusText = "Odaya Baglandi! Start Bekleniyor"
                vibeClick()
                handleClientConnection(sock)
            } catch (_: Throwable) {}
        }
    }

    private fun handleClientConnection(sock: BluetoothSocket) {
        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream)
                while (btRunning && lobbyRole == 2 && sock.isConnected) {
                    out.writeFloat(carX)
                    out.writeFloat(carY)
                    out.writeFloat(carYaw)
                    out.writeInt(selectedColor)
                    out.writeInt(myAssignedModel)
                    out.flush()
                    Thread.sleep(45)
                }
            } catch (_: Throwable) {}
        }

        try {
            val inp = DataInputStream(sock.inputStream)
            while (btRunning && lobbyRole == 2 && sock.isConnected) {
                val hostState = inp.readInt()
                val hostTrack = inp.readInt().coerceIn(0, tracks.size - 1)
                val totalPlayers = inp.readInt()
                val assignedModel = inp.readInt().coerceIn(0, 3)
                myAssignedModel = assignedModel
                selectedModel = assignedModel
                lobbyStatusText = "Odada $totalPlayers/4 Oyuncu (${carModels[assignedModel]})"

                remotePlayers[0].active = true
                remotePlayers[0].x = inp.readFloat()
                remotePlayers[0].y = inp.readFloat()
                remotePlayers[0].yaw = inp.readFloat()
                remotePlayers[0].colorIdx = inp.readInt().coerceIn(0, 7)
                remotePlayers[0].modelIdx = inp.readInt().coerceIn(0, 3)

                for (slotIdx in 1..2) {
                    val act = inp.readBoolean()
                    remotePlayers[slotIdx].active = act
                    remotePlayers[slotIdx].x = inp.readFloat()
                    remotePlayers[slotIdx].y = inp.readFloat()
                    remotePlayers[slotIdx].yaw = inp.readFloat()
                    remotePlayers[slotIdx].colorIdx = inp.readInt().coerceIn(0, 7)
                    remotePlayers[slotIdx].modelIdx = inp.readInt().coerceIn(0, 3)
                }

                if (hostState == 3 && state != 3) {
                    currentTrackIdx = hostTrack
                    post { startRace(fromNetwork = true) }
                }
            }
        } catch (_: Throwable) {
            remotePlayers[0].active = false
            clientSocket = null
            lobbyStatusText = "Oda Baglantisi Koptu"
        }
    }

    // Pil ve Isınma Korumalı Haptik Titreşim
    private fun triggerHaptic(durationMs: Long, amplitude: Int = 180) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVibeWallTime < 28L && durationMs < 40L) return
        lastVibeWallTime = now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibeClick() = triggerHaptic(20L, 185)

    // ==================== CROWN / BEZEL ROTASYON KONTROLÜ (crownPerStep Yumuşatmalı) ====================
    fun handleRotaryScroll(event: MotionEvent): Boolean {
        var delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (delta == 0f) return false

        when (state) {
            0 -> {
                if (delta > 0) currentTrackIdx = (currentTrackIdx + 1) % tracks.size
                else currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size
                buildTrackSpline(currentTrackIdx)
                vibeClick()
            }
            1 -> {
                selectedModel = (selectedModel + if (delta > 0) 1 else 3) % 4
                myAssignedModel = selectedModel
                saveSettings()
                vibeClick()
            }
            3 -> {
                if (countdownValue < 0 && !isFullStopped) {
                    // Referans CrownDriftGame.handleCrownRotation: crownAccum += -delta * 0.4f
                    crownAccum = (crownAccum + delta * 0.42f).coerceIn(-1.6f, 1.6f)
                    triggerHaptic(6L, 95)
                }
            }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && handleRotaryScroll(event)) return true
        return super.onGenericMotionEvent(event)
    }

    // ==================== PİST GEOMETRİSİ, DRIFT ZONE (Track3D) VE ENGELLER ====================
    private fun buildTrackSpline(idx: Int) {
        trackPts.clear()
        trackNormals.clear()
        trackTangents.clear()
        obstacles.clear()

        val cps = tracks[idx].controlPoints
        val n = cps.size
        val stepsPerSeg = 26
        for (i in 0 until n) {
            val p0 = cps[(i - 1 + n) % n]
            val p1 = cps[i]
            val p2 = cps[(i + 1) % n]
            val p3 = cps[(i + 2) % n]
            for (s in 0 until stepsPerSeg) {
                val t = s / stepsPerSeg.toFloat()
                val t2 = t * t
                val t3 = t2 * t
                val x = 0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t +
                        (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                        (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3)
                val y = 0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t +
                        (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                        (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3)
                trackPts.add(Vec2(x, y))
            }
        }

        val total = trackPts.size
        // Track3D.getNormal: Önceki ve sonraki segment normallerinin ortalaması
        for (i in 0 until total) {
            val prev = trackPts[(i - 1 + total) % total]
            val cur = trackPts[i]
            val next = trackPts[(i + 1) % total]

            val dx1 = cur.x - prev.x; val dy1 = cur.y - prev.y
            val l1 = hypot(dx1, dy1).coerceAtLeast(0.001f)
            val n1x = -dy1 / l1; val n1y = dx1 / l1

            val dx2 = next.x - cur.x; val dy2 = next.y - cur.y
            val l2 = hypot(dx2, dy2).coerceAtLeast(0.001f)
            val n2x = -dy2 / l2; val n2y = dx2 / l2

            val sx = n1x + n2x; val sy = n1y + n2y
            val sl = hypot(sx, sy).coerceAtLeast(0.001f)
            val nx = sx / sl; val ny = sy / sl

            trackNormals.add(Vec2(nx, ny))
            trackTangents.add(atan2(dy2, dx2))

            // Çevre Ağaçları ve Binaları (type 0 = Ağaç, type 1 = Bina)
            if (i % 16 == 0) {
                val side = if ((i / 16) % 2 == 0) 1f else -1f
                val distFromRoad = roadHalfWidth + 72f + (i % 35)
                val ox = cur.x + nx * side * distFromRoad
                val oy = cur.y + ny * side * distFromRoad
                val isTree = (i / 16) % 3 != 0
                obstacles.add(
                    Obstacle3D(
                        ox, oy,
                        if (isTree) 16f else 26f,
                        if (isTree) 34f else 48f,
                        if (isTree) 0 else 1,
                        trackTangents[i]
                    )
                )
            }

            // 3.4 Sabit Yol Kenarı / Şikan Bariyerleri (type 2 = Sabit Bariyer)
            if (i > 24 && i < total - 20 && i % 38 == 0) {
                val barrierSide = if ((i / 38) % 2 == 0) 1f else -1f
                val barrierOffset = roadHalfWidth - 6f
                val bx = cur.x + nx * barrierSide * barrierOffset
                val by = cur.y + ny * barrierSide * barrierOffset
                obstacles.add(Obstacle3D(bx, by, 11f, 13f, 2, trackTangents[i]))
            }
        }

        // Referans Track3D.computeDriftZones(): En keskin 2 viraj bölgesini otomatik tespit et
        computeDriftZones()
    }

    private fun computeDriftZones() {
        val n = trackPts.size
        zoneMask = BooleanArray(n)
        if (n < 40) return

        val turn = FloatArray(n)
        for (i in 0 until n) {
            val p0 = trackPts[(i - 1 + n) % n]
            val p1 = trackPts[i]
            val p2 = trackPts[(i + 1) % n]
            val a1 = atan2(p1.y - p0.y, p1.x - p0.x)
            val a2 = atan2(p2.y - p1.y, p2.x - p1.x)
            turn[i] = abs(wrapAngle(a2 - a1))
        }

        val w = n / 24
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (k in -w..w) s += turn[(i + k + n) % n]
            smooth[i] = s
        }

        val used = BooleanArray(n)
        for (pass in 0 until 2) {
            var best = -1
            for (i in 0 until n) {
                if (i < n / 10 || i > n - n / 10) continue
                if (used[i]) continue
                if (best < 0 || smooth[i] > smooth[best]) best = i
            }
            if (best < 0) break
            for (k in -w..w) zoneMask[(best + k + n) % n] = true
            for (k in -(n / 4)..(n / 4)) used[(best + k + n) % n] = true
        }
    }

    // Performans Optimizasyonu: Tüm pisti taramak yerine yerel pencerede (-15..+15) en yakın noktayı bul
    private fun findNearestWaypointLocal(px: Float, py: Float, hintWp: Int, forceFullScan: Boolean = false): Int {
        val n = trackPts.size
        if (n == 0) return 0
        var best = (hintWp % n + n) % n
        var bestD = Float.MAX_VALUE

        if (!forceFullScan) {
            for (k in -16..18) {
                val idx = (best + k + n) % n
                val p = trackPts[idx]
                val dx = p.x - px
                val dy = p.y - py
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = idx
                }
            }
            if (bestD < (roadHalfWidth * 3.5f) * (roadHalfWidth * 3.5f)) {
                return best
            }
        }

        for (i in 0 until n) {
            val p = trackPts[i]
            val dx = p.x - px
            val dy = p.y - py
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    // Kesintisiz Pist İlerlemesi (1.1 Sıralama Hatasını Önleyen Kesin Hesap)
    private fun computeContinuousProgress(px: Float, py: Float, wpIdx: Int, lapsDone: Int): Float {
        val total = trackPts.size
        if (total == 0) return 0f
        if (lapsDone >= totalLaps) {
            return totalLaps * total.toFloat() + 100f
        }
        val p0 = trackPts[wpIdx]
        val p1 = trackPts[(wpIdx + 1) % total]
        val segDx = p1.x - p0.x
        val segDy = p1.y - p0.y
        val segLenSq = (segDx * segDx + segDy * segDy).coerceAtLeast(1f)
        val frac = (((px - p0.x) * segDx + (py - p0.y) * segDy) / segLenSq).coerceIn(0f, 0.99f)
        return lapsDone * total.toFloat() + wpIdx.toFloat() + frac
    }

    // ==================== YARIŞ BAŞLATMA & SÜRPRİZ KUTUSU / ENGEL KURULUMU ====================
    private fun startRace(fromNetwork: Boolean = false) {
        buildTrackSpline(currentTrackIdx)
        val p0 = trackPts[0]
        val n0 = trackNormals[0]
        val startOffset = if (lobbyRole == 2) 16f else if (lobbyRole == 1) -16f else 0f

        carX = p0.x + n0.x * startOffset
        carY = p0.y + n0.y * startOffset
        carZ = 0f
        carVZ = 0f
        carYaw = trackTangents[0]
        rollAngle = 0f

        // Harita 3 (Glacier S) buzlu zemin grip değeri (Referans CrownDriftGame: ICE_GRIP)
        grip = if (tracks[currentTrackIdx].theme == 3) ICE_GRIP else DEFAULT_GRIP
        carVX = 0f
        carVY = 0f
        speed = 0f
        smoothedSteering = 0f
        frontWheelAngleDeg = 0f

        prevX = carX; prevY = carY; prevYaw = carYaw; prevRoll = 0f
        renderX = carX; renderY = carY; renderYaw = carYaw; renderRoll = 0f

        camYaw = carYaw
        camDist = 98f
        camZ = 138f
        camFovScale = 1.48f
        shakeTime = 0f

        isCurrentlyDrifting = false
        driftAmount = 0f
        skidIntensity = 0f
        onGrass = false
        edgeActive = false

        boostMeter = 0f
        boostTime = 0f
        boostReady = false
        boostRequest = false
        boostHintTime = 0f

        isFullStopped = false
        isHoldingStop = false
        steerInput = 0f
        crownAccum = 0f
        physicsAccumulator = 0f
        stepCounter = 0
        hapticTimer = 0f
        isBezelDragging = false

        // 3.1 Turnuva Başlangıcı: 3 - 2 - 1 - GO Geri Sayımı
        countdownValue = 3
        countdownTimer = 0.75f

        // Hasar & Canlanma Sıfırlama
        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false
        isCrashing = false
        crashTimer = 0f
        respawnBannerTimer = 0f
        respawnShieldTimer = 0f

        activePower = POWER_NONE
        powerTimer = 0f
        frontGunCooldown = 0f
        statusToastText = ""
        statusToastTimer = 0f

        // Havuzları Temizle
        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (s in smokePool) s.life = 0f
        for (seg in skidSegs) seg.active = false
        skidHead = 0; skidCount = 0
        breakAllSkidStrips()
        for (pr in projectiles) pr.active = false
        for (sp in roadSpikes) sp.active = false
        for (hz in dynamicHazards) hz.active = false
        hazardSpawnTimer = 1.5f

        // Yola Sürpriz Kutularını Yerleştir ve Başlangıçta Rastgele Dinamik Engeller Doğur
        initSurpriseBoxes()
        for (k in 0 until 4) spawnRandomRoadHazard()

        currentLapRecording.clear()
        ghostFrameIdx = 0

        // 4. Yapay Zeka (Bot) Araçları: 3 Rekabetçi Bot (Toplam 4 Yarışçı: 1/4 .. 4/4)
        bots.clear()
        if (botsEnabled) {
            val botColors = intArrayOf(
                Color.rgb(255, 195, 20),
                Color.rgb(235, 45, 90),
                Color.rgb(60, 235, 140)
            )
            val startWps = intArrayOf(4, 7, 10)
            val startLanes = floatArrayOf(-20f, 20f, -8f)
            // Hızları oyuncuya yakın ve rekabetçi (0.94x - 1.01x), hata yapabilen gerçek fizikli botlar
            val spdFactors = floatArrayOf(0.95f, 0.98f, 1.01f)
            for (bIdx in 0..2) {
                val wp = startWps[bIdx]
                val lane = startLanes[bIdx]
                val bot = BotCar(
                    wpIdx = wp,
                    laneOffset = lane,
                    targetLane = lane,
                    baseSpeedFactor = spdFactors[bIdx],
                    color = botColors[bIdx],
                    modelIdx = (selectedModel + bIdx + 1) % 4
                )
                val bp = trackPts[wp]
                val bn = trackNormals[wp]
                bot.x = bp.x + bn.x * lane
                bot.y = bp.y + bn.y * lane
                bot.yaw = trackTangents[wp]
                bot.wheelIdBase = 2 + bIdx * 2
                bots.add(bot)
            }
        }

        lap = 1
        completedLaps = 0
        currentWp = 0
        maxWpReached = 0
        playerProgress = 0f
        playerFinishOrder = 0
        nextFinishOrder = 1
        racePosition = 1
        raceTime = 0f
        lapStartTime = 0f
        currentLapTime = 0f
        lastLapTime = 0f
        lapFlashTimer = 0f
        lapWasBest = false

        score = 0f
        comboTime = 0f
        comboGap = 0f
        multiplier = 1

        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        isNewRecord = false
        state = 3
        if (!fromNetwork) vibeClick()
        requestFocus()
    }

    private fun initSurpriseBoxes() {
        val total = trackPts.size
        if (total < 40) return
        val spacing = total / surpriseBoxes.size
        for (i in surpriseBoxes.indices) {
            val box = surpriseBoxes[i]
            val wp = (i * spacing + 22) % total
            val lane = ((i % 3) - 1) * 20f
            val p = trackPts[wp]
            val n = trackNormals[wp]
            box.wpIdx = wp
            box.laneOffset = lane
            box.x = p.x + n.x * lane
            box.y = p.y + n.y * lane
            box.active = true
            box.respawnTimer = 0f
            box.spinYaw = i * 0.8f
        }
    }

    // 3.4 Yolun Ortasında Rastgele, Sabit Yeri Olmayan Dinamik Engeller
    private fun spawnRandomRoadHazard() {
        val total = trackPts.size
        if (total < 40) return
        var slot: DynamicHazard? = null
        for (hz in dynamicHazards) {
            if (!hz.active) {
                slot = hz
                break
            }
        }
        if (slot == null) return

        // Oyuncunun 35..120 waypoint ilerisinde rastgele bir yol noktası seç
        val ahead = 35 + Random.nextInt(85)
        val wp = (currentWp + ahead) % total
        if (wp < 12 || wp > total - 12) return

        val p = trackPts[wp]
        val n = trackNormals[wp]
        val lane = (Random.nextFloat() * 2f - 1f) * (roadHalfWidth * 0.62f)
        slot.active = true
        slot.x = p.x + n.x * lane
        slot.y = p.y + n.y * lane
        slot.yaw = trackTangents[wp]
        slot.kind = Random.nextInt(3)
        slot.radius = if (slot.kind == 0) 13f else 11f
        slot.height = if (slot.kind == 1) 16f else 12f
        slot.life = 18f + Random.nextFloat() * 10f
    }

    // ==================== PARÇACIK, DUMAN (Smoke) VE SKIDTRAIL MOTORU ====================
    private fun spawnDebris(ix: Float, iy: Float, count: Int, baseCol: Int) {
        for (i in 0 until count) {
            val d = debrisPool[debrisHead]
            debrisHead = (debrisHead + 1) % debrisPool.size
            val ang = Random.nextFloat() * 6.28f
            val spd = 40f + Random.nextFloat() * 95f
            d.active = true
            d.x = ix; d.y = iy; d.z = 4f + Random.nextFloat() * 6f
            d.vx = cos(ang) * spd
            d.vy = sin(ang) * spd
            d.vz = 65f + Random.nextFloat() * 90f
            d.yaw = ang
            d.size = 2.8f + Random.nextFloat() * 3.0f
            d.color = if (i % 2 == 0) baseCol else Color.rgb(40, 40, 45)
            d.life = 4.0f
        }
    }

    private fun spawnParticle(px: Float, py: Float, pz: Float, vx: Float, vy: Float, vz: Float, sz: Float, col: Int, alpha: Int) {
        val p = particlePool[particleHead]
        particleHead = (particleHead + 1) % particlePool.size
        p.active = true
        p.x = px; p.y = py; p.z = pz
        p.vx = vx; p.vy = vy; p.vz = vz
        p.size = sz; p.color = col; p.alpha = alpha
    }

    // Referans CrownDriftGame.spawnSmoke: Harita Temasına Göre 32 Parçacıklı Duman/Toz Havuzu
    private fun spawnSmoke(x: Float, y: Float, vx: Float, vy: Float, dust: Boolean) {
        val s = smokePool[smokeHead]
        smokeHead = (smokeHead + 1) % SMOKE_COUNT
        s.x = x + (Random.nextFloat() - 0.5f) * 3.5f
        s.y = y + (Random.nextFloat() - 0.5f) * 3.5f
        s.z = 2.5f
        s.vx = -vx * 0.22f
        s.vy = -vy * 0.22f
        val life = (if (dust) 0.65f else 0.92f) * (Random.nextFloat() * 0.25f + 0.85f)
        s.lifeTotal = life
        s.life = life
        s.startSize = if (dust) 4.2f else 3.4f

        val theme = tracks[currentTrackIdx].theme
        when {
            theme == 3 -> { s.r = 242; s.g = 248; s.b = 255 } // Kar beyazı
            dust -> { s.r = 140; s.g = 115; s.b = 82 }        // Çim/Toprak tozu
            theme == 1 -> { s.r = 140; s.g = 218; s.b = 255 } // Cyber Neon dumanı
            else -> { s.r = 225; s.g = 225; s.b = 232 }       // Klasik lastik dumanı
        }
    }

    // Referans SkidTrail.extend(): Dairesel Tamponlu ve Zamanla Solan 3B Lastik İzi Şeritleri
    private fun extendSkidTrail(wheel: Int, x: Float, y: Float, skidStrength: Float, isGrassMark: Boolean, width: Float = 2.6f) {
        if (wheel !in 0 until WHEEL_CHANNELS) return
        if (!wheelActive[wheel]) {
            if (skidStrength <= 0.02f) return
            wheelActive[wheel] = true
            wheelEdgeValid[wheel] = false
            wheelLastX[wheel] = x
            wheelLastY[wheel] = y
            wheelLastS[wheel] = 0f
            return
        }

        val dx = x - wheelLastX[wheel]
        val dy = y - wheelLastY[wheel]
        val dist = hypot(dx, dy)

        if (dist > 34f) {
            wheelActive[wheel] = false
            return
        }
        if (dist < 1.2f) return

        val offX = -dy / dist * (width * 0.5f)
        val offY = dx / dist * (width * 0.5f)

        if (!wheelEdgeValid[wheel]) {
            wheelAX[wheel] = wheelLastX[wheel] - offX
            wheelAY[wheel] = wheelLastY[wheel] - offY
            wheelBX[wheel] = wheelLastX[wheel] + offX
            wheelBY[wheel] = wheelLastY[wheel] + offY
            wheelEdgeValid[wheel] = true
        }

        val curAX = x - offX
        val curAY = y - offY
        val curBX = x + offX
        val curBY = y + offY

        val seg = skidSegs[skidHead]
        seg.active = true
        seg.ax = wheelAX[wheel]; seg.ay = wheelAY[wheel]
        seg.bx = wheelBX[wheel]; seg.by = wheelBY[wheel]
        seg.cx = curBX; seg.cy = curBY
        seg.dx = curAX; seg.dy = curAY
        seg.strength0 = wheelLastS[wheel]
        seg.strength1 = skidStrength
        seg.isGrass = isGrassMark

        skidHead = (skidHead + 1) % MAX_SKID_SEGS
        if (skidCount < MAX_SKID_SEGS) skidCount++

        wheelAX[wheel] = curAX; wheelAY[wheel] = curAY
        wheelBX[wheel] = curBX; wheelBY[wheel] = curBY
        wheelLastX[wheel] = x; wheelLastY[wheel] = y
        wheelLastS[wheel] = skidStrength

        if (skidStrength <= 0.02f) {
            wheelActive[wheel] = false
        }
    }

    private fun breakPlayerSkidStrips() {
        wheelActive[0] = false
        wheelActive[1] = false
    }

    private fun breakAllSkidStrips() {
        for (i in 0 until WHEEL_CHANNELS) wheelActive[i] = false
    }

    // ==================== 1.2 PATLAMA & EN YAKIN GÜVENLİ YOLDA 3 SN CANLANMA ====================
    private fun triggerExplosionAtSpot() {
        if (isCrashing) return
        isCrashing = true
        crashTimer = 3.0f // İstenen 3 saniyelik yeniden doğma öncesi canlanma süresi
        crashSpotX = carX
        crashSpotY = carY
        carVZ = 145f
        speed = 0f
        carVX = 0f
        carVY = 0f
        smoothedSteering = 0f
        isCurrentlyDrifting = false
        driftAmount = 0f
        skidIntensity = 0f
        comboTime = 0f
        multiplier = 1
        breakPlayerSkidStrips()

        spawnDebris(carX, carY, 12, carColors[selectedColor])
        for (i in 0 until 14) {
            val ang = Random.nextFloat() * 6.28f
            val spd = 20f + Random.nextFloat() * 60f
            val fireCol = if (i % 2 == 0) Color.rgb(255, 110, 20) else Color.rgb(255, 210, 40)
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 40f + Random.nextFloat() * 50f, 5.5f, fireCol, 240)
        }
        triggerHaptic(180L, 255)
    }

    // 1.2 Düzeltmesi: Asla binanın içinde doğmaz; patlama noktasına en yakın güvenli asfalt merkezinde doğar!
    private fun respawnAtNearestRoadPoint() {
        isCrashing = false
        carZ = 0f
        carVZ = 0f
        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false

        val safeWp = findNearestWaypointLocal(crashSpotX, crashSpotY, currentWp, forceFullScan = true)
        currentWp = safeWp
        val roadPt = trackPts[safeWp]
        val roadTan = trackTangents[safeWp]

        // Doğuş noktasındaki dinamik engelleri temizle ki doğar doğmaz tekrar çarpmasın
        for (hz in dynamicHazards) {
            if (hz.active && hypot(hz.x - roadPt.x, hz.y - roadPt.y) < 65f) {
                hz.active = false
            }
        }

        carX = roadPt.x
        carY = roadPt.y
        carYaw = roadTan
        rollAngle = 0f
        smoothedSteering = 0f
        speed = 95f
        carVX = cos(roadTan) * speed
        carVY = sin(roadTan) * speed

        prevX = carX; prevY = carY; prevYaw = carYaw; prevRoll = 0f
        renderX = carX; renderY = carY; renderYaw = carYaw; renderRoll = 0f

        isFullStopped = false
        respawnBannerTimer = 1.8f
        respawnShieldTimer = 3.0f // 3 saniyelik doğuş koruma kalkanı
        breakPlayerSkidStrips()
        triggerHaptic(55L, 200)
    }

    // ==================== 3.3 SÜRPRİZ KUTUSU ÖZELLİKLERİ (FÜZE, DİKEN, KALKAN, ÖN SİLAH, KÖTÜ KUTU) ====================
    private fun showStatusToast(msg: String, duration: Float = 2.0f) {
        statusToastText = msg
        statusToastTimer = duration
    }

    private fun activateSurpriseBox() {
        // %18 ihtimalle Kötü Kutu (Eğer kalkan yoksa)
        val roll = Random.nextInt(100)
        val chosen = when {
            roll < 24 -> POWER_MISSILE
            roll < 46 -> POWER_SPIKE
            roll < 66 -> POWER_SHIELD
            roll < 84 -> POWER_FRONT_GUN
            else -> POWER_BAD_SLOW
        }

        if (chosen == POWER_BAD_SLOW) {
            if (activePower == POWER_SHIELD || respawnShieldTimer > 0f) {
                showStatusToast("KALKAN TUZAĞI ENGELLEDİ!", 1.8f)
                triggerHaptic(35L, 160)
                return
            }
            activePower = POWER_BAD_SLOW
            powerMaxTimer = 3.5f
            powerTimer = 3.5f
            carDamage = (carDamage + 16f).coerceAtMost(92f)
            speed *= 0.62f
            carVX *= 0.62f
            carVY *= 0.62f
            shakeTime = 0.25f
            showStatusToast("TUZAK KUTU! YAVAŞLAMA", 2.2f)
            triggerHaptic(110L, 240)
            return
        }

        activePower = chosen
        when (chosen) {
            POWER_MISSILE -> {
                powerMaxTimer = 9.0f
                powerTimer = 9.0f
                showStatusToast("FÜZE HAZIR (HEDEF GÖRÜNCE ATAR)", 2.0f)
            }
            POWER_SPIKE -> {
                powerMaxTimer = 7.0f
                powerTimer = 7.0f
                deployRoadSpike()
                showStatusToast("DİKEN BIRAKILDI!", 2.0f)
            }
            POWER_SHIELD -> {
                powerMaxTimer = 7.5f
                powerTimer = 7.5f
                showStatusToast("KORUMA KALKANI AKTİF!", 2.0f)
            }
            POWER_FRONT_GUN -> {
                powerMaxTimer = 7.0f
                powerTimer = 7.0f
                frontGunCooldown = 0.1f
                showStatusToast("ÖN SİLAH AKTİF!", 2.0f)
            }
        }
        triggerHaptic(45L, 210)
    }

    private fun deployRoadSpike() {
        val sp = roadSpikes[spikeHead]
        spikeHead = (spikeHead + 1) % roadSpikes.size
        sp.active = true
        // Rakip öndeyse ileri fırlat, arkadaysa arkaya bırak
        var enemyAhead = false
        val fwdX = cos(carYaw); val fwdY = sin(carYaw)
        for (bot in bots) {
            val dx = bot.x - carX; val dy = bot.y - carY
            val dist = hypot(dx, dy)
            if (dist < 180f && (dx * fwdX + dy * fwdY) > 0f) {
                enemyAhead = true
                break
            }
        }
        val offsetDist = if (enemyAhead) 34f else -22f
        sp.x = carX + fwdX * offsetDist
        sp.y = carY + fwdY * offsetDist
        sp.yaw = carYaw
        sp.radius = 15f
        sp.life = 12.0f
        sp.ownerIsPlayer = true
    }

    private fun spawnProjectile(isMissile: Boolean, px: Float, py: Float, yaw: Float) {
        val pr = projectiles[projectileHead]
        projectileHead = (projectileHead + 1) % projectiles.size
        pr.active = true
        pr.isMissile = isMissile
        pr.x = px
        pr.y = py
        pr.z = 5.5f
        pr.yaw = yaw
        val projSpeed = if (isMissile) 520f else 620f
        pr.vx = cos(yaw) * projSpeed
        pr.vy = sin(yaw) * projSpeed
        pr.life = if (isMissile) 2.4f else 1.1f
    }

    private fun updatePowerupsAndProjectiles(dt: Float) {
        if (statusToastTimer > 0f) statusToastTimer -= dt

        // Sürpriz Kutularının Dönüşü ve Toplama Kontrolü
        for (box in surpriseBoxes) {
            if (!box.active) {
                box.respawnTimer -= dt
                if (box.respawnTimer <= 0f) box.active = true
                continue
            }
            box.spinYaw += 2.6f * dt
            val dx = carX - box.x
            val dy = carY - box.y
            if (!isCrashing && dx * dx + dy * dy < 24f * 24f) {
                box.active = false
                box.respawnTimer = 8.5f
                activateSurpriseBox()
            }
        }

        // Aktif Özellik Süresi ve Otomatik Tetiklemeler
        if (activePower != POWER_NONE) {
            powerTimer -= dt
            if (powerTimer <= 0f) {
                activePower = POWER_NONE
                powerTimer = 0f
            } else {
                val fwdX = cos(carYaw)
                val fwdY = sin(carYaw)

                // FÜZE: Rakip görüş açısına ve doğrultusuna girince otomatik ateşlenir ve dümdüz gider
                if (activePower == POWER_MISSILE && botsEnabled) {
                    for (bot in bots) {
                        if (bot.flipTimer > 0f) continue
                        val dx = bot.x - carX
                        val dy = bot.y - carY
                        val dist = hypot(dx, dy)
                        if (dist in 25f..430f) {
                            val dot = (dx / dist) * fwdX + (dy / dist) * fwdY
                            if (dot > 0.86f) { // Görüş konisi ve doğrultusunda!
                                spawnProjectile(isMissile = true, carX + fwdX * 18f, carY + fwdY * 18f, carYaw)
                                showStatusToast("FÜZE ATEŞLENDİ!", 1.4f)
                                triggerHaptic(65L, 250)
                                activePower = POWER_NONE
                                powerTimer = 0f
                                break
                            }
                        }
                    }
                }

                // ARAÇ ÖNÜ SİLAHI: Önündeki rakiplere ve engellere seri ateş eder
                if (activePower == POWER_FRONT_GUN) {
                    frontGunCooldown -= dt
                    if (frontGunCooldown <= 0f) {
                        frontGunCooldown = 0.24f
                        val rx = -fwdY * 5.5f
                        val ry = fwdX * 5.5f
                        val side = if ((stepCounter % 2) == 0) 1f else -1f
                        spawnProjectile(
                            isMissile = false,
                            carX + fwdX * 16f + rx * side,
                            carY + fwdY * 16f + ry * side,
                            carYaw
                        )
                    }
                }

                // DİKEN: Her 2.2 saniyede bir yeni diken bırakabilir
                if (activePower == POWER_SPIKE) {
                    frontGunCooldown -= dt
                    if (frontGunCooldown <= 0f) {
                        frontGunCooldown = 2.3f
                        deployRoadSpike()
                    }
                }
            }
        }

        // Füzelerin ve Mermilerin Hareketi & Rakipleri Devirme Kontrolü
        for (pr in projectiles) {
            if (!pr.active) continue
            pr.life -= dt
            if (pr.life <= 0f) {
                pr.active = false
                continue
            }
            pr.x += pr.vx * dt
            pr.y += pr.vy * dt

            if (pr.isMissile && (stepCounter % 2 == 0)) {
                spawnParticle(pr.x, pr.y, pr.z, 0f, 0f, 15f, 3.2f, Color.rgb(255, 160, 30), 210)
            }

            // Botlara İsabet Kontrolü
            if (botsEnabled) {
                for (bot in bots) {
                    val dx = pr.x - bot.x
                    val dy = pr.y - bot.y
                    val hitRad = if (pr.isMissile) 22f else 17f
                    if (dx * dx + dy * dy < hitRad * hitRad) {
                        pr.active = false
                        bot.flipTimer = if (pr.isMissile) 2.2f else 1.2f
                        bot.vz = if (pr.isMissile) 135f else 85f
                        bot.vx *= 0.25f
                        bot.vy *= 0.25f
                        bot.slowTimer = 2.8f
                        spawnDebris(bot.x, bot.y, if (pr.isMissile) 9 else 4, bot.color)
                        showStatusToast("RAKİP DEVİLDİ!", 1.4f)
                        triggerHaptic(50L, 230)
                        break
                    }
                }
            }

            // Dinamik Engelleri Parçalama Kontrolü
            if (pr.active) {
                for (hz in dynamicHazards) {
                    if (!hz.active) continue
                    val dx = pr.x - hz.x
                    val dy = pr.y - hz.y
                    if (dx * dx + dy * dy < (hz.radius + 10f) * (hz.radius + 10f)) {
                        pr.active = false
                        hz.active = false
                        spawnDebris(hz.x, hz.y, 6, Color.rgb(255, 140, 25))
                        break
                    }
                }
            }
        }

        // Yoldaki Dikenlerin Kontrolü (Rakip çarpınca devrilir/yavaşlar)
        for (sp in roadSpikes) {
            if (!sp.active) continue
            sp.life -= dt
            if (sp.life <= 0f) {
                sp.active = false
                continue
            }
            if (botsEnabled) {
                for (bot in bots) {
                    if (bot.flipTimer > 0f) continue
                    val dx = bot.x - sp.x
                    val dy = bot.y - sp.y
                    if (dx * dx + dy * dy < (sp.radius + 10f) * (sp.radius + 10f)) {
                        sp.active = false
                        bot.flipTimer = 1.8f
                        bot.vz = 110f
                        bot.vx *= 0.3f
                        bot.vy *= 0.3f
                        bot.slowTimer = 3.2f
                        spawnDebris(bot.x, bot.y, 6, bot.color)
                        showStatusToast("RAKİP DİKENE ÇARPTI!", 1.5f)
                        triggerHaptic(40L, 200)
                        break
                    }
                }
            }
        }

        // Dinamik Yol Engelleri Yaşam Döngüsü ve Yeniden Doğması
        hazardSpawnTimer -= dt
        if (hazardSpawnTimer <= 0f) {
            hazardSpawnTimer = 3.5f
            spawnRandomRoadHazard()
        }
        for (hz in dynamicHazards) {
            if (!hz.active) continue
            hz.life -= dt
            if (hz.life <= 0f) hz.active = false
        }
    }

    // ==================== 4. YAPAY ZEKA (BOT) FİZİĞİ: AYNI FİZİK MOTORU + GERÇEKÇİ HATA PAYI ====================
    private fun updateBotsStep(dt: Float) {
        val total = trackPts.size
        if (total == 0) return

        val playerBaseSpeed = when (selectedModel) { 0 -> 275f; 1 -> 292f; 2 -> 282f; else -> 306f }

        for (bot in bots) {
            // Devrilme / Havaya Savrulma Durumu (Füze veya Diken İsabeti)
            if (bot.flipTimer > 0f) {
                bot.flipTimer -= dt
                bot.z += bot.vz * dt
                bot.vz -= 240f * dt
                if (bot.z < 0f) {
                    bot.z = 0f
                    bot.vz = abs(bot.vz) * 0.4f
                }
                bot.rollAngle += 380f * dt
                bot.x += bot.vx * dt
                bot.y += bot.vy * dt
                bot.vx *= 0.94f
                bot.vy *= 0.94f
                extendSkidTrail(bot.wheelIdBase, bot.x, bot.y, 0f, false)
                extendSkidTrail(bot.wheelIdBase + 1, bot.x, bot.y, 0f, false)
                continue
            } else {
                bot.z = 0f
                bot.vz = 0f
            }

            if (bot.slowTimer > 0f) bot.slowTimer -= dt

            // Botun En Yakın Waypoint'i ve Viraj Hata Mekanizması
            val prevWp = bot.wpIdx
            bot.wpIdx = findNearestWaypointLocal(bot.x, bot.y, bot.wpIdx)
            val wp = bot.wpIdx
            val inSharpCorner = zoneMask.isNotEmpty() && zoneMask[wp]

            // Gerçekçi Hata Yapma (Virajı geniş alma / kaçırma, şerit hatası)
            bot.mistakeTimer -= dt
            if (bot.mistakeTimer <= 0f) {
                bot.mistakeTimer = 1.6f + Random.nextFloat() * 2.4f
                if (inSharpCorner && Random.nextFloat() < 0.42f) {
                    // Virajda dışa savrulma / virajı kaçırma hatası
                    val outward = if (Random.nextBoolean()) 1f else -1f
                    bot.mistakeOffset = outward * (roadHalfWidth * 0.78f)
                } else {
                    bot.mistakeOffset = (Random.nextFloat() - 0.5f) * 26f
                }
                bot.targetLane = (bot.mistakeOffset).coerceIn(-roadHalfWidth * 0.85f, roadHalfWidth * 0.85f)
            }

            // İlerideki hedef noktaya yönelme
            val lookAhead = if (inSharpCorner) 6 else 9
            val targetWp = (wp + lookAhead) % total
            val tp = trackPts[targetWp]
            val tn = trackNormals[targetWp]
            val aimX = tp.x + tn.x * bot.targetLane
            val aimY = tp.y + tn.y * bot.targetLane

            val desiredYaw = atan2(aimY - bot.y, aimX - bot.x)
            val yawError = wrapAngle(desiredYaw - bot.yaw)
            val steerCmd = (yawError * 1.15f).coerceIn(-0.65f, 0.65f)

            // Oyuncuyla Aynı Car3D.steer & Grip Hız Vektörü Fiziği
            bot.smoothedSteering += (steerCmd - bot.smoothedSteering) * 0.08f
            bot.yaw = wrapAngle(bot.yaw + 1.3f * bot.smoothedSteering * (dt * 60f) * 0.045f)
            bot.rollAngle += ((-bot.smoothedSteering * 42f) - bot.rollAngle) * 0.14f
            bot.rollAngle *= 0.88f

            // Botun yoldan/çimden etkilenmesi
            val centerPt = trackPts[wp]
            val distFromCenter = hypot(bot.x - centerPt.x, bot.y - centerPt.y)
            val botOnGrass = distFromCenter > roadHalfWidth + kerbWidth
            val cornerSlowdown = if (inSharpCorner && abs(yawError) > 0.35f) 0.86f else 1.0f
            val grassMult = if (botOnGrass) GRASS_SPEED else 1.0f
            val slowMult = if (bot.slowTimer > 0f) 0.58f else 1.0f

            val targetThrust = playerBaseSpeed * bot.baseSpeedFactor * cornerSlowdown * grassMult * slowMult
            val fwdX = cos(bot.yaw)
            val fwdY = sin(bot.yaw)
            val botGrip = grip * 1.15f
            bot.vx += (fwdX * targetThrust - bot.vx) * botGrip
            bot.vy += (fwdY * targetThrust - bot.vy) * botGrip

            bot.x += bot.vx * dt
            bot.y += bot.vy * dt

            // Bot Sabit/Dinamik Engellere Sürtme Kontrolü
            for (hz in dynamicHazards) {
                if (!hz.active) continue
                val dx = bot.x - hz.x
                val dy = bot.y - hz.y
                val minD = hz.radius + 10f
                if (dx * dx + dy * dy < minD * minD) {
                    hz.active = false
                    bot.vx *= 0.62f
                    bot.vy *= 0.62f
                    spawnDebris(hz.x, hz.y, 4, bot.color)
                }
            }

            // Bot Drift ve SkidTrail İzleri (6.2)
            val bSpd = hypot(bot.vx, bot.vy)
            var bDrift = 0f
            if (bSpd > 40f) {
                val align = (bot.vx / bSpd) * fwdX + (bot.vy / bSpd) * fwdY
                bDrift = (1f - align).coerceIn(0f, 1f)
            }
            val targetSkid = if (bSpd > 65f && (bDrift > 0.045f || botOnGrass)) {
                ((bDrift - 0.02f) / 0.12f).coerceIn(0.25f, 0.9f)
            } else 0f
            bot.skidIntensity += (targetSkid - bot.skidIntensity) * 0.25f

            val rearCx = bot.x - fwdX * 11.5f
            val rearCy = bot.y - fwdY * 11.5f
            val offX = -fwdY * 6.8f
            val offY = fwdX * 6.8f
            extendSkidTrail(bot.wheelIdBase, rearCx + offX, rearCy + offY, bot.skidIntensity, botOnGrass, 2.4f)
            extendSkidTrail(bot.wheelIdBase + 1, rearCx - offX, rearCy - offY, bot.skidIntensity, botOnGrass, 2.4f)

            // Bot Tur ve İlerleme Takibi (1.1 Sıralama Düzeltmesi)
            if (wp > bot.maxWpInLap && wp - bot.maxWpInLap < total / 3) {
                bot.maxWpInLap = wp
            }
            if (wp < total / 10 && bot.maxWpInLap > (total * 3) / 4 && prevWp > (total * 3) / 4) {
                bot.maxWpInLap = 0
                if (bot.completedLaps < totalLaps) {
                    bot.completedLaps++
                    if (bot.completedLaps >= totalLaps && bot.finishOrder == 0) {
                        bot.finishOrder = nextFinishOrder++
                    }
                }
            }
            bot.totalProgress = computeContinuousProgress(bot.x, bot.y, bot.wpIdx, bot.completedLaps)
        }
    }

    // ==================== 6.1 – 6.5 ANA FİZİK ADIMI (SABİT 60Hz + INTERPOLASYON) ====================
    private fun beginPhysicsStep() {
        prevX = carX
        prevY = carY
        prevYaw = carYaw
        prevRoll = rollAngle
    }

    private fun applyInterpolatedPose(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        renderX = prevX + (carX - prevX) * a
        renderY = prevY + (carY - prevY) * a
        renderYaw = prevYaw + wrapAngle(carYaw - prevYaw) * a
        renderRoll = prevRoll + (rollAngle - prevRoll) * a
        frontWheelAngleDeg = (smoothedSteering * 500f).coerceIn(-28f, 28f)
    }

    private fun updateCamera(dt: Float) {
        // Referans CrownDriftGame.updateCamera: smooth = 1 - 0.85^(dt * 60)
        val smooth = (1f - 0.85f.pow(dt * 60f)).coerceIn(0.05f, 1f)

        var fx = cos(renderYaw)
        var fy = sin(renderYaw)
        val spd = hypot(carVX, carVY)

        // 6.4 Hız vektörü harmanlaması: %55 bakış yönü + %45 hız vektörü
        if (spd > 15f) {
            fx = fx * 0.55f + (carVX / spd) * 0.45f
            fy = fy * 0.55f + (carVY / spd) * 0.45f
        }
        val len = hypot(fx, fy).coerceAtLeast(0.001f)
        fx /= len
        fy /= len

        val targetCamYaw = atan2(fy, fx)
        camYaw = wrapAngle(camYaw + wrapAngle(targetCamYaw - camYaw) * smooth)

        // Boost ve kombo çarpanına bağlı dinamik kamera uzaklığı, yüksekliği ve FOV
        val zoom = (multiplier - 1) * 5.5f + if (boostTime > 0f) 14f else 0f
        val targetDist = 96f + zoom * 0.85f
        val targetHeight = 136f + zoom * 0.65f
        val targetFov = if (boostTime > 0f) 1.32f else 1.48f // Düşük fovScale = Daha geniş görüş açısı (75° FOV)

        camDist += (targetDist - camDist) * smooth
        camZ += (targetHeight - camZ) * smooth
        camFovScale += (targetFov - camFovScale) * smooth

        // Çimde veya sarsıntıda kamera titremesi (shakeTime)
        if (shakeTime > 0f) {
            shakeTime -= dt
            shakeOffsetX = (Random.nextFloat() - 0.5f) * 4.2f
            shakeOffsetY = (Random.nextFloat() - 0.5f) * 3.0f
        } else {
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }

        camX = renderX + shakeOffsetX
        camY = renderY + shakeOffsetY
        camCosYaw = cos(camYaw)
        camSinYaw = sin(camYaw)
    }

    private fun physicsStep(stepSteerInput: Float) {
        beginPhysicsStep()
        stepCounter++

        if (respawnBannerTimer > 0f) respawnBannerTimer -= STEP
        if (respawnShieldTimer > 0f) respawnShieldTimer -= STEP

        // Kırık Parçalar (Debris) Fiziği
        for (d in debrisPool) {
            if (!d.active) continue
            d.life -= STEP
            if (d.life <= 0f) {
                d.active = false
            } else {
                d.x += d.vx * STEP; d.y += d.vy * STEP; d.z += d.vz * STEP
                d.vz -= 240f * STEP
                if (d.z < 0.5f) {
                    d.z = 0.5f; d.vz = -d.vz * 0.45f
                    d.vx *= 0.75f; d.vy *= 0.75f
                }
            }
        }

        // Kademeli Hasar Duman/Alev Parçacıkları
        if (carDamage > 25f && (stepCounter % 4 == 0)) {
            val isFire = carDamage > 70f
            val col = if (isFire) Color.rgb(255, 140, 25) else Color.rgb(65, 65, 70)
            spawnParticle(carX, carY, carZ + 5f, 0f, 0f, 28f, if (isFire) 4.2f else 3.4f, col, 175)
        }
        for (p in particlePool) {
            if (!p.active) continue
            p.x += p.vx * STEP; p.y += p.vy * STEP; p.z += p.vz * STEP
            p.size += 4.5f * STEP
            p.alpha -= (210 * STEP).toInt()
            if (p.alpha <= 0) p.active = false
        }

        // 32 Parçacıklı Duman/Toz Havuzu Güncellemesi (CrownDriftGame.updateAndDrawSmoke)
        for (s in smokePool) {
            if (s.life <= 0f) continue
            s.life -= STEP
            if (s.life <= 0f) continue
            s.x += s.vx * STEP
            s.y += s.vy * STEP
            s.z += 14f * STEP
            s.vx *= 0.96f
            s.vy *= 0.96f
        }

        // Patlama Durumunda 3 Saniyelik Canlanma Sayacı
        if (isCrashing) {
            crashTimer -= STEP
            carZ += carVZ * STEP
            carVZ -= 235f * STEP
            if (carZ < 0f) {
                carZ = 0f
                carVZ = abs(carVZ) * 0.45f
                triggerHaptic(35L, 210)
            }
            rollAngle += 320f * STEP
            // Oyuncu beklerken botlar yarışmaya devam eder
            if (botsEnabled) updateBotsStep(STEP)
            updateRaceStandings()
            if (crashTimer <= 0f) {
                respawnAtNearestRoadPoint()
            }
            return
        }

        raceTime += STEP
        currentLapTime = raceTime - lapStartTime
        if (lapFlashTimer > 0f) lapFlashTimer -= STEP
        if (boostHintTime > 0f) boostHintTime -= STEP

        // 3.2 & 6.3 Turbo (Boost) Ateşleme
        if (boostTime > 0f) boostTime -= STEP
        if (boostRequest) {
            boostRequest = false
            if (boostReady) {
                boostReady = false
                boostMeter = 0f
                boostTime = BOOST_SECONDS
                triggerHaptic(85L, 245)
                showStatusToast("TURBO AKTİF!", 1.4f)
            }
        }

        // 6.1 Car3D.steer(): Yumuşatılmış Direksiyon, Yaw ve Roll Güncellemesi
        val sensFactor = when (sensLevel) {
            0 -> 0.65f
            2 -> 1.38f
            else -> 1.0f
        }
        val rawSteer = (stepSteerInput * sensFactor).coerceIn(-0.14f, 0.14f)
        smoothedSteering += (rawSteer - smoothedSteering) * 0.045f
        if (!isBezelDragging && abs(stepSteerInput) < 0.001f) {
            smoothedSteering *= 0.92f
        }
        steerInput = (smoothedSteering * 10f).coerceIn(-1f, 1f)

        if (!isFullStopped && !isHoldingStop && speed > 8f) {
            carYaw = wrapAngle(carYaw + 1.3f * smoothedSteering)
            rollAngle += ((-smoothedSteering * 70f) - rollAngle) * 0.1f
        }
        rollAngle *= 0.85f

        // 6.1 Car3D.update(): Grip Tabanlı Hız Vektörü Yakınsaması (velocity += (fwd * thrust - velocity) * grip)
        val fwdX = cos(carYaw)
        val fwdY = sin(carYaw)
        val baseMaxForward = when (selectedModel) { 0 -> 275f; 1 -> 292f; 2 -> 282f; else -> 306f }
        val speedFactor = when {
            isFullStopped || isHoldingStop -> 0f
            boostTime > 0f -> BOOST_SPEED
            activePower == POWER_BAD_SLOW -> 0.64f
            onGrass -> GRASS_SPEED
            else -> 1.0f
        }
        val thrust = baseMaxForward * speedFactor

        if (isFullStopped || isHoldingStop) {
            carVX *= 0.88f
            carVY *= 0.88f
        } else {
            val effectiveGrip = if (boostTime > 0f) grip * 1.25f else grip
            carVX += (fwdX * thrust - carVX) * effectiveGrip
            carVY += (fwdY * thrust - carVY) * effectiveGrip
        }

        carX += carVX * STEP
        carY += carVY * STEP
        speed = hypot(carVX, carVY)

        // Turbo Egzoz Alevi
        if (boostTime > 0f && (stepCounter % 2 == 0)) {
            spawnParticle(
                carX - fwdX * 15f, carY - fwdY * 15f, 3f,
                -fwdX * 60f, -fwdY * 60f, 10f,
                4.0f, Color.rgb(0, 225, 255), 220
            )
        }

        // Arka Tekerlek Koordinatları ve Histerezisli Drift Tespiti (0.93f / 0.955f)
        val rearCx = carX - fwdX * 11.5f
        val rearCy = carY - fwdY * 11.5f
        val offX = -fwdY * 7.0f
        val offY = fwdX * 7.0f
        val rearLX = rearCx + offX
        val rearLY = rearCy + offY
        val rearRX = rearCx - offX
        val rearRY = rearCy - offY

        val wasDrifting = isCurrentlyDrifting
        isCurrentlyDrifting = false
        driftAmount = 0f
        var drift = 0f

        if (speed > 25f) {
            val alignment = (carVX / speed) * fwdX + (carVY / speed) * fwdY
            drift = (1f - alignment).coerceIn(0f, 1f)
            driftAmount = drift
            val threshold = if (wasDrifting) 0.955f else 0.93f
            if (speed > 48f && alignment < threshold) {
                isCurrentlyDrifting = true
            }
        }

        // Lastik İzi Yoğunluğu ve SkidTrail Uzatma (Car3D.update + SkidTrail.extend)
        var targetSkid = 0f
        if (speed > 45f && (isCurrentlyDrifting || onGrass)) {
            targetSkid = if (onGrass) 0.75f else ((drift - 0.015f) / 0.09f).coerceIn(0f, 1f)
        }
        skidIntensity += (targetSkid - skidIntensity) * 0.25f

        extendSkidTrail(0, rearLX, rearLY, skidIntensity, onGrass, 2.6f)
        extendSkidTrail(1, rearRX, rearRY, skidIntensity, onGrass, 2.6f)

        // Ghost Kaydı
        if (currentLapRecording.size < 3600) {
            currentLapRecording.add(GhostPose(carX, carY, carYaw, rollAngle))
        }
        ghostFrameIdx++

        // Sürpriz Kutuları, Silahlar ve Dinamik Engeller
        updatePowerupsAndProjectiles(STEP)

        // Bot Fiziği ve Çarpışmaları
        if (botsEnabled) {
            updateBotsStep(STEP)
            val shielded = (activePower == POWER_SHIELD || respawnShieldTimer > 0f)
            for (bot in bots) {
                val dx = carX - bot.x
                val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 529f && distSq > 0.01f) {
                    val dist = sqrt(distSq)
                    val pushAng = atan2(dy, dx)
                    val overlap = 23f - dist
                    carX += cos(pushAng) * overlap * 0.55f
                    carY += sin(pushAng) * overlap * 0.55f
                    bot.x -= cos(pushAng) * overlap * 0.45f
                    bot.y -= sin(pushAng) * overlap * 0.45f

                    carVX += cos(pushAng) * 55f
                    carVY += sin(pushAng) * 55f
                    bot.vx -= cos(pushAng) * 95f
                    bot.vy -= sin(pushAng) * 95f

                    if (!shielded) {
                        carDamage = (carDamage + 8f).coerceAtMost(100f)
                        if (carDamage > 35f) bumperBroken = true
                        if (carDamage > 65f) spoilerBroken = true
                    }
                    shakeTime = 0.14f
                    spawnDebris((carX + bot.x) * 0.5f, (carY + bot.y) * 0.5f, 4, carColors[selectedColor])
                    triggerHaptic(40L, 220)
                }
            }
        }

        // Sabit Engellere (Ağaç, Bina, Yol Kenarı Bariyeri) Çarpma
        val shielded = (activePower == POWER_SHIELD || respawnShieldTimer > 0f)
        for (obs in obstacles) {
            val dx = carX - obs.x
            val dy = carY - obs.y
            val minD = obs.radius + 11f
            if (dx * dx + dy * dy < minD * minD) {
                val ang = atan2(dy, dx)
                carX = obs.x + cos(ang) * (minD + 2f)
                carY = obs.y + sin(ang) * (minD + 2f)

                if (obs.type == 2) {
                    // Yol kenarı bariyeri: yavaşlatır ve sektirir
                    carVX *= 0.55f
                    carVY *= 0.55f
                    if (!shielded) carDamage = (carDamage + 12f).coerceAtMost(100f)
                    shakeTime = 0.15f
                    spawnDebris(carX, carY, 5, Color.rgb(255, 110, 20))
                    triggerHaptic(50L, 210)
                } else {
                    // Ağaç veya Bina
                    if (!shielded) {
                        carDamage += speed * 0.25f
                        if (carDamage > 35f) bumperBroken = true
                        if (carDamage > 65f) spoilerBroken = true
                        if (speed > 210f || carDamage >= 100f) {
                            triggerExplosionAtSpot()
                            return
                        }
                    }
                    carVX = cos(ang) * (speed * 0.42f)
                    carVY = sin(ang) * (speed * 0.42f)
                    shakeTime = 0.18f
                    spawnDebris(carX, carY, 6, carColors[selectedColor])
                    triggerHaptic(75L, 245)
                }
            }
        }

        // Dinamik Yol Engellerine (Sabit Yeri Olmayan Engeller) Çarpma
        for (hz in dynamicHazards) {
            if (!hz.active) continue
            val dx = carX - hz.x
            val dy = carY - hz.y
            val minD = hz.radius + 10f
            if (dx * dx + dy * dy < minD * minD) {
                hz.active = false
                if (!shielded) {
                    carDamage = (carDamage + if (hz.kind == 0) 9f else 16f).coerceAtMost(100f)
                    if (carDamage > 35f) bumperBroken = true
                    if (carDamage > 65f) spoilerBroken = true
                    if (carDamage >= 100f) {
                        triggerExplosionAtSpot()
                        return
                    }
                    carVX *= if (hz.kind == 0) 0.68f else 0.52f
                    carVY *= if (hz.kind == 0) 0.68f else 0.52f
                }
                shakeTime = 0.16f
                spawnDebris(hz.x, hz.y, 7, Color.rgb(255, 130, 20))
                triggerHaptic(55L, 225)
            }
        }

        // Yerel Waypoint Araması ve Pist Üzerindeki Konum (onGrass & edgeActive)
        val total = trackPts.size
        val bestIdx = findNearestWaypointLocal(carX, carY, currentWp)
        val tp = trackPts[bestIdx]
        lastRoadDist = hypot(carX - tp.x, carY - tp.y)
        val curbEdge = roadHalfWidth + kerbWidth

        // 6.2 Referanstaki onGrass ve Edge Bonus: Yoldan çıkınca yarış bitmez, %40 yavaşlar + kamera sarsılır
        onGrass = lastRoadDist > curbEdge
        edgeActive = isCurrentlyDrifting && !onGrass && lastRoadDist > (curbEdge - EDGE_BAND)

        val outerFence = roadHalfWidth * 4.5f
        if (lastRoadDist > outerFence) {
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * outerFence
            carY = tp.y + sin(angToCar) * outerFence
            carVX *= 0.65f
            carVY *= 0.65f
        }

        // 6.3 & 6.5 Puan, Kombo, Drift Zone, Boost Dolumu ve Haptik Formülü
        val inZone = zoneMask.isNotEmpty() && zoneMask[bestIdx]
        if (onGrass) {
            comboTime = 0f
            multiplier = 1
            comboGap = 0f
            shakeTime = 0.15f
            hapticTimer -= STEP
            if (hapticTimer <= 0f && speed > 45f) {
                triggerHaptic(12L, 110)
                hapticTimer = 0.14f
            }
        } else if (isCurrentlyDrifting) {
            comboGap = 0f
            comboTime += STEP
            val newMult = min(MAX_MULTIPLIER, (comboTime / COMBO_STEP_SECONDS).toInt() + 1)
            if (newMult > multiplier) triggerHaptic(25L, 195)
            multiplier = newMult

            val pts = multiplier * (POINTS_PER_SECOND * STEP) *
                    (if (inZone) 2.0f else 1.0f) *
                    (if (edgeActive) EDGE_FACTOR else 1.0f) *
                    (if (boostTime > 0f) 2.0f else 1.0f)
            score += pts

            if (boostTime <= 0f && !boostReady) {
                boostMeter += (multiplier * 0.022f + 0.042f) * STEP
                if (boostMeter >= 1f) {
                    boostMeter = 1f
                    boostReady = true
                    boostHintTime = BOOST_HINT_SECONDS
                    triggerHaptic(60L, 230)
                }
            }

            // 6.5 Referans Haptik Formülü: min((driftAmount * 70 + 6), 28)
            hapticTimer -= STEP
            if (hapticTimer <= 0f) {
                val dur = min((driftAmount * 70f + 6f).toInt(), 28).toLong()
                val amp = (110 + driftAmount * 140f).toInt().coerceIn(100, 255)
                triggerHaptic(dur, amp)
                hapticTimer = 0.16f
            }
        } else {
            comboGap += STEP
            if (comboGap > COMBO_BREAK_SECONDS) {
                comboTime = 0f
                multiplier = 1
            }
        }

        // Duman Üretimi (CrownDriftGame: 2 adımda bir sağ/sol arka tekerlek sırayla)
        if ((isCurrentlyDrifting || onGrass) && speed > 50f && (stepCounter % 2 == 0)) {
            val leftWheel = ((stepCounter / 2) % 2 == 0)
            spawnSmoke(
                if (leftWheel) rearLX else rearRX,
                if (leftWheel) rearLY else rearRY,
                carVX, carVY, onGrass
            )
        }

        // Tur Sayacı ve 1.1 Bitiş Sırası Düzeltmesi
        if (bestIdx > maxWpReached && bestIdx - maxWpReached < total / 3) {
            maxWpReached = bestIdx
        }
        if (bestIdx < total / 10 && maxWpReached > (total * 3) / 4 && currentWp > (total * 3) / 4) {
            maxWpReached = 0
            lastLapTime = raceTime - lapStartTime
            lapStartTime = raceTime
            lapWasBest = lastLapTime < bestLapTime || bestLapGhost.isEmpty()
            if (lapWasBest) {
                bestLapTime = lastLapTime
                bestLapGhost = currentLapRecording.toList()
                prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply()
            }
            lapFlashTimer = 2.0f
            currentLapRecording.clear()
            ghostFrameIdx = 0

            completedLaps++
            if (completedLaps < totalLaps) {
                lap = completedLaps + 1
                triggerHaptic(55L, 255)
            } else {
                // 1.1 Düzeltmesi: Bitiş çizgisini geçtiğimiz anda bitiş sıramızı (1., 2. vb.) kilitle!
                if (playerFinishOrder == 0) {
                    playerFinishOrder = nextFinishOrder++
                }
                playerProgress = totalLaps * total.toFloat() + 100f
                updateRaceStandings()

                val finalScore = score.toInt()
                val oldBest = prefs.getInt("best_score_$currentTrackIdx", 0)
                if (finalScore > oldBest) {
                    isNewRecord = true
                    prefs.edit().putInt("best_score_$currentTrackIdx", finalScore).apply()
                }
                triggerHaptic(220L, 255)
                state = 4
                return
            }
        }
        currentWp = bestIdx
        playerProgress = computeContinuousProgress(carX, carY, currentWp, completedLaps)
        updateRaceStandings()
    }

    // 1.1 Bitiş Sırası ve Anlık Sıralama Hesabı (Kesin ve Hatasız)
    private fun updateRaceStandings() {
        if (playerFinishOrder > 0) {
            // Oyuncu yarışı bitirdiyse o andaki bitiş sırası (örn. 1.) kilitlenir!
            racePosition = playerFinishOrder
            return
        }
        var pos = 1
        if (botsEnabled) {
            for (bot in bots) {
                if (bot.finishOrder > 0 || bot.totalProgress > playerProgress) {
                    pos++
                }
            }
        }
        for (rp in remotePlayers) {
            if (rp.active && rp.finishOrder > 0) pos++
        }
        racePosition = pos
    }

    // --- BÖLÜM 2 SONU: FİZİK, DRİFT MOTORU, SÜRPRİZ KUTULARI, BOTLAR VE CO-OP ---

    // --- BÖLÜM 3 BAŞLANGICI: 3B PROJEKSİYON MOTORU, ARAÇ ÇİZİMLERİ, HUD VE EKRANLAR ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)

        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f
        val x = event.x
        val y = event.y

        when (state) {
            0 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.35f)..(cy + R * 0.18f)) {
                    if (x < cx - R * 0.42f) {
                        currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size
                        buildTrackSpline(currentTrackIdx); vibeClick()
                    } else if (x > cx + R * 0.42f) {
                        currentTrackIdx = (currentTrackIdx + 1) % tracks.size
                        buildTrackSpline(currentTrackIdx); vibeClick()
                    }
                }
                if (hypot(x - (cx - R * 0.60f), y - (cy + R * 0.32f)) < R * 0.20f) {
                    state = 1; vibeClick()
                } else if (hypot(x - (cx + R * 0.60f), y - (cy + R * 0.32f)) < R * 0.20f) {
                    state = 2; vibeClick()
                } else if (y in (cy + R * 0.32f)..(cy + R * 0.52f) && abs(x - cx) < R * 0.36f) {
                    state = 5; vibeClick()
                } else if (y > cy + R * 0.54f && abs(x - cx) < R * 0.50f) {
                    vibeClick(); startRace()
                }
            }

            1 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.12f)..(cy + R * 0.14f)) {
                    selectedModel = (selectedModel + 1) % carModels.size
                    myAssignedModel = selectedModel
                    saveSettings(); vibeClick()
                }
                val gridTop = cy + R * 0.18f
                val gridBot = cy + R * 0.62f
                if (y in gridTop..gridBot) {
                    val row = if (y < (gridTop + gridBot) / 2f) 0 else 1
                    val col = ((x - (cx - R * 0.56f)) / (R * 1.12f) * 4f).toInt().coerceIn(0, 3)
                    selectedColor = row * 4 + col
                    saveSettings(); vibeClick()
                }
                if (y > cy + R * 0.64f) {
                    state = 0; vibeClick()
                }
            }

            2 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.66f)..(cy - R * 0.38f)) { sensLevel = (sensLevel + 1) % 3; saveSettings(); vibeClick() }
                else if (y in (cy - R * 0.37f)..(cy - R * 0.11f)) { state = 5; vibeClick() }
                else if (y in (cy - R * 0.10f)..(cy + R * 0.16f)) { ghostEnabled = !ghostEnabled; saveSettings(); vibeClick() }
                else if (y in (cy + R * 0.17f)..(cy + R * 0.44f)) { botsEnabled = !botsEnabled; saveSettings(); vibeClick() }
                else if (y > cy + R * 0.46f) {
                    if (x < cx) { showFps = !showFps; saveSettings() } else state = 0
                    vibeClick()
                }
            }

            5 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.45f)..(cy - R * 0.15f)) {
                    if (x < cx) startHostLobby() else scanAndJoinLobby()
                    vibeClick()
                } else if (y in (cy + R * 0.42f)..(cy + R * 0.72f) && abs(x - cx) < R * 0.48f) {
                    if (lobbyRole != 2) startRace()
                } else if (y > cy + R * 0.73f) {
                    state = 0; vibeClick()
                }
            }

            3 -> {
                // 2. UI Değişiklikleri Koordinatları:
                // - DUR (Pause/Stop) Butonu: Harita balonunun hemen üstünde (Arabanın üstünü kapatmaz!)
                val stopBtnX = cx + R * 0.38f
                val stopBtnY = cy - R * 0.56f
                // - ÇIK Butonu: Dur butonunun sol yanında, üst kenarda
                val exitBtnX = cx + R * 0.08f
                val exitBtnY = cy - R * 0.62f
                // - TURBO İkonu: Sol kenarda (Altında aktif özellik simgesi yer alır)
                val turboBtnX = cx - R * 0.54f
                val turboBtnY = cy - R * 0.08f

                val distFromCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.12f) {
                            state = 0
                            vibeClick()
                            return true
                        }
                        // Harita balonunun üstündeki DUR / SÜR butonu
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.14f) {
                            isFullStopped = !isFullStopped
                            isHoldingStop = true
                            vibeClick()
                            return true
                        }
                        // 3.2 & 6.3 Turbo İkonuna veya Ekran Ortasına (BOOST_TAP_RADIUS) Dokunarak Turbo Ateşleme
                        if (boostReady && (hypot(x - turboBtnX, y - turboBtnY) < R * 0.16f || distFromCenter < R * 0.32f)) {
                            boostRequest = true
                            return true
                        }
                        // Dış Çemberde (Crown / Bezel Hattı: > 0.72R) Dairesel Kaydırma
                        if (distFromCenter > R * 0.72f) {
                            isBezelDragging = true
                            lastBezelAngle = atan2(y - cy, x - cx)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isBezelDragging && distFromCenter > R * 0.65f) {
                            val curAng = atan2(y - cy, x - cx)
                            val dAng = wrapAngle(curAng - lastBezelAngle)
                            lastBezelAngle = curAng
                            crownAccum = (crownAccum + dAng * 1.35f).coerceIn(-1.6f, 1.6f)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHoldingStop = false
                        isBezelDragging = false
                    }
                }
            }

            4 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy + R * 0.22f)..(cy + R * 0.55f) && abs(x - cx) < R * 0.50f) {
                    vibeClick(); startRace()
                } else if (y > cy + R * 0.56f) {
                    vibeClick(); state = 0
                }
            }
        }
        return true
    }

    private fun wrapAngle(a: Float): Float {
        var r = (a + PI.toFloat()) % (2f * PI.toFloat())
        if (r < 0) r += 2f * PI.toFloat()
        return r - PI.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.004f, 0.10f)
        lastFrameTime = now
        currentFps = (1f / dt).toInt()

        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f

        when (state) {
            0 -> drawMainMenu(canvas, cx, cy, R)
            1 -> drawGarage(canvas, cx, cy, R, dt)
            2 -> drawSettings(canvas, cx, cy, R)
            5 -> drawCoopLobby(canvas, cx, cy, R)
            3 -> {
                updateRaceLoop(dt)
                drawTrue3DWorld(canvas, cx, cy, R)
                drawGameHud(canvas, cx, cy, R)
            }
            4 -> {
                drawTrue3DWorld(canvas, cx, cy, R)
                drawWinScreen(canvas, cx, cy, R)
            }
        }

        if (showFps) {
            paint.color = Color.GREEN
            paint.textSize = R * 0.064f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.88f, paint)
        }

        // 5. Pil Optimizasyonu: Sadece aktif sahnelerde 60 FPS animasyon tetikle
        if (!isHostPaused) {
            if (state == 3 || state == 1) {
                postInvalidateOnAnimation()
            } else {
                postInvalidateDelayed(33L)
            }
        }
    }

    private fun updateRaceLoop(dt: Float) {
        // 3.1 Başlangıç 3 - 2 - 1 - GO Geri Sayımı
        if (countdownValue >= 0) {
            countdownTimer -= dt
            if (countdownTimer <= 0f) {
                countdownValue--
                countdownTimer = if (countdownValue == 0) 0.55f else 0.75f
                triggerHaptic(if (countdownValue == 0) 65L else 35L, 210)
            }
            applyInterpolatedPose(1f)
            updateCamera(dt)
            return
        }

        // Referans CrownDriftGame.render(): Sabit 60Hz Fizik Adımı + crownPerStep Bölünmesi
        physicsAccumulator = (physicsAccumulator + dt).coerceAtMost(0.15f)
        val steps = (physicsAccumulator / STEP).toInt()
        if (steps > 0) {
            val crownPerStep = crownAccum / steps
            crownAccum = 0f
            for (i in 0 until steps) {
                physicsAccumulator -= STEP
                physicsStep(crownPerStep)
                if (state != 3) break
            }
        }
        val alpha = (physicsAccumulator / STEP).coerceIn(0f, 1f)
        applyInterpolatedPose(alpha)
        updateCamera(dt)
    }

    // ==================== GERÇEK 3B PERSPEKTİF MOTORU (Dinamik FOV & Derinlik) ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - camX
        val dy = wy - camY

        val forward = dx * camCosYaw + dy * camSinYaw
        val right = -dx * camSinYaw + dy * camCosYaw

        val relF = forward + camDist
        val relH = wz - camZ
        val cosP = 0.682f
        val sinP = 0.731f

        val depth = relF * cosP - relH * sinP
        if (depth < 8f || depth > 1420f) return false
        val vert = relF * sinP + relH * cosP

        val fov = R * camFovScale
        projScale = fov / depth
        projX = cx + right * projScale
        projY = (cy + R * 0.16f) - vert * projScale
        return true
    }

    private fun drawQuad3D(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        color: Int
    ) {
        if (!project3D(x1, y1, z1, cx, cy, R)) return
        val sx1 = projX; val sy1 = projY
        if (!project3D(x2, y2, z2, cx, cy, R)) return
        val sx2 = projX; val sy2 = projY
        if (!project3D(x3, y3, z3, cx, cy, R)) return
        val sx3 = projX; val sy3 = projY
        if (!project3D(x4, y4, z4, cx, cy, R)) return
        val sx4 = projX; val sy4 = projY

        polyPath.reset()
        polyPath.moveTo(sx1, sy1)
        polyPath.lineTo(sx2, sy2)
        polyPath.lineTo(sx3, sy3)
        polyPath.lineTo(sx4, sy4)
        polyPath.close()

        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(polyPath, paint)
    }

    // 6.1 Gövde Yatması (rollDeg) Destekli 3B Kutu Çizimi
    private fun draw3DBox(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        bx: Float, by: Float, bz: Float,
        halfL: Float, halfW: Float, height: Float,
        yaw: Float, topColor: Int, sideColor: Int, rearColor: Int,
        rollDeg: Float = 0f
    ) {
        val c = cos(yaw)
        val s = sin(yaw)
        val fx = c * halfL; val fy = s * halfL
        val rx = -s * halfW; val ry = c * halfW

        val tiltZ = sin(Math.toRadians(rollDeg.toDouble())).toFloat() * halfW * 0.32f
        val leftBaseZ = bz + tiltZ
        val rightBaseZ = bz - tiltZ
        val leftTopZ = leftBaseZ + height
        val rightTopZ = rightBaseZ + height

        val flX = bx + fx - rx; val flY = by + fy - ry
        val frX = bx + fx + rx; val frY = by + fy + ry
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry

        drawQuad3D(canvas, cx, cy, R, flX, flY, leftBaseZ, rlX, rlY, leftBaseZ, rlX, rlY, leftTopZ, flX, flY, leftTopZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, rightBaseZ, rrX, rrY, rightBaseZ, rrX, rrY, rightTopZ, frX, frY, rightTopZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, leftBaseZ, frX, frY, rightBaseZ, frX, frY, rightTopZ, flX, flY, leftTopZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, leftBaseZ, rrX, rrY, rightBaseZ, rrX, rrY, rightTopZ, rlX, rlY, leftTopZ, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, leftTopZ, frX, frY, rightTopZ, rrX, rrY, rightTopZ, rlX, rlY, leftTopZ, topColor)
    }

    // ==================== 4 FARKLI 3B SPOR ARAÇ MODELİ (RollAngle + ±28° Ön Tekerlek + Ön Silah + Kalkan) ====================
    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false,
        carRollDeg: Float = 0f, frontSteerDeg: Float = 0f
    ) {
        val alpha = if (isGhost) 105 else 255
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val damageDarken = if (isPlayer) (1f - (carDamage / 210f)) else 1f
        val topCol = Color.argb(alpha, (r * damageDarken).toInt(), (g * damageDarken).toInt(), (b * damageDarken).toInt())
        val sideCol = Color.argb(alpha, (r * 0.75f * damageDarken).toInt(), (g * 0.75f * damageDarken).toInt(), (b * 0.75f * damageDarken).toInt())
        val rearCol = Color.argb(alpha, (r * 0.56f * damageDarken).toInt(), (g * 0.56f * damageDarken).toInt(), (b * 0.56f * damageDarken).toInt())
        val glassCol = Color.argb(alpha, 30, 48, 65)
        val wheelCol = Color.argb(alpha, 20, 22, 25)
        val chromeCol = Color.argb(alpha, 215, 220, 230)

        val c = cos(yaw)
        val s = sin(yaw)
        val rxVec = -s
        val ryVec = c

        // Araç Altı Yumuşak Gölge
        if (!isGhost) {
            val fx = c * 16.5f; val fy = s * 16.5f
            val rx = rxVec * 10.5f; val ry = ryVec * 10.5f
            drawQuad3D(
                canvas, cx, cy, R,
                x + fx - rx, y + fy - ry, 0.2f,
                x + fx + rx, y + fy + ry, 0.2f,
                x - fx + rx, y - fy + ry, 0.2f,
                x - fx - rx, y - fy - ry, 0.2f,
                Color.argb(135, 0, 0, 0)
            )
        }

        val bz = zOffset + 1.4f

        // 4 Tekerlek (Ön tekerlekler Car3D.applyInterpolated gibi ±28° döner)
        if (!isGhost) {
            val wxF = 9.8f; val wxR = -9.8f
            val wyOff = if (modelIdx == 3) 10.4f else 9.4f
            val wWidth = if (modelIdx == 3) 2.2f else 1.7f
            val steerRad = Math.toRadians(frontSteerDeg.toDouble()).toFloat()
            draw3DBox(canvas, cx, cy, R, x + c * wxF - rxVec * wyOff, y + s * wxF - ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + steerRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxF + rxVec * wyOff, y + s * wxF + ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + steerRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR - rxVec * wyOff, y + s * wxR - ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR + rxVec * wyOff, y + s * wxR + ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
        }

        when (modelIdx % 4) {
            0 -> {
                val bodyLen = if (isPlayer && bumperBroken) 13.5f else 15.8f
                draw3DBox(canvas, cx, cy, R, x, y, bz, bodyLen, 8.8f, 4.3f, yaw, topCol, sideCol, rearCol, carRollDeg)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 11.2f, y + s * 11.2f, bz + 0.4f, 4.8f, 8.2f, 2.8f, yaw, topCol, sideCol, wheelCol, carRollDeg)
                }
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz + 4.3f, 7.6f, 6.8f, 3.7f, yaw, topCol, glassCol, glassCol, carRollDeg)
                if (!(isPlayer && spoilerBroken)) {
                    val spX = x - c * 14.0f; val spY = y - s * 14.0f
                    draw3DBox(canvas, cx, cy, R, spX, spY, bz + 6.4f, 2.0f, 9.4f, 1.1f, yaw, wheelCol, sideCol, rearCol, carRollDeg)
                }
            }

            1 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.0f else 16.8f
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz, bodyLen * 0.85f, 9.8f, 3.4f, yaw, topCol, sideCol, rearCol, carRollDeg)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 12.0f, y + s * 12.0f, bz, 5.5f, 8.8f, 2.1f, yaw, topCol, sideCol, wheelCol, carRollDeg)
                }
                draw3DBox(canvas, cx, cy, R, x + c * 1.0f, y + s * 1.0f, bz + 3.4f, 8.2f, 6.4f, 2.9f, yaw, topCol, glassCol, rearCol, carRollDeg)
                draw3DBox(canvas, cx, cy, R, x - c * 4.5f, y - s * 4.5f, bz + 2.2f, 5.0f, 10.2f, 1.8f, yaw, sideCol, wheelCol, rearCol, carRollDeg)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, yaw, rearCol, sideCol, wheelCol, carRollDeg)
                }
            }

            2 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.2f else 16.5f
                draw3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, bodyLen, 9.0f, 4.6f, yaw, topCol, sideCol, rearCol, carRollDeg)
                draw3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, yaw, chromeCol, wheelCol, chromeCol, carRollDeg)
                draw3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, yaw, topCol, glassCol, glassCol, carRollDeg)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 15.2f, y - s * 15.2f, bz + 5.0f, 1.4f, 8.8f, 1.8f, yaw, rearCol, sideCol, rearCol, carRollDeg)
                }
            }

            else -> {
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, yaw, topCol, sideCol, rearCol, carRollDeg)
                draw3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, yaw, topCol, sideCol, rearCol, carRollDeg)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 16.2f, y + s * 16.2f, bz - 0.4f, 2.2f, 10.6f, 1.1f, yaw, chromeCol, topCol, sideCol, carRollDeg)
                }
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, yaw, glassCol, glassCol, rearCol, carRollDeg)
                draw3DBox(canvas, cx, cy, R, x - c * 8.0f, y - s * 8.0f, bz + 3.2f, 5.0f, 0.8f, 3.6f, yaw, topCol, sideCol, rearCol, carRollDeg)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, yaw, chromeCol, topCol, rearCol, carRollDeg)
                }
            }
        }

        // 3.3 Araç Önü Silahı Aktifse Kaput Üstünde İkiz Namlu Çiz
        if (isPlayer && activePower == POWER_FRONT_GUN) {
            val gunCol = Color.rgb(255, 195, 35)
            draw3DBox(canvas, cx, cy, R, x + c * 12.5f - rxVec * 5.2f, y + s * 12.5f - ryVec * 5.2f, bz + 4.4f, 5.2f, 1.1f, 1.2f, yaw, gunCol, chromeCol, wheelCol, carRollDeg)
            draw3DBox(canvas, cx, cy, R, x + c * 12.5f + rxVec * 5.2f, y + s * 12.5f + ryVec * 5.2f, bz + 4.4f, 5.2f, 1.1f, 1.2f, yaw, gunCol, chromeCol, wheelCol, carRollDeg)
        }

        // Arka Stop Lambaları
        if (!isGhost) {
            val tailX = x - c * 15.5f
            val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.rgb(255, 220, 40) else Color.rgb(255, 30, 30)
            draw3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol, carRollDeg)
            draw3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol, carRollDeg)
        }

        // 3.3 Kalkan veya Yeniden Doğma Koruması Halkası
        if (isPlayer && (activePower == POWER_SHIELD || respawnShieldTimer > 0f)) {
            if (project3D(x, y, bz + 4f, cx, cy, R)) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f
                paint.color = if (activePower == POWER_SHIELD) Color.argb(210, 0, 235, 255) else Color.argb(190, 90, 255, 140)
                canvas.drawCircle(projX, projY, (22f * projScale).coerceAtLeast(12f), paint)
            }
        }
    }

    // ==================== 3B DÜNYA ÇİZİMİ ====================
    private fun drawTrue3DWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val theme = tracks[currentTrackIdx].theme
        val bgColor = when (theme) {
            0 -> Color.rgb(34, 78, 24)
            1 -> Color.rgb(12, 10, 28)
            2 -> Color.rgb(18, 22, 32)
            3 -> Color.rgb(175, 210, 230)
            else -> Color.rgb(95, 55, 30)
        }
        canvas.drawColor(bgColor)

        // Zemin Izgarası
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 65f
        val baseGX = ((renderX / gridStep).toInt()) * gridStep
        val baseGY = ((renderY / gridStep).toInt()) * gridStep
        for (i in -5..5) {
            val gx = baseGX + i * gridStep
            for (j in -5..4) {
                val gy1 = baseGY + j * gridStep
                val gy2 = gy1 + gridStep
                if (project3D(gx, gy1, 0f, cx, cy, R)) {
                    val x1 = projX; val y1 = projY
                    if (project3D(gx, gy2, 0f, cx, cy, R)) {
                        canvas.drawLine(x1, y1, projX, projY, paint)
                    }
                }
            }
        }

        // Yol ve Drift Zone Bordürleri (Track3D: Drift Zone olan virajlarda Sarı-Siyah bordür!)
        val total = trackPts.size
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(70, 74, 78)

        for (step in 46 downTo -24) {
            val i0 = (currentWp + step + total) % total
            val i1 = (i0 + 1) % total
            val p0 = trackPts[i0]
            val p1 = trackPts[i1]
            val n0 = trackNormals[i0]
            val n1 = trackNormals[i1]

            val l0x = p0.x - n0.x * roadHalfWidth; val l0y = p0.y - n0.y * roadHalfWidth
            val r0x = p0.x + n0.x * roadHalfWidth; val r0y = p0.y + n0.y * roadHalfWidth
            val l1x = p1.x - n1.x * roadHalfWidth; val l1y = p1.y - n1.y * roadHalfWidth
            val r1x = p1.x + n1.x * roadHalfWidth; val r1y = p1.y + n1.y * roadHalfWidth

            val kl0x = l0x - n0.x * kerbWidth; val kl0y = l0y - n0.y * kerbWidth
            val kl1x = l1x - n1.x * kerbWidth; val kl1y = l1y - n1.y * kerbWidth
            val kr0x = r0x + n0.x * kerbWidth; val kr0y = r0y + n0.y * kerbWidth
            val kr1x = r1x + n1.x * kerbWidth; val kr1y = r1y + n1.y * kerbWidth

            val inDriftZone = zoneMask.isNotEmpty() && zoneMask[i0]
            val kerbColor = if (inDriftZone) {
                if (i0 % 2 == 0) Color.rgb(255, 193, 7) else Color.rgb(22, 22, 26)
            } else {
                if (i0 % 2 == 0) Color.rgb(225, 38, 38) else Color.WHITE
            }

            drawQuad3D(canvas, cx, cy, R, kl0x, kl0y, 0f, l0x, l0y, 0f, l1x, l1y, 0f, kl1x, kl1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, r0x, r0y, 0f, kr0x, kr0y, 0f, kr1x, kr1y, 0f, r1x, r1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0f, r0x, r0y, 0f, r1x, r1y, 0f, l1x, l1y, 0f, roadCol)

            if (i0 == 0) {
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0.3f, r0x, r0y, 0.3f, r1x, r1y, 0.3f, l1x, l1y, 0.3f, Color.WHITE)
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 34f, r0x, r0y, 34f, r1x, r1y, 40f, l1x, l1y, 40f, Color.rgb(210, 40, 40))
            }
        }

        // 6.4 Dairesel Tamponlu SkidTrail (Zamanla Solan 3B Lastik İzleri)
        for (i in 0 until skidCount) {
            val segIdx = (skidHead - 1 - i + MAX_SKID_SEGS) % MAX_SKID_SEGS
            val seg = skidSegs[segIdx]
            if (!seg.active) continue
            val t = i.toFloat() / MAX_SKID_SEGS.toFloat()
            val fade = (1f - t * t) * 0.62f
            val alpha = (((seg.strength0 + seg.strength1) * 0.5f) * fade * 255f).toInt().coerceIn(0, 210)
            if (alpha < 10) continue
            val col = if (seg.isGrass) {
                Color.argb(alpha, 68, 46, 24)
            } else {
                Color.argb(alpha, 18, 18, 20)
            }
            drawQuad3D(
                canvas, cx, cy, R,
                seg.ax, seg.ay, 0.22f,
                seg.bx, seg.by, 0.22f,
                seg.cx, seg.cy, 0.22f,
                seg.dx, seg.dy, 0.22f,
                col
            )
        }

        // Yola Bırakılan Dikenler (RoadSpikes)
        for (sp in roadSpikes) {
            if (!sp.active) continue
            draw3DBox(
                canvas, cx, cy, R,
                sp.x, sp.y, 0.3f,
                5.5f, 11f, 3.2f, sp.yaw,
                Color.rgb(255, 50, 50), Color.rgb(190, 25, 25), Color.DKGRAY
            )
        }

        // 3.3 Yoldaki Sürpriz Kutuları
        for (box in surpriseBoxes) {
            if (!box.active) continue
            val dx = box.x - renderX
            val dy = box.y - renderY
            if (dx * dx + dy * dy < 340000f) {
                draw3DBox(
                    canvas, cx, cy, R,
                    box.x, box.y, 3.5f,
                    6.2f, 6.2f, 10.5f, box.spinYaw,
                    Color.rgb(255, 215, 0), Color.rgb(0, 220, 255), Color.rgb(235, 60, 215)
                )
            }
        }

        // 3.4 Dinamik Yol Engelleri (Sabit yeri olmayan engeller)
        for (hz in dynamicHazards) {
            if (!hz.active) continue
            val dx = hz.x - renderX
            val dy = hz.y - renderY
            if (dx * dx + dy * dy < 340000f) {
                when (hz.kind) {
                    0 -> draw3DBox(canvas, cx, cy, R, hz.x, hz.y, 0f, 5.5f, 11.5f, hz.height, hz.yaw, Color.rgb(255, 125, 20), Color.WHITE, Color.rgb(210, 85, 15))
                    1 -> draw3DBox(canvas, cx, cy, R, hz.x, hz.y, 0f, 8.5f, 8.5f, hz.height, hz.yaw, Color.rgb(125, 130, 140), Color.rgb(90, 95, 105), Color.rgb(65, 70, 78))
                    else -> draw3DBox(canvas, cx, cy, R, hz.x, hz.y, 0f, 6.5f, 6.5f, hz.height, hz.yaw, Color.rgb(220, 40, 40), Color.rgb(245, 195, 25), Color.rgb(160, 25, 25))
                }
            }
        }

        // Sabit Ağaçlar, Binalar ve Yol Kenarı Bariyerleri
        for (obs in obstacles) {
            val dx = obs.x - renderX
            val dy = obs.y - renderY
            if (dx * dx + dy * dy < 350000f) {
                when (obs.type) {
                    0 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
                    1 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(45, 50, 70), Color.rgb(28, 32, 48), Color.rgb(22, 25, 38))
                    else -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, 10f, 4.5f, obs.height, obs.yaw, Color.rgb(245, 95, 20), Color.WHITE, Color.rgb(190, 50, 15))
                }
            }
        }

        // Aktif Füzeler ve Mermiler
        for (pr in projectiles) {
            if (!pr.active) continue
            if (pr.isMissile) {
                draw3DBox(canvas, cx, cy, R, pr.x, pr.y, pr.z, 7.5f, 2.4f, 2.6f, pr.yaw, Color.rgb(255, 70, 40), Color.WHITE, Color.rgb(255, 200, 30))
            } else {
                draw3DBox(canvas, cx, cy, R, pr.x, pr.y, pr.z, 4.8f, 1.3f, 1.3f, pr.yaw, Color.rgb(255, 235, 60), Color.rgb(255, 180, 20), Color.WHITE)
            }
        }

        // Kırık Parçalar
        for (d in debrisPool) {
            if (!d.active) continue
            draw3DBox(canvas, cx, cy, R, d.x, d.y, d.z, d.size, d.size * 0.7f, d.size * 0.5f, d.yaw, d.color, d.color, Color.DKGRAY)
        }

        // Hayalet Araç
        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val gPose = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            drawSport3DCar(canvas, cx, cy, R, gPose.x, gPose.y, 0f, gPose.yaw, Color.rgb(0, 225, 255), selectedModel, isGhost = true, isPlayer = false, carRollDeg = gPose.roll)
        }

        // Co-op Diğer Oyuncular
        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) {
                drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
                if (project3D(rp.x, rp.y, 16f, cx, cy, R)) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(0, 255, 220)
                    paint.textSize = R * 0.056f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("P${i + 2}", projX, projY, paint)
                }
            }
        }

        // Yapay Zeka Bot Araçları (RollAngle + Ön Tekerlek Dönüşü ile)
        if (botsEnabled) {
            for (bot in bots) {
                val botWheelDeg = (bot.smoothedSteering * 120f).coerceIn(-24f, 24f)
                drawSport3DCar(
                    canvas, cx, cy, R,
                    bot.x, bot.y, bot.z, bot.yaw,
                    bot.color, bot.modelIdx,
                    isGhost = false, isPlayer = false,
                    carRollDeg = bot.rollAngle, frontSteerDeg = botWheelDeg
                )
            }
        }

        // Oyuncunun 3B Spor Aracı (Gövde Yatması rollAngle + ±28° Ön Tekerlek Dönüşü ile)
        drawSport3DCar(
            canvas, cx, cy, R,
            renderX, renderY, carZ, renderYaw,
            carColors[selectedColor], selectedModel,
            isGhost = false, isPlayer = true,
            carRollDeg = renderRoll, frontSteerDeg = frontWheelAngleDeg
        )

        // 6.4 32 Parçacıklı Duman/Toz Havuzu Çizimi
        paint.style = Paint.Style.FILL
        for (s in smokePool) {
            if (s.life <= 0f) continue
            if (project3D(s.x, s.y, s.z, cx, cy, R)) {
                val k = (s.life / s.lifeTotal).coerceIn(0f, 1f)
                val size = s.startSize + (1f - k) * 9.5f
                val fadeIn = ((s.lifeTotal - s.life) / 0.16f).coerceIn(0f, 1f)
                val alpha = (fadeIn * 0.48f * k * 255f).toInt().coerceIn(0, 185)
                paint.color = Color.argb(alpha, s.r, s.g, s.b)
                canvas.drawCircle(projX, projY, (size * projScale).coerceAtLeast(1.8f), paint)
            }
        }

        // Patlama Alevi ve Kıvılcım Parçacıkları
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== 2. YENİ OYUN İÇİ ARAYÜZ (HUD) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val totalRacers = if (botsEnabled) 1 + bots.size else 1
        val posKey = racePosition * 10 + totalRacers
        if (posKey != cachedPosKey) {
            cachedPosKey = posKey
            cachedPosStr = "$racePosition/$totalRacers"
        }
        val lapDisp = completedLaps.coerceAtMost(totalLaps - 1) + 1
        if (lapDisp != cachedLapKey) {
            cachedLapKey = lapDisp
            cachedLapStr = "L$lapDisp/$totalLaps"
        }
        val scInt = score.toInt()
        if (scInt != cachedScoreInt) {
            cachedScoreInt = scInt
            cachedScoreStr = scInt.toString()
        }

        // 1. Sol Üst: Kompakt Sıralama Göstergesi ("2/4") + Küçük Tur/Puan/Kombo Rozeti (Büyük balon kaldırıldı!)
        tempRect.set(cx - R * 0.62f, cy - R * 0.62f, cx - R * 0.18f, cy - R * 0.36f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(175, 12, 18, 26)
        canvas.drawRoundRect(tempRect, R * 0.06f, R * 0.06f, paint)

        // Sıralama ("1/4", "2/4" vb.)
        paint.textAlign = Paint.Align.LEFT
        paint.color = if (racePosition == 1) Color.rgb(255, 215, 0) else Color.WHITE
        paint.textSize = R * 0.105f
        canvas.drawText(cachedPosStr, tempRect.left + R * 0.04f, cy - R * 0.48f, paint)

        // Yanında Tur ("L1/3")
        paint.color = Color.rgb(185, 200, 215)
        paint.textSize = R * 0.052f
        canvas.drawText(cachedLapStr, tempRect.left + R * 0.25f, cy - R * 0.51f, paint)

        // Alt satırda kompakt skor ve kombo çarpanı (x2, x3, x4)
        val inZoneDrift = isCurrentlyDrifting && zoneMask.isNotEmpty() && zoneMask[currentWp]
        paint.color = when {
            inZoneDrift -> Color.rgb(255, 215, 0)
            isCurrentlyDrifting -> Color.rgb(0, 225, 255)
            else -> Color.rgb(220, 230, 240)
        }
        paint.textSize = R * 0.062f
        canvas.drawText(cachedScoreStr, tempRect.left + R * 0.04f, cy - R * 0.39f, paint)

        if (multiplier > 1) {
            paint.color = Color.rgb(255, 65, 145)
            paint.textSize = R * 0.056f
            canvas.drawText(multLabels[multiplier.coerceIn(1, 4)], tempRect.right - R * 0.11f, cy - R * 0.39f, paint)
        }

        // 2. Sağ Kenar: Mini Harita Balonu ve Hemen Üstünde DUR (Pause) + ÇIK Butonları
        val mapCX = cx + R * 0.42f
        val mapCY = cy - R * 0.24f
        val mapRad = R * 0.175f

        // Harita Balonunun Hemen Üstünde DUR / SÜR Butonu (Arabanın üstünü asla kapatmaz!)
        val stopBtnX = cx + R * 0.38f
        val stopBtnY = cy - R * 0.56f
        paint.style = Paint.Style.FILL
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(205, 215, 50, 50)
        canvas.drawCircle(stopBtnX, stopBtnY, R * 0.105f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.055f
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.020f, paint)

        // ÇIK Butonu (Dur butonunun sol yanında, üst kenarda)
        val exitBtnX = cx + R * 0.08f
        val exitBtnY = cy - R * 0.62f
        paint.color = Color.argb(190, 55, 65, 80)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.090f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.048f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.018f, paint)

        // Mini Harita Çizimi
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(175, 12, 18, 24)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(120, 0, 225, 255)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)

        val miniScale = mapRad / 1850f
        miniPath.reset()
        for (i in trackPts.indices step 3) {
            val mx = mapCX + trackPts[i].x * miniScale
            val my = mapCY + trackPts[i].y * miniScale
            if (i == 0) miniPath.moveTo(mx, my) else miniPath.lineTo(mx, my)
        }
        miniPath.close()
        paint.strokeWidth = 2.4f
        paint.color = Color.rgb(120, 135, 150)
        canvas.drawPath(miniPath, paint)

        paint.style = Paint.Style.FILL
        if (botsEnabled) {
            for (bot in bots) {
                paint.color = bot.color
                canvas.drawCircle(mapCX + bot.x * miniScale, mapCY + bot.y * miniScale, R * 0.017f, paint)
            }
        }
        for (rp in remotePlayers) {
            if (rp.active) {
                paint.color = Color.rgb(0, 255, 220)
                canvas.drawCircle(mapCX + rp.x * miniScale, mapCY + rp.y * miniScale, R * 0.022f, paint)
            }
        }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + renderX * miniScale, mapCY + renderY * miniScale, R * 0.024f, paint)

        // 3. Sol Kenarda Küçük TURBO İkonu ve Dolum Halkası
        val turboX = cx - R * 0.54f
        val turboY = cy - R * 0.08f
        val turboRad = R * 0.115f
        val fillRatio = if (boostTime > 0f) (boostTime / BOOST_SECONDS).coerceIn(0f, 1f) else if (boostReady) 1f else boostMeter

        paint.style = Paint.Style.FILL
        paint.color = when {
            boostTime > 0f -> Color.argb(230, 0, 200, 255)
            boostReady -> Color.argb(225, 255, 185, 20)
            else -> Color.argb(180, 18, 26, 36)
        }
        canvas.drawCircle(turboX, turboY, turboRad, paint)

        // Turbo Dolum Yayı
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = R * 0.022f
        paint.color = if (boostReady || boostTime > 0f) Color.rgb(0, 245, 255) else Color.rgb(0, 165, 210)
        tempRect.set(turboX - turboRad, turboY - turboRad, turboX + turboRad, turboY + turboRad)
        canvas.drawArc(tempRect, -90f, fillRatio * 360f, false, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (boostReady || boostTime > 0f) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.046f
        canvas.drawText(if (boostReady) "HAZIR" else "TURBO", turboX, turboY + R * 0.016f, paint)

        // 4. Turbo Butonunun Hemen Altında Aktif Sürpriz Kutu Özelliği Simgesi
        if (activePower != POWER_NONE && powerTimer > 0f) {
            val pwX = turboX
            val pwY = turboY + R * 0.24f
            val pwRad = R * 0.095f
            val (pwLabel, pwCol) = when (activePower) {
                POWER_MISSILE -> "FÜZE" to Color.rgb(255, 75, 55)
                POWER_SPIKE -> "DİKEN" to Color.rgb(255, 165, 30)
                POWER_SHIELD -> "KALKAN" to Color.rgb(0, 225, 255)
                POWER_FRONT_GUN -> "SİLAH" to Color.rgb(255, 215, 25)
                else -> "YAVAŞ" to Color.rgb(185, 50, 220)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(200, 16, 22, 30)
            canvas.drawCircle(pwX, pwY, pwRad, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = R * 0.018f
            paint.color = pwCol
            tempRect.set(pwX - pwRad, pwY - pwRad, pwX + pwRad, pwY + pwRad)
            val pwRatio = (powerTimer / powerMaxTimer).coerceIn(0f, 1f)
            canvas.drawArc(tempRect, -90f, pwRatio * 360f, false, paint)

            paint.style = Paint.Style.FILL
            paint.color = pwCol
            paint.textSize = R * 0.042f
            canvas.drawText(pwLabel, pwX, pwY + R * 0.015f, paint)
        }

        // 6.3 Drift Zone / Edge Bonus / Boost Durum Rozeti ve Ortada Turbo İpucu
        if (boostReady && boostHintTime > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = Color.argb(170, 0, 225, 255)
            canvas.drawCircle(cx, cy, R * 0.26f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(0, 240, 255)
            paint.textSize = R * 0.052f
            canvas.drawText("DOKUN: TURBO!", cx, cy - R * 0.29f, paint)
        } else {
            val badgeText = when {
                boostTime > 0f -> "TURBO x2"
                inZoneDrift -> "DRIFT ZONE x2"
                edgeActive -> "KENAR BONUSU x1.5"
                else -> ""
            }
            if (badgeText.isNotEmpty()) {
                paint.style = Paint.Style.FILL
                paint.color = when {
                    boostTime > 0f -> Color.rgb(0, 225, 255)
                    inZoneDrift -> Color.rgb(255, 215, 0)
                    else -> Color.rgb(255, 80, 160)
                }
                paint.textSize = R * 0.052f
                canvas.drawText(badgeText, cx, cy - R * 0.68f, paint)
            }
        }

        // Tur Bitiş Bildirimi
        if (lapFlashTimer > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = if (lapWasBest) Color.rgb(75, 250, 140) else Color.WHITE
            paint.textSize = R * 0.058f
            val lapMsg = (if (lapWasBest) "EN İYİ TUR " else "TUR ") + String.format("%.2fs", lastLapTime)
            canvas.drawText(lapMsg, cx, cy - R * 0.22f, paint)
        }

        // Sürpriz Kutusu ve Oyun Bildirimleri
        if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55)
            paint.textSize = R * 0.078f
            val secStr = String.format("%.1fs", crashTimer.coerceAtLeast(0f))
            canvas.drawText("PATLADI! DOĞUŞ: $secStr", cx, cy - R * 0.08f, paint)
        } else if (respawnBannerTimer > 0f) {
            paint.color = Color.rgb(80, 255, 140)
            paint.textSize = R * 0.068f
            canvas.drawText("YOLDA GÜVENLİ DOĞDU!", cx, cy - R * 0.08f, paint)
        } else if (statusToastTimer > 0f && statusToastText.isNotEmpty()) {
            paint.color = Color.rgb(255, 220, 45)
            paint.textSize = R * 0.058f
            canvas.drawText(statusToastText, cx, cy - R * 0.12f, paint)
        } else if (onGrass) {
            paint.color = Color.rgb(255, 205, 40)
            paint.textSize = R * 0.058f
            canvas.drawText("ÇİM ALAN (%40 YAVAŞ)", cx, cy - R * 0.12f, paint)
        }

        // 3.1 Turnuva Başlangıcı: 3 - 2 - 1 - GO Geri Sayım Paneli (CrownDriftGame.drawHud)
        if (countdownValue >= 0) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(145, 8, 12, 18)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            // Trafik Işıkları Kutusu
            tempRect.set(cx - R * 0.44f, cy - R * 0.22f, cx - R * 0.28f, cy + R * 0.22f)
            paint.color = Color.rgb(22, 28, 38)
            canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)

            paint.color = if (countdownValue == 3) Color.rgb(255, 45, 45) else Color.argb(70, 255, 45, 45)
            canvas.drawCircle(tempRect.centerX(), cy - R * 0.12f, R * 0.048f, paint)
            paint.color = if (countdownValue == 2) Color.rgb(255, 215, 25) else Color.argb(70, 255, 215, 25)
            canvas.drawCircle(tempRect.centerX(), cy, R * 0.048f, paint)
            paint.color = if (countdownValue <= 1) Color.rgb(55, 235, 100) else Color.argb(70, 55, 235, 100)
            canvas.drawCircle(tempRect.centerX(), cy + R * 0.12f, R * 0.048f, paint)

            // Büyük Sayı / GO Dairesi
            val ringX = cx + R * 0.14f
            paint.color = Color.rgb(22, 28, 38)
            canvas.drawCircle(ringX, cy, R * 0.23f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = R * 0.025f
            paint.color = if (countdownValue > 0) Color.rgb(0, 225, 255) else Color.rgb(55, 235, 100)
            canvas.drawCircle(ringX, cy, R * 0.23f, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (countdownValue > 0) Color.WHITE else Color.rgb(55, 235, 100)
            paint.textSize = if (countdownValue > 0) R * 0.24f else R * 0.15f
            val countTxt = if (countdownValue > 0) "$countdownValue" else "BAŞLA!"
            canvas.drawText(countTxt, ringX, cy + R * 0.075f, paint)
        }

        // Alt Kenar 21 Noktalı Yay Göstergesi (Direksiyon & Boost Kombine)
        val totalDots = 21
        val centerDot = totalDots / 2
        val activeDotOffset = (steerInput * centerDot).toInt().coerceIn(-centerDot, centerDot)
        for (i in 0 until totalDots) {
            val offset = i - centerDot
            val deg = 90f - offset * 3.1f
            val rad = Math.toRadians(deg.toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f)
            val dy = cy + sin(rad).toFloat() * (R * 0.88f)

            val isLit = (activeDotOffset >= 0 && offset in 0..activeDotOffset) ||
                        (activeDotOffset < 0 && offset in activeDotOffset..0) || offset == 0
            paint.style = Paint.Style.FILL
            paint.color = if (isLit) Color.rgb(0, 225, 255) else Color.argb(135, 70, 80, 90)
            canvas.drawCircle(dx, dy, R * 0.017f, paint)
        }
    }

    // ==================== CO-OP ODA, ANA MENÜ, GARAJ, AYARLAR VE SONUÇ EKRANI ====================
    private fun drawCoopLobby(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(0, 225, 255)
        paint.textSize = R * 0.105f
        canvas.drawText("CO-OP ODA (EŞLEŞMESİZ)", cx, cy - R * 0.56f, paint)

        tempRect.set(cx - R * 0.68f, cy - R * 0.42f, cx - R * 0.04f, cy - R * 0.16f)
        paint.color = if (lobbyRole == 1) Color.rgb(0, 225, 255) else Color.rgb(26, 34, 44)
        canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = if (lobbyRole == 1) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.070f
        canvas.drawText("ODA KUR", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        tempRect.set(cx + R * 0.04f, cy - R * 0.42f, cx + R * 0.68f, cy - R * 0.16f)
        paint.color = if (lobbyRole == 2) Color.rgb(0, 225, 255) else Color.rgb(26, 34, 44)
        canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = if (lobbyRole == 2) Color.BLACK else Color.WHITE
        canvas.drawText("TARA & KATIL", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        paint.color = Color.rgb(255, 205, 40)
        paint.textSize = R * 0.064f
        canvas.drawText(lobbyStatusText, cx, cy - R * 0.04f, paint)

        for (i in 0..3) {
            val sy = cy + R * (0.07f + i * 0.09f)
            val isOccupied = if (i == 0) true else remotePlayers[i - 1].active
            val mName = if (i == 0) carModels[myAssignedModel] else carModels[remotePlayers[i - 1].modelIdx]
            val slotName = if (i == 0) "1. SEN [$mName]"
                           else "${i + 1}. ${if (isOccupied) "${remotePlayers[i - 1].name} [$mName]" else "Araniyor..."}"
            paint.color = if (isOccupied) Color.rgb(75, 245, 130) else Color.rgb(110, 120, 135)
            paint.textSize = R * 0.060f
            canvas.drawText(slotName, cx, sy, paint)
        }

        tempRect.set(cx - R * 0.46f, cy + R * 0.44f, cx + R * 0.46f, cy + R * 0.68f)
        paint.color = if (lobbyRole == 2) Color.rgb(45, 55, 65) else Color.rgb(0, 225, 255)
        canvas.drawRoundRect(tempRect, R * 0.12f, R * 0.12f, paint)
        paint.color = if (lobbyRole == 2) Color.LTGRAY else Color.BLACK
        paint.textSize = R * 0.085f
        canvas.drawText(if (lobbyRole == 2) "HOST BEKLENİYOR" else "BAŞLAT (START)", cx, tempRect.centerY() + R * 0.03f, paint)

        paint.color = Color.rgb(160, 175, 190)
        paint.textSize = R * 0.075f
        canvas.drawText("GERİ", cx, cy + R * 0.82f, paint)
    }

    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))

        arcPath.reset()
        tempRect.set(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f)
        arcPath.addArc(tempRect, 222f, 96f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(190, 200, 210)
        paint.textSize = R * 0.095f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.letterSpacing = 0.18f
        canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint)
        paint.letterSpacing = 0f

        val mapScale = R * 0.00016f
        miniPath.reset()
        val total = trackPts.size
        for (i in 0 until total) {
            val px = cx + trackPts[i].x * mapScale
            val py = cy - R * 0.16f + trackPts[i].y * mapScale
            if (i == 0) miniPath.moveTo(px, py) else miniPath.lineTo(px, py)
        }
        miniPath.close()

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = R * 0.042f
        paint.color = if (tracks[currentTrackIdx].theme == 1) Color.rgb(0, 225, 255) else Color.WHITE
        canvas.drawPath(miniPath, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(150, 160, 175)
        paint.textSize = R * 0.14f
        canvas.drawText("<", cx - R * 0.68f, cy - R * 0.10f, paint)
        canvas.drawText(">", cx + R * 0.68f, cy - R * 0.10f, paint)

        paint.color = Color.WHITE
        paint.textSize = R * 0.13f
        canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.22f, paint)

        tempRect.set(cx - R * 0.34f, cy + R * 0.32f, cx + R * 0.34f, cy + R * 0.48f)
        paint.color = Color.rgb(25, 42, 55)
        canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = Color.rgb(0, 240, 220)
        paint.textSize = R * 0.072f
        canvas.drawText("CO-OP ODA (4P)", cx, tempRect.centerY() + R * 0.025f, paint)

        val gx = cx - R * 0.60f
        val gy = cy + R * 0.32f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(gx, gy, R * 0.14f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(gx - R * 0.055f, gy - R * 0.04f, gx + R * 0.055f, gy + R * 0.04f, 6f, 6f, paint)

        val sx = cx + R * 0.60f
        val sy = cy + R * 0.32f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(sx, sy, R * 0.14f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.12f
        canvas.drawText("⚙", sx, sy + R * 0.04f, paint)

        paint.color = Color.rgb(0, 225, 255)
        tempRect.set(cx - R * 0.46f, cy + R * 0.54f, cx + R * 0.46f, cy + R * 0.79f)
        canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.125f
        canvas.drawText("START", cx, cy + R * 0.71f, paint)
    }

    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float, dt: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.14f
        canvas.drawText("Garage", cx, cy - R * 0.58f, paint)

        garagePreviewYaw += dt * 1.1f
        val savedCamX = camX; val savedCamY = camY; val savedCamZ = camZ
        val savedDist = camDist; val savedCos = camCosYaw; val savedSin = camSinYaw
        camX = 0f; camY = 0f; camZ = 138f; camDist = 98f; camCosYaw = 1f; camSinYaw = 0f
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = false)
        camX = savedCamX; camY = savedCamY; camZ = savedCamZ
        camDist = savedDist; camCosYaw = savedCos; camSinYaw = savedSin

        tempRect.set(cx - R * 0.56f, cy - R * 0.07f, cx - R * 0.04f, cy + R * 0.11f)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.BLACK
        paint.textSize = R * 0.074f
        canvas.drawText(carModels[selectedModel], tempRect.centerX(), tempRect.centerY() + R * 0.028f, paint)

        tempRect.set(cx + R * 0.04f, cy - R * 0.07f, cx + R * 0.56f, cy + R * 0.11f)
        paint.color = Color.rgb(24, 30, 38)
        canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawText(carModels[(selectedModel + 1) % carModels.size], tempRect.centerX(), tempRect.centerY() + R * 0.028f, paint)

        for (i in 0 until 8) {
            val row = i / 4
            val col = i % 4
            val dotX = cx + (col - 1.5f) * (R * 0.28f)
            val dotY = cy + R * 0.27f + row * (R * 0.24f)
            if (i == selectedColor) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = R * 0.025f
                paint.color = Color.rgb(0, 225, 255)
                canvas.drawCircle(dotX, dotY, R * 0.105f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = carColors[i]
            canvas.drawCircle(dotX, dotY, R * 0.082f, paint)
        }

        paint.color = Color.rgb(150, 165, 180)
        paint.textSize = R * 0.09f
        canvas.drawText("BACK", cx, cy + R * 0.78f, paint)
    }

    private fun drawSettings(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        val sensNames = arrayOf("LOW", "MEDIUM", "HIGH")

        drawSettingRow(canvas, cx, cy - R * 0.52f, R, 0.64f, "SENS", sensNames[sensLevel], false)
        drawSettingRow(canvas, cx, cy - R * 0.24f, R, 0.76f, "CO-OP ODA", "AC", false)
        drawSettingRow(canvas, cx, cy + R * 0.04f, R, 0.76f, "GHOST", "", ghostEnabled)
        drawSettingRow(canvas, cx, cy + R * 0.32f, R, 0.68f, "AI BOTS", "", botsEnabled)

        tempRect.set(cx - R * 0.48f, cy + R * 0.50f, cx - R * 0.03f, cy + R * 0.70f)
        paint.color = Color.rgb(22, 28, 36)
        canvas.drawRoundRect(tempRect, R * 0.1f, R * 0.1f, paint)
        paint.color = if (showFps) Color.rgb(0, 225, 255) else Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.075f
        canvas.drawText("SHOW FPS", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        tempRect.set(cx + R * 0.03f, cy + R * 0.50f, cx + R * 0.48f, cy + R * 0.70f)
        paint.color = Color.rgb(22, 28, 36)
        canvas.drawRoundRect(tempRect, R * 0.1f, R * 0.1f, paint)
        paint.color = Color.WHITE
        canvas.drawText("BACK", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)
    }

    private fun drawSettingRow(canvas: Canvas, cx: Float, y: Float, R: Float, wRatio: Float, label: String, valueText: String, toggleVal: Boolean) {
        tempRect.set(cx - R * wRatio, y - R * 0.11f, cx + R * wRatio, y + R * 0.11f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 26, 34)
        canvas.drawRoundRect(tempRect, R * 0.11f, R * 0.11f, paint)

        paint.color = Color.WHITE
        paint.textSize = R * 0.080f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, tempRect.left + R * 0.10f, y + R * 0.03f, paint)

        if (valueText.isNotEmpty()) {
            paint.color = Color.rgb(0, 200, 230)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(valueText, tempRect.right - R * 0.10f, y + R * 0.03f, paint)
        } else {
            val swRight = tempRect.right - R * 0.08f
            val swLeft = swRight - R * 0.20f
            paint.color = if (toggleVal) Color.rgb(0, 180, 215) else Color.rgb(45, 55, 65)
            canvas.drawRoundRect(swLeft, y - R * 0.055f, swRight, y + R * 0.055f, R * 0.055f, R * 0.055f, paint)
            paint.color = Color.WHITE
            val knobX = if (toggleVal) swRight - R * 0.055f else swLeft + R * 0.055f
            canvas.drawCircle(knobX, y, R * 0.042f, paint)
        }
    }

    private fun drawWinScreen(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.argb(215, 10, 16, 20))

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val finalPos = if (playerFinishOrder > 0) playerFinishOrder else racePosition
        paint.color = if (finalPos == 1) Color.rgb(80, 235, 155) else Color.rgb(255, 195, 45)
        paint.textSize = R * 0.145f
        val totalRacers = if (botsEnabled) 1 + bots.size else 1
        val winTitle = if (botsEnabled && finalPos > 1) "FINISHED #$finalPos/$totalRacers" else "1st - YOU WIN!"
        canvas.drawText(winTitle, cx, cy - R * 0.52f, paint)

        if (isNewRecord) {
            tempRect.set(cx - R * 0.36f, cy - R * 0.45f, cx + R * 0.36f, cy - R * 0.32f)
            paint.color = Color.rgb(245, 190, 25)
            canvas.drawRoundRect(tempRect, R * 0.065f, R * 0.065f, paint)
            paint.color = Color.rgb(20, 20, 20)
            paint.textSize = R * 0.075f
            canvas.drawText("NEW RECORD!", cx, tempRect.centerY() + R * 0.026f, paint)
        }

        val finalScore = score.toInt()
        paint.color = Color.WHITE
        paint.textSize = R * 0.25f
        canvas.drawText("$finalScore", cx, cy - R * 0.08f, paint)

        val goldReq = tracks[currentTrackIdx].goldScore
        paint.color = Color.rgb(175, 185, 195)
        paint.textSize = R * 0.07f
        val bestStr = String.format("%.2fs", bestLapTime)
        canvas.drawText("GOLD $goldReq • BEST LAP $bestStr", cx, cy + R * 0.20f, paint)

        tempRect.set(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.11f
        canvas.drawText("RESTART", cx, tempRect.centerY() + R * 0.04f, paint)

        paint.color = Color.rgb(160, 175, 190)
        paint.textSize = R * 0.085f
        canvas.drawText("MENU", cx, cy + R * 0.68f, paint)
    }
}
// --- BÖLÜM 3 SONU: 3B PROJEKSİYON MOTORU, ARAÇ ÇİZİMLERİ, HUD VE EKRANLAR ---
