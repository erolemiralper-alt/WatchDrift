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
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
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
data class GhostPose(val x: Float, val y: Float, val yaw: Float, val roll: Float)

data class RemotePlayer(
    var active: Boolean = false, var name: String = "Bos",
    var x: Float = 0f, var y: Float = 0f, var yaw: Float = 0f,
    var colorIdx: Int = 7, var modelIdx: Int = 1
)

data class BotCar(
    var wpFloat: Float, var laneOffset: Float, var targetLane: Float,
    val baseSpeed: Float, val color: Int, val modelIdx: Int,
    var x: Float = 0f, var y: Float = 0f, var yaw: Float = 0f, var roll: Float = 0f,
    var vx: Float = 0f, var vy: Float = 0f, var spinTimer: Float = 0f, var errSeed: Float = 0f
)

// type: 0=Ağaç, 1=Bina, 2=Sabit Bariyer, 3=Yol İçi Rastgele Engel
data class Obstacle3D(val x: Float, val y: Float, val radius: Float, val height: Float, val type: Int, val yaw: Float = 0f)
data class MysteryBox(var x: Float, var y: Float, var active: Boolean = true, var respawnTimer: Float = 0f)
// type: 1=Füze, 2=Diken
class Projectile { var active = false; var type = 1; var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var yaw = 0f; var life = 0f }
class DebrisPart { var active = false; var x = 0f; var y = 0f; var z = 0f; var vx = 0f; var vy = 0f; var vz = 0f; var yaw = 0f; var size = 3f; var color = 0; var life = 0f }
class Particle3D { var active = false; var x = 0f; var y = 0f; var z = 0f; var vx = 0f; var vy = 0f; var vz = 0f; var size = 3f; var alpha = 0; var color = 0 }
class TireMark { var active = false; var lx1 = 0f; var ly1 = 0f; var lx2 = 0f; var ly2 = 0f; var rx1 = 0f; var ry1 = 0f; var rx2 = 0f; var ry2 = 0f; var strength = 1f; var isGrass = false }
data class TrackData(val name: String, val theme: Int, val goldScore: Int, val controlPoints: List<Vec2>)

