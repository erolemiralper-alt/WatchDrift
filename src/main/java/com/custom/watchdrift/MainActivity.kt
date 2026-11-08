package com.custom.watchdrift

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.*
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
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 102)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && gameView.handleRotaryScroll(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_1) && gameView.onSideButtonPressed()) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!gameView.onSideButtonPressed()) super.onBackPressed()
    }

    override fun onDestroy() {
        gameView.cleanup()
        super.onDestroy()
    }
}

data class Vec2(val x: Float, val y: Float)
data class GhostPose(val x: Float, val y: Float, val yaw: Float)

data class RemotePlayer(
    var active: Boolean = false, var name: String = "Bos",
    var x: Float = 0f, var y: Float = 0f, var yaw: Float = 0f,
    var colorIdx: Int = 7, var modelIdx: Int = 1
)

class BotCar(val color: Int, val modelIdx: Int, val baseSpeedMult: Float) {
    var wpIdx = 0; var laps = 0; var totalProgress = 0f
    var x = 0f; var y = 0f; var yaw = 0f; var roll = 0f
    var vx = 0f; var vy = 0f; var steer = 0f
    var laneOffset = 0f; var targetLane = 0f
    var mistakeTimer = 0f; var spinTimer = 0f
}

// type: 0=Ağaç, 1=Bina, 2=Sabit Bariyer, 3=Yol İçi Rastgele Engel
data class Obstacle3D(val x: Float, val y: Float, val radius: Float, val height: Float, val type: Int)

class MysteryBox(var x: Float = 0f, var y: Float = 0f, var active: Boolean = true, var respawnTimer: Float = 0f)
class ActiveProjectile(var active: Boolean = false, var type: Int = 0, var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f, var life: Float = 0f, var fromPlayer: Boolean = true)

class DebrisPart { var active = false; var x = 0f; var y = 0f; var z = 0f; var vx = 0f; var vy = 0f; var vz = 0f; var yaw = 0f; var size = 3f; var color = 0; var life = 0f }
class Particle3D { var active = false; var x = 0f; var y = 0f; var z = 0f; var vx = 0f; var vy = 0f; var vz = 0f; var size = 3f; var alpha = 0; var color = 0 }
class TireMark { var active = false; var lx1 = 0f; var ly1 = 0f; var lx2 = 0f; var ly2 = 0f; var rx1 = 0f; var ry1 = 0f; var rx2 = 0f; var ry2 = 0f; var baseColor = 0; var strength = 1f }
data class TrackData(val name: String, val theme: Int, val goldScore: Int, val controlPoints: List<Vec2>)