class CrownDriftView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("crowndrift_v7", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val polyPath = Path()
    private val arcPath = Path()
    private val miniPath = Path()
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
    private val obstacles = ArrayList<Obstacle3D>(64)
    private val mysteryBoxes = ArrayList<MysteryBox>(12)
    private val projectiles = Array(10) { Projectile() }
    private var projHead = 0
    private val roadHalfWidth = 58f

    // Referans CrownDrift Fiziği Değişkenleri (Car3D & CrownDriftGame)
    private var carX = 0f; private var carY = 0f; private var carZ = 0f; private var carVZ = 0f
    private var carYaw = 0f; private var rollAngle = 0f
    private var velocityX = 0f; private var velocityY = 0f
    private var smoothedSteering = 0f
    private var isCurrentlyDrifting = false
    private var driftAmount = 0f; private var skidIntensity = 0f
    private var isOffRoad = false; private var edgeActive = false; private var inDriftZone = false
    private var isFullStopped = false; private var isHoldingStop = false
    private var crownAccum = 0f; private var touchSteer = 0f
    private var isBezelDragging = false; private var lastBezelAngle = 0f

    // Kamera & Sarsıntı
    private var camX = 0f; private var camY = 0f; private var camYaw = 0f
    private var camFovFactor = 1.48f; private var camDistOffset = 98f; private var shakeTime = 0f

    // Geri Sayım, Turbo (Boost) ve Sürpriz Kutu Güçleri
    private var countdownValue = 3; private var countdownTimer = 0f
    private var boostMeter = 0f; private var boostReady = false; private var boostTime = 0f
    // activePower: 0=Yok, 1=Füze, 2=Diken, 3=Kalkan, 4=Ön Silah, 5=Kötü Kutu(Yavaşlatma)
    private var activePower = 0; private var powerTimer = 0f; private var powerCooldown = 0f

    // Hasar ve En Yakın Yolda Yeniden Doğma (3 sn)
    private var carDamage = 0f; private var spoilerBroken = false; private var bumperBroken = false
    private var isCrashing = false; private var crashTimer = 0f; private var respawnBannerTimer = 0f

    // Havuzlar (Zero-GC Object Pooling)
    private val debrisPool = Array(24) { DebrisPart() }; private var debrisHead = 0
    private val particlePool = Array(32) { Particle3D() }; private var particleHead = 0
    private val tirePool = Array(160) { TireMark() }; private var tireHead = 0; private var tireCount = 0
    private var prevBackLX = 0f; private var prevBackLY = 0f; private var prevBackRX = 0f; private var prevBackRY = 0f
    private var hasPrevSkid = false

    // Yarış, Skor ve Kombo
    private var lap = 1; private val totalLaps = 3
    private var currentWp = 0; private var maxWpReached = 0
    private var playerProgress = 0f; private var racePosition = 1; private var finalRank = 1
    private var lapStartTime = 0L; private var currentLapTime = 0f; private var bestLapTime = 99.99f
    private var score = 0f; private var comboTime = 0f; private var comboGap = 0f; private var multiplier = 1
    private var isNewRecord = false

    // HUD Önbelleği (String tahsisini önlemek için)
    private var hudLapKey = -1; private var hudLapText = ""
    private var hudPosKey = -1; private var hudPosText = "1/4"
    private var hudScoreKey = -1; private var hudScoreText = "0"

    private val currentLapRecording = ArrayList<GhostPose>(1200)
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
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
                if (dev?.address != null && triedAddresses.add(dev.address)) tryConnectToDiscoveredDevice(dev)
            }
        }
    }
    private var receiverRegistered = false

    private var projX = 0f; private var projY = 0f; private var projScale = 0f
    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60; private var hapticTimer = 0f; private var stepCounter = 0

    init {
        isFocusable = true; isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        myAssignedModel = selectedModel
        buildTrackSpline(0)
        try { context.registerReceiver(btDiscoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND)); receiverRegistered = true } catch (_: Throwable) {}
    }

    fun cleanup() {
        stopAllBluetooth()
        if (receiverRegistered) { try { context.unregisterReceiver(btDiscoveryReceiver) } catch (_: Throwable) {}; receiverRegistered = false }
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
        try { clientSocket?.close() } catch (_: Throwable) {}; clientSocket = null
        triedAddresses.clear()
        for (rp in remotePlayers) rp.active = false
    }

    private fun startHostLobby() {
        stopAllBluetooth(); lobbyRole = 1; myAssignedModel = selectedModel; btRunning = true
        lobbyStatusText = "Oda Acik (1/4) Bekleniyor"
        try { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } catch (_: Throwable) {}
        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (adapter == null || !adapter.isEnabled) { lobbyStatusText = "BT Kapali!"; postInvalidate(); return@thread }
                btServerSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("CrownDriftRoom", btUuid)
                while (btRunning && lobbyRole == 1 && hostClientSockets.size < 3) {
                    val sock = btServerSocket?.accept() ?: break
                    val slot = hostClientSockets.size
                    if (slot < 3) {
                        hostClientSockets.add(sock)
                        remotePlayers[slot].apply {
                            active = true; modelIdx = (selectedModel + slot + 1) % 4
                            name = try { sock.remoteDevice?.name ?: "Saat ${slot + 2}" } catch (_: SecurityException) { "Saat ${slot + 2}" }
                        }
                        lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4 Oyuncu"
                        vibeClick(); postInvalidate()
                        handleHostClient(sock, slot)
                    } else sock.close()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun handleHostClient(sock: BluetoothSocket, slot: Int) {
        thread(start = true, isDaemon = true) {
            try {
                val inp = DataInputStream(sock.inputStream)
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    remotePlayers[slot].x = inp.readFloat(); remotePlayers[slot].y = inp.readFloat()
                    remotePlayers[slot].yaw = inp.readFloat(); remotePlayers[slot].colorIdx = inp.readInt().coerceIn(0, 7)
                    remotePlayers[slot].modelIdx = inp.readInt().coerceIn(0, 3)
                }
            } catch (_: Throwable) {
                remotePlayers[slot].active = false; hostClientSockets.remove(sock)
                lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4 Oyuncu"; postInvalidate()
            }
        }
        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream)
                val assignedModel = (selectedModel + slot + 1) % 4
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    out.writeInt(state); out.writeInt(currentTrackIdx); out.writeInt(hostClientSockets.size + 1); out.writeInt(assignedModel)
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
        stopAllBluetooth(); lobbyRole = 2; btRunning = true; lobbyStatusText = "Cevre Taraniyor..."; invalidate()
        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (adapter == null || !adapter.isEnabled) { lobbyStatusText = "BT Kapali!"; postInvalidate(); return@thread }
                val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
                bonded?.forEach { dev ->
                    if (btRunning && lobbyRole == 2 && clientSocket == null) { triedAddresses.add(dev.address); tryConnectToDiscoveredDevice(dev) }
                }
                try { if (adapter.isDiscovering) adapter.cancelDiscovery(); adapter.startDiscovery() } catch (_: SecurityException) {}
            } catch (_: Throwable) {}
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
                lobbyStatusText = "Baglandi! Start Bekleniyor"; vibeClick(); postInvalidate()
                handleClientConn(sock)
            } catch (_: Throwable) {}
        }
    }

    private fun handleClientConn(sock: BluetoothSocket) {
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
                val hostState = inp.readInt(); val hostTrack = inp.readInt().coerceIn(0, tracks.size - 1)
                val totalP = inp.readInt(); val assignedM = inp.readInt().coerceIn(0, 3)
                myAssignedModel = assignedM; selectedModel = assignedM
                lobbyStatusText = "Odada $totalP/4 Oyuncu (${carModels[assignedM]})"
                remotePlayers[0].apply { active = true; x = inp.readFloat(); y = inp.readFloat(); yaw = inp.readFloat(); colorIdx = inp.readInt().coerceIn(0, 7); modelIdx = inp.readInt().coerceIn(0, 3) }
                for (s in 1..2) remotePlayers[s].apply { active = inp.readBoolean(); x = inp.readFloat(); y = inp.readFloat(); yaw = inp.readFloat(); colorIdx = inp.readInt().coerceIn(0, 7); modelIdx = inp.readInt().coerceIn(0, 3) }
                if (hostState == 3 && state != 3) { currentTrackIdx = hostTrack; post { startRace(true) } }
                postInvalidate()
            }
        } catch (_: Throwable) { remotePlayers[0].active = false; clientSocket = null; lobbyStatusText = "Baglanti Koptu"; postInvalidate() }
    }

    private fun triggerHaptic(durationMs: Long, amplitude: Int = 180) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibeClick() = triggerHaptic(20L, 180)

    // ==================== CROWN & DOKUNMATİK KONTROLLER ====================
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
            3 -> if (countdownValue < 0) {
                crownAccum += delta * 0.42f
                triggerHaptic(5L, 95)
            }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && handleRotaryScroll(event)) return true
        return super.onGenericMotionEvent(event)
    }

    // ==================== PİST, DRIFT ZONE, SÜRPRİZ KUTU VE ENGELLER ====================
    private fun computeDriftZones() {
        val n = trackPts.size
        zoneMask = BooleanArray(n)
        if (n < 40) return
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val p0 = trackPts[(i - 1 + n) % n]; val p1 = trackPts[i]; val p2 = trackPts[(i + 1) % n]
            val d = wrapAngle(atan2(p2.y - p1.y, p2.x - p1.x) - atan2(p1.y - p0.y, p1.x - p0.x))
            turn[i] = abs(d)
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
            for (i in (n / 10) until (n - n / 10)) {
                if (!used[i] && (best < 0 || smooth[i] > smooth[best])) best = i
            }
            if (best < 0) break
            for (k in -w..w) zoneMask[(best + k + n) % n] = true
            for (k in -(n / 4)..(n / 4)) used[(best + k + n) % n] = true
        }
    }

    private fun buildTrackSpline(idx: Int) {
        trackPts.clear(); trackNormals.clear(); trackTangents.clear(); obstacles.clear(); mysteryBoxes.clear()
        val cps = tracks[idx].controlPoints
        val n = cps.size
        val stepsPerSeg = 24
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
            val tang = atan2(dy, dx)
            trackTangents.add(tang)

            // Çimdeki Ağaç ve Binalar
            if (i % 16 == 0) {
                val side = if ((i / 16) % 2 == 0) 1f else -1f
                val dist = roadHalfWidth + 68f + (i % 35)
                val isTree = (i / 16) % 3 != 0
                obstacles.add(Obstacle3D(trackPts[i].x + nx * side * dist, trackPts[i].y + ny * side * dist, if (isTree) 16f else 26f, if (isTree) 34f else 48f, if (isTree) 0 else 1))
            }
            // Kenar Sabit Bariyerler
            if (i > 20 && i % 28 == 0) {
                val side = if ((i / 28) % 2 == 0) 1f else -1f
                val bDist = roadHalfWidth + 11f
                obstacles.add(Obstacle3D(trackPts[i].x + nx * side * bDist, trackPts[i].y + ny * side * bDist, 10f, 12f, 2, tang))
            }
            // Sürpriz Kutular (Yolda)
            if (i > 18 && i % 36 == 0) {
                val lane = ((i / 36) % 3 - 1) * 20f
                mysteryBoxes.add(MysteryBox(trackPts[i].x + nx * lane, trackPts[i].y + ny * lane))
            }
        }
        computeDriftZones()
    }

    private fun spawnRandomRoadObstacles() {
        obstacles.removeAll { it.type == 3 }
        val total = trackPts.size
        for (k in 0 until 8) {
            val idx = Random.nextInt(25, total - 15)
            val lane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.15f)
            val p = trackPts[idx]; val n = trackNormals[idx]
            obstacles.add(Obstacle3D(p.x + n.x * lane, p.y + n.y * lane, 9.5f, 13f, 3, trackTangents[idx]))
        }
    }

    private fun startRace(fromNetwork: Boolean = false) {
        buildTrackSpline(currentTrackIdx)
        spawnRandomRoadObstacles()

        val p0 = trackPts[0]; val n0 = trackNormals[0]
        val startOffset = if (lobbyRole == 2) 16f else if (lobbyRole == 1) -16f else 0f
        carX = p0.x + n0.x * startOffset; carY = p0.y + n0.y * startOffset; carZ = 0f; carVZ = 0f
        carYaw = trackTangents[0]; rollAngle = 0f; smoothedSteering = 0f
        velocityX = cos(carYaw) * 0.5f; velocityY = sin(carYaw) * 0.5f
        camX = carX; camY = carY; camYaw = carYaw; camFovFactor = 1.48f; camDistOffset = 98f

        isOffRoad = false; isCurrentlyDrifting = false; driftAmount = 0f; skidIntensity = 0f
        isFullStopped = false; isHoldingStop = false; crownAccum = 0f; touchSteer = 0f; isBezelDragging = false

        // 3 - 2 - 1 Başlangıç Geri Sayımı
        countdownValue = 3; countdownTimer = 0.65f
        boostMeter = 0f; boostReady = false; boostTime = 0f
        activePower = 0; powerTimer = 0f; powerCooldown = 0f

        carDamage = 0f; spoilerBroken = false; bumperBroken = false
        isCrashing = false; crashTimer = 0f; respawnBannerTimer = 0f

        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (tm in tirePool) tm.active = false
        for (pr in projectiles) pr.active = false
        tireHead = 0; tireCount = 0; hasPrevSkid = false
        currentLapRecording.clear(); ghostFrameIdx = 0

        // Hızlandırılmış ve Oyuncu Fiziğine Yakın 3 Yapay Zeka Botu (Toplam 4 Araç)
        bots.clear()
        if (botsEnabled) {
            val colors = intArrayOf(Color.rgb(255, 195, 20), Color.rgb(235, 45, 90), Color.rgb(60, 220, 110))
            for (b in 0..2) {
                val wpStart = (b + 1) * 2.5f
                val idx = wpStart.toInt()
                val lane = if (b % 2 == 0) -18f else 18f
                val bx = trackPts[idx].x + trackNormals[idx].x * lane
                val by = trackPts[idx].y + trackNormals[idx].y * lane
                val byaw = trackTangents[idx]
                bots.add(BotCar(
                    wpFloat = wpStart, laneOffset = lane, targetLane = lane,
                    baseSpeed = 292f + b * 11f, color = colors[b], modelIdx = (selectedModel + b + 1) % 4,
                    x = bx, y = by, yaw = byaw, vx = cos(byaw) * 180f, vy = sin(byaw) * 180f, errSeed = (b + 1) * 1.9f
                ))
            }
        }

        lap = 1; currentWp = 0; maxWpReached = 0; playerProgress = 0f; racePosition = 1; finalRank = 1
        score = 0f; comboTime = 0f; comboGap = 0f; multiplier = 1; isNewRecord = false
        lapStartTime = SystemClock.uptimeMillis()
        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        state = 3
        if (!fromNetwork) vibeClick()
        requestFocus()
        invalidate()
    }

    private fun spawnDebris(ix: Float, iy: Float, count: Int, baseCol: Int) {
        for (i in 0 until count) {
            val d = debrisPool[debrisHead]; debrisHead = (debrisHead + 1) % debrisPool.size
            val ang = Random.nextFloat() * 6.28f; val spd = 45f + Random.nextFloat() * 90f
            d.active = true; d.x = ix; d.y = iy; d.z = 4f
            d.vx = cos(ang) * spd; d.vy = sin(ang) * spd; d.vz = 65f + Random.nextFloat() * 85f
            d.yaw = ang; d.size = 3.2f; d.color = if (i % 2 == 0) baseCol else Color.DKGRAY; d.life = 2.8f
        }
    }

    private fun spawnParticle(px: Float, py: Float, pz: Float, vx: Float, vy: Float, vz: Float, sz: Float, col: Int, alpha: Int) {
        val p = particlePool[particleHead]; particleHead = (particleHead + 1) % particlePool.size
        p.active = true; p.x = px; p.y = py; p.z = pz; p.vx = vx; p.vy = vy; p.vz = vz; p.size = sz; p.color = col; p.alpha = alpha
    }

    // 1.2: Patlama ve 3 Saniyelik Geri Sayım Sonrası En Yakın Yolda Doğma
    private fun triggerExplosionAtSpot() {
        if (isCrashing) return
        isCrashing = true
        crashTimer = 3.0f // İstenen 3 saniyelik canlanma süresi
        carVZ = 140f; velocityX = 0f; velocityY = 0f
        spawnDebris(carX, carY, 10, carColors[selectedColor])
        for (i in 0 until 10) {
            val ang = Random.nextFloat() * 6.28f; val spd = 25f + Random.nextFloat() * 55f
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 45f, 5.5f, Color.rgb(255, 130, 20), 240)
        }
        triggerHaptic(190L, 255)
    }

    private fun respawnOnNearestRoad() {
        isCrashing = false; carZ = 0f; carVZ = 0f; carDamage = 0f; spoilerBroken = false; bumperBroken = false
        // Bina içinden kurtarmak için en yakın yol merkez noktasına (currentWp) ışınla
        val safeWp = currentWp.coerceIn(0, trackPts.size - 1)
        carX = trackPts[safeWp].x
        carY = trackPts[safeWp].y
        carYaw = trackTangents[safeWp]
        rollAngle = 0f; smoothedSteering = 0f
        velocityX = cos(carYaw) * 110f; velocityY = sin(carYaw) * 110f
        isFullStopped = false; hasPrevSkid = false
        respawnBannerTimer = 2.5f // Doğduktan sonra 2.5 sn koruma + bilgi
        triggerHaptic(55L, 200)
    }

    private fun fireProjectile(type: Int) {
        val pr = projectiles[projHead]; projHead = (projHead + 1) % projectiles.size
        pr.active = true; pr.type = type; pr.yaw = carYaw
        if (type == 1) { // Füze: İleri dümdüz gider
            pr.x = carX + cos(carYaw) * 20f; pr.y = carY + sin(carYaw) * 20f
            pr.vx = cos(carYaw) * 540f; pr.vy = sin(carYaw) * 540f; pr.life = 3.0f
        } else { // Diken: Yola bırakılır
            pr.x = carX - cos(carYaw) * 20f; pr.y = carY - sin(carYaw) * 20f
            pr.vx = 0f; pr.vy = 0f; pr.life = 14.0f
        }
        triggerHaptic(35L, 220)
    }

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
                // Yeni Buton Konumları:
                // 1) DUR Butonu: Harita balonunun (cx+0.42R, cy-0.22R) hemen üstünde -> (cx+0.36R, cy-0.55R)
                val stopBtnX = cx + R * 0.36f; val stopBtnY = cy - R * 0.55f
                // 2) ÇIK Butonu: Sol üst içte -> (cx+0.08R, cy-0.55R)
                val exitBtnX = cx + R * 0.08f; val exitBtnY = cy - R * 0.55f
                // 3) TURBO İkonu: Sol kenarda -> (cx-0.64R, cy-0.06R)
                val turboBtnX = cx - R * 0.64f; val turboBtnY = cy - R * 0.06f
                val distCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.12f) { state = 0; vibeClick(); invalidate(); return true }
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.13f) { isFullStopped = !isFullStopped; isHoldingStop = true; vibeClick(); return true }
                        // Turbo (Boost) Ateşleme: Turbo ikonuna veya ekranın tam ortasına dokununca
                        if (boostReady && (hypot(x - turboBtnX, y - turboBtnY) < R * 0.16f || distCenter < R * 0.28f)) {
                            boostReady = false; boostMeter = 0f; boostTime = 3.0f
                            triggerHaptic(90L, 255)
                            return true
                        }
                        if (distCenter > R * 0.72f) {
                            isBezelDragging = true; lastBezelAngle = atan2(y - cy, x - cx)
                        } else {
                            // Referans Kod: Sol/Sağ Dokunmatik Direksiyon Desteği
                            touchSteer = if (x < cx) -0.055f else 0.055f
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isBezelDragging && distCenter > R * 0.65f) {
                            val curAng = atan2(y - cy, x - cx)
                            val dAng = wrapAngle(curAng - lastBezelAngle)
                            lastBezelAngle = curAng
                            crownAccum += dAng * 1.8f
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHoldingStop = false; isBezelDragging = false; touchSteer = 0f
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

        if (showFps) {
            paint.color = Color.GREEN; paint.textSize = R * 0.065f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.88f, paint)
        }
    }

    // ==================== 3B PERSPEKTİF VE DİNAMİK KAMERA ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - camX; val dy = wy - camY
        val cosYaw = cos(camYaw); val sinYaw = sin(camYaw)
        val forward = dx * cosYaw + dy * sinYaw
        val right = -dx * sinYaw + dy * cosYaw

        val relF = forward + camDistOffset
        val relH = wz - 138f
        val depth = relF * 0.682f - relH * 0.731f
        if (depth < 16f || depth > 1350f) return false
        val vert = relF * 0.731f + relH * 0.682f

        val fov = R * camFovFactor
        projScale = fov / depth
        val shakeX = if (shakeTime > 0f) (Random.nextFloat() - 0.5f) * R * 0.025f else 0f
        val shakeY = if (shakeTime > 0f) (Random.nextFloat() - 0.5f) * R * 0.025f else 0f
        projX = cx + right * projScale + shakeX
        projY = (cy + R * 0.16f) - vert * projScale + shakeY
        return true
    }

    private fun drawQuad3D(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float, color: Int
    ) {
        if (!project3D(x1, y1, z1, cx, cy, R)) return; val sx1 = projX; val sy1 = projY
        if (!project3D(x2, y2, z2, cx, cy, R)) return; val sx2 = projX; val sy2 = projY
        if (!project3D(x3, y3, z3, cx, cy, R)) return; val sx3 = projX; val sy3 = projY
        if (!project3D(x4, y4, z4, cx, cy, R)) return; val sx4 = projX; val sy4 = projY

        polyPath.reset(); polyPath.moveTo(sx1, sy1); polyPath.lineTo(sx2, sy2); polyPath.lineTo(sx3, sy3); polyPath.lineTo(sx4, sy4); polyPath.close()
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
        val flX = bx + fx - rx; val flY = by + fy - ry
        val frX = bx + fx + rx; val frY = by + fy + ry
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry
        val tilt = (rollDeg * 0.12f).coerceIn(-2.2f, 2.2f)
        val topL = bz + height + tilt; val topR = bz + height - tilt

        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, rlX, rlY, bz, rlX, rlY, topL, flX, flY, topL, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, bz, rrX, rrY, bz, rrX, rrY, topR, frX, frY, topR, sideColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, frX, frY, bz, frX, frY, topR, flX, flY, topL, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, bz, rrX, rrY, bz, rrX, rrY, topR, rlX, rlY, topL, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, topL, frX, frY, topR, rrX, rrY, topR, rlX, rlY, topL, topColor)
    }

    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float, roll: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false
    ) {
        val alpha = if (isGhost) 105 else 255
        val dmg = if (isPlayer) (1f - (carDamage / 210f)) else 1f
        val r = (Color.red(baseColor) * dmg).toInt(); val g = (Color.green(baseColor) * dmg).toInt(); val b = (Color.blue(baseColor) * dmg).toInt()
        val topCol = Color.argb(alpha, r, g, b)
        val sideCol = Color.argb(alpha, (r * 0.75f).toInt(), (g * 0.75f).toInt(), (b * 0.75f).toInt())
        val rearCol = Color.argb(alpha, (r * 0.55f).toInt(), (g * 0.55f).toInt(), (b * 0.55f).toInt())
        val glassCol = Color.argb(alpha, 30, 48, 65); val wheelCol = Color.argb(alpha, 20, 22, 25); val chromeCol = Color.argb(alpha, 215, 220, 230)

        val c = cos(yaw); val s = sin(yaw); val rxVec = -s; val ryVec = c

        if (!isGhost) {
            val fx = c * 16.5f; val fy = s * 16.5f; val rx = rxVec * 10.5f; val ry = ryVec * 10.5f
            drawQuad3D(canvas, cx, cy, R, x + fx - rx, y + fy - ry, 0.2f, x + fx + rx, y + fy + ry, 0.2f, x - fx + rx, y - fy + ry, 0.2f, x - fx - rx, y - fy - ry, 0.2f, Color.argb(135, 0, 0, 0))
        }

        val bz = zOffset + 1.4f
        // Ön tekerleklerin ±28 derece (0.49 rad) dönme animasyonu (Referans Car3D)
        if (!isGhost) {
            val wxF = 9.8f; val wxR = -9.8f
            val wyOff = if (modelIdx == 3) 10.4f else 9.4f
            val wW = if (modelIdx == 3) 2.2f else 1.7f
            val wheelRad = if (isPlayer) (smoothedSteering * 8.5f).coerceIn(-0.49f, 0.49f) else 0f
            draw3DBox(canvas, cx, cy, R, x + c * wxF - rxVec * wyOff, y + s * wxF - ryVec * wyOff, 0.5f + zOffset, 3.5f, wW, 3.6f, yaw + wheelRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxF + rxVec * wyOff, y + s * wxF + ryVec * wyOff, 0.5f + zOffset, 3.5f, wW, 3.6f, yaw + wheelRad, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR - rxVec * wyOff, y + s * wxR - ryVec * wyOff, 0.5f + zOffset, 3.7f, wW, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR + rxVec * wyOff, y + s * wxR + ryVec * wyOff, 0.5f + zOffset, 3.7f, wW, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
        }

        when (modelIdx % 4) {
            0 -> {
                val len = if (isPlayer && bumperBroken) 13.5f else 15.8f
                draw3DBox(canvas, cx, cy, R, x, y, bz, len, 8.8f, 4.3f, yaw, topCol, sideCol, rearCol, roll)
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz + 4.3f, 7.6f, 6.8f, 3.7f, yaw, topCol, glassCol, glassCol, roll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14f, y - s * 14f, bz + 6.4f, 2f, 9.4f, 1.1f, yaw, wheelCol, sideCol, rearCol, roll)
            }
            1 -> {
                val len = if (isPlayer && bumperBroken) 14f else 16.8f
                draw3DBox(canvas, cx, cy, R, x - c, y - s, bz, len * 0.85f, 9.8f, 3.4f, yaw, topCol, sideCol, rearCol, roll)
                draw3DBox(canvas, cx, cy, R, x + c, y + s, bz + 3.4f, 8.2f, 6.4f, 2.9f, yaw, topCol, glassCol, rearCol, roll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, yaw, rearCol, sideCol, wheelCol, roll)
            }
            2 -> {
                val len = if (isPlayer && bumperBroken) 14.2f else 16.5f
                draw3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, len, 9.0f, 4.6f, yaw, topCol, sideCol, rearCol, roll)
                draw3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, yaw, chromeCol, wheelCol, chromeCol, roll)
                draw3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, yaw, topCol, glassCol, glassCol, roll)
            }
            else -> {
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, yaw, topCol, sideCol, rearCol, roll)
                draw3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, yaw, topCol, sideCol, rearCol, roll)
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, yaw, glassCol, glassCol, rearCol, roll)
                if (!(isPlayer && spoilerBroken)) draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, yaw, chromeCol, topCol, rearCol, roll)
            }
        }

        // Araç Önü Silahı veya Kalkan Görseli
        if (isPlayer && activePower == 4) { // Ön Silah
            draw3DBox(canvas, cx, cy, R, x + c * 17f - rxVec * 4f, y + s * 17f - ryVec * 4f, bz + 2f, 4.5f, 1.2f, 1.2f, yaw, chromeCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * 17f + rxVec * 4f, y + s * 17f + ryVec * 4f, bz + 2f, 4.5f, 1.2f, 1.2f, yaw, chromeCol, wheelCol, wheelCol)
        }
        if (isPlayer && (activePower == 3 || respawnBannerTimer > 0f)) { // Kalkan Halesi
            if (project3D(x, y, bz + 4f, cx, cy, R)) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f
                paint.color = if (activePower == 3) Color.argb(190, 0, 235, 255) else Color.argb(160, 80, 255, 130)
                canvas.drawCircle(projX, projY, 22f * projScale, paint)
            }
        }

        if (!isGhost) {
            val tailX = x - c * 15.5f; val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.YELLOW else if (isPlayer && boostTime > 0f) Color.CYAN else Color.RED
            draw3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2f, 0.8f, 2f, 1.1f, yaw, tailCol, tailCol, tailCol)
            draw3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2f, 0.8f, 2f, 1.1f, yaw, tailCol, tailCol, tailCol)
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
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(66, 70, 74)

        for (step in 42 downTo -18) {
            val i0 = (currentWp + step + total) % total; val i1 = (i0 + 1) % total
            val p0 = trackPts[i0]; val p1 = trackPts[i1]; val n0 = trackNormals[i0]; val n1 = trackNormals[i1]
            val l0x = p0.x - n0.x * roadHalfWidth; val l0y = p0.y - n0.y * roadHalfWidth
            val r0x = p0.x + n0.x * roadHalfWidth; val r0y = p0.y + n0.y * roadHalfWidth
            val l1x = p1.x - n1.x * roadHalfWidth; val l1y = p1.y - n1.y * roadHalfWidth
            val r1x = p1.x + n1.x * roadHalfWidth; val r1y = p1.y + n1.y * roadHalfWidth

            val isZone = zoneMask.isNotEmpty() && zoneMask[i0]
            val kerbColor = if (isZone) {
                if (i0 % 2 == 0) Color.rgb(255, 195, 0) else Color.rgb(25, 25, 25)
            } else {
                if (i0 % 2 == 0) Color.rgb(225, 35, 35) else Color.WHITE
            }

            drawQuad3D(canvas, cx, cy, R, l0x - n0.x * kerbW, l0y - n0.y * kerbW, 0f, l0x, l0y, 0f, l1x, l1y, 0f, l1x - n1.x * kerbW, l1y - n1.y * kerbW, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, r0x, r0y, 0f, r0x + n0.x * kerbW, r0y + n0.y * kerbW, 0f, r1x + n1.x * kerbW, r1y + n1.y * kerbW, 0f, r1x, r1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0f, r0x, r0y, 0f, r1x, r1y, 0f, l1x, l1y, 0f, roadCol)

            if (i0 == 0) {
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0.3f, r0x, r0y, 0.3f, r1x, r1y, 0.3f, l1x, l1y, 0.3f, Color.WHITE)
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 34f, r0x, r0y, 34f, r1x, r1y, 40f, l1x, l1y, 40f, Color.rgb(210, 40, 40))
            }
        }

        // Dairesel Tamponlu Solarak Kaybolan SkidTrail (Referans SkidTrail.java)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f
        for (i in 0 until tireCount) {
            val idx = (tireHead - 1 - i + tirePool.size) % tirePool.size
            val m = tirePool[idx]
            if (!m.active) continue
            val t = i.toFloat() / tirePool.size
            val alpha = ((1f - t * t) * 165f * m.strength).toInt().coerceIn(0, 220)
            if (alpha < 12) continue
            paint.color = if (m.isGrass) Color.argb(alpha, 70, 48, 25) else Color.argb(alpha, 15, 15, 18)
            if (project3D(m.lx1, m.ly1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(m.lx2, m.ly2, 0.2f, cx, cy, R)) canvas.drawLine(x1, y1, projX, projY, paint)
            }
            if (project3D(m.rx1, m.ry1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(m.rx2, m.ry2, 0.2f, cx, cy, R)) canvas.drawLine(x1, y1, projX, projY, paint)
            }
        }

        // Sürpriz Kutular
        for (box in mysteryBoxes) {
            if (!box.active) continue
            val dx = box.x - carX; val dy = box.y - carY
            if (dx * dx + dy * dy < 320000f) {
                draw3DBox(canvas, cx, cy, R, box.x, box.y, 2f, 6.5f, 6.5f, 11f, garagePreviewYaw, Color.rgb(255, 215, 0), Color.rgb(255, 140, 0), Color.rgb(200, 80, 0))
            }
        }

        // Füze ve Dikenler
        for (pr in projectiles) {
            if (!pr.active) continue
            if (pr.type == 1) draw3DBox(canvas, cx, cy, R, pr.x, pr.y, 4f, 6f, 2.2f, 2.5f, pr.yaw, Color.RED, Color.YELLOW, Color.WHITE)
            else draw3DBox(canvas, cx, cy, R, pr.x, pr.y, 0f, 6.5f, 6.5f, 5.5f, pr.yaw, Color.MAGENTA, Color.DKGRAY, Color.BLACK)
        }

        // Ağaç, Bina, Bariyer ve Yol İçi Rastgele Engeller
        for (obs in obstacles) {
            val dx = obs.x - carX; val dy = obs.y - carY
            if (dx * dx + dy * dy < 340000f) {
                when (obs.type) {
                    0 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
                    1 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(45, 50, 70), Color.rgb(28, 32, 48), Color.rgb(22, 25, 38))
                    2 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, 13f, 4.5f, obs.height, obs.yaw, Color.rgb(230, 50, 40), Color.WHITE, Color.rgb(180, 30, 30))
                    3 -> draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, 8f, 8f, obs.height, obs.yaw, Color.rgb(255, 145, 0), Color.rgb(40, 40, 40), Color.rgb(200, 90, 0))
                }
            }
        }

        for (d in debrisPool) if (d.active) draw3DBox(canvas, cx, cy, R, d.x, d.y, d.z, d.size, d.size * 0.7f, d.size * 0.5f, d.yaw, d.color, d.color, Color.DKGRAY)

        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val g = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            drawSport3DCar(canvas, cx, cy, R, g.x, g.y, 0f, g.yaw, g.roll, Color.rgb(0, 225, 255), selectedModel, isGhost = true)
        }

        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, 0f, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
        }

        if (botsEnabled) {
            for (bot in bots) drawSport3DCar(canvas, cx, cy, R, bot.x, bot.y, 0f, bot.yaw, bot.roll, bot.color, bot.modelIdx, isGhost = false)
        }

        drawSport3DCar(canvas, cx, cy, R, carX, carY, carZ, carYaw, rollAngle, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = true)

        paint.style = Paint.Style.FILL
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== BOT YAPAY ZEKASI VE FİZİK ADIMI ====================
    private fun updateBots(dt: Float) {
        val total = trackPts.size
        for (bot in bots) {
            if (bot.spinTimer > 0f) {
                bot.spinTimer -= dt
                bot.yaw += 9.5f * dt
                bot.x += bot.vx * dt * 0.35f; bot.y += bot.vy * dt * 0.35f
                continue
            }
            // İnsani Hata Salınımı (Virajı geniş alma / şerit kaçırma)
            val errWave = sin(SystemClock.uptimeMillis() * 0.0018f + bot.errSeed)
            val lookAhead = ((bot.wpFloat.toInt() + 5) % total + total) % total
            val curIdx = ((bot.wpFloat.toInt()) % total + total) % total
            val sharpTurn = abs(wrapAngle(trackTangents[lookAhead] - trackTangents[curIdx])) > 0.24f

            if (sharpTurn && errWave > 0.62f) {
                bot.targetLane = (roadHalfWidth * 0.92f) * sign(sin(bot.errSeed))
            } else if (abs(bot.laneOffset - bot.targetLane) < 2.5f && Random.nextInt(90) == 0) {
                bot.targetLane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.1f)
            }
            bot.laneOffset += (bot.targetLane - bot.laneOffset).coerceIn(-26f * dt, 26f * dt)

            val targetPt = trackPts[lookAhead]; val targetN = trackNormals[lookAhead]
            val tx = targetPt.x + targetN.x * bot.laneOffset
            val ty = targetPt.y + targetN.y * bot.laneOffset

            val desiredYaw = atan2(ty - bot.y, tx - bot.x)
            val yawDiff = wrapAngle(desiredYaw - bot.yaw)
            bot.yaw += yawDiff.coerceIn(-3.6f * dt, 3.6f * dt)
            bot.roll += ((-yawDiff * 24f) - bot.roll) * 0.15f

            // Oyuncuyla Aynı Grip/Thrust Yakınsama Fiziği
            val onGrassBot = abs(bot.laneOffset) > roadHalfWidth
            val botThrust = bot.baseSpeed * (if (onGrassBot) 0.68f else if (sharpTurn) 0.88f else 1.0f)
            val botGrip = (if (tracks[currentTrackIdx].theme == 3) 2.2f else 3.4f) * dt
            bot.vx += (cos(bot.yaw) * botThrust - bot.vx) * botGrip
            bot.vy += (sin(bot.yaw) * botThrust - bot.vy) * botGrip
            bot.x += bot.vx * dt; bot.y += bot.vy * dt

            // Botun pistteki ilerlemesini güncelle (Sadece yakın pencerede ara - Performans)
            var bestD = Float.MAX_VALUE; var bestI = curIdx
            for (k in -8..12) {
                val checkI = (curIdx + k + total) % total
                val dx = bot.x - trackPts[checkI].x; val dy = bot.y - trackPts[checkI].y
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; bestI = checkI }
            }
            val stepDiff = (bestI - curIdx + total) % total
            if (stepDiff < total / 3) bot.wpFloat += stepDiff.toFloat()
            val p1 = trackPts[(bestI + 1) % total]; val p0 = trackPts[bestI]
            val segFrac = ((bot.x - p0.x) * (p1.x - p0.x) + (bot.y - p0.y) * (p1.y - p0.y)) / ((p1.x - p0.x).pow(2) + (p1.y - p0.y).pow(2)).coerceAtLeast(1f)
            bot.wpFloat = floor(bot.wpFloat) + segFrac.coerceIn(0f, 0.99f)
        }
    }

    private fun updateGamePhysics(dt: Float, now: Long) {
        garagePreviewYaw += dt * 2.2f
        if (respawnBannerTimer > 0f) respawnBannerTimer -= dt
        if (shakeTime > 0f) shakeTime -= dt
        if (hapticTimer > 0f) hapticTimer -= dt

        // 3.1: Başlangıç 3 - 2 - 1 Geri Sayımı
        if (countdownValue >= 0) {
            countdownTimer -= dt
            if (countdownTimer <= 0f) {
                countdownValue--
                countdownTimer = if (countdownValue == 0) 0.55f else 0.65f
                triggerHaptic(45L, 210)
                if (countdownValue < 0) lapStartTime = SystemClock.uptimeMillis()
            }
            updateCamera(dt)
            return
        }

        currentLapTime = (now - lapStartTime) / 1000f
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
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt; p.size += 5.5f * dt
            p.alpha -= (215 * dt).toInt()
            if (p.alpha <= 0) p.active = false
        }

        // Patlama ve 3 sn Geri Sayım
        if (isCrashing) {
            crashTimer -= dt
            carZ += carVZ * dt; carVZ -= 235f * dt
            if (carZ < 0f) { carZ = 0f; carVZ = abs(carVZ) * 0.4f }
            if (crashTimer <= 0f) respawnOnNearestRoad()
            return
        }

        // 6.1 & 6.5: Orijinal CrownDrift Direksiyon Yumuşatma ve Gövde Yatması (Car3D.java)
        val stepRatio = dt * 60f
        val sensFactor = when (sensLevel) { 0 -> 0.65f; 1 -> 1.0f; else -> 1.4f }
        val steerIn = (touchSteer + crownAccum) * sensFactor
        crownAccum = 0f

        smoothedSteering += (steerIn - smoothedSteering) * 0.08f * stepRatio
        if (touchSteer == 0f && !isBezelDragging) smoothedSteering *= 0.88f.pow(stepRatio)
        carYaw += 0.048f * smoothedSteering * stepRatio
        rollAngle += ((-smoothedSteering * 65f) - rollAngle) * 0.12f * stepRatio
        rollAngle *= 0.85f.pow(stepRatio)

        // Hız Çarpanları (Turbo, Çim Cezası %40, Kötü Kutu, Tam Durdurma)
        if (boostTime > 0f) boostTime -= dt
        val modelBaseSpeed = when (selectedModel) { 0 -> 295f; 1 -> 310f; 2 -> 300f; else -> 322f }
        val speedFactor = when {
            isFullStopped || isHoldingStop -> 0f
            boostTime > 0f -> 1.24f
            activePower == 5 -> 0.58f
            isOffRoad -> 0.60f // Referans GRASS_SPEED = 0.6f (%40 yavaşlama)
            else -> 1.0f
        }
        val thrust = modelBaseSpeed * speedFactor

        // Orijinal CrownDrift Grip Yakınsama Denklemi: velocity += (fwd * thrust - velocity) * grip
        val baseGrip = if (tracks[currentTrackIdx].theme == 3) 0.022f else 0.036f // Glacier S buzlu zemin
        val grip = (if (isFullStopped || isHoldingStop) 0.12f else baseGrip) * stepRatio
        val fwdX = cos(carYaw); val fwdY = sin(carYaw)
        velocityX += (fwdX * thrust - velocityX) * grip
        velocityY += (fwdY * thrust - velocityY) * grip
        carX += velocityX * dt
        carY += velocityY * dt

        val speedSq = velocityX * velocityX + velocityY * velocityY
        val speed = sqrt(speedSq)

        // Orijinal Histerezisli Drift Tespiti (0.93f / 0.955f)
        val wasDrifting = isCurrentlyDrifting
        isCurrentlyDrifting = false
        driftAmount = 0f
        if (speed > 40f) {
            val alignment = (velocityX / speed) * fwdX + (velocityY / speed) * fwdY
            driftAmount = (1f - alignment).coerceIn(0f, 1f)
            val threshold = if (wasDrifting) 0.965f else 0.935f
            if (alignment < threshold) isCurrentlyDrifting = true
        }
        val targetSkid = if (speed > 45f) ((driftAmount - 0.02f) / 0.12f).coerceIn(0f, 1f) else 0f
        skidIntensity += (targetSkid - skidIntensity) * 0.25f

        currentLapRecording.add(GhostPose(carX, carY, carYaw, rollAngle))
        ghostFrameIdx++

        // 5: Performans Optimizasyonu -> Tüm pist yerine sadece currentWp ± 15 penceresinde ara (Track3D.java)
        val total = trackPts.size
        var bestDistSq = Float.MAX_VALUE
        var bestIdx = currentWp
        for (k in -15..15) {
            val idx = (currentWp + k + total) % total
            val dx = carX - trackPts[idx].x; val dy = carY - trackPts[idx].y
            val dSq = dx * dx + dy * dy
            if (dSq < bestDistSq) { bestDistSq = dSq; bestIdx = idx }
        }
        if (bestDistSq > (roadHalfWidth * 3.5f).pow(2)) {
            for (i in 0 until total) {
                val dx = carX - trackPts[i].x; val dy = carY - trackPts[i].y
                val dSq = dx * dx + dy * dy
                if (dSq < bestDistSq) { bestDistSq = dSq; bestIdx = i }
            }
        }
        val bestDist = sqrt(bestDistSq)
        currentWp = bestIdx
        inDriftZone = zoneMask.isNotEmpty() && zoneMask[currentWp]
        isOffRoad = bestDist > roadHalfWidth + 6f
        edgeActive = isCurrentlyDrifting && !isOffRoad && bestDist > roadHalfWidth - 14f

        // 6.3: Puan, Kombo (4x), Edge Bonus, Drift Zone ve Boost Dolumu
        if (isOffRoad) {
            comboTime = 0f; multiplier = 1; comboGap = 0f
            if (speed > 40f) {
                shakeTime = 0.15f
                if (hapticTimer <= 0f) { triggerHaptic(12L, 95); hapticTimer = 0.14f }
            }
        } else if (isCurrentlyDrifting) {
            comboGap = 0f; comboTime += dt
            val newMult = min(4, (comboTime / 3.0f).toInt() + 1)
            if (newMult > multiplier) triggerHaptic(25L, 200)
            multiplier = newMult

            val pts = multiplier * 100f * dt * (if (inDriftZone) 2.0f else 1.0f) * (if (edgeActive) 1.5f else 1.0f) * (if (boostTime > 0f) 2.0f else 1.0f)
            score += pts

            if (boostTime <= 0f && !boostReady) {
                boostMeter += (multiplier * 0.045f + 0.06f) * dt
                if (boostMeter >= 1f) {
                    boostMeter = 1f; boostReady = true
                    triggerHaptic(60L, 240)
                }
            }
            if (hapticTimer <= 0f) {
                val ms = min((driftAmount * 70f + 6f).toLong(), 28L)
                triggerHaptic(ms, 175)
                hapticTimer = 0.16f
            }
        } else {
            comboGap += dt
            if (comboGap > 0.35f) { comboTime = 0f; multiplier = 1 }
        }

        // Lastik İzi (SkidTrail) ve 32'li Duman Havuzu
        val backX = carX - fwdX * 12f; val backY = carY - fwdY * 12f
        val perpX = -fwdY * 7f; val perpY = fwdX * 7f
        val curLX = backX - perpX; val curLY = backY - perpY
        val curRX = backX + perpX; val curRY = backY + perpY

        if ((skidIntensity > 0.05f || isOffRoad) && speed > 45f) {
            if (hasPrevSkid && hypot(curLX - prevBackLX, curLY - prevBackLY) < 42f) {
                val tm = tirePool[tireHead]
                tireHead = (tireHead + 1) % tirePool.size
                if (tireCount < tirePool.size) tireCount++
                tm.active = true; tm.lx1 = prevBackLX; tm.ly1 = prevBackLY; tm.lx2 = curLX; tm.ly2 = curLY
                tm.rx1 = prevBackRX; tm.ry1 = prevBackRY; tm.rx2 = curRX; tm.ry2 = curRY
                tm.strength = if (isOffRoad) 0.85f else skidIntensity.coerceAtLeast(0.35f)
                tm.isGrass = isOffRoad
            }
            prevBackLX = curLX; prevBackLY = curLY; prevBackRX = curRX; prevBackRY = curRY; hasPrevSkid = true

            if (stepCounter % 3 == 0) {
                val left = (stepCounter / 3) % 2 == 0
                val sx = if (left) curLX else curRX; val sy = if (left) curLY else curRY
                val sCol = when {
                    isOffRoad -> Color.rgb(120, 90, 55)
                    tracks[currentTrackIdx].theme == 1 -> Color.rgb(120, 220, 255)
                    else -> Color.rgb(225, 225, 235)
                }
                spawnParticle(sx, sy, 2f, -velocityX * 0.15f, -velocityY * 0.15f, 22f, 3.2f, sCol, 165)
            }
        } else hasPrevSkid = false

        // 3.3: Sürpriz Kutular ve Aktif Silah/Özellik Sistemi
        for (box in mysteryBoxes) {
            if (!box.active) {
                box.respawnTimer -= dt
                if (box.respawnTimer <= 0f) box.active = true
                continue
            }
            val dx = carX - box.x; val dy = carY - box.y
            if (dx * dx + dy * dy < 440f) {
                box.active = false; box.respawnTimer = 8.0f
                activePower = Random.nextInt(1, 6) // 1=Füze, 2=Diken, 3=Kalkan, 4=Ön Silah, 5=Kötü Kutu
                powerTimer = if (activePower == 5) 3.5f else 7.5f
                powerCooldown = 0.2f
                if (activePower == 5) { // Kötü Kutu: Zarar verir ve yavaşlatır
                    carDamage = (carDamage + 18f).coerceAtMost(95f)
                    shakeTime = 0.3f
                }
                triggerHaptic(50L, 230)
            }
        }

        if (activePower > 0) {
            powerTimer -= dt
            if (powerCooldown > 0f) powerCooldown -= dt
            when (activePower) {
                1 -> if (powerCooldown <= 0f && botsEnabled) { // Füze: Rakip görüş açısına ve doğrultusuna girince ateşlenir
                    for (bot in bots) {
                        val dx = bot.x - carX; val dy = bot.y - carY
                        val dist = hypot(dx, dy)
                        if (dist in 35f..420f) {
                            val dot = (dx / dist) * fwdX + (dy / dist) * fwdY
                            if (dot > 0.90f) { // Görüş doğrultusunda!
                                fireProjectile(1)
                                activePower = 0
                                break
                            }
                        }
                    }
                }
                2 -> if (powerCooldown <= 0f) { // Diken: Yola bırakılır
                    fireProjectile(2)
                    activePower = 0
                }
                4 -> if (powerCooldown <= 0f && botsEnabled) { // Araç Önü Silahı: Yakın öndeki rakibi tarar ve devirir
                    for (bot in bots) {
                        val dx = bot.x - carX; val dy = bot.y - carY
                        val dist = hypot(dx, dy)
                        if (dist < 175f && ((dx / dist) * fwdX + (dy / dist) * fwdY) > 0.82f) {
                            bot.spinTimer = 1.6f
                            spawnDebris(bot.x, bot.y, 4, bot.color)
                            powerCooldown = 0.6f
                            triggerHaptic(30L, 200)
                        }
                    }
                }
            }
            if (powerTimer <= 0f) activePower = 0
        }

        // Füze ve Diken Hareketleri
        for (pr in projectiles) {
            if (!pr.active) continue
            pr.life -= dt
            if (pr.life <= 0f) { pr.active = false; continue }
            pr.x += pr.vx * dt; pr.y += pr.vy * dt
            if (botsEnabled) {
                for (bot in bots) {
                    val dx = pr.x - bot.x; val dy = pr.y - bot.y
                    if (dx * dx + dy * dy < 360f) {
                        bot.spinTimer = 2.0f
                        bot.vx *= 0.2f; bot.vy *= 0.2f
                        spawnDebris(bot.x, bot.y, 6, bot.color)
                        pr.active = false
                        triggerHaptic(45L, 220)
                        break
                    }
                }
            }
        }

        // Botlar ve Çarpışma
        val shielded = activePower == 3 || respawnBannerTimer > 0f
        if (botsEnabled) {
            updateBots(dt)
            for (bot in bots) {
                val dx = carX - bot.x; val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 529f && distSq > 0.01f) {
                    val dist = sqrt(distSq); val pushAng = atan2(dy, dx); val overlap = 23f - dist
                    carX += cos(pushAng) * overlap * 0.6f; carY += sin(pushAng) * overlap * 0.6f
                    bot.vx -= cos(pushAng) * 150f; bot.vy -= sin(pushAng) * 150f
                    if (!shielded) {
                        carDamage = (carDamage + 9f).coerceAtMost(100f)
                        if (carDamage > 45f) bumperBroken = true
                    } else bot.spinTimer = 1.2f
                    triggerHaptic(40L, 220)
                }
            }
        }

        // Engellere (Bina, Ağaç, Bariyer, Yol Engeli) Çarpma
        for (obs in obstacles) {
            val dx = carX - obs.x; val dy = carY - obs.y
            val minD = obs.radius + 11f
            if (dx * dx + dy * dy < minD * minD) {
                val ang = atan2(dy, dx)
                carX = obs.x + cos(ang) * (minD + 2f)
                carY = obs.y + sin(ang) * (minD + 2f)
                if (!shielded) {
                    carDamage += if (obs.type >= 2) 24f else speed * 0.28f
                    if (carDamage > 35f) bumperBroken = true
                    if (carDamage > 65f) spoilerBroken = true
                    if ((obs.type <= 1 && speed > 205f) || carDamage >= 100f) {
                        triggerExplosionAtSpot()
                        return
                    }
                }
                velocityX *= 0.45f; velocityY *= 0.45f
                spawnDebris(carX, carY, 5, carColors[selectedColor])
                triggerHaptic(75L, 250)
            }
        }

        // 1.1: Kesin Sıralama (Ranking) ve Tur Hesabı
        if (currentWp > maxWpReached && currentWp - maxWpReached < total / 3) maxWpReached = currentWp
        val nextP = trackPts[(currentWp + 1) % total]; val curP = trackPts[currentWp]
        val segFrac = (((carX - curP.x) * (nextP.x - curP.x) + (carY - curP.y) * (nextP.y - curP.y)) / ((nextP.x - curP.x).pow(2) + (nextP.y - curP.y).pow(2)).coerceAtLeast(1f)).coerceIn(0f, 0.99f)
        playerProgress = (lap - 1) * total + maxWpReached.toFloat() + segFrac

        var pos = 1
        if (botsEnabled) {
            for (bot in bots) if (bot.wpFloat > playerProgress) pos++
        }
        racePosition = pos

        if (currentWp < total / 10 && maxWpReached > (total * 3) / 4) {
            maxWpReached = 0
            if (currentLapTime < bestLapTime || bestLapGhost.isEmpty()) {
                bestLapTime = currentLapTime
                bestLapGhost = currentLapRecording.toList()
                prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply()
            }
            currentLapRecording.clear(); ghostFrameIdx = 0

            if (lap < totalLaps) {
                lap++
                lapStartTime = now
                spawnRandomRoadObstacles() // Her tur yoldaki rastgele engelleri yenile
                triggerHaptic(55L, 255)
            } else {
                // Bitiş çizgisini geçerken mevcut sıralamayı kilitle! (1. bitirince 4. gösterme hatası çözüldü)
                finalRank = racePosition
                val finalScore = score.toInt()
                if (finalScore > prefs.getInt("best_score_$currentTrackIdx", 0)) {
                    isNewRecord = true
                    prefs.edit().putInt("best_score_$currentTrackIdx", finalScore).apply()
                }
                state = 4
                invalidate()
                return
            }
        }

        updateCamera(dt)
    }

    // 6.4: Hız Vektörü Harmanlamalı (%55 Bakış + %45 Hız Yönü) ve Dinamik FOV Kamera
    private fun updateCamera(dt: Float) {
        val smooth = 1f - 0.85f.pow(dt * 60f)
        val spd = hypot(velocityX, velocityY)
        var fx = cos(carYaw); var fy = sin(carYaw)
        if (spd > 15f) {
            fx = fx * 0.55f + (velocityX / spd) * 0.45f
            fy = fy * 0.55f + (velocityY / spd) * 0.45f
        }
        val targetYaw = atan2(fy, fx)
        camYaw += wrapAngle(targetYaw - camYaw) * smooth
        camX += (carX - camX) * 0.45f
        camY += (carY - camY) * 0.45f

        val zoom = (multiplier - 1) * 4.5f + (if (boostTime > 0f) 14f else 0f)
        camDistOffset += ((98f + zoom) - camDistOffset) * smooth
        val targetFov = if (boostTime > 0f) 1.28f else 1.48f
        camFovFactor += (targetFov - camFovFactor) * smooth
    }

    // ==================== 2. YENİ OYUN İÇİ ARAYÜZ (HUD) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // 1. Sol-Üst Küçük Sıralama Göstergesi ("1/4") + Tur + Skor (Büyük drift balonu kaldırıldı)
        tempRect.set(cx - R * 0.58f, cy - R * 0.64f, cx - R * 0.08f, cy - R * 0.34f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(175, 12, 18, 24)
        canvas.drawRoundRect(tempRect, R * 0.06f, R * 0.06f, paint)

        val totalCars = if (botsEnabled) bots.size + 1 else 1
        val posKey = racePosition * 10 + totalCars
        if (posKey != hudPosKey) { hudPosKey = posKey; hudPosText = "$racePosition/$totalCars" }
        paint.textAlign = Paint.Align.LEFT; paint.color = Color.rgb(255, 215, 35); paint.textSize = R * 0.095f
        canvas.drawText(hudPosText, tempRect.left + R * 0.04f, cy - R * 0.51f, paint)

        val lapKey = lap * 100 + (currentLapTime * 10f).toInt()
        if (lapKey != hudLapKey) { hudLapKey = lapKey; hudLapText = "L$lap/$totalLaps" }
        paint.textAlign = Paint.Align.RIGHT; paint.color = Color.LTGRAY; paint.textSize = R * 0.062f
        canvas.drawText(hudLapText, tempRect.right - R * 0.04f, cy - R * 0.52f, paint)

        val scKey = score.toInt()
        if (scKey != hudScoreKey) { hudScoreKey = scKey; hudScoreText = "$scKey" }
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (inDriftZone && isCurrentlyDrifting) Color.rgb(255, 210, 0) else if (isCurrentlyDrifting) Color.rgb(0, 225, 255) else Color.WHITE
        paint.textSize = R * 0.092f
        canvas.drawText(hudScoreText, tempRect.centerX(), cy - R * 0.38f, paint)

        if (multiplier > 1) {
            paint.color = Color.rgb(255, 65, 160); paint.textSize = R * 0.065f
            canvas.drawText("x$multiplier", tempRect.right - R * 0.08f, cy - R * 0.38f, paint)
        }

        // 2. ÇIK Butonu (Sol-Üst İçte) ve DUR Butonu (Mini Harita Balonunun Tam Üstünde!)
        val exitBtnX = cx + R * 0.08f; val exitBtnY = cy - R * 0.55f
        paint.color = Color.argb(200, 185, 35, 45)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.095f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.052f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.02f, paint)

        val stopBtnX = cx + R * 0.36f; val stopBtnY = cy - R * 0.55f
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(200, 215, 55, 50)
        canvas.drawCircle(stopBtnX, stopBtnY, R * 0.105f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.055f
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.02f, paint)

        // 3. Sağdaki Mini Harita Balonu (DUR butonunun hemen altında)
        val mapCX = cx + R * 0.42f; val mapCY = cy - R * 0.22f; val mapRad = R * 0.18f
        paint.style = Paint.Style.FILL; paint.color = Color.argb(175, 12, 18, 24)
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
        if (botsEnabled) for (bot in bots) { paint.color = bot.color; canvas.drawCircle(mapCX + bot.x * miniScale, mapCY + bot.y * miniScale, R * 0.017f, paint) }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + carX * miniScale, mapCY + carY * miniScale, R * 0.025f, paint)

        // 4. Sol Kenar TURBO İkonu ve Altında Aktif Özellik Simgesi
        val turboX = cx - R * 0.64f; val turboY = cy - R * 0.06f; val turboR = R * 0.115f
        paint.style = Paint.Style.FILL
        paint.color = if (boostReady || boostTime > 0f) Color.argb(220, 0, 200, 255) else Color.argb(175, 18, 26, 34)
        canvas.drawCircle(turboX, turboY, turboR, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = R * 0.022f; paint.color = Color.rgb(0, 235, 255)
        tempRect.set(turboX - turboR, turboY - turboR, turboX + turboR, turboY + turboR)
        val sweep = (if (boostTime > 0f) boostTime / 3.0f else boostMeter) * 360f
        canvas.drawArc(tempRect, -90f, sweep, false, paint)
        paint.style = Paint.Style.FILL; paint.color = if (boostReady) Color.BLACK else Color.WHITE; paint.textSize = R * 0.048f
        canvas.drawText(if (boostReady) "BAS!" else "TURBO", turboX, turboY + R * 0.018f, paint)

        // Turbo Butonunun Altında Aktif Sürpriz Kutu Özelliği Simgesi
        if (activePower > 0) {
            val pwX = turboX; val pwY = cy + R * 0.20f; val pwR = R * 0.095f
            val pwCol = when (activePower) {
                1 -> Color.rgb(255, 65, 55); 2 -> Color.rgb(190, 60, 255); 3 -> Color.rgb(0, 220, 255)
                4 -> Color.rgb(255, 195, 20); else -> Color.rgb(130, 80, 40)
            }
            val pwName = when (activePower) { 1 -> "FÜZE"; 2 -> "DİKEN"; 3 -> "KALKAN"; 4 -> "SİLAH"; else -> "YAVAŞ" }
            paint.color = pwCol
            canvas.drawCircle(pwX, pwY, pwR, paint)
            paint.color = Color.BLACK; paint.textSize = R * 0.044f
            canvas.drawText(pwName, pwX, pwY + R * 0.015f, paint)
        }

        // 5. Durum Etiketleri (Drift Zone / Edge Bonus / Patlama / 3-2-1 Başlangıç)
        if (countdownValue >= 0) {
            paint.color = Color.argb(150, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = if (countdownValue == 3) Color.RED else if (countdownValue == 2) Color.YELLOW else Color.GREEN
            paint.textSize = R * 0.28f
            canvas.drawText(if (countdownValue > 0) "$countdownValue" else "GO!", cx, cy + R * 0.10f, paint)
            return
        }

        if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55); paint.textSize = R * 0.085f
            canvas.drawText("CANLANIYOR: ${String.format("%.1fs", crashTimer.coerceAtLeast(0f))}", cx, cy - R * 0.06f, paint)
        } else if (boostTime > 0f) {
            paint.color = Color.rgb(0, 235, 255); paint.textSize = R * 0.068f
            canvas.drawText("TURBO x2 PUAN!", cx, cy - R * 0.14f, paint)
        } else if (inDriftZone && isCurrentlyDrifting) {
            paint.color = Color.rgb(255, 215, 0); paint.textSize = R * 0.068f
            canvas.drawText("DRIFT ZONE x2", cx, cy - R * 0.14f, paint)
        } else if (edgeActive) {
            paint.color = Color.rgb(255, 80, 180); paint.textSize = R * 0.065f
            canvas.drawText("KENAR BONUSU x1.5", cx, cy - R * 0.14f, paint)
        } else if (isOffRoad) {
            paint.color = Color.rgb(255, 200, 40); paint.textSize = R * 0.065f
            canvas.drawText("ÇİM ALAN (%40 YAVAŞ)", cx, cy - R * 0.14f, paint)
        }

        // 6. Alt Kenar 21 Noktalı Yay Gösterge (Direksiyon + Turbo Işıltısı)
        val totalDots = 21; val centerDot = totalDots / 2
        val activeDotOffset = (smoothedSteering * 14f * centerDot).toInt().coerceIn(-centerDot, centerDot)
        for (i in 0 until totalDots) {
            val offset = i - centerDot
            val rad = Math.toRadians((90f - offset * 3.1f).toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f)
            val dy = cy + sin(rad).toFloat() * (R * 0.88f)
            val isLit = (activeDotOffset >= 0 && offset in 0..activeDotOffset) || (activeDotOffset < 0 && offset in activeDotOffset..0) || offset == 0
            paint.color = if (boostReady) Color.rgb(255, 215, 0) else if (isLit) Color.rgb(0, 225, 255) else Color.argb(120, 70, 80, 90)
            canvas.drawCircle(dx, dy, R * 0.017f, paint)
        }
    }

    // ==================== CO-OP ODA, ANA MENÜ, GARAJ, AYARLAR VE BİTİŞ EKRANI ====================
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
            val occ = if (i == 0) true else remotePlayers[i - 1].active
            val mName = if (i == 0) carModels[myAssignedModel] else carModels[remotePlayers[i - 1].modelIdx]
            val txt = if (i == 0) "1. SEN [$mName]" else "${i + 1}. ${if (occ) "${remotePlayers[i - 1].name} [$mName]" else "Araniyor..."}"
            paint.color = if (occ) Color.rgb(75, 245, 130) else Color.rgb(110, 120, 135); paint.textSize = R * 0.058f
            canvas.drawText(txt, cx, sy, paint)
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
        arcPath.reset(); tempRect.set(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f); arcPath.addArc(tempRect, 222f, 96f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 200, 210); paint.textSize = R * 0.095f; paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = 0.18f; canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint); paint.letterSpacing = 0f

        val mapScale = R * 0.00016f; miniPath.reset()
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

        paint.color = Color.WHITE; paint.textSize = R * 0.125f
        canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.22f, paint)

        tempRect.set(cx - R * 0.34f, cy + R * 0.32f, cx + R * 0.34f, cy + R * 0.48f)
        paint.color = Color.rgb(25, 42, 55); canvas.drawRoundRect(tempRect, R * 0.08f, R * 0.08f, paint)
        paint.color = Color.rgb(0, 240, 220); paint.textSize = R * 0.070f
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
        paint.color = Color.rgb(10, 20, 28); paint.textSize = R * 0.12f
        canvas.drawText("START", cx, cy + R * 0.71f, paint)
    }

    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float, dt: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = R * 0.13f
        canvas.drawText("Garage", cx, cy - R * 0.58f, paint)

        garagePreviewYaw += dt * 1.1f
        val sX = camX; val sY = camY; val sYaw = camYaw
        camX = 0f; camY = 0f; camYaw = 0f
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, 0f, carColors[selectedColor], selectedModel, isGhost = false)
        camX = sX; camY = sY; camYaw = sYaw

        tempRect.set(cx - R * 0.56f, cy - R * 0.07f, cx - R * 0.04f, cy + R * 0.11f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.BLACK; paint.textSize = R * 0.072f
        canvas.drawText(carModels[selectedModel], tempRect.centerX(), tempRect.centerY() + R * 0.026f, paint)

        tempRect.set(cx + R * 0.04f, cy - R * 0.07f, cx + R * 0.56f, cy + R * 0.11f)
        paint.color = Color.rgb(24, 30, 38); canvas.drawRoundRect(tempRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawText(carModels[(selectedModel + 1) % carModels.size], tempRect.centerX(), tempRect.centerY() + R * 0.026f, paint)

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
        paint.color = Color.rgb(150, 165, 180); paint.textSize = R * 0.088f
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
        paint.color = if (showFps) Color.rgb(0, 225, 255) else Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = R * 0.072f
        canvas.drawText("SHOW FPS", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)

        tempRect.set(cx + R * 0.03f, cy + R * 0.50f, cx + R * 0.48f, cy + R * 0.70f)
        paint.color = Color.rgb(22, 28, 36); canvas.drawRoundRect(tempRect, R * 0.1f, R * 0.1f, paint)
        paint.color = Color.WHITE; canvas.drawText("BACK", tempRect.centerX(), tempRect.centerY() + R * 0.025f, paint)
    }

    private fun drawSettingRow(canvas: Canvas, cx: Float, y: Float, R: Float, wRatio: Float, label: String, valueText: String, toggleVal: Boolean) {
        tempRect.set(cx - R * wRatio, y - R * 0.11f, cx + R * wRatio, y + R * 0.11f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(20, 26, 34)
        canvas.drawRoundRect(tempRect, R * 0.11f, R * 0.11f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.078f; paint.textAlign = Paint.Align.LEFT
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
        paint.color = if (finalRank == 1) Color.rgb(80, 235, 155) else Color.rgb(255, 195, 45)
        paint.textSize = R * 0.15f
        val totalCars = if (botsEnabled) bots.size + 1 else 1
        val winTitle = if (botsEnabled && finalRank > 1) "FINISHED #$finalRank/$totalCars" else "1ST PLACE WIN!"
        canvas.drawText(winTitle, cx, cy - R * 0.52f, paint)

        if (isNewRecord) {
            tempRect.set(cx - R * 0.36f, cy - R * 0.45f, cx + R * 0.36f, cy - R * 0.32f)
            paint.color = Color.rgb(245, 190, 25); canvas.drawRoundRect(tempRect, R * 0.065f, R * 0.065f, paint)
            paint.color = Color.rgb(20, 20, 20); paint.textSize = R * 0.072f
            canvas.drawText("NEW RECORD!", cx, tempRect.centerY() + R * 0.025f, paint)
        }

        paint.color = Color.WHITE; paint.textSize = R * 0.24f
        canvas.drawText("${score.toInt()}", cx, cy - R * 0.08f, paint)

        paint.color = Color.rgb(175, 185, 195); paint.textSize = R * 0.068f
        canvas.drawText("GOLD ${tracks[currentTrackIdx].goldScore} • BEST ${String.format("%.2fs", bestLapTime)}", cx, cy + R * 0.20f, paint)

        tempRect.set(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28); paint.textSize = R * 0.105f
        canvas.drawText("RESTART", cx, tempRect.centerY() + R * 0.038f, paint)

        paint.color = Color.rgb(160, 175, 190); paint.textSize = R * 0.082f
        canvas.drawText("MENU", cx, cy + R * 0.68f, paint)
    }
}