class CrownDriftView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("crowndrift_v7", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val polyPath = Path(); private val arcPath = Path(); private val miniPath = Path()
    private val tempRect = RectF()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // 0=MENU, 1=GARAGE, 2=SETTINGS, 3=GAME, 4=WIN, 5=COOP_LOBBY
    @Volatile private var state = 0

    private val carColors = intArrayOf(
        Color.rgb(30, 105, 255), Color.rgb(235, 45, 45), Color.rgb(255, 200, 25), Color.rgb(55, 195, 75),
        Color.rgb(255, 135, 20), Color.rgb(165, 50, 215), Color.rgb(240, 240, 240), Color.rgb(0, 225, 255)
    )
    private val carModels = arrayOf("JDM GT", "SUPER V12", "MUSCLE V8", "F1 HYPER")
    private var selectedColor = prefs.getInt("color", 0)
    private var selectedModel = prefs.getInt("model", 0)
    private var garagePreviewYaw = 0.5f

    private var sensLevel = prefs.getInt("sens", 1)
    private var ghostEnabled = prefs.getBoolean("ghost", true)
    private var botsEnabled = prefs.getBoolean("bots", true)
    private var showFps = prefs.getBoolean("fps", false)

    private val tracks = listOf(
        TrackData("Classic GP", 0, 8000, listOf(
            Vec2(0f, -1400f), Vec2(850f, -1350f), Vec2(1350f, -850f), Vec2(900f, -300f), Vec2(1450f, 200f),
            Vec2(1250f, 950f), Vec2(450f, 700f), Vec2(150f, 1350f), Vec2(-650f, 1400f), Vec2(-1250f, 850f),
            Vec2(-750f, 200f), Vec2(-1400f, -350f), Vec2(-1150f, -1100f), Vec2(-500f, -1350f)
        )),
        TrackData("Cyber Touge", 1, 9500, listOf(
            Vec2(-700f, -1450f), Vec2(900f, -1450f), Vec2(1500f, -950f), Vec2(750f, -450f), Vec2(1400f, 50f),
            Vec2(600f, 450f), Vec2(1250f, 1050f), Vec2(500f, 1500f), Vec2(-450f, 1100f), Vec2(-1100f, 1500f),
            Vec2(-1550f, 800f), Vec2(-900f, 150f), Vec2(-1500f, -500f), Vec2(-1250f, -1200f)
        )),
        TrackData("Tokyo Bay", 2, 10200, listOf(
            Vec2(0f, -1550f), Vec2(1100f, -1350f), Vec2(1550f, -550f), Vec2(650f, -150f), Vec2(450f, 450f),
            Vec2(1450f, 750f), Vec2(1150f, 1450f), Vec2(150f, 1550f), Vec2(-450f, 900f), Vec2(-1200f, 1350f),
            Vec2(-1550f, 500f), Vec2(-800f, -150f), Vec2(-1500f, -800f), Vec2(-950f, -1450f)
        )),
        TrackData("Glacier S", 3, 8800, listOf(
            Vec2(0f, -1450f), Vec2(1050f, -1200f), Vec2(1500f, -300f), Vec2(750f, 250f), Vec2(1350f, 950f),
            Vec2(600f, 1500f), Vec2(-550f, 1450f), Vec2(-1350f, 900f), Vec2(-700f, 200f), Vec2(-1500f, -400f), Vec2(-1050f, -1250f)
        )),
        TrackData("Canyon Hairpin", 4, 11500, listOf(
            Vec2(-600f, -1550f), Vec2(950f, -1500f), Vec2(1600f, -800f), Vec2(550f, -350f), Vec2(1550f, 250f),
            Vec2(650f, 750f), Vec2(1400f, 1350f), Vec2(200f, 1650f), Vec2(-650f, 1050f), Vec2(-1350f, 1500f),
            Vec2(-1650f, 600f), Vec2(-750f, 0f), Vec2(-1600f, -650f), Vec2(-1250f, -1350f)
        ))
    )
    private var currentTrackIdx = 0

    private val trackPts = ArrayList<Vec2>(400)
    private val trackNormals = ArrayList<Vec2>(400)
    private val trackTangents = ArrayList<Float>(400)
    private var zoneMask = BooleanArray(0)
    private val obstacles = ArrayList<Obstacle3D>(70)
    private val mysteryBoxes = ArrayList<MysteryBox>(14)
    private val projectiles = Array(10) { ActiveProjectile() }
    private val roadHalfWidth = 58f

    // Orijinal CrownDrift Araç Fiziği Değişkenleri
    private var carX = 0f; private var carY = 0f; private var carZ = 0f; private var carVZ = 0f
    private var carYaw = 0f; private var rollAngle = 0f
    private var velX = 0f; private var velY = 0f; private var speed = 0f
    private var smoothedSteering = 0f; private var crownSteer = 0f; private var crownAccum = 0f
    private var isCurrentlyDrifting = false; private var driftAmount = 0f
    private var isOffRoad = false; private var edgeActive = false; private var inZone = false

    // Kamera & Sarsıntı
    private var camYaw = 0f; private var camDist = 98f; private var camHeight = 138f; private var camFovFactor = 1.48f
    private var shakeTime = 0f

    // Kontroller
    private var isFullStopped = false; private var isHoldingStop = false
    private var isBezelDragging = false; private var lastBezelAngle = 0f

    // 3-2-1 Başlangıç, Turbo & Sürpriz Kutu Özellikleri
    private var countdownVal = 3; private var countdownTimer = 0f
    private var boostMeter = 0f; private var boostTime = 0f; private var boostReady = false
    // 0=Yok, 1=Füze, 2=Diken, 3=Kalkan, 4=Ön Silah, 5=Kötü Kutu (Yavaşlatma)
    private var activeItem = 0; private var activeItemTimer = 0f; private var weaponFireTimer = 0f

    // Hasar & 3 Saniyelik Güvenli Yolda Yeniden Doğma
    private var carDamage = 0f; private var spoilerBroken = false; private var bumperBroken = false
    private var isCrashing = false; private var crashTimer = 0f; private var respawnWp = 0
    private var respawnBannerTimer = 0f; private var invulnerableTimer = 0f

    // Havuzlar (Pil & Bellek Optimizasyonu: Sınırlandırılmış Boyutlar)
    private val debrisPool = Array(18) { DebrisPart() }; private var debrisHead = 0
    private val particlePool = Array(32) { Particle3D() }; private var particleHead = 0
    private val tirePool = Array(96) { TireMark() }; private var tireHead = 0
    private var prevBackLX = 0f; private var prevBackLY = 0f; private var prevBackRX = 0f; private var prevBackRY = 0f
    private var hasPrevSkid = false; private var stepCounter = 0

    // Yarış & Sıralama Bilgileri (Kilitlemeli Sıralama)
    private var lap = 1; private val totalLaps = 3
    private var currentWp = 0; private var maxWpReached = 0
    private var playerTotalProgress = 0f; private var racePosition = 1; private var lockedFinalRank = 0
    private var lapStartTime = 0L; private var currentLapTime = 0f; private var bestLapTime = 99.99f
    private var score = 0f; private var driftCombo = 1; private var comboTime = 0f; private var comboGap = 0f
    private var isNewRecord = false

    // String Önbelleği (Zero-GC Optimizasyonu)
    private var cachedScoreInt = -1; private var cachedScoreStr = "0"
    private var cachedPosKey = -1; private var cachedPosStr = "1/4"

    private val currentLapRecording = ArrayList<GhostPose>(900)
    private var bestLapGhost = listOf<GhostPose>(); private var ghostFrameIdx = 0
    private val bots = ArrayList<BotCar>(3)

    // Bluetooth Co-op
    private val btUuid: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    @Volatile private var lobbyRole = 0
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
            if (BluetoothDevice.ACTION_FOUND == intent?.action && btRunning && lobbyRole == 2 && clientSocket == null) {
                @Suppress("DEPRECATION")
                val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val addr = dev?.address ?: return
                if (triedAddresses.add(addr)) tryConnectToDiscoveredDevice(dev)
            }
        }
    }
    private var receiverRegistered = false

    private var projX = 0f; private var projY = 0f; private var projScale = 0f
    private var lastFrameTime = SystemClock.uptimeMillis(); private var currentFps = 60; private var lastVibeTime = 0L

    init {
        isFocusable = true; isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        paint.typeface = boldTypeface
        myAssignedModel = selectedModel
        buildTrackSpline(0)
        try {
            context.registerReceiver(btDiscoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            receiverRegistered = true
        } catch (_: Throwable) {}
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
        return if (state != 0) { state = 0; vibeClick(); invalidate(); true } else false
    }

    private fun saveSettings() {
        prefs.edit().putInt("color", selectedColor).putInt("model", selectedModel)
            .putInt("sens", sensLevel).putBoolean("ghost", ghostEnabled)
            .putBoolean("bots", botsEnabled).putBoolean("fps", showFps).apply()
    }

    // ==================== BLUETOOTH CO-OP ====================
    fun stopAllBluetooth() {
        btRunning = false; lobbyRole = 0
        try { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.cancelDiscovery() } catch (_: SecurityException) {}
        try { btServerSocket?.close() } catch (_: Throwable) {}
        for (s in hostClientSockets) { try { s.close() } catch (_: Throwable) {} }
        hostClientSockets.clear()
        try { clientSocket?.close() } catch (_: Throwable) {}
        clientSocket = null; triedAddresses.clear()
        for (rp in remotePlayers) rp.active = false
    }

    private fun startHostLobby() {
        stopAllBluetooth()
        lobbyRole = 1; myAssignedModel = selectedModel; btRunning = true
        lobbyStatusText = "Oda Acik (1/4) Bekleniyor"
        try {
            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (_: Throwable) {}

        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (adapter == null || !adapter.isEnabled) { lobbyStatusText = "BT Kapali!"; postInvalidate(); return@thread }
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
                        vibeClick(); postInvalidate()
                        handleHostClientConnection(sock, slot)
                    } else sock.close()
                }
            } catch (_: Throwable) {
                if (lobbyRole == 1 && hostClientSockets.isEmpty()) { lobbyStatusText = "Oda Hazir (1/4)"; postInvalidate() }
            }
        }
    }

    private fun handleHostClientConnection(sock: BluetoothSocket, slot: Int) {
        thread(start = true, isDaemon = true) {
            try {
                val inp = DataInputStream(sock.inputStream)
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    remotePlayers[slot].x = inp.readFloat(); remotePlayers[slot].y = inp.readFloat(); remotePlayers[slot].yaw = inp.readFloat()
                    remotePlayers[slot].colorIdx = inp.readInt().coerceIn(0, 7); remotePlayers[slot].modelIdx = inp.readInt().coerceIn(0, 3)
                }
            } catch (_: Throwable) {
                remotePlayers[slot].active = false; hostClientSockets.remove(sock)
                lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4 Oyuncu"; postInvalidate()
            }
        }
        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream)
                val assignedClientModel = (selectedModel + slot + 1) % 4
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    out.writeInt(state); out.writeInt(currentTrackIdx); out.writeInt(hostClientSockets.size + 1); out.writeInt(assignedClientModel)
                    out.writeFloat(carX); out.writeFloat(carY); out.writeFloat(carYaw); out.writeInt(selectedColor); out.writeInt(selectedModel)
                    for (i in 0..2) if (i != slot) {
                        val rp = remotePlayers[i]
                        out.writeBoolean(rp.active); out.writeFloat(rp.x); out.writeFloat(rp.y); out.writeFloat(rp.yaw); out.writeInt(rp.colorIdx); out.writeInt(rp.modelIdx)
                    }
                    out.flush(); Thread.sleep(45)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun scanAndJoinLobby() {
        stopAllBluetooth()
        lobbyRole = 2; btRunning = true; lobbyStatusText = "Cevre Taraniyor..."
        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (adapter == null || !adapter.isEnabled) { lobbyStatusText = "BT Kapali!"; postInvalidate(); return@thread }
                val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
                if (!bonded.isNullOrEmpty()) {
                    for (dev in bonded) {
                        if (!btRunning || lobbyRole != 2 || clientSocket != null) break
                        triedAddresses.add(dev.address); tryConnectToDiscoveredDevice(dev)
                    }
                }
                try { if (adapter.isDiscovering) adapter.cancelDiscovery(); adapter.startDiscovery() } catch (_: SecurityException) { lobbyStatusText = "Tarama Izni Gerekli"; postInvalidate() }
            } catch (_: Throwable) { lobbyStatusText = "Tarama Hatasi"; postInvalidate() }
        }
    }

    private fun tryConnectToDiscoveredDevice(dev: BluetoothDevice) {
        thread(start = true, isDaemon = true) {
            try {
                if (!btRunning || lobbyRole != 2 || clientSocket != null) return@thread
                val sock = dev.createInsecureRfcommSocketToServiceRecord(btUuid)
                sock.connect()
                if (clientSocket != null) { sock.close(); return@thread }
                clientSocket = sock
                try { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.cancelDiscovery() } catch (_: SecurityException) {}
                remotePlayers[0].active = true
                remotePlayers[0].name = try { dev.name ?: "Kurucu" } catch (_: SecurityException) { "Kurucu" }
                lobbyStatusText = "Odaya Baglandi! Start Bekleniyor"; vibeClick(); postInvalidate()
                handleClientConnection(sock)
            } catch (_: Throwable) {}
        }
    }

    private fun handleClientConnection(sock: BluetoothSocket) {
        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream)
                while (btRunning && lobbyRole == 2 && sock.isConnected) {
                    out.writeFloat(carX); out.writeFloat(carY); out.writeFloat(carYaw); out.writeInt(selectedColor); out.writeInt(myAssignedModel)
                    out.flush(); Thread.sleep(45)
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
                myAssignedModel = assignedModel; selectedModel = assignedModel
                lobbyStatusText = "Odada $totalPlayers/4 (${carModels[assignedModel]})"
                remotePlayers[0].active = true
                remotePlayers[0].x = inp.readFloat(); remotePlayers[0].y = inp.readFloat(); remotePlayers[0].yaw = inp.readFloat()
                remotePlayers[0].colorIdx = inp.readInt().coerceIn(0, 7); remotePlayers[0].modelIdx = inp.readInt().coerceIn(0, 3)
                for (slotIdx in 1..2) {
                    remotePlayers[slotIdx].active = inp.readBoolean()
                    remotePlayers[slotIdx].x = inp.readFloat(); remotePlayers[slotIdx].y = inp.readFloat(); remotePlayers[slotIdx].yaw = inp.readFloat()
                    remotePlayers[slotIdx].colorIdx = inp.readInt().coerceIn(0, 7); remotePlayers[slotIdx].modelIdx = inp.readInt().coerceIn(0, 3)
                }
                if (hostState == 3 && state != 3) { currentTrackIdx = hostTrack; post { startRace(fromNetwork = true) } } else postInvalidate()
            }
        } catch (_: Throwable) {
            remotePlayers[0].active = false; clientSocket = null; lobbyStatusText = "Baglanti Koptu"; postInvalidate()
        }
    }

    // ==================== HAPTİK VE BEZEL GİRİŞİ ====================
    private fun triggerHaptic(durationMs: Long, amplitude: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVibeTime < 45L && durationMs < 40L) return
        lastVibeTime = now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibeClick() = triggerHaptic(20L, 180)

    fun handleRotaryScroll(event: MotionEvent): Boolean {
        var delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (delta == 0f) return false

        when (state) {
            0 -> {
                currentTrackIdx = (currentTrackIdx + if (delta > 0) 1 else tracks.size - 1) % tracks.size
                buildTrackSpline(currentTrackIdx); vibeClick()
            }
            1 -> {
                selectedModel = (selectedModel + if (delta > 0) 1 else 3) % 4
                myAssignedModel = selectedModel; saveSettings(); vibeClick()
            }
            3 -> if (countdownVal < 0) {
                val sensMult = when (sensLevel) { 0 -> 0.25f; 1 -> 0.42f; else -> 0.62f }
                crownAccum += delta.coerceIn(-1.5f, 1.5f) * sensMult
                triggerHaptic(6L, 90)
            }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && handleRotaryScroll(event)) return true
        return super.onGenericMotionEvent(event)
    }

    // ==================== PİST, DRIFT ZONE, KUTULAR VE ENGELLER ====================
    private fun buildTrackSpline(idx: Int) {
        trackPts.clear(); trackNormals.clear(); trackTangents.clear()
        obstacles.clear(); mysteryBoxes.clear()

        val cps = tracks[idx].controlPoints
        val n = cps.size
        val stepsPerSeg = 22 // Optimize edilmiş segment sayısı
        for (i in 0 until n) {
            val p0 = cps[(i - 1 + n) % n]; val p1 = cps[i]; val p2 = cps[(i + 1) % n]; val p3 = cps[(i + 2) % n]
            for (s in 0 until stepsPerSeg) {
                val t = s / stepsPerSeg.toFloat(); val t2 = t * t; val t3 = t2 * t
                val x = 0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t + (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 + (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3)
                val y = 0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t + (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 + (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3)
                trackPts.add(Vec2(x, y))
            }
        }
        val total = trackPts.size
        for (i in 0 until total) {
            val next = trackPts[(i + 1) % total]; val prev = trackPts[(i - 1 + total) % total]
            val dx = next.x - prev.x; val dy = next.y - prev.y
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = -dy / len; val ny = dx / len
            trackNormals.add(Vec2(nx, ny))
            trackTangents.add(atan2(dy, dx))

            // Çevre Ağaç ve Binalar
            if (i % 16 == 0) {
                val side = if ((i / 16) % 2 == 0) 1f else -1f
                val distFromRoad = roadHalfWidth + 68f + (i % 30)
                val isTree = (i / 16) % 3 != 0
                obstacles.add(Obstacle3D(trackPts[i].x + nx * side * distFromRoad, trackPts[i].y + ny * side * distFromRoad, if (isTree) 16f else 26f, if (isTree) 34f else 48f, if (isTree) 0 else 1))
            }
            // Yol Kenarı Sabit Bariyerler
            if (i > 20 && i % 38 == 0) {
                val side = if ((i / 38) % 2 == 0) 1f else -1f
                val bx = trackPts[i].x + nx * side * (roadHalfWidth - 6f)
                val by = trackPts[i].y + ny * side * (roadHalfWidth - 6f)
                obstacles.add(Obstacle3D(bx, by, 10f, 14f, 2))
            }
            // Sürpriz Kutular (Yol Üzerinde)
            if (i > 18 && i % 42 == 0) {
                val lane = ((i / 42) % 3 - 1) * 20f
                mysteryBoxes.add(MysteryBox(trackPts[i].x + nx * lane, trackPts[i].y + ny * lane, true, 0f))
            }
        }
        computeDriftZones()
    }

    // Referans Track3D.computeDriftZones() Uyarlaması
    private fun computeDriftZones() {
        val n = trackPts.size
        zoneMask = BooleanArray(n)
        if (n < 40) return
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val a1 = trackTangents[(i - 1 + n) % n]
            val a2 = trackTangents[i]
            turn[i] = abs(wrapAngle(a2 - a1))
        }
        val w = n / 22
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (k in -w..w) s += turn[(i + k + n) % n]
            smooth[i] = s
        }
        val used = BooleanArray(n)
        for (pass in 0 until 2) {
            var best = -1
            for (i in (n / 10) until (n - n / 10)) {
                if (!used[i] && (best < 0 || smooth[i] > smooth[best])) best = i
            }
            if (best < 0) break
            for (k in -w..w) zoneMask[(best + k + n) % n] = true
            for (k in -(n / 4)..(n / 4)) used[(best + k + n) % n] = true
        }
    }

    private fun startRace(fromNetwork: Boolean = false) {
        buildTrackSpline(currentTrackIdx)
        val total = trackPts.size
        // Yolun Ortasında Rastgele Doğmuş Engeller (Her yarışta farklı yerlerde)
        for (r in 0 until 7) {
            val rIdx = Random.nextInt(25, total - 20)
            val rLane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.1f)
            val pt = trackPts[rIdx]; val nm = trackNormals[rIdx]
            obstacles.add(Obstacle3D(pt.x + nm.x * rLane, pt.y + nm.y * rLane, 11f, 16f, 3))
        }

        val p0 = trackPts[0]; val n0 = trackNormals[0]
        val startOffset = if (lobbyRole == 2) 16f else if (lobbyRole == 1) -16f else 0f
        carX = p0.x + n0.x * startOffset; carY = p0.y + n0.y * startOffset
        carZ = 0f; carVZ = 0f
        carYaw = trackTangents[0]; camYaw = carYaw; rollAngle = 0f
        velX = 0f; velY = 0f; speed = 0f
        smoothedSteering = 0f; crownSteer = 0f; crownAccum = 0f
        isCurrentlyDrifting = false; driftAmount = 0f; isOffRoad = false; edgeActive = false; inZone = false

        isFullStopped = false; isHoldingStop = false; isBezelDragging = false
        countdownVal = 3; countdownTimer = 0.85f
        boostMeter = 0f; boostTime = 0f; boostReady = false
        activeItem = 0; activeItemTimer = 0f; weaponFireTimer = 0f

        carDamage = 0f; spoilerBroken = false; bumperBroken = false
        isCrashing = false; crashTimer = 0f; respawnBannerTimer = 0f; invulnerableTimer = 0f

        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (tm in tirePool) tm.active = false
        for (pr in projectiles) pr.active = false
        hasPrevSkid = false; currentLapRecording.clear(); ghostFrameIdx = 0

        // 3 Gerçekçi Hızlı Bot (Aynı fizik + hata yapabilme)
        bots.clear()
        if (botsEnabled) {
            val botColors = intArrayOf(Color.rgb(255, 195, 20), Color.rgb(235, 45, 90), Color.rgb(60, 235, 130))
            val lanes = floatArrayOf(-20f, 20f, -8f)
            val mults = floatArrayOf(0.95f, 0.99f, 1.03f)
            for (b in 0..2) {
                val bot = BotCar(botColors[b], (selectedModel + b + 1) % 4, mults[b])
                bot.wpIdx = (b + 1) * 2
                val bp = trackPts[bot.wpIdx]; val bn = trackNormals[bot.wpIdx]
                bot.laneOffset = lanes[b]; bot.targetLane = lanes[b]
                bot.x = bp.x + bn.x * bot.laneOffset; bot.y = bp.y + bn.y * bot.laneOffset
                bot.yaw = trackTangents[bot.wpIdx]
                bot.totalProgress = bot.wpIdx.toFloat()
                bots.add(bot)
            }
        }

        lap = 1; currentWp = 0; maxWpReached = 0; playerTotalProgress = 0f
        racePosition = 1; lockedFinalRank = 0
        score = 0f; driftCombo = 1; comboTime = 0f; comboGap = 0f

        lapStartTime = SystemClock.uptimeMillis()
        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        isNewRecord = false
        state = 3
        if (!fromNetwork) vibeClick()
        requestFocus()
        invalidate()
    }

    private fun spawnDebris(ix: Float, iy: Float, count: Int, baseCol: Int) {
        for (i in 0 until count) {
            val d = debrisPool[debrisHead]
            debrisHead = (debrisHead + 1) % debrisPool.size
            val ang = Random.nextFloat() * 6.28f; val spd = 40f + Random.nextFloat() * 90f
            d.active = true; d.x = ix; d.y = iy; d.z = 4f + Random.nextFloat() * 6f
            d.vx = cos(ang) * spd; d.vy = sin(ang) * spd; d.vz = 65f + Random.nextFloat() * 85f
            d.yaw = ang; d.size = 2.8f + Random.nextFloat() * 2.8f
            d.color = if (i % 2 == 0) baseCol else Color.rgb(40, 40, 45); d.life = 3.5f
        }
    }

    private fun spawnParticle(px: Float, py: Float, pz: Float, vx: Float, vy: Float, vz: Float, sz: Float, col: Int, alpha: Int) {
        val p = particlePool[particleHead]
        particleHead = (particleHead + 1) % particlePool.size
        p.active = true; p.x = px; p.y = py; p.z = pz
        p.vx = vx; p.vy = vy; p.vz = vz; p.size = sz; p.color = col; p.alpha = alpha
    }

    private fun fireProjectile(type: Int, sx: Float, sy: Float, dirYaw: Float, fromPlayer: Boolean) {
        for (pr in projectiles) {
            if (!pr.active) {
                pr.active = true; pr.type = type; pr.fromPlayer = fromPlayer
                if (type == 2) { // Diken: Aracın arkasına yola bırakılır
                    pr.x = sx - cos(dirYaw) * 20f; pr.y = sy - sin(dirYaw) * 20f
                    pr.vx = 0f; pr.vy = 0f; pr.life = 14f
                } else { // Füze (1) veya Ön Silah Mermisi (4)
                    val spd = if (type == 1) 460f else 540f
                    pr.x = sx + cos(dirYaw) * 18f; pr.y = sy + sin(dirYaw) * 18f
                    pr.vx = cos(dirYaw) * spd; pr.vy = sin(dirYaw) * spd; pr.life = 2.5f
                }
                break
            }
        }
    }

    // 1.2 Hata Çözümü: Patlayınca en yakın yol merkezini kaydeder ve 3.0s sayar
    private fun triggerExplosionAtSpot() {
        if (isCrashing) return
        isCrashing = true
        crashTimer = 3.0f // 3 saniyelik canlanma süresi
        respawnWp = currentWp
        carVZ = 140f; velX = 0f; velY = 0f; speed = 0f

        spawnDebris(carX, carY, 10, carColors[selectedColor])
        for (i in 0 until 10) {
            val ang = Random.nextFloat() * 6.28f; val spd = 20f + Random.nextFloat() * 55f
            val fireCol = if (i % 2 == 0) Color.rgb(255, 110, 20) else Color.rgb(255, 210, 40)
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 40f + Random.nextFloat() * 45f, 5.5f, fireCol, 240)
        }
        triggerHaptic(180L, 255)
    }

    private fun respawnOnRoad() {
        isCrashing = false
        carZ = 0f; carVZ = 0f; carDamage = 0f
        spoilerBroken = false; bumperBroken = false
        // Bina içinde değil, en yakın güvenli yol noktasında (trackPts[respawnWp]) doğar!
        val safePt = trackPts[respawnWp]
        carX = safePt.x; carY = safePt.y
        carYaw = trackTangents[respawnWp]; rollAngle = 0f
        velX = cos(carYaw) * 90f; velY = sin(carYaw) * 90f; speed = 90f
        isFullStopped = false
        respawnBannerTimer = 1.6f
        invulnerableTimer = 2.5f // Doğduktan sonra 2.5 sn çarpışma koruması
        triggerHaptic(55L, 200)
    }

    // ==================== DOKUNMATİK KONTROLLER ====================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f
        val x = event.x; val y = event.y

        when (state) {
            0 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.35f)..(cy + R * 0.18f)) {
                    if (x < cx - R * 0.42f) { currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size; buildTrackSpline(currentTrackIdx); vibeClick() }
                    else if (x > cx + R * 0.42f) { currentTrackIdx = (currentTrackIdx + 1) % tracks.size; buildTrackSpline(currentTrackIdx); vibeClick() }
                }
                if (hypot(x - (cx - R * 0.60f), y - (cy + R * 0.32f)) < R * 0.20f) { state = 1; vibeClick() }
                else if (hypot(x - (cx + R * 0.60f), y - (cy + R * 0.32f)) < R * 0.20f) { state = 2; vibeClick() }
                else if (y in (cy + R * 0.32f)..(cy + R * 0.52f) && abs(x - cx) < R * 0.36f) { state = 5; vibeClick() }
                else if (y > cy + R * 0.54f && abs(x - cx) < R * 0.50f) { vibeClick(); startRace() }
                invalidate()
            }
            1 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.12f)..(cy + R * 0.14f)) { selectedModel = (selectedModel + 1) % carModels.size; myAssignedModel = selectedModel; saveSettings(); vibeClick() }
                val gridTop = cy + R * 0.18f; val gridBot = cy + R * 0.62f
                if (y in gridTop..gridBot) {
                    val row = if (y < (gridTop + gridBot) / 2f) 0 else 1
                    val col = ((x - (cx - R * 0.56f)) / (R * 1.12f) * 4f).toInt().coerceIn(0, 3)
                    selectedColor = row * 4 + col; saveSettings(); vibeClick()
                }
                if (y > cy + R * 0.64f) { state = 0; vibeClick() }
                invalidate()
            }
            2 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.66f)..(cy - R * 0.38f)) { sensLevel = (sensLevel + 1) % 3; saveSettings(); vibeClick() }
                else if (y in (cy - R * 0.37f)..(cy - R * 0.11f)) { state = 5; vibeClick() }
                else if (y in (cy - R * 0.10f)..(cy + R * 0.16f)) { ghostEnabled = !ghostEnabled; saveSettings(); vibeClick() }
                else if (y in (cy + R * 0.17f)..(cy + R * 0.44f)) { botsEnabled = !botsEnabled; saveSettings(); vibeClick() }
                else if (y > cy + R * 0.46f) { if (x < cx) { showFps = !showFps; saveSettings() } else state = 0; vibeClick() }
                invalidate()
            }
            5 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.45f)..(cy - R * 0.15f)) { if (x < cx) startHostLobby() else scanAndJoinLobby(); vibeClick() }
                else if (y in (cy + R * 0.42f)..(cy + R * 0.72f) && abs(x - cx) < R * 0.48f) { if (lobbyRole != 2) startRace() }
                else if (y > cy + R * 0.73f) { state = 0; vibeClick() }
                invalidate()
            }
            3 -> {
                // UI Konumları:
                // ÇIK Butonu: Sol-Üst sıralama kutusunun yanında
                val exitBtnX = cx - R * 0.04f; val exitBtnY = cy - R * 0.54f
                // DUR (Pause) Butonu: Sağ kenarda, Harita Balonunun Üstünde! (Arabayı kapatmaz)
                val stopBtnX = cx + R * 0.36f; val stopBtnY = cy - R * 0.64f
                // TURBO İkonu: Sol kenarda
                val turboBtnX = cx - R * 0.56f; val turboBtnY = cy - R * 0.08f
                val distFromCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.12f) { state = 0; vibeClick(); invalidate(); return true }
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.13f) { isFullStopped = !isFullStopped; isHoldingStop = true; vibeClick(); return true }
                        // Turbo İkonuna veya Ekran Ortasına Dokunarak Turbo Ateşleme
                        if (boostReady && (hypot(x - turboBtnX, y - turboBtnY) < R * 0.16f || distFromCenter < R * 0.28f)) {
                            boostReady = false; boostMeter = 0f; boostTime = 3.0f
                            triggerHaptic(85L, 255)
                            return true
                        }
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
                            val sensMult = when (sensLevel) { 0 -> 0.85f; 1 -> 1.25f; else -> 1.75f }
                            crownAccum += dAng * sensMult
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHoldingStop = false; isBezelDragging = false
                    }
                }
            }
            4 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy + R * 0.22f)..(cy + R * 0.55f) && abs(x - cx) < R * 0.50f) { vibeClick(); startRace() }
                else if (y > cy + R * 0.56f) { vibeClick(); state = 0; invalidate() }
            }
        }
        return true
    }

    private fun wrapAngle(a: Float): Float {
        var r = (a + PI.toFloat()) % (2f * PI.toFloat())
        if (r < 0) r += 2f * PI.toFloat()
        return r - PI.toFloat()
    }

    // ==================== RENDER DÖNGÜSÜ (BATARYA OPTİMİZASYONLU) ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.005f, 0.05f)
        lastFrameTime = now
        currentFps = (1f / dt).toInt()

        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f

        when (state) {
            0 -> drawMainMenu(canvas, cx, cy, R)
            1 -> { drawGarage(canvas, cx, cy, R, dt); postInvalidateOnAnimation() }
            2 -> drawSettings(canvas, cx, cy, R)
            5 -> drawCoopLobby(canvas, cx, cy, R)
            3 -> {
                updateGamePhysics(dt, now)
                drawTrue3DWorld(canvas, cx, cy, R)
                drawGameHud(canvas, cx, cy, R)
                postInvalidateOnAnimation()
            }
            4 -> {
                drawTrue3DWorld(canvas, cx, cy, R)
                drawWinScreen(canvas, cx, cy, R)
            }
        }

        if (showFps && (state == 3 || state == 1)) {
            paint.color = Color.GREEN; paint.textSize = R * 0.065f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.88f, paint)
        }
        // Not: Statik menülerde (state 0, 2, 4, 5) sürekli postInvalidateOnAnimation() çağrılmaz -> Pil ve ısınma %70 azalır!
    }

    // ==================== 3B PROJEKSİYON VE MODEL ÇİZİMİ ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - carX; val dy = wy - carY
        val cosYaw = cos(camYaw); val sinYaw = sin(camYaw)
        val forward = dx * cosYaw + dy * sinYaw
        val right = -dx * sinYaw + dy * cosYaw

        val relF = forward + camDist
        val relH = wz - camHeight
        val depth = relF * 0.682f - relH * 0.731f
        if (depth < 16f || depth > 1350f) return false
        val vert = relF * 0.731f + relH * 0.682f

        projScale = (R * camFovFactor) / depth
        val shakeX = if (shakeTime > 0f) (Random.nextFloat() - 0.5f) * R * 0.025f else 0f
        val shakeY = if (shakeTime > 0f) (Random.nextFloat() - 0.5f) * R * 0.020f else 0f
        projX = cx + right * projScale + shakeX
        projY = (cy + R * 0.16f) - vert * projScale + shakeY
        return true
    }

    private fun drawQuad3D(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float, color: Int
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
        polyPath.moveTo(sx1, sy1); polyPath.lineTo(sx2, sy2); polyPath.lineTo(sx3, sy3); polyPath.lineTo(sx4, sy4); polyPath.close()
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawPath(polyPath, paint)
    }

    private fun draw3DBox(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        bx: Float, by: Float, bz: Float, halfL: Float, halfW: Float, height: Float,
        yaw: Float, topColor: Int, sideColor: Int, rearColor: Int, rollDeg: Float = 0f
    ) {
        val c = cos(yaw); val s = sin(yaw)
        val fx = c * halfL; val fy = s * halfL
        val rx = -s * halfW; val ry = c * halfW
        // Gövde Yatma (Roll) Efekti
        val rollZ = sin(Math.toRadians(rollDeg.toDouble())).toFloat() * halfW * 0.45f

        val flX = bx + fx - rx; val flY = by + fy - ry; val zL = bz - rollZ; val zLT = zL + height
        val frX = bx + fx + rx; val frY = by + fy + ry; val zR = bz + rollZ; val zRT = zR + height
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry

        drawQuad3D(canvas, cx, cy, R, flX, flY, zL, rlX, rlY, zL, rlX, rlY, zLT, flX, flY, zLT, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, zR, rrX, rrY, zR, rrX, rrY, zRT, frX, frY, zRT, sideColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, zL, frX, frY, zR, frX, frY, zRT, flX, flY, zLT, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, zL, rrX, rrY, zR, rrX, rrY, zRT, rlX, rlY, zLT, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, zLT, frX, frY, zRT, rrX, rrY, zRT, rlX, rlY, zLT, topColor)
    }

    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false, carRoll: Float = 0f, wheelSteerRad: Float = 0f
    ) {
        if (isPlayer && invulnerableTimer > 0f && ((invulnerableTimer * 10).toInt() % 2 == 0)) return
        val alpha = if (isGhost) 110 else 255
        val damageDarken = if (isPlayer) (1f - (carDamage / 210f)) else 1f
        val r = (Color.red(baseColor) * damageDarken).toInt()
        val g = (Color.green(baseColor) * damageDarken).toInt()
        val b = (Color.blue(baseColor) * damageDarken).toInt()

        val topCol = Color.argb(alpha, r, g, b)
        val sideCol = Color.argb(alpha, (r * 0.75f).toInt(), (g * 0.75f).toInt(), (b * 0.75f).toInt())
        val rearCol = Color.argb(alpha, (r * 0.56f).toInt(), (g * 0.56f).toInt(), (b * 0.56f).toInt())
        val glassCol = Color.argb(alpha, 30, 48, 65)
        val wheelCol = Color.argb(alpha, 20, 22, 25)
        val chromeCol = Color.argb(alpha, 215, 220, 230)

        val c = cos(yaw); val s = sin(yaw); val rxVec = -s; val ryVec = c

        if (!isGhost) {
            val fx = c * 16.5f; val fy = s * 16.5f; val rx = rxVec * 10.5f; val ry = ryVec * 10.5f
            drawQuad3D(canvas, cx, cy, R, x + fx - rx, y + fy - ry, 0.2f, x + fx + rx, y + fy + ry, 0.2f, x - fx + rx, y - fy + ry, 0.2f, x - fx - rx, y - fy - ry, 0.2f, Color.argb(135, 0, 0, 0))
        }

        val bz = zOffset + 1.4f
        // 4 Tekerlek (Ön tekerlekler Car3D'deki gibi ±28° = ±0.49 rad döner)
        if (!isGhost) {
            val wxF = 9.8f; val wxR = -9.8f
            val wyOff = if (modelIdx == 3) 10.4f else 9.4f
            val wWidth = if (modelIdx == 3) 2.2f else 1.7f
            draw3DBox(canvas, cx, cy, R, x + c * wxF - rxVec * wyOff, y + s * wxF - ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + wheelSteerRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxF + rxVec * wyOff, y + s * wxF + ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + wheelSteerRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR - rxVec * wyOff, y + s * wxR - ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR + rxVec * wyOff, y + s * wxR + ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
        }

        when (modelIdx % 4) {
            0 -> {
                val bodyLen = if (isPlayer && bumperBroken) 13.5f else 15.8f
                draw3DBox(canvas, cx, cy, R, x, y, bz, bodyLen, 8.8f, 4.3f, yaw, topCol, sideCol, rearCol, carRoll)
                if (!(isPlayer && bumperBroken)) draw3DBox(canvas, cx, cy, R, x + c * 11.2f, y + s * 11.2f, bz + 0.4f, 4.8f, 8.2f, 2.8f, yaw, topCol, sideCol, wheelCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz + 4.3f, 7.6f, 6.8f, 3.7f, yaw, topCol, glassCol, glassCol, carRoll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14.0f, y - s * 14.0f, bz + 6.4f, 2.0f, 9.4f, 1.1f, yaw, wheelCol, sideCol, rearCol, carRoll)
            }
            1 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.0f else 16.8f
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz, bodyLen * 0.85f, 9.8f, 3.4f, yaw, topCol, sideCol, rearCol, carRoll)
                if (!(isPlayer && bumperBroken)) draw3DBox(canvas, cx, cy, R, x + c * 12.0f, y + s * 12.0f, bz, 5.5f, 8.8f, 2.1f, yaw, topCol, sideCol, wheelCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x + c * 1.0f, y + s * 1.0f, bz + 3.4f, 8.2f, 6.4f, 2.9f, yaw, topCol, glassCol, rearCol, carRoll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, yaw, rearCol, sideCol, wheelCol, carRoll)
            }
            2 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.2f else 16.5f
                draw3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, bodyLen, 9.0f, 4.6f, yaw, topCol, sideCol, rearCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, yaw, chromeCol, wheelCol, chromeCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, yaw, topCol, glassCol, glassCol, carRoll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 15.2f, y - s * 15.2f, bz + 5.0f, 1.4f, 8.8f, 1.8f, yaw, rearCol, sideCol, rearCol, carRoll)
            }
            else -> {
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, yaw, topCol, sideCol, rearCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, yaw, topCol, sideCol, rearCol, carRoll)
                if (!(isPlayer && bumperBroken)) draw3DBox(canvas, cx, cy, R, x + c * 16.2f, y + s * 16.2f, bz - 0.4f, 2.2f, 10.6f, 1.1f, yaw, chromeCol, topCol, sideCol, carRoll)
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, yaw, glassCol, glassCol, rearCol, carRoll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, yaw, chromeCol, topCol, rearCol, carRoll)
            }
        }

        // Araç Önü Silahı veya Kalkan Görseli
        if (isPlayer && activeItem == 4) {
            draw3DBox(canvas, cx, cy, R, x + c * 17f - rxVec * 5f, y + s * 17f - ryVec * 5f, bz + 2.5f, 4.5f, 1.2f, 1.2f, yaw, chromeCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * 17f + rxVec * 5f, y + s * 17f + ryVec * 5f, bz + 2.5f, 4.5f, 1.2f, 1.2f, yaw, chromeCol, wheelCol, wheelCol)
        }
        if (isPlayer && activeItem == 3 && project3D(x, y, bz + 4f, cx, cy, R)) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f; paint.color = Color.argb(180, 0, 235, 255)
            canvas.drawCircle(projX, projY, 24f * projScale, paint)
        }

        if (!isGhost) {
            val tailX = x - c * 15.5f; val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.rgb(255, 220, 40) else Color.rgb(255, 30, 30)
            draw3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol, carRoll)
            draw3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol, carRoll)
        }
    }

    private fun drawTrue3DWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val theme = tracks[currentTrackIdx].theme
        canvas.drawColor(when (theme) {
            0 -> Color.rgb(34, 78, 24); 1 -> Color.rgb(12, 10, 28); 2 -> Color.rgb(18, 22, 32)
            3 -> Color.rgb(175, 210, 230); else -> Color.rgb(95, 55, 30)
        })

        val total = trackPts.size
        val kerbW = 9.5f
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(70, 74, 78)

        // Optimize Edilmiş Görüş Penceresi (38 ileri, 14 geri)
        for (step in 38 downTo -14) {
            val i0 = (currentWp + step + total) % total
            val i1 = (i0 + 1) % total
            val p0 = trackPts[i0]; val p1 = trackPts[i1]; val n0 = trackNormals[i0]; val n1 = trackNormals[i1]

            val l0x = p0.x - n0.x * roadHalfWidth; val l0y = p0.y - n0.y * roadHalfWidth
            val r0x = p0.x + n0.x * roadHalfWidth; val r0y = p0.y + n0.y * roadHalfWidth
            val l1x = p1.x - n1.x * roadHalfWidth; val l1y = p1.y - n1.y * roadHalfWidth
            val r1x = p1.x + n1.x * roadHalfWidth; val r1y = p1.y + n1.y * roadHalfWidth

            val kl0x = l0x - n0.x * kerbW; val kl0y = l0y - n0.y * kerbW
            val kl1x = l1x - n1.x * kerbW; val kl1y = l1y - n1.y * kerbW
            val kr0x = r0x + n0.x * kerbW; val kr0y = r0y + n0.y * kerbW
            val kr1x = r1x + n1.x * kerbW; val kr1y = r1y + n1.y * kerbW

            val isZone = zoneMask.isNotEmpty() && zoneMask[i0]
            val kerbColor = if (isZone) {
                if (i0 % 2 == 0) Color.rgb(255, 195, 10) else Color.rgb(25, 25, 25) // Drift Zone Altın-Siyah Kerb
            } else {
                if (i0 % 2 == 0) Color.rgb(230, 35, 35) else Color.WHITE
            }

            drawQuad3D(canvas, cx, cy, R, kl0x, kl0y, 0f, l0x, l0y, 0f, l1x, l1y, 0f, kl1x, kl1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, r0x, r0y, 0f, kr0x, kr0y, 0f, kr1x, kr1y, 0f, r1x, r1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0f, r0x, r0y, 0f, r1x, r1y, 0f, l1x, l1y, 0f, roadCol)

            if (i0 == 0) {
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0.3f, r0x, r0y, 0.3f, r1x, r1y, 0.3f, l1x, l1y, 0.3f, Color.WHITE)
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 34f, r0x, r0y, 34f, r1x, r1y, 40f, l1x, l1y, 40f, Color.rgb(210, 40, 40))
            }
        }

        // Dairesel Tamponlu Solarak Kaybolan Lastik İzleri (SkidTrail Uyarlaması)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f
        val maxSegs = tirePool.size
        for (i in 0 until maxSegs) {
            val mark = tirePool[i]
            if (!mark.active) continue
            val age = (tireHead - 1 - i + maxSegs) % maxSegs
            val t = age.toFloat() / maxSegs
            val fadeAlpha = ((1f - t * t) * 165f * mark.strength).toInt().coerceIn(0, 200)
            if (fadeAlpha < 10) continue
            paint.color = (mark.baseColor and 0x00FFFFFF) or (fadeAlpha shl 24)
            if (project3D(mark.lx1, mark.ly1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(mark.lx2, mark.ly2, 0.2f, cx, cy, R)) canvas.drawLine(x1, y1, projX, projY, paint)
            }
            if (project3D(mark.rx1, mark.ry1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(mark.rx2, mark.ry2, 0.2f, cx, cy, R)) canvas.drawLine(x1, y1, projX, projY, paint)
            }
        }

        // Sürpriz Kutular (Yolda Dönen Altın/Mor Kutular)
        for (mb in mysteryBoxes) {
            if (!mb.active) continue
            val dx = mb.x - carX; val dy = mb.y - carY
            if (dx * dx + dy * dy < 320000f) {
                draw3DBox(canvas, cx, cy, R, mb.x, mb.y, 2f, 6.5f, 6.5f, 11f, garagePreviewYaw, Color.rgb(255, 215, 20), Color.rgb(220, 140, 10), Color.rgb(180, 50, 220))
            }
        }

        // Füzeler, Mermiler ve Dikenler
        for (pr in projectiles) {
            if (!pr.active) continue
            if (pr.type == 2) { // Diken
                draw3DBox(canvas, cx, cy, R, pr.x, pr.y, 0f, 6f, 6f, 5f, 0f, Color.rgb(220, 30, 30), Color.DKGRAY, Color.BLACK)
            } else { // Füze / Mermi
                draw3DBox(canvas, cx, cy, R, pr.x, pr.y, 4f, 6f, 2.2f, 2.2f, atan2(pr.vy, pr.vx), Color.rgb(255, 90, 20), Color.YELLOW, Color.RED)
            }
        }

        // Ağaç, Bina, Bariyer ve Rastgele Yol Engelleri
        for (obs in obstacles) {
            val dx = obs.x - carX; val dy = obs.y - carY
            if (dx * dx + dy * dy < 320000f) {
                when (obs.type) {
                    0 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
                    1 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(45, 50, 70), Color.rgb(28, 32, 48), Color.rgb(22, 25, 38))
                    2 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius * 0.6f, obs.height, 0f, Color.rgb(230, 40, 40), Color.WHITE, Color.rgb(180, 30, 30))
                    else -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0.5f, Color.rgb(255, 140, 0), Color.rgb(40, 40, 40), Color.rgb(200, 90, 0))
                }
            }
        }

        for (d in debrisPool) if (d.active) draw3DBox(canvas, cx, cy, R, d.x, d.y, d.z, d.size, d.size * 0.7f, d.size * 0.5f, d.yaw, d.color, d.color, Color.DKGRAY)

        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val gPose = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            drawSport3DCar(canvas, cx, cy, R, gPose.x, gPose.y, 0f, gPose.yaw, Color.rgb(0, 225, 255), selectedModel, isGhost = true)
        }

        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) {
                drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
            }
        }

        if (botsEnabled) {
            for (bot in bots) {
                drawSport3DCar(canvas, cx, cy, R, bot.x, bot.y, 0f, bot.yaw, bot.color, bot.modelIdx, isGhost = false, carRoll = bot.roll)
            }
        }

        // Oyuncu Aracı (Ön tekerlek ±28° dönüş ve Car3D rollAngle ile)
        val frontWheelAngle = (smoothedSteering * 1.4f).coerceIn(-0.49f, 0.49f)
        drawSport3DCar(canvas, cx, cy, R, carX, carY, carZ, carYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = true, carRoll = rollAngle, wheelSteerRad = frontWheelAngle)

        paint.style = Paint.Style.FILL
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== ORİJİNAL CROWN DRIFT FİZİĞİ, BOTLAR VE SÜRPRİZ KUTULAR ====================
    private fun updateBots(dt: Float, scale60: Float) {
        val total = trackPts.size
        val baseMaxSpeed = 285f
        for (bot in bots) {
            if (bot.spinTimer > 0f) {
                bot.spinTimer -= dt
                bot.yaw += 9f * dt
                bot.x += bot.vx * dt; bot.y += bot.vy * dt
                bot.vx *= 0.92f; bot.vy *= 0.92f
                continue
            }

            // Gerçekçi Yapay Zeka Hata Mekaniği (Virajı geniş alma / savrulma)
            bot.mistakeTimer -= dt
            if (bot.mistakeTimer <= 0f) {
                bot.mistakeTimer = 3.5f + Random.nextFloat() * 5.0f
                bot.targetLane = if (Random.nextFloat() < 0.30f) {
                    (if (Random.nextBoolean()) 1f else -1f) * (roadHalfWidth * 0.85f) // Hata: Viraj dışına savrulma
                } else {
                    (Random.nextFloat() - 0.5f) * 38f
                }
            }
            bot.laneOffset += (bot.targetLane - bot.laneOffset) * 2.2f * dt

            // Hedef Nokta Takibi
            val lookAheadIdx = (bot.wpIdx + 5) % total
            val targetPt = trackPts[lookAheadIdx]; val targetNm = trackNormals[lookAheadIdx]
            val tx = targetPt.x + targetNm.x * bot.laneOffset
            val ty = targetPt.y + targetNm.y * bot.laneOffset

            val desiredYaw = atan2(ty - bot.y, tx - bot.x)
            val diff = wrapAngle(desiredYaw - bot.yaw)
            bot.steer += (diff.coerceIn(-0.7f, 0.7f) - bot.steer) * 0.15f * scale60
            bot.yaw += bot.steer * 0.085f * scale60
            bot.roll += ((-bot.steer * 14f) - bot.roll) * 0.12f * scale60

            // Oyuncuyla Aynı Grip/Thrust Fiziği
            val botThrust = baseMaxSpeed * bot.baseSpeedMult * (if ( abs(bot.laneOffset) > roadHalfWidth) 0.65f else 1f)
            val botGrip = 0.024f * scale60
            bot.vx += (cos(bot.yaw) * botThrust - bot.vx) * botGrip
            bot.vy += (sin(bot.yaw) * botThrust - bot.vy) * botGrip
            bot.x += bot.vx * dt
            bot.y += bot.vy * dt

            // Botun En Yakın Yol İndeksi ve Tur İlerlemesi
            var bestD = Float.MAX_VALUE; var bestI = bot.wpIdx
            for (k in -8..12) {
                val idx = (bot.wpIdx + k + total) % total
                val dx = bot.x - trackPts[idx].x; val dy = bot.y - trackPts[idx].y
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; bestI = idx }
            }
            if (bot.wpIdx > (total * 3) / 4 && bestI < total / 4) bot.laps++
            bot.wpIdx = bestI
            val nextPt = trackPts[(bestI + 1) % total]
            val segFrac = (1f - (sqrt(bestD) / 60f)).coerceIn(0f, 0.99f)
            bot.totalProgress = bot.laps * total + bestI + segFrac
        }
    }

    private fun updateGamePhysics(dt: Float, now: Long) {
        garagePreviewYaw += dt * 2.2f
        if (shakeTime > 0f) shakeTime -= dt
        if (respawnBannerTimer > 0f) respawnBannerTimer -= dt
        if (invulnerableTimer > 0f) invulnerableTimer -= dt

        // 3.1 Turnuva Başlangıcı: 3 - 2 - 1 - GO! Geri Sayımı
        if (countdownVal >= 0) {
            countdownTimer -= dt
            if (countdownTimer <= 0f) {
                countdownVal--
                countdownTimer = if (countdownVal == 0) 0.65f else 0.85f
                triggerHaptic(if (countdownVal == 0) 70L else 40L, 220)
            }
            lapStartTime = now
            return
        }

        currentLapTime = (now - lapStartTime) / 1000f
        val scale60 = dt * 60f
        stepCounter++

        for (d in debrisPool) {
            if (!d.active) continue
            d.life -= dt
            if (d.life <= 0f) d.active = false else {
                d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt; d.vz -= 240f * dt
                if (d.z < 0.5f) { d.z = 0.5f; d.vz = -d.vz * 0.45f; d.vx *= 0.75f; d.vy *= 0.75f }
            }
        }

        for (p in particlePool) {
            if (!p.active) continue
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.size += 4.5f * dt; p.alpha -= (215 * dt).toInt()
            if (p.alpha <= 0) p.active = false
        }

        if (isCrashing) {
            crashTimer -= dt
            carZ += carVZ * dt; carVZ -= 235f * dt
            if (carZ < 0f) { carZ = 0f; carVZ = abs(carVZ) * 0.45f }
            if (crashTimer <= 0f) respawnOnRoad()
            return
        }

        // 6.5 Bezel / Crown Girişinin Adım Başına Yumuşatılması (crownPerStep)
        crownSteer = (crownSteer + crownAccum).coerceIn(-1f, 1f)
        crownAccum = 0f
        if (!isBezelDragging) crownSteer *= 0.90f

        // 6.1 Orijinal Car3D.steer() ve Car3D.update() Fiziği
        smoothedSteering += (crownSteer - smoothedSteering) * (0.14f * scale60).coerceAtMost(1f)
        if (speed > 8f) {
            carYaw += 0.052f * smoothedSteering * scale60
        }
        rollAngle += ((-smoothedSteering * 18f) - rollAngle) * (0.12f * scale60).coerceAtMost(1f)
        rollAngle *= 0.88f

        if (boostTime > 0f) boostTime -= dt
        if (activeItemTimer > 0f) {
            activeItemTimer -= dt
            if (activeItemTimer <= 0f) activeItem = 0
        }

        val baseMaxForward = when (selectedModel) { 0 -> 275f; 1 -> 290f; 2 -> 282f; else -> 305f }
        // Referans Car3D speedFactor: Boost = 1.22f, Çim (onGrass) = 0.6f, Kötü Kutu (5) = 0.55f
        val speedFactor = when {
            isFullStopped || isHoldingStop -> 0f
            boostTime > 0f -> 1.24f
            activeItem == 5 -> 0.55f
            isOffRoad -> 0.60f
            else -> 1.0f
        }
        val thrust = baseMaxForward * speedFactor
        val isIce = tracks[currentTrackIdx].theme == 3
        val baseGrip = if (isIce) 0.014f else 0.025f
        val grip = (if (isFullStopped) 0.12f else baseGrip) * scale60

        val fwdX = cos(carYaw); val fwdY = sin(carYaw)
        velX += (fwdX * thrust - velX) * grip.coerceAtMost(1f)
        velY += (fwdY * thrust - velY) * grip.coerceAtMost(1f)
        carX += velX * dt
        carY += velY * dt
        speed = hypot(velX, velY)

        // 6.1 Histerezisli Drift Tespiti (0.93f / 0.955f)
        val wasDrifting = isCurrentlyDrifting
        isCurrentlyDrifting = false
        driftAmount = 0f
        if (speed > 45f) {
            val alignment = (velX / speed) * fwdX + (velY / speed) * fwdY
            driftAmount = (1f - alignment).coerceIn(0f, 1f)
            val threshold = if (wasDrifting) 0.965f else 0.935f
            if (alignment < threshold) isCurrentlyDrifting = true
        }

        // 6.4 Kamera Hız Vektörü Harmanlaması (%55 Bakış + %45 Hız Yönü) ve Dinamik FOV/Uzaklık
        val velAngle = if (speed > 15f) atan2(velY, velX) else carYaw
        val targetCamYaw = carYaw + wrapAngle(velAngle - carYaw) * 0.45f
        val camSmooth = (1f - 0.85f.pow(scale60)).coerceIn(0.05f, 1f)
        camYaw += wrapAngle(targetCamYaw - camYaw) * camSmooth

        val zoom = (driftCombo - 1) * 3.5f + (if (boostTime > 0f) 10f else 0f)
        camDist += ((98f + zoom) - camDist) * camSmooth
        camHeight += ((138f + zoom * 0.7f) - camHeight) * camSmooth
        val targetFov = if (boostTime > 0f) 1.32f else 1.48f
        camFovFactor += (targetFov - camFovFactor) * camSmooth

        if (stepCounter % 2 == 0) currentLapRecording.add(GhostPose(carX, carY, carYaw))
        ghostFrameIdx = currentLapRecording.size - 1

        // 3.3 Sürpriz Kutuları Toplama ve Aktif Özellik Kullanımı
        for (mb in mysteryBoxes) {
            if (!mb.active) {
                mb.respawnTimer -= dt
                if (mb.respawnTimer <= 0f) mb.active = true
                continue
            }
            val dx = carX - mb.x; val dy = carY - mb.y
            if (dx * dx + dy * dy < 480f) {
                mb.active = false; mb.respawnTimer = 8.0f
                // 1=Füze, 2=Diken, 3=Kalkan, 4=Ön Silah, 5=Kötü Kutu (Zarar/Yavaşlatma)
                activeItem = Random.nextInt(1, 6)
                activeItemTimer = if (activeItem == 5) 3.5f else 7.5f
                if (activeItem == 2) { // Diken anında yola bırakılır
                    fireProjectile(2, carX, carY, carYaw, true)
                } else if (activeItem == 5) { // Kötü kutu sarsar ve yavaşlatır
                    velX *= 0.45f; velY *= 0.45f; shakeTime = 0.35f
                    spawnDebris(carX, carY, 4, Color.MAGENTA)
                }
                triggerHaptic(55L, 230)
            }
        }

        // Füze (Rakip görüş açısına girince otomatik ateşlenir) ve Ön Silah
        if (activeItem == 1 && botsEnabled) {
            for (bot in bots) {
                val dx = bot.x - carX; val dy = bot.y - carY
                val dist = hypot(dx, dy)
                val angDiff = abs(wrapAngle(atan2(dy, dx) - carYaw))
                if (dist < 420f && angDiff < 0.28f) {
                    fireProjectile(1, carX, carY, atan2(dy, dx), true)
                    activeItem = 0; activeItemTimer = 0f
                    triggerHaptic(65L, 250)
                    break
                }
            }
        } else if (activeItem == 4) {
            weaponFireTimer -= dt
            if (weaponFireTimer <= 0f) {
                weaponFireTimer = 0.45f
                fireProjectile(4, carX, carY, carYaw, true)
            }
        }

        // Mermi / Füze / Diken Hareketleri ve Bot Vuruşları
        for (pr in projectiles) {
            if (!pr.active) continue
            pr.life -= dt
            if (pr.life <= 0f) { pr.active = false; continue }
            pr.x += pr.vx * dt; pr.y += pr.vy * dt
            if (botsEnabled) {
                for (bot in bots) {
                    val dx = pr.x - bot.x; val dy = pr.y - bot.y
                    if (dx * dx + dy * dy < 360f) {
                        pr.active = false
                        bot.spinTimer = 1.6f; bot.vx *= 0.2f; bot.vy *= 0.2f
                        spawnDebris(bot.x, bot.y, 6, bot.color)
                        break
                    }
                }
            }
        }

        // Bot Güncellemesi ve Bot Çarpışmaları
        if (botsEnabled) {
            updateBots(dt, scale60)
            for (bot in bots) {
                val dx = carX - bot.x; val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 484f && distSq > 0.01f) {
                    val dist = sqrt(distSq); val pushAng = atan2(dy, dx); val overlap = 22f - dist
                    carX += cos(pushAng) * overlap * 0.6f; carY += sin(pushAng) * overlap * 0.6f
                    bot.vx -= cos(pushAng) * 120f; bot.vy -= sin(pushAng) * 120f
                    if (activeItem != 3 && invulnerableTimer <= 0f) {
                        carDamage = (carDamage + 8f).coerceAtMost(100f)
                        if (carDamage > 45f) bumperBroken = true
                    }
                    triggerHaptic(40L, 220)
                }
            }
        }

        // Engellere Çarpma (Kalkan veya Doğma Koruması Yoksa)
        if (invulnerableTimer <= 0f) {
            for (obs in obstacles) {
                val dx = carX - obs.x; val dy = carY - obs.y
                val minD = obs.radius + 10f
                if (dx * dx + dy * dy < minD * minD) {
                    val ang = atan2(dy, dx)
                    carX = obs.x + cos(ang) * minD; carY = obs.y + sin(ang) * minD
                    if (activeItem == 3) { // Kalkan engeli kırar, hasar almaz
                        activeItem = 0; activeItemTimer = 0f
                        triggerHaptic(45L, 200)
                        break
                    }
                    carDamage += speed * 0.25f
                    if (carDamage > 35f) bumperBroken = true
                    if (carDamage > 65f) spoilerBroken = true
                    if (speed > 205f || carDamage >= 100f) {
                        triggerExplosionAtSpot()
                        return
                    } else {
                        velX *= 0.4f; velY *= 0.4f
                        spawnDebris(carX, carY, 5, carColors[selectedColor])
                        triggerHaptic(75L, 255)
                    }
                }
            }
        }

        // Performans Optimizasyonlu En Yakın Yol Noktası Araması (Track3D.isOffRoad: ±18 penceresi)
        val total = trackPts.size
        var bestDistSq = Float.MAX_VALUE
        var bestIdx = currentWp
        for (k in -18..18) {
            val i = (currentWp + k + total) % total
            val dx = carX - trackPts[i].x; val dy = carY - trackPts[i].y
            val dSq = dx * dx + dy * dy
            if (dSq < bestDistSq) { bestDistSq = dSq; bestIdx = i }
        }
        if (bestDistSq > 16000f) { // Çok uzaktaysa tam tarama
            for (i in 0 until total) {
                val dx = carX - trackPts[i].x; val dy = carY - trackPts[i].y
                val dSq = dx * dx + dy * dy
                if (dSq < bestDistSq) { bestDistSq = dSq; bestIdx = i }
            }
        }
        val bestDist = sqrt(bestDistSq)

        // 6.2 & 6.3 OnGrass (%40 Yavaşlama + Kamera Sarsıntısı), Edge Bonus ve Drift Zone
        isOffRoad = bestDist > roadHalfWidth + 6f
        inZone = zoneMask.isNotEmpty() && zoneMask[bestIdx]
        edgeActive = isCurrentlyDrifting && !isOffRoad && bestDist > (roadHalfWidth - 14f)

        if (isOffRoad) {
            comboTime = 0f; driftCombo = 1; comboGap = 0f
            shakeTime = 0.15f
            if (speed > 50f && stepCounter % 4 == 0) {
                spawnParticle(carX - fwdX * 12f, carY - fwdY * 12f, 2f, Random.nextFloat() * 16f - 8f, Random.nextFloat() * 16f - 8f, 30f, 3.2f, Color.rgb(95, 75, 45), 190)
                triggerHaptic(12L, 95)
            }
        } else if (isCurrentlyDrifting) {
            comboGap = 0f; comboTime += dt
            val newMult = (comboTime / 3.0f).toInt().coerceAtMost(3) + 1
            if (newMult > driftCombo) triggerHaptic(25L, 200)
            driftCombo = newMult

            // CrownDriftGame Puan Formülü (Zone x2, Edge x1.5, Boost x2)
            val pts = driftCombo * 120f * dt * (if (inZone) 2f else 1f) * (if (edgeActive) 1.5f else 1f) * (if (boostTime > 0f) 2f else 1f)
            score += pts

            if (boostTime <= 0f && !boostReady) {
                boostMeter += (driftCombo * 0.045f + 0.06f) * dt
                if (boostMeter >= 1f) {
                    boostMeter = 1f; boostReady = true
                    triggerHaptic(60L, 240)
                }
            }
            // 6.5 Drift Şiddetine Duyarlı Haptik Titreşim (driftAmount * 70 + 6)
            if (stepCounter % 9 == 0) {
                val vibeMs = (driftAmount * 70f + 6f).toLong().coerceAtMost(28L)
                triggerHaptic(vibeMs, (110 + driftAmount * 140f).toInt())
            }
        } else {
            comboGap += dt
            if (comboGap > 0.35f) { comboTime = 0f; driftCombo = 1 }
        }

        // Lastik İzi ve 32'lik Duman Havuzu
        val backX = carX - fwdX * 12f; val backY = carY - fwdY * 12f
        val perpX = -fwdY * 7.0f; val perpY = fwdX * 7.0f
        val curLX = backX - perpX; val curLY = backY - perpY
        val curRX = backX + perpX; val curRY = backY + perpY

        if ((isCurrentlyDrifting || isOffRoad) && speed > 55f) {
            if (hasPrevSkid && hypot(curLX - prevBackLX, curLY - prevBackLY) < 42f) {
                val tm = tirePool[tireHead]
                tireHead = (tireHead + 1) % tirePool.size
                tm.active = true
                tm.lx1 = prevBackLX; tm.ly1 = prevBackLY; tm.lx2 = curLX; tm.ly2 = curLY
                tm.rx1 = prevBackRX; tm.ry1 = prevBackRY; tm.rx2 = curRX; tm.ry2 = curRY
                tm.baseColor = if (isOffRoad) Color.rgb(65, 45, 25) else Color.rgb(15, 15, 15)
                tm.strength = if (isOffRoad) 0.8f else (driftAmount * 3.5f).coerceIn(0.35f, 1f)
            }
            prevBackLX = curLX; prevBackLY = curLY; prevBackRX = curRX; prevBackRY = curRY
            hasPrevSkid = true

            if (stepCounter % 3 == 0) {
                val leftWheel = (stepCounter / 3) % 2 == 0
                val sx = if (leftWheel) curLX else curRX
                val sy = if (leftWheel) curLY else curRY
                val smokeCol = if (isOffRoad) Color.rgb(140, 115, 80) else if (tracks[currentTrackIdx].theme == 1) Color.rgb(140, 220, 255) else Color.rgb(225, 225, 230)
                spawnParticle(sx, sy, 2.5f, -velX * 0.15f, -velY * 0.15f, 22f, 3.8f, smokeCol, 155)
            }
        } else {
            hasPrevSkid = false
        }

        val outerFence = roadHalfWidth * 3.8f
        if (bestDist > outerFence) {
            val tp = trackPts[bestIdx]
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * outerFence; carY = tp.y + sin(angToCar) * outerFence
            velX *= 0.6f; velY *= 0.6f
        }

        // 1.1 Tur Sayacı ve Kesin Sıralama (Ranking) Hesaplaması
        if (bestIdx > maxWpReached && bestIdx - maxWpReached < total / 3) maxWpReached = bestIdx
        val segProgress = (1f - (bestDist / 80f)).coerceIn(0f, 0.99f)
        playerTotalProgress = (lap - 1) * total + bestIdx + segProgress

        var pos = 1
        if (botsEnabled) {
            for (bot in bots) if (bot.totalProgress > playerTotalProgress) pos++
        }
        racePosition = pos

        if (bestIdx < total / 10 && maxWpReached > (total * 3) / 4) {
            maxWpReached = 0
            if (currentLapTime < bestLapTime || bestLapGhost.isEmpty()) {
                bestLapTime = currentLapTime
                bestLapGhost = currentLapRecording.toList()
                prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply()
            }
            currentLapRecording.clear(); ghostFrameIdx = 0

            if (lap < totalLaps) {
                lap++; lapStartTime = now; triggerHaptic(55L, 255)
            } else {
                // Yarış bittiği an oyuncunun sırası kilitlenir! (1. bitirip 4. gösterme hatası çözüldü)
                lockedFinalRank = racePosition
                val finalScore = score.toInt()
                if (finalScore > prefs.getInt("best_score_$currentTrackIdx", 0)) {
                    isNewRecord = true
                    prefs.edit().putInt("best_score_$currentTrackIdx", finalScore).apply()
                }
                state = 4
            }
        }
        currentWp = bestIdx
    }

    // ==================== OYUN İÇİ HUD (YENİ DÜZEN) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // 1. Sol Üst: Kompakt Sıralama ("1/4") ve Tur/Puan Göstergesi (Büyük balon kaldırıldı)
        val totalRacers = if (botsEnabled) bots.size + 1 else 1
        val posKey = racePosition * 10 + totalRacers
        if (posKey != cachedPosKey) { cachedPosKey = posKey; cachedPosStr = "$racePosition/$totalRacers" }
        val sInt = score.toInt()
        if (sInt != cachedScoreInt) { cachedScoreInt = sInt; cachedScoreStr = "$sInt" }

        tempRect.set(cx - R * 0.56f, cy - R * 0.66f, cx - R * 0.18f, cy - R * 0.40f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(175, 12, 18, 26)
        canvas.drawRoundRect(tempRect, R * 0.06f, R * 0.06f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(0, 230, 255); paint.textSize = R * 0.095f
        canvas.drawText(cachedPosStr, tempRect.centerX(), cy - R * 0.53f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.056f
        canvas.drawText("L$lap/$totalLaps • $cachedScoreStr", tempRect.centerX(), cy - R * 0.43f, paint)

        // Çarpan Rozeti (x2, x3, x4)
        if (driftCombo > 1) {
            paint.color = Color.rgb(255, 45, 115)
            canvas.drawCircle(cx - R * 0.12f, cy - R * 0.44f, R * 0.055f, paint)
            paint.color = Color.WHITE; paint.textSize = R * 0.052f
            canvas.drawText("x$driftCombo", cx - R * 0.12f, cy - R * 0.42f, paint)
        }

        // Güvenli ÇIKIŞ (ÇIK) Butonu (Sol üst kutunun sağında)
        val exitBtnX = cx - R * 0.04f; val exitBtnY = cy - R * 0.56f
        paint.color = Color.argb(195, 185, 35, 45)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.085f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.048f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.018f, paint)

        // 2. Sağ Kenar: Mini Harita Balonu ve Hemen Üstünde DUR (Pause) Butonu! (Arabayı kapatmaz)
        val mapCX = cx + R * 0.42f; val mapCY = cy - R * 0.28f; val mapRad = R * 0.17f
        val stopBtnX = cx + R * 0.36f; val stopBtnY = cy - R * 0.62f
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(200, 215, 50, 50)
        canvas.drawCircle(stopBtnX, stopBtnY, R * 0.095f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.050f
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.018f, paint)

        paint.style = Paint.Style.FILL; paint.color = Color.argb(170, 12, 18, 24)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.argb(120, 0, 225, 255)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)

        val miniScale = mapRad / 1850f
        miniPath.reset()
        for (i in trackPts.indices step 4) {
            val mx = mapCX + trackPts[i].x * miniScale; val my = mapCY + trackPts[i].y * miniScale
            if (i == 0) miniPath.moveTo(mx, my) else miniPath.lineTo(mx, my)
        }
        miniPath.close()
        paint.strokeWidth = 2.4f; paint.color = Color.rgb(120, 135, 150)
        canvas.drawPath(miniPath, paint)

        paint.style = Paint.Style.FILL
        if (botsEnabled) {
            for (bot in bots) {
                paint.color = bot.color
                canvas.drawCircle(mapCX + bot.x * miniScale, mapCY + bot.y * miniScale, R * 0.018f, paint)
            }
        }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + carX * miniScale, mapCY + carY * miniScale, R * 0.024f, paint)

        // 3. Sol Kenar: TURBO (Boost) İkonu ve Altında Aktif Özellik Simgesi
        val turboX = cx - R * 0.56f; val turboY = cy - R * 0.08f; val turboRad = R * 0.115f
        paint.style = Paint.Style.FILL
        paint.color = if (boostTime > 0f) Color.rgb(255, 140, 0) else if (boostReady) Color.rgb(0, 225, 255) else Color.argb(175, 20, 28, 36)
        canvas.drawCircle(turboX, turboY, turboRad, paint)

        // Turbo Dolum Halkası
        paint.style = Paint.Style.STROKE; paint.strokeWidth = R * 0.022f
        paint.color = Color.rgb(0, 225, 255)
        tempRect.set(turboX - turboRad, turboY - turboRad, turboX + turboRad, turboY + turboRad)
        val fillSweep = (if (boostTime > 0f || boostReady) 1f else boostMeter) * 360f
        canvas.drawArc(tempRect, -90f, fillSweep, false, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (boostReady || boostTime > 0f) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.046f
        canvas.drawText(if (boostReady) "TURBO!" else "BOOST", turboX, turboY + R * 0.016f, paint)

        // Turbo Butonunun Altında Aktif Sürpriz Kutu Özelliği Simgesi
        if (activeItem > 0) {
            val itemX = turboX; val itemY = turboY + R * 0.24f
            paint.color = if (activeItem == 5) Color.rgb(200, 30, 60) else Color.argb(210, 35, 45, 60)
            canvas.drawCircle(itemX, itemY, R * 0.095f, paint)
            paint.color = Color.rgb(255, 215, 25); paint.textSize = R * 0.044f
            val itemLabel = when (activeItem) {
                1 -> "FÜZE"; 2 -> "DİKEN"; 3 -> "KALKAN"; 4 -> "SİLAH"; else -> "YAVAŞ!"
            }
            canvas.drawText(itemLabel, itemX, itemY + R * 0.015f, paint)
        }

        // Drift Zone / Edge Bonus Rozeti
        if (boostTime > 0f || (isCurrentlyDrifting && (inZone || edgeActive))) {
            val badgeText = when {
                boostTime > 0f -> "TURBO x2"
                inZone -> "DRIFT ZONE x2"
                else -> "EDGE BONUS x1.5"
            }
            paint.color = if (inZone) Color.rgb(255, 195, 10) else Color.rgb(0, 225, 255)
            paint.textSize = R * 0.055f
            canvas.drawText(badgeText, cx, cy - R * 0.24f, paint)
        }

        // 4. Turnuva Başlangıcı 3-2-1-GO! veya Patlama / Yeniden Doğma Bildirimi
        if (countdownVal >= 0) {
            paint.color = Color.argb(165, 10, 14, 20)
            canvas.drawCircle(cx, cy, R * 0.28f, paint)
            paint.color = if (countdownVal == 0) Color.rgb(70, 250, 120) else Color.rgb(255, 210, 35)
            paint.textSize = R * 0.22f
            canvas.drawText(if (countdownVal > 0) "$countdownVal" else "GO!", cx, cy + R * 0.08f, paint)
        } else if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55); paint.textSize = R * 0.082f
            canvas.drawText("CANLANIYOR: ${String.format("%.1fs", crashTimer.coerceAtLeast(0f))}", cx, cy - R * 0.06f, paint)
        } else if (respawnBannerTimer > 0f) {
            paint.color = Color.rgb(80, 255, 140); paint.textSize = R * 0.070f
            canvas.drawText("YOLDA DOĞDU!", cx, cy - R * 0.06f, paint)
        }

        // 5. Alt Kenar 19 Noktalı Crown Direksiyon & Boost Yay Göstergesi
        val totalDots = 19; val centerDot = totalDots / 2
        val activeDotOffset = (smoothedSteering * centerDot).toInt().coerceIn(-centerDot, centerDot)
        for (i in 0 until totalDots) {
            val offset = i - centerDot
            val rad = Math.toRadians((90f - offset * 3.4f).toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f)
            val dy = cy + sin(rad).toFloat() * (R * 0.88f)
            val isLit = (activeDotOffset >= 0 && offset in 0..activeDotOffset) || (activeDotOffset < 0 && offset in activeDotOffset..0) || offset == 0
            paint.color = if (isLit) Color.rgb(0, 225, 255) else Color.argb(120, 70, 80, 90)
            canvas.drawCircle(dx, dy, R * 0.017f, paint)
        }
    }

    // ==================== MENÜLER VE BİTİŞ EKRANI ====================
    private fun drawCoopLobby(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(0, 225, 255); paint.textSize = R * 0.10f
        canvas.drawText("CO-OP ODA (4P)", cx, cy - R * 0.56f, paint)

        tempRect.set(cx - R * 0.68f, cy - R * 0.42f, cx - R * 0.04f, cy - R * 0.16f)
        paint.color = if (lobbyRole == 1) Color.rgb(0, 225, 255) else Color.rgb(26, 34, 44)
        canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = if (lobbyRole == 1) Color.BLACK else Color.WHITE; paint.textSize = R * 0.068f
        canvas.drawText("ODA KUR", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        tempRect.set(cx + R * 0.04f, cy - R * 0.42f, cx + R * 0.68f, cy - R * 0.16f)
        paint.color = if (lobbyRole == 2) Color.rgb(0, 225, 255) else Color.rgb(26, 34, 44)
        canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = if (lobbyRole == 2) Color.BLACK else Color.WHITE
        canvas.drawText("TARA & KATIL", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        paint.color = Color.rgb(255, 205, 40); paint.textSize = R * 0.062f
        canvas.drawText(lobbyStatusText, cx, cy - R * 0.04f, paint)

        for (i in 0..3) {
            val sy = cy + R * (0.07f + i * 0.09f)
            val isOccupied = if (i == 0) true else remotePlayers[i - 1].active
            val mName = if (i == 0) carModels[myAssignedModel] else carModels[remotePlayers[i - 1].modelIdx]
            val slotName = if (i == 0) "1. SEN [$mName]" else "${i + 1}. ${if (isOccupied) "${remotePlayers[i - 1].name} [$mName]" else "Araniyor..."}"
            paint.color = if (isOccupied) Color.rgb(75, 245, 130) else Color.rgb(110, 120, 135)
            paint.textSize = R * 0.058f
            canvas.drawText(slotName, cx, sy, paint)
        }

        tempRect.set(cx - R * 0.46f, cy + R * 0.44f, cx + R * 0.46f, cy + R * 0.68f)
        paint.color = if (lobbyRole == 2) Color.rgb(45, 55, 65) else Color.rgb(0, 225, 255)
        canvas.drawRoundRect(tempRect, R * 0.12f, R * 0.12f, paint)
        paint.color = if (lobbyRole == 2) Color.LTGRAY else Color.BLACK; paint.textSize = R * 0.082f
        canvas.drawText(if (lobbyRole == 2) "BEKLENİYOR" else "BAŞLAT", cx, tempRect.centerY() + R * 0.03f, paint)

        paint.color = Color.rgb(160, 175, 190); paint.textSize = R * 0.072f
        canvas.drawText("GERİ", cx, cy + R * 0.82f, paint)
    }

    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        arcPath.reset()
        tempRect.set(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f)
        arcPath.addArc(tempRect, 222f, 96f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 200, 210)
        paint.textSize = R * 0.095f; paint.textAlign = Paint.Align.CENTER; paint.letterSpacing = 0.18f
        canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint)
        paint.letterSpacing = 0f

        val mapScale = R * 0.00016f
        miniPath.reset()
        for (i in trackPts.indices step 2) {
            val px = cx + trackPts[i].x * mapScale; val py = cy - R * 0.16f + trackPts[i].y * mapScale
            if (i == 0) miniPath.moveTo(px, py) else miniPath.lineTo(px, py)
        }
        miniPath.close()

        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = R * 0.042f
        paint.color = if (tracks[currentTrackIdx].theme == 1) Color.rgb(0, 225, 255) else Color.WHITE
        canvas.drawPath(miniPath, paint)

        paint.style = Paint.Style.FILL; paint.color = Color.rgb(150, 160, 175); paint.textSize = R * 0.14f
        canvas.drawText("<", cx - R * 0.68f, cy - R * 0.10f, paint)
        canvas.drawText(">", cx + R * 0.68f, cy - R * 0.10f, paint)

        paint.color = Color.WHITE; paint.textSize = R * 0.13f
        canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.22f, paint)

        tempRect.set(cx - R * 0.34f, cy + R * 0.32f, cx + R * 0.34f, cy + R * 0.48f)
        paint.color = Color.rgb(25, 42, 55); canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = Color.rgb(0, 240, 220); paint.textSize = R * 0.072f
        canvas.drawText("CO-OP ODA (4P)", cx, tempRect.centerY() + R * 0.025f, paint)

        val gx = cx - R * 0.60f; val gy = cy + R * 0.32f
        paint.color = Color.rgb(28, 36, 44); canvas.drawCircle(gx, gy, R * 0.14f, paint)
        paint.color = Color.WHITE; canvas.drawRoundRect(gx - R * 0.055f, gy - R * 0.04f, gx + R * 0.055f, gy + R * 0.04f, 6f, 6f, paint)

        val sx = cx + R * 0.60f; val sy = cy + R * 0.32f
        paint.color = Color.rgb(28, 36, 44); canvas.drawCircle(sx, sy, R * 0.14f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.12f; canvas.drawText("⚙", sx, sy + R * 0.04f, paint)

        paint.color = Color.rgb(0, 225, 255)
        tempRect.set(cx - R * 0.46f, cy + R * 0.54f, cx + R * 0.46f, cy + R * 0.79f)
        canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28); paint.textSize = R * 0.125f
        canvas.drawText("START", cx, cy + R * 0.71f, paint)
    }

    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float, dt: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = R * 0.14f
        canvas.drawText("Garage", cx, cy - R * 0.58f, paint)

        garagePreviewYaw += dt * 1.1f
        val savedX = carX; val savedY = carY; val savedCam = camYaw
        carX = 0f; carY = 0f; camYaw = 0f
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, carColors[selectedColor], selectedModel, isGhost = false)
        carX = savedX; carY = savedY; camYaw = savedCam

        tempRect.set(cx - R * 0.56f, cy - R * 0.07f, cx - R * 0.04f, cy + R * 0.11f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.BLACK; paint.textSize = R * 0.074f
        canvas.drawText(carModels[selectedModel], tempRect.centerX(), tempRect.centerY() + R * 0.028f, paint)

        tempRect.set(cx + R * 0.04f, cy - R * 0.07f, cx + R * 0.56f, cy + R * 0.11f)
        paint.color = Color.rgb(24, 30, 38); canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawText(carModels[(selectedModel + 1) % carModels.size], tempRect.centerX(), tempRect.centerY() + R * 0.028f, paint)

        for (i in 0 until 8) {
            val dotX = cx + ((i % 4) - 1.5f) * (R * 0.28f)
            val dotY = cy + R * 0.27f + (i / 4) * (R * 0.24f)
            if (i == selectedColor) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = R * 0.025f; paint.color = Color.rgb(0, 225, 255)
                canvas.drawCircle(dotX, dotY, R * 0.105f, paint)
            }
            paint.style = Paint.Style.FILL; paint.color = carColors[i]
            canvas.drawCircle(dotX, dotY, R * 0.082f, paint)
        }
        paint.color = Color.rgb(150, 165, 180); paint.textSize = R * 0.09f
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
        paint.color = Color.rgb(22, 28, 36); canvas.drawRoundRect(tempRect, R * 0.1f, R * 0.1f, paint)
        paint.color = if (showFps) Color.rgb(0, 225, 255) else Color.WHITE
        paint.textAlign = Paint.Align.CENTER; paint.textSize = R * 0.075f
        canvas.drawText("SHOW FPS", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        tempRect.set(cx + R * 0.03f, cy + R * 0.50f, cx + R * 0.48f, cy + R * 0.70f)
        paint.color = Color.rgb(22, 28, 36); canvas.drawRoundRect(tempRect, R * 0.1f, R * 0.1f, paint)
        paint.color = Color.WHITE
        canvas.drawText("BACK", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)
    }

    private fun drawSettingRow(canvas: Canvas, cx: Float, y: Float, R: Float, wRatio: Float, label: String, valueText: String, toggleVal: Boolean) {
        tempRect.set(cx - R * wRatio, y - R * 0.11f, cx + R * wRatio, y + R * 0.11f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(20, 26, 34)
        canvas.drawRoundRect(tempRect, R * 0.11f, R * 0.11f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.080f; paint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, tempRect.left + R * 0.10f, y + R * 0.03f, paint)

        if (valueText.isNotEmpty()) {
            paint.color = Color.rgb(0, 200, 230); paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(valueText, tempRect.right - R * 0.10f, y + R * 0.03f, paint)
        } else {
            val swRight = tempRect.right - R * 0.08f; val swLeft = swRight - R * 0.20f
            paint.color = if (toggleVal) Color.rgb(0, 180, 215) else Color.rgb(45, 55, 65)
            canvas.drawRoundRect(swLeft, y - R * 0.055f, swRight, y + R * 0.055f, R * 0.055f, R * 0.055f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(if (toggleVal) swRight - R * 0.055f else swLeft + R * 0.055f, y, R * 0.042f, paint)
        }
    }

    private fun drawWinScreen(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.argb(215, 10, 16, 20))
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        val finalPos = if (lockedFinalRank > 0) lockedFinalRank else racePosition
        paint.color = if (finalPos == 1) Color.rgb(80, 235, 155) else Color.rgb(255, 195, 40)
        paint.textSize = R * 0.15f
        val winTitle = if (botsEnabled && finalPos > 1) "FINISHED #$finalPos/4" else "1ST PLACE!"
        canvas.drawText(winTitle, cx, cy - R * 0.52f, paint)

        if (isNewRecord) {
            tempRect.set(cx - R * 0.36f, cy - R * 0.45f, cx + R * 0.36f, cy - R * 0.32f)
            paint.color = Color.rgb(245, 190, 25); canvas.drawRoundRect(tempRect, R * 0.065f, R * 0.065f, paint)
            paint.color = Color.rgb(20, 20, 20); paint.textSize = R * 0.075f
            canvas.drawText("NEW RECORD!", cx, tempRect.centerY() + R * 0.026f, paint)
        }

        paint.color = Color.WHITE; paint.textSize = R * 0.24f
        canvas.drawText("${score.toInt()}", cx, cy - R * 0.08f, paint)

        paint.color = Color.rgb(175, 185, 195); paint.textSize = R * 0.068f
        canvas.drawText("GOLD ${tracks[currentTrackIdx].goldScore} • BEST ${String.format("%.2fs", bestLapTime)}", cx, cy + R * 0.20f, paint)

        tempRect.set(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28); paint.textSize = R * 0.11f
        canvas.drawText("RESTART", cx, tempRect.centerY() + R * 0.04f, paint)

        paint.color = Color.rgb(160, 175, 190); paint.textSize = R * 0.085f
        canvas.drawText("MENU", cx, cy + R * 0.68f, paint)
    }
}
