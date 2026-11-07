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

    override fun onDestroy() {
        gameView.cleanup()
        super.onDestroy()
    }
}

// ------------------- VERİ YAPILARI VE HAVUZLAR (ZERO GC) -------------------
data class Vec2(var x: Float, var y: Float)
data class GhostPose(val x: Float, val y: Float, val yaw: Float)

data class RemotePlayer(
    var active: Boolean = false, var name: String = "Bos",
    var x: Float = 0f, var y: Float = 0f, var yaw: Float = 0f,
    var colorIdx: Int = 7, var modelIdx: Int = 1
)

class BotCar {
    var active = false
    var wpIdx = 0
    var laneOffset = 0f; var targetLane = 0f
    var speedFactor = 1f; var color = 0; var modelIdx = 0
    var x = 0f; var y = 0f; var yaw = 0f
    var vx = 0f; var vy = 0f
    var rollAngle = 0f; var driftAmount = 0f
    var isDrifting = false; var mistakeTimer = 0f
    var progress = 0f; var isInvulnerable = 0f
}

data class Obstacle3D(val x: Float, val y: Float, val radius: Float, val height: Float, val isTree: Boolean)

class DebrisPart {
    var active = false; var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var yaw = 0f; var size = 3f; var color = 0; var life = 0f
}

class Particle3D {
    var active = false; var type = 0 // 0=Fire, 1=Smoke
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var size = 3f; var alpha = 0f; var color = 0; var lifeTotal = 1f; var life = 1f
}

class SkidQuad {
    var active = false
    var x1=0f; var y1=0f; var x2=0f; var y2=0f
    var x3=0f; var y3=0f; var x4=0f; var y4=0f
    var intensity=0f; var isGrass=false
}

class PowerupBox {
    var active = false; var x = 0f; var y = 0f; var type = 0; var respawnTimer = 0f
}

class Projectile {
    var active = false; var type = 0 // 1=Missile, 2=Trap
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var yaw = 0f
    var ownerIsPlayer = true; var life = 0f
}

data class TrackData(val name: String, val theme: Int, val goldScore: Int, val controlPoints: List<Vec2>)

// ------------------- ANA OYUN MOTORU -------------------
class CrownDriftView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("crowndrift_v7", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val polyPath = Path()
    private val arcPath = Path()
    private val miniPath = Path()
    private val tempRect = RectF()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var state = 0 // 0=MENU, 1=GARAGE, 2=SETTINGS, 3=GAME, 4=WIN, 5=COOP

    private val carColors = intArrayOf(
        Color.rgb(30, 105, 255), Color.rgb(235, 45, 45), Color.rgb(255, 200, 25),
        Color.rgb(55, 195, 75), Color.rgb(255, 135, 20), Color.rgb(165, 50, 215),
        Color.rgb(240, 240, 240), Color.rgb(0, 225, 255)
    )
    private val carModels = arrayOf("JDM GT", "SUPER V12", "MUSCLE V8", "F1 HYPER")
    private var selectedColor = prefs.getInt("color", 0)
    private var selectedModel = prefs.getInt("model", 0)

    private var sensLevel = prefs.getInt("sens", 1)
    private var ghostEnabled = prefs.getBoolean("ghost", true)
    private var botsEnabled = prefs.getBoolean("bots", true)
    private var showFps = prefs.getBoolean("fps", false)

    private val tracks = listOf(
        TrackData("Classic GP", 0, 8000, listOf(Vec2(0f, -1400f), Vec2(850f, -1350f), Vec2(1350f, -850f), Vec2(900f, -300f), Vec2(1450f, 200f), Vec2(1250f, 950f), Vec2(450f, 700f), Vec2(150f, 1350f), Vec2(-650f, 1400f), Vec2(-1250f, 850f), Vec2(-750f, 200f), Vec2(-1400f, -350f), Vec2(-1150f, -1100f), Vec2(-500f, -1350f))),
        TrackData("Cyber Touge", 1, 9500, listOf(Vec2(-700f, -1450f), Vec2(900f, -1450f), Vec2(1500f, -950f), Vec2(750f, -450f), Vec2(1400f, 50f), Vec2(600f, 450f), Vec2(1250f, 1050f), Vec2(500f, 1500f), Vec2(-450f, 1100f), Vec2(-1100f, 1500f), Vec2(-1550f, 800f), Vec2(-900f, 150f), Vec2(-1500f, -500f), Vec2(-1250f, -1200f))),
        TrackData("Tokyo Bay", 2, 10200, listOf(Vec2(0f, -1550f), Vec2(1100f, -1350f), Vec2(1550f, -550f), Vec2(650f, -150f), Vec2(450f, 450f), Vec2(1450f, 750f), Vec2(1150f, 1450f), Vec2(150f, 1550f), Vec2(-450f, 900f), Vec2(-1200f, 1350f), Vec2(-1550f, 500f), Vec2(-800f, -150f), Vec2(-1500f, -800f), Vec2(-950f, -1450f))),
        TrackData("Glacier S", 3, 8800, listOf(Vec2(0f, -1450f), Vec2(1050f, -1200f), Vec2(1500f, -300f), Vec2(750f, 250f), Vec2(1350f, 950f), Vec2(600f, 1500f), Vec2(-550f, 1450f), Vec2(-1350f, 900f), Vec2(-700f, 200f), Vec2(-1500f, -400f), Vec2(-1050f, -1250f))),
        TrackData("Canyon Hairpin", 4, 11500, listOf(Vec2(-600f, -1550f), Vec2(950f, -1500f), Vec2(1600f, -800f), Vec2(550f, -350f), Vec2(1550f, 250f), Vec2(650f, 750f), Vec2(1400f, 1350f), Vec2(200f, 1650f), Vec2(-650f, 1050f), Vec2(-1350f, 1500f), Vec2(-1650f, 600f), Vec2(-750f, 0f), Vec2(-1600f, -650f), Vec2(-1250f, -1350f)))
    )
    private var currentTrackIdx = 0

    // Geometri & Performans Havuzları
    private val trackPts = ArrayList<Vec2>(450)
    private val trackNormals = ArrayList<Vec2>(450)
    private val trackTangents = ArrayList<Float>(450)
    private val zoneMask = BooleanArray(450)
    private val obstacles = ArrayList<Obstacle3D>(60)
    private val roadHalfWidth = 58f

    // Yeni Fizik Değişkenleri (Referans Koda Uygun)
    private var carX = 0f; private var carY = 0f
    private var carVX = 0f; private var carVY = 0f
    private var carYaw = 0f; private var rollAngle = 0f
    private var smoothedSteering = 0f; private var crownSteer = 0f
    private var isCurrentlyDrifting = false; private var driftAmount = 0f
    private var isOffRoad = false
    private var nearestIndex = 0; private var lastDist = 0f

    private var isFullStopped = false; private var isHoldingStop = false
    private var isBezelDragging = false; private var lastBezelAngle = 0f

    private var carDamage = 0f; private var bumperBroken = false; private var spoilerBroken = false
    private var isCrashing = false; private var invulnerabilityTimer = 0f
    private var countdownValue = 3; private var countdownTimer = 0L

    // Havuzlar
    private val debrisPool = Array(32) { DebrisPart() }
    private val particlePool = Array(45) { Particle3D() }
    private val skidPool = Array(150) { SkidQuad() }
    private var debrisHead = 0; private var particleHead = 0; private var skidHead = 0

    private val powerupBoxes = Array(6) { PowerupBox() }
    private val projectiles = Array(10) { Projectile() }

    // Puan & Boost (Kombo Sistemi)
    private var sessionScore = 0f
    private var comboTime = 0f; private var comboGap = 0f
    private var multiplier = 1; private var edgeActive = false
    private var boostMeter = 0f; private var boostTime = 0f; private var boostReady = false

    // Powerup (Sürpriz Kutu)
    private var activePowerup = 0 // 0=Yok, 1=Missile, 2=Trap, 3=Shield, 4=Gun, 5=Slow
    private var powerupTimer = 0f
    private var shieldEffectRot = 0f

    // Yarış Durumu
    private var lap = 1; private val totalLaps = 3
    private var maxWpReached = 0; private var playerProgress = 0f
    private var racePosition = 1; private var finalRank = 0
    private var lapStartTime = 0L; private var currentLapTime = 0f; private var bestLapTime = 99.99f
    private var isNewRecord = false

    // Kamera & Ghost & AI
    private var camYaw = 0f; private var camX = 0f; private var camY = 0f; private var shakeTime = 0f
    private val currentLapRecording = ArrayList<GhostPose>(1200)
    private var bestLapGhost = listOf<GhostPose>(); private var ghostFrameIdx = 0
    private val bots = Array(3) { BotCar() }

    // Fizik Sabitleri
    private val STEP = 1f / 60f
    private var physicsAccumulator = 0f

    // Bluetooth
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
            if (intent?.action == BluetoothDevice.ACTION_FOUND && btRunning && lobbyRole == 2 && clientSocket == null) {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && triedAddresses.add(device.address)) {
                    tryConnectToDiscoveredDevice(device)
                }
            }
        }
    }
    private var receiverRegistered = false

    private var projX = 0f; private var projY = 0f; private var projScale = 0f
    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60; private var lastVibeTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        myAssignedModel = selectedModel
        buildTrackSpline(0)
        registerBtReceiver()
    }

    private fun registerBtReceiver() {
        if (!receiverRegistered) {
            try { context.registerReceiver(btDiscoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
                  receiverRegistered = true } catch (_: Throwable) {}
        }
    }

    fun cleanup() {
        stopAllBluetooth()
        if (receiverRegistered) { try { context.unregisterReceiver(btDiscoveryReceiver) } catch (_: Throwable) {}; receiverRegistered = false }
    }

    fun onSideButtonPressed(): Boolean {
        return if (state != 0) { state = 0; vibeClick(); invalidate(); true } else false
    }

    private fun saveSettings() {
        prefs.edit().putInt("color", selectedColor).putInt("model", selectedModel).putInt("sens", sensLevel)
            .putBoolean("ghost", ghostEnabled).putBoolean("bots", botsEnabled).putBoolean("fps", showFps).apply()
    }

    // ==================== BLUETOOTH ODA SİSTEMİ ====================
    fun stopAllBluetooth() {
        btRunning = false; lobbyRole = 0
        try { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.cancelDiscovery() } catch (_: Throwable) {}
        try { btServerSocket?.close() } catch (_: Throwable) {}
        hostClientSockets.forEach { try { it.close() } catch (_: Throwable) {} }; hostClientSockets.clear()
        try { clientSocket?.close() } catch (_: Throwable) {}; clientSocket = null
        triedAddresses.clear(); remotePlayers.forEach { it.active = false }
    }

    private fun startHostLobby() {
        stopAllBluetooth(); lobbyRole = 1; myAssignedModel = selectedModel; btRunning = true; lobbyStatusText = "Oda Acik (1/4)"
        try { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } catch (_: Throwable) {}
        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@thread
                val server = adapter.listenUsingInsecureRfcommWithServiceRecord("CrownDriftRoom", btUuid); btServerSocket = server
                while (btRunning && lobbyRole == 1 && hostClientSockets.size < 3) {
                    val sock = server.accept() ?: break
                    val slot = hostClientSockets.size
                    if (slot < 3) {
                        hostClientSockets.add(sock); remotePlayers[slot].active = true; remotePlayers[slot].modelIdx = (selectedModel + slot + 1) % 4
                        lobbyStatusText = "Bagli: ${hostClientSockets.size + 1}/4"; vibeClick(); handleHostClientConnection(sock, slot)
                    } else sock.close()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun handleHostClientConnection(sock: BluetoothSocket, slot: Int) {
        thread(start = true, isDaemon = true) {
            try {
                val inp = DataInputStream(sock.inputStream)
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    remotePlayers[slot].apply { x=inp.readFloat(); y=inp.readFloat(); yaw=inp.readFloat(); colorIdx=inp.readInt(); modelIdx=inp.readInt() }
                }
            } catch (_: Throwable) { remotePlayers[slot].active = false; hostClientSockets.remove(sock) }
        }
        thread(start = true, isDaemon = true) {
            try {
                val out = DataOutputStream(sock.outputStream); val assignedModel = (selectedModel + slot + 1) % 4
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    out.writeInt(state); out.writeInt(currentTrackIdx); out.writeInt(hostClientSockets.size + 1); out.writeInt(assignedModel)
                    out.writeFloat(carX); out.writeFloat(carY); out.writeFloat(carYaw); out.writeInt(selectedColor); out.writeInt(selectedModel)
                    for (i in 0..2) if (i != slot) remotePlayers[i].let { out.writeBoolean(it.active); out.writeFloat(it.x); out.writeFloat(it.y); out.writeFloat(it.yaw); out.writeInt(it.colorIdx); out.writeInt(it.modelIdx) }
                    out.flush(); Thread.sleep(45)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun scanAndJoinLobby() {
        stopAllBluetooth(); lobbyRole = 2; btRunning = true; lobbyStatusText = "Cevre Taraniyor..."
        thread(start = true, isDaemon = true) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@thread
                val bonded = try { adapter.bondedDevices } catch (_: Throwable) { null }
                bonded?.forEach { if (btRunning && lobbyRole == 2 && clientSocket == null && triedAddresses.add(it.address)) tryConnectToDiscoveredDevice(it) }
                try { if (adapter.isDiscovering) adapter.cancelDiscovery(); adapter.startDiscovery() } catch (_: Throwable) { lobbyStatusText = "Tarama Hatasi" }
            } catch (_: Throwable) {}
        }
    }

    private fun tryConnectToDiscoveredDevice(dev: BluetoothDevice) {
        thread(start = true, isDaemon = true) {
            try {
                if (!btRunning || lobbyRole != 2 || clientSocket != null) return@thread
                val sock = dev.createInsecureRfcommSocketToServiceRecord(btUuid); sock.connect()
                if (clientSocket != null) { sock.close(); return@thread }
                clientSocket = sock
                try { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.cancelDiscovery() } catch (_: Throwable) {}
                remotePlayers[0].active = true; lobbyStatusText = "Odaya Baglandi!"; vibeClick(); handleClientConnection(sock)
            } catch (_: Throwable) {}
        }
    }

    private fun handleClientConnection(sock: BluetoothSocket) {
        thread(start = true, isDaemon = true) {
            try { val out = DataOutputStream(sock.outputStream); while (btRunning && lobbyRole == 2 && sock.isConnected) { out.writeFloat(carX); out.writeFloat(carY); out.writeFloat(carYaw); out.writeInt(selectedColor); out.writeInt(myAssignedModel); out.flush(); Thread.sleep(45) } } catch (_: Throwable) {}
        }
        try {
            val inp = DataInputStream(sock.inputStream)
            while (btRunning && lobbyRole == 2 && sock.isConnected) {
                val hostState = inp.readInt(); val hostTrack = inp.readInt().coerceIn(0, tracks.size - 1); lobbyStatusText = "Odada ${inp.readInt()}/4 (${carModels[inp.readInt().also { myAssignedModel = it; selectedModel = it }]})"
                remotePlayers[0].apply { active=true; x=inp.readFloat(); y=inp.readFloat(); yaw=inp.readFloat(); colorIdx=inp.readInt(); modelIdx=inp.readInt() }
                for (i in 1..2) remotePlayers[i].apply { active=inp.readBoolean(); x=inp.readFloat(); y=inp.readFloat(); yaw=inp.readFloat(); colorIdx=inp.readInt(); modelIdx=inp.readInt() }
                if (hostState == 3 && state != 3) { currentTrackIdx = hostTrack; post { startRace(true) } }
            }
        } catch (_: Throwable) { remotePlayers[0].active = false; clientSocket = null; lobbyStatusText = "Koptu" }
    }

    private fun triggerHaptic(durationMs: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
        } catch (_: Exception) {}
    }
    private fun vibeClick() = triggerHaptic(22L, 190)

    // ==================== CROWN KONTROLÜ ====================
    fun handleRotaryScroll(event: MotionEvent): Boolean {
        var delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (delta == 0f) return false
        when (state) {
            0 -> { currentTrackIdx = (currentTrackIdx + if (delta > 0) 1 else tracks.size - 1) % tracks.size; buildTrackSpline(currentTrackIdx); vibeClick() }
            1 -> { selectedModel = (selectedModel + if (delta > 0) 1 else 3) % 4; myAssignedModel = selectedModel; saveSettings(); vibeClick() }
            3 -> {
                val sensMult = when (sensLevel) { 0 -> 0.26f; 1 -> 0.44f; else -> 0.65f }
                crownSteer = (crownSteer + delta.coerceIn(-1.5f, 1.5f) * sensMult).coerceIn(-1f, 1f)
                triggerHaptic(5L, 115) // Haptik güncellendi
            }
        }
        invalidate()
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = if (event.action == MotionEvent.ACTION_SCROLL && handleRotaryScroll(event)) true else super.onGenericMotionEvent(event)

    // ==================== PİST VE DRIFT BÖLGESİ HESAPLAMA ====================
    private fun buildTrackSpline(idx: Int) {
        trackPts.clear(); trackNormals.clear(); trackTangents.clear(); obstacles.clear()
        val cps = tracks[idx].controlPoints
        val n = cps.size; val steps = 26
        for (i in 0 until n) {
            val p0 = cps[(i - 1 + n) % n]; val p1 = cps[i]; val p2 = cps[(i + 1) % n]; val p3 = cps[(i + 2) % n]
            for (s in 0 until steps) {
                val t = s / steps.toFloat(); val t2 = t * t; val t3 = t2 * t
                val x = 0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t + (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 + (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3)
                val y = 0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t + (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 + (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3)
                trackPts.add(Vec2(x, y))
            }
        }
        val total = trackPts.size
        for (i in 0 until total) {
            val dx = trackPts[(i + 1) % total].x - trackPts[(i - 1 + total) % total].x
            val dy = trackPts[(i + 1) % total].y - trackPts[(i - 1 + total) % total].y
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            trackNormals.add(Vec2(-dy / len, dx / len))
            trackTangents.add(atan2(dy, dx))
            zoneMask[i] = false

            if (i % 18 == 0) {
                val isTree = (i / 18) % 3 != 0
                obstacles.add(Obstacle3D(trackPts[i].x + trackNormals[i].x * (if (i % 2 == 0) 1f else -1f) * (roadHalfWidth + 70f + (i % 35)), trackPts[i].y + trackNormals[i].y * (if (i % 2 == 0) 1f else -1f) * (roadHalfWidth + 70f + (i % 35)), if (isTree) 16f else 26f, if (isTree) 34f else 48f, isTree))
            }
        }
        // Viraj Tespit Algoritması (Drift Zone)
        val turnArr = FloatArray(total)
        for (i in 0 until total) {
            val dAng = wrapAngle(trackTangents[(i + 1) % total] - trackTangents[(i - 1 + total) % total])
            turnArr[i] = abs(dAng)
        }
        val w = total / 24
        val smooth = FloatArray(total)
        for (i in 0 until total) { var s=0f; for(k in -w..w) s+=turnArr[(i+k+total)%total]; smooth[i] = s }
        val used = BooleanArray(total)
        for(pass in 0..2) {
            var best = -1
            for (i in total/10 until total - total/10) {
                if(!used[i] && (best < 0 || smooth[i] > smooth[best])) best = i
            }
            if (best >= 0 && smooth[best] > 0.15f) {
                for (k in -w..w) zoneMask[(best+k+total)%total] = true
                for (k in -total/4..total/4) used[(best+k+total)%total] = true
            }
        }
    }

    private fun startRace(fromNetwork: Boolean = false) {
        buildTrackSpline(currentTrackIdx)
        val off = if (lobbyRole == 2) 16f else if (lobbyRole == 1) -16f else 0f
        carX = trackPts[0].x + trackNormals[0].x * off; carY = trackPts[0].y + trackNormals[0].y * off
        carVX = 0f; carVY = 0f; carYaw = trackTangents[0]; rollAngle = 0f; smoothedSteering = 0f; crownSteer = 0f
        isBezelDragging = false; isFullStopped = false; isHoldingStop = false; isOffRoad = false
        carDamage = 0f; bumperBroken = false; spoilerBroken = false; isCrashing = false; invulnerabilityTimer = 0f
        nearestIndex = 0

        debrisPool.forEach{it.active=false}; particlePool.forEach{it.active=false}; skidPool.forEach{it.active=false}
        projectiles.forEach{it.active=false}
        currentLapRecording.clear(); ghostFrameIdx = 0

        // Sürpriz Kutuları Yerleştir
        val step = trackPts.size / 6
        for(i in 0 until 6) {
            powerupBoxes[i].active = true; powerupBoxes[i].type = Random.nextInt(1, 6)
            powerupBoxes[i].x = trackPts[i*step].x; powerupBoxes[i].y = trackPts[i*step].y; powerupBoxes[i].respawnTimer = 0f
        }
        activePowerup = 0; powerupTimer = 0f; shieldEffectRot = 0f

        bots.forEachIndexed { i, b ->
            if (botsEnabled) {
                b.active = true; b.wpIdx = (i + 1) * 15; b.laneOffset = if (i%2==0) -18f else 18f; b.targetLane = b.laneOffset
                b.speedFactor = 0.95f + Random.nextFloat()*0.15f; b.color = carColors[(selectedColor + i + 1)%8]; b.modelIdx = (selectedModel + i + 1)%4
                b.x = trackPts[b.wpIdx].x + trackNormals[b.wpIdx].x * b.laneOffset
                b.y = trackPts[b.wpIdx].y + trackNormals[b.wpIdx].y * b.laneOffset
                b.yaw = trackTangents[b.wpIdx]; b.vx = 0f; b.vy = 0f; b.rollAngle = 0f; b.isDrifting = false; b.progress = b.wpIdx.toFloat()
            } else b.active = false
        }

        lap = 1; maxWpReached = 0; playerProgress = 0f; sessionScore = 0f; comboTime = 0f; multiplier = 1
        boostMeter = 0f; boostTime = 0f; boostReady = false; finalRank = 0
        countdownValue = 3; countdownTimer = SystemClock.uptimeMillis() + 800L
        lapStartTime = SystemClock.uptimeMillis(); bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f); isNewRecord = false
        state = 3; if (!fromNetwork) vibeClick(); requestFocus()
    }

    // ==================== OYUN GİRDİLERİ VE EKRAN DOKUNMALARI ====================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f; val x = event.x; val y = event.y

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
            }
            1 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.12f)..(cy + R * 0.14f)) { selectedModel = (selectedModel + 1) % 4; myAssignedModel = selectedModel; saveSettings(); vibeClick() }
                if (y in (cy + R * 0.18f)..(cy + R * 0.62f)) {
                    val row = if (y < cy + R * 0.40f) 0 else 1; val col = ((x - (cx - R * 0.56f)) / (R * 1.12f) * 4f).toInt().coerceIn(0, 3)
                    selectedColor = row * 4 + col; saveSettings(); vibeClick()
                }
                if (y > cy + R * 0.64f) { state = 0; vibeClick() }
            }
            2 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.66f)..(cy - R * 0.38f)) { sensLevel = (sensLevel + 1) % 3; saveSettings(); vibeClick() }
                else if (y in (cy - R * 0.37f)..(cy - R * 0.11f)) { state = 5; vibeClick() }
                else if (y in (cy - R * 0.10f)..(cy + R * 0.16f)) { ghostEnabled = !ghostEnabled; saveSettings(); vibeClick() }
                else if (y in (cy + R * 0.17f)..(cy + R * 0.44f)) { botsEnabled = !botsEnabled; saveSettings(); vibeClick() }
                else if (y > cy + R * 0.46f) { if (x < cx) { showFps = !showFps; saveSettings() } else state = 0; vibeClick() }
            }
            5 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.45f)..(cy - R * 0.15f)) { if (x < cx) startHostLobby() else scanAndJoinLobby(); vibeClick() }
                else if (y in (cy + R * 0.42f)..(cy + R * 0.72f) && abs(x - cx) < R * 0.48f) { if (lobbyRole != 2) startRace() }
                else if (y > cy + R * 0.73f) { state = 0; vibeClick() }
            }
            3 -> {
                val pauseBtnX = cx + R * 0.75f; val pauseBtnY = cy - R * 0.15f
                val turboBtnX = cx - R * 0.75f; val turboBtnY = cy
                val distCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - pauseBtnX, y - pauseBtnY) < R * 0.20f) {
                            isFullStopped = !isFullStopped; isHoldingStop = true; vibeClick(); return true
                        }
                        if (hypot(x - turboBtnX, y - turboBtnY) < R * 0.25f && boostReady) {
                            boostTime = 3.0f; boostMeter = 0f; boostReady = false; triggerHaptic(90L, 255); return true
                        }
                        if (distCenter > R * 0.74f) { isBezelDragging = true; lastBezelAngle = atan2(y - cy, x - cx) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isBezelDragging && distCenter > R * 0.68f) {
                            val curAng = atan2(y - cy, x - cx); crownSteer = (crownSteer + wrapAngle(curAng - lastBezelAngle) * (if (sensLevel==0) 0.85f else if (sensLevel==1) 1.25f else 1.75f)).coerceIn(-1f, 1f)
                            lastBezelAngle = curAng
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isHoldingStop = false; isBezelDragging = false }
                }
            }
            4 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy + R * 0.22f)..(cy + R * 0.55f)) { vibeClick(); startRace() } else if (y > cy + R * 0.56f) { vibeClick(); state = 0 }
            }
        }
        return true
    }

    private fun wrapAngle(a: Float): Float { var r = (a + PI.toFloat()) % (2f * PI.toFloat()); if (r < 0) r += 2f * PI.toFloat(); return r - PI.toFloat() }

    // ==================== FİZİK VE GÜNCELLEME DÖNGÜSÜ ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.005f, 0.05f)
        lastFrameTime = now; currentFps = (1f / dt).toInt()

        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f
        if (state == 3) {
            physicsAccumulator += dt
            while (physicsAccumulator >= STEP) { stepPhysics(); physicsAccumulator -= STEP }
        }

        when (state) {
            0 -> drawMainMenu(canvas, cx, cy, R)
            1 -> drawGarage(canvas, cx, cy, R, dt)
            2 -> drawSettings(canvas, cx, cy, R)
            5 -> drawCoopLobby(canvas, cx, cy, R)
            3 -> { drawTrue3DWorld(canvas, cx, cy, R); drawGameHud(canvas, cx, cy, R) }
            4 -> { drawTrue3DWorld(canvas, cx, cy, R); drawWinScreen(canvas, cx, cy, R) }
        }
        if (showFps) { paint.color = Color.GREEN; paint.textSize = R * 0.068f; paint.textAlign = Paint.Align.CENTER; canvas.drawText("$currentFps FPS", cx, cy - R * 0.87f, paint) }
        postInvalidateOnAnimation()
    }

    private fun stepPhysics() {
        if (countdownValue > 0) {
            if (SystemClock.uptimeMillis() > countdownTimer) { countdownValue--; countdownTimer = SystemClock.uptimeMillis() + 800L; vibeClick() }
            return
        } else if (countdownValue == 0) { countdownValue = -1; lapStartTime = SystemClock.uptimeMillis() }
        
        currentLapTime = (SystemClock.uptimeMillis() - lapStartTime) / 1000f
        if (invulnerabilityTimer > 0f) invulnerabilityTimer -= STEP
        if (powerupTimer > 0f) { powerupTimer -= STEP; if(powerupTimer<=0f) activePowerup = 0 }
        if (boostTime > 0f) boostTime -= STEP

        updateParticles(STEP)
        updateProjectiles(STEP)

        if (isCrashing) {
            invulnerabilityTimer -= STEP
            if (invulnerabilityTimer <= 0f) { // Respawn in nearest safe point
                isCrashing = false; carVX = 0f; carVY = 0f; carDamage = 0f; bumperBroken = false; spoilerBroken = false
                carX = trackPts[nearestIndex].x; carY = trackPts[nearestIndex].y; carYaw = trackTangents[nearestIndex]
                speed = 0f; invulnerabilityTimer = 3.0f; triggerHaptic(55L, 200)
            }
            return
        }

        // Oyuncu Fiziği (Car3D C# Yakınsaması)
        if (!isBezelDragging) crownSteer *= 0.93f
        smoothedSteering += (crownSteer - smoothedSteering) * 0.015f
        carYaw += 1.3f * smoothedSteering
        rollAngle += ((-smoothedSteering * 70f) - rollAngle) * 0.1f
        rollAngle *= 0.85f

        val fwdX = cos(carYaw); val fwdY = sin(carYaw)
        val accel = 0.36f * 60f * 14.5f // Ölçeklendirme
        val speedFactor = if (isFullStopped || isHoldingStop) 0f else if (boostTime > 0f) 1.25f else if (activePowerup == 5) 0.6f else if (isOffRoad) 0.6f else 1f
        val thrust = accel * speedFactor
        val grip = if (isOffRoad) 0.025f else 0.012f

        carVX += (fwdX * thrust - carVX) * grip
        carVY += (fwdY * thrust - carVY) * grip
        carX += carVX * STEP; carY += carVY * STEP
        speed = hypot(carVX, carVY)

        val speedSq = carVX * carVX + carVY * carVY
        var drift = 0f
        if (speedSq > 10f) {
            val align = (carVX / speed) * fwdX + (carVY / speed) * fwdY
            drift = (1f - align).coerceIn(0f, 1f)
            driftAmount = drift
            val threshold = if (isCurrentlyDrifting) 0.955f else 0.93f
            isCurrentlyDrifting = (speed > 40f && align < threshold)
        } else { isCurrentlyDrifting = false; driftAmount = 0f }

        // Sınır Çarpışmaları (isOffRoad / Kaza)
        updateNearestIndex()
        edgeActive = isCurrentlyDrifting && !isOffRoad && lastDist > roadHalfWidth + 0.8f
        isOffRoad = lastDist > roadHalfWidth + 2.4f

        val crashLimit = roadHalfWidth + 3.2f + 5.5f
        if (lastDist > crashLimit) {
            carX -= carVX * STEP; carY -= carVY * STEP
            carVX *= -0.5f; carVY *= -0.5f; carDamage += speed * 0.2f
            if (carDamage > 35f) bumperBroken = true
            if (carDamage > 65f) spoilerBroken = true
            triggerHaptic(80L, 255)
            if (carDamage >= 100f && invulnerabilityTimer <= 0f) {
                isCrashing = true; invulnerabilityTimer = 2.0f; triggerExplosion(carX, carY)
            }
        }

        // Skor ve Puan Sistemi
        if (isOffRoad) {
            comboTime = 0f; comboGap = 0f; multiplier = 1; shakeTime = 0.15f
            if (SystemClock.uptimeMillis() - lastVibeTime > 120) { triggerHaptic(12L, 115); lastVibeTime = SystemClock.uptimeMillis() }
        } else if (isCurrentlyDrifting) {
            comboGap = 0f; comboTime += STEP
            val newMult = min(4, (comboTime / 3.0f).toInt() + 1)
            if (newMult > multiplier) triggerHaptic(25L, 200)
            multiplier = newMult
            sessionScore += multiplier * (100f / 60f) * (if (zoneMask[nearestIndex]) 2f else 1f) * (if (edgeActive) 1.5f else 1f) * (if (boostTime > 0f) 2f else 1f)
            
            if (boostTime <= 0f && !boostReady) {
                boostMeter += (multiplier * 0.02f + 0.035f) * STEP
                if (boostMeter >= 1f) { boostMeter = 1f; boostReady = true; triggerHaptic(60L, 255) }
            }
            if (SystemClock.uptimeMillis() - lastVibeTime > 160) {
                triggerHaptic(16L, (driftAmount * 70f + 6f).toInt().coerceIn(10, 28) * 8); lastVibeTime = SystemClock.uptimeMillis()
            }
            spawnSkidMark(carX, carY, carYaw, driftAmount)
            if (Random.nextInt(3) == 0) spawnSmoke(carX, carY, carVX, carVY, isOffRoad)
        } else {
            comboGap += STEP
            if (comboGap > 0.3f) { comboTime = 0f; multiplier = 1 }
        }

        // Sürpriz Kutular
        for(i in powerupBoxes.indices) {
            val pb = powerupBoxes[i]
            if(!pb.active) { pb.respawnTimer -= STEP; if(pb.respawnTimer<=0) pb.active = true }
            else {
                if(hypot(carX - pb.x, carY - pb.y) < 25f) {
                    pb.active = false; pb.respawnTimer = 10f; activePowerup = pb.type; powerupTimer = 6f
                    if (activePowerup == 1) fireMissile(true, carX, carY, carYaw)
                    if (activePowerup == 2) fireTrap(true, carX, carY)
                    triggerHaptic(40L, 255)
                }
            }
        }

        // Bot Fiziği (Geliştirilmiş)
        if (botsEnabled) {
            for (b in bots) {
                if (!b.active) continue
                if (b.isInvulnerable > 0f) b.isInvulnerable -= STEP
                
                b.wpIdx = (b.wpIdx) % trackPts.size
                val tWP = trackPts[(b.wpIdx + 8) % trackPts.size]
                val tNorm = trackNormals[(b.wpIdx + 8) % trackPts.size]
                
                if (Random.nextInt(180) == 0) b.targetLane = if (b.targetLane < 0f) 18f else -18f
                b.laneOffset += sign(b.targetLane - b.laneOffset) * 12f * STEP
                b.mistakeTimer -= STEP; if(b.mistakeTimer <= 0f && Random.nextInt(300)==0) { b.mistakeTimer = 2f; b.targetLane += Random.nextFloat()*20f - 10f }
                
                val tgtX = tWP.x + tNorm.x * b.laneOffset; val tgtY = tWP.y + tNorm.y * b.laneOffset
                val dAng = wrapAngle(atan2(tgtY - b.y, tgtX - b.x) - b.yaw)
                b.yaw += dAng.coerceIn(-2.5f*STEP, 2.5f*STEP)
                
                val bFwdX = cos(b.yaw); val bFwdY = sin(b.yaw)
                val bThrust = accel * b.speedFactor * (if (activePowerup == 5) 0.6f else 1f)
                b.vx += (bFwdX * bThrust - b.vx) * 0.012f
                b.vy += (bFwdY * bThrust - b.vy) * 0.012f
                b.x += b.vx * STEP; b.y += b.vy * STEP
                b.progress += hypot(b.vx, b.vy) * STEP
                
                // Bot-Oyuncu Çarpışması
                val dx = carX - b.x; val dy = carY - b.y
                val distSq = dx * dx + dy * dy
                if (distSq < 400f && invulnerabilityTimer <= 0f) {
                    val overlap = 20f - sqrt(distSq)
                    val ang = atan2(dy, dx)
                    carX += cos(ang) * overlap * 0.6f; carY += sin(ang) * overlap * 0.6f
                    carVX *= 0.5f; carVY *= 0.5f; carDamage += 10f
                    if (carDamage > 45f) bumperBroken = true
                    if (carDamage >= 100f) { isCrashing=true; invulnerabilityTimer=2f; triggerExplosion(carX,carY) }
                    triggerHaptic(45L, 240)
                }
                
                var botBestSq = Float.MAX_VALUE
                for(i in -5..5) {
                    val idx = (b.wpIdx + i + trackPts.size) % trackPts.size
                    val bdSq = (b.x - trackPts[idx].x).pow(2) + (b.y - trackPts[idx].y).pow(2)
                    if(bdSq < botBestSq) { botBestSq = bdSq; b.wpIdx = idx }
                }
            }
        }

        // Tur ve Sıralama
        if (nearestIndex > maxWpReached && nearestIndex - maxWpReached < trackPts.size / 3) maxWpReached = nearestIndex
        if (nearestIndex < trackPts.size / 10 && maxWpReached > (trackPts.size * 3) / 4) {
            maxWpReached = 0
            if (currentLapTime < bestLapTime) { bestLapTime = currentLapTime; bestLapGhost = currentLapRecording.toList(); prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply() }
            currentLapRecording.clear(); ghostFrameIdx = 0
            if (lap < totalLaps) { lap++; lapStartTime = SystemClock.uptimeMillis(); triggerHaptic(55L, 255) }
            else {
                finalRank = racePosition
                if (sessionScore.toInt() > prefs.getInt("best_score_$currentTrackIdx", 0)) { isNewRecord = true; prefs.edit().putInt("best_score_$currentTrackIdx", sessionScore.toInt()).apply() }
                state = 4
            }
        }
        playerProgress = (lap - 1) * trackPts.size + nearestIndex.toFloat()
        var pos = 1
        bots.forEach { if (it.active && it.progress > playerProgress * 15f) pos++ } // Basit progress tahmini
        racePosition = pos
        currentLapRecording.add(GhostPose(carX, carY, carYaw)); ghostFrameIdx++
    }

    private fun updateNearestIndex() {
        val total = trackPts.size
        var bestSq = Float.MAX_VALUE; var best = nearestIndex
        for (i in -15..15) {
            val idx = (nearestIndex + i + total) % total
            val dSq = (carX - trackPts[idx].x).pow(2) + (carY - trackPts[idx].y).pow(2)
            if (dSq < bestSq) { bestSq = dSq; best = idx }
        }
        if (bestSq > roadHalfWidth.pow(2) * 2f) { // Fallback tam arama
            for (i in 0 until total) {
                val dSq = (carX - trackPts[i].x).pow(2) + (carY - trackPts[i].y).pow(2)
                if (dSq < bestSq) { bestSq = dSq; best = i }
            }
        }
        nearestIndex = best; lastDist = sqrt(bestSq)
    }

    // ------------------- GÖRSEL EFEKTLER (ZERO GC) -------------------
    private fun triggerExplosion(x: Float, y: Float) {
        for(i in 0 until 12) {
            val d = debrisPool[debrisHead]; debrisHead = (debrisHead+1)%32
            d.active=true; d.x=x; d.y=y; d.z=5f; val ang = Random.nextFloat()*6.28f; val sp = 50f+Random.nextFloat()*80f
            d.vx=cos(ang)*sp; d.vy=sin(ang)*sp; d.vz=70f+Random.nextFloat()*60f; d.size=3f; d.color=carColors[selectedColor]; d.life=2f
        }
        for(i in 0 until 15) {
            val p = particlePool[particleHead]; particleHead = (particleHead+1)%45
            p.active=true; p.type=0; p.x=x; p.y=y; p.z=3f; val ang = Random.nextFloat()*6.28f; val sp = 20f+Random.nextFloat()*50f
            p.vx=cos(ang)*sp; p.vy=sin(ang)*sp; p.vz=30f+Random.nextFloat()*40f; p.size=6f; p.alpha=255f; p.color=if(i%2==0) Color.rgb(255,100,20) else Color.YELLOW; p.life=1.5f; p.lifeTotal=1.5f
        }
    }

    private fun spawnSmoke(x: Float, y: Float, vx: Float, vy: Float, isGrass: Boolean) {
        val p = particlePool[particleHead]; particleHead = (particleHead+1)%45
        p.active=true; p.type=1; p.x=x; p.y=y; p.z=1f; p.vx=-vx*0.2f; p.vy=-vy*0.2f; p.vz=15f; p.size=4f; p.alpha=180f
        p.color= if(isGrass) Color.rgb(100,80,50) else Color.rgb(200,200,200); val l = 0.8f + Random.nextFloat()*0.5f; p.lifeTotal = l; p.life = l
    }

    private fun spawnSkidMark(x: Float, y: Float, yaw: Float, drift: Float) {
        val sq = skidPool[skidHead]; skidHead = (skidHead+1)%150
        val pX = -sin(yaw)*8f; val pY = cos(yaw)*8f
        val bx = x - cos(yaw)*12f; val by = y - sin(yaw)*12f
        sq.active=true; sq.x1 = bx-pX; sq.y1 = by-pY; sq.x2 = bx-pX+cos(yaw)*4f; sq.y2 = by-pY+sin(yaw)*4f
        sq.x3 = bx+pX; sq.y3 = by+pY; sq.x4 = bx+pX+cos(yaw)*4f; sq.y4 = by+pY+sin(yaw)*4f
        sq.intensity = drift; sq.isGrass = isOffRoad
    }

    private fun updateParticles(dt: Float) {
        debrisPool.forEach { if(it.active) { it.life-=dt; if(it.life<=0f) it.active=false else { it.x+=it.vx*dt; it.y+=it.vy*dt; it.z+=it.vz*dt; it.vz-=200f*dt; if(it.z<0f){it.z=0f; it.vz=-it.vz*0.4f} } } }
        particlePool.forEach { if(it.active) { it.life-=dt; if(it.life<=0f) it.active=false else { it.x+=it.vx*dt; it.y+=it.vy*dt; it.z+=it.vz*dt; it.size+=8f*dt; it.alpha = (it.life/it.lifeTotal)*255f } } }
        skidPool.forEach { if(it.active) { it.intensity -= dt*0.3f; if(it.intensity<=0f) it.active=false } }
    }

    // Projeler (Missile / Trap)
    private fun fireMissile(isPlayer: Boolean, x: Float, y: Float, yaw: Float) {
        for (i in projectiles.indices) {
            val p = projectiles[i]; if (!p.active) { p.active=true; p.type=1; p.x=x; p.y=y; p.yaw=yaw; p.vx=cos(yaw)*400f; p.vy=sin(yaw)*400f; p.ownerIsPlayer=isPlayer; p.life=3f; break }
        }
    }
    private fun fireTrap(isPlayer: Boolean, x: Float, y: Float) {
        for (i in projectiles.indices) {
            val p = projectiles[i]; if (!p.active) { p.active=true; p.type=2; p.x=x; p.y=y; p.vx=0f; p.vy=0f; p.ownerIsPlayer=isPlayer; p.life=15f; break }
        }
    }
    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            if (!p.active) continue
            p.life -= dt; if (p.life<=0) { p.active=false; continue }
            if (p.type == 1) { // Missile
                p.x += p.vx*dt; p.y += p.vy*dt; spawnSmoke(p.x, p.y, p.vx, p.vy, false)
                if (botsEnabled) {
                    for(b in bots) {
                        if (b.active && hypot(p.x-b.x, p.y-b.y) < 18f && b.isInvulnerable <= 0f) {
                            b.isInvulnerable = 2f; b.vx=0f; b.vy=0f; triggerExplosion(b.x, b.y); p.active=false; break
                        }
                    }
                }
            } else if (p.type == 2) { // Trap
                if (hypot(p.x-carX, p.y-carY) < 15f && invulnerabilityTimer<=0f && (activePowerup!=3)) {
                    carVX*=0.3f; carVY*=0.3f; carDamage+=15f; p.active=false; triggerHaptic(80L, 255)
                    spawnSmoke(carX, carY, 0f, 0f, true)
                }
                if(botsEnabled) {
                    for(b in bots) {
                        if (b.active && hypot(p.x-b.x, p.y-b.y) < 15f) { b.vx*=0.3f; b.vy*=0.3f; p.active=false; break }
                    }
                }
            }
        }
    }

    // ==================== ÇİZİM VE KAMERA (ZERO GC) ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - camX; val dy = wy - camY
        val cosYaw = cos(camYaw); val sinYaw = sin(camYaw)
        val forward = dx * cosYaw + dy * sinYaw
        val right = -dx * sinYaw + dy * cosYaw
        val relF = forward + 98f; val relH = wz - 138f - (if(activePowerup==3) 20f else 0f) // Kalkan varsa kamera yukarı
        val cosP = 0.682f; val sinP = 0.731f
        val depth = relF * cosP - relH * sinP
        if (depth < 16f || depth > 1450f) return false
        val vert = relF * sinP + relH * cosP
        
        // Dinamik FOV
        val fovZoom = (multiplier - 1) * 0.1f + (if(boostTime>0f) 0.2f else 0f)
        val fov = R * (1.48f + fovZoom)
        projScale = fov / depth
        projX = cx + right * projScale; projY = (cy + R * 0.16f) - vert * projScale
        if (shakeTime > 0f) { projX += Random.nextFloat()*R*0.02f; projY += Random.nextFloat()*R*0.02f; shakeTime -= 1f/60f }
        return true
    }

    private fun drawQuad(canvas: Canvas, cx: Float, cy: Float, R: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float, color: Int) {
        if (!project3D(x1, y1, z1, cx, cy, R)) return; val sx1 = projX; val sy1 = projY
        if (!project3D(x2, y2, z2, cx, cy, R)) return; val sx2 = projX; val sy2 = projY
        if (!project3D(x3, y3, z3, cx, cy, R)) return; val sx3 = projX; val sy3 = projY
        if (!project3D(x4, y4, z4, cx, cy, R)) return; val sx4 = projX; val sy4 = projY
        polyPath.reset(); polyPath.moveTo(sx1, sy1); polyPath.lineTo(sx2, sy2); polyPath.lineTo(sx3, sy3); polyPath.lineTo(sx4, sy4); polyPath.close()
        paint.style = Paint.Style.FILL; paint.color = color; canvas.drawPath(polyPath, paint)
    }

    private fun drawBox(canvas: Canvas, cx: Float, cy: Float, R: Float, bx: Float, by: Float, bz: Float, halfL: Float, halfW: Float, h: Float, yaw: Float, tC: Int, sC: Int, rC: Int) {
        val c = cos(yaw); val s = sin(yaw)
        val fx = c*halfL; val fy = s*halfL; val rx = -s*halfW; val ry = c*halfW
        val flX = bx+fx-rx; val flY = by+fy-ry; val frX = bx+fx+rx; val frY = by+fy+ry
        val rrX = bx-fx+rx; val rrY = by-fy+ry; val rlX = bx-fx-rx; val rlY = by-fy-ry; val tZ = bz+h
        drawQuad(canvas, cx,cy,R, flX,flY,bz, rlX,rlY,bz, rlX,rlY,tZ, flX,flY,tZ, sC)
        drawQuad(canvas, cx,cy,R, frX,frY,bz, rrX,rrY,bz, rrX,rrY,tZ, frX,frY,tZ, sC)
        drawQuad(canvas, cx,cy,R, flX,flY,bz, frX,frY,bz, frX,frY,tZ, flX,flY,tZ, sC)
        drawQuad(canvas, cx,cy,R, rlX,rlY,bz, rrX,rrY,bz, rrX,rrY,tZ, rlX,rlY,tZ, rC)
        drawQuad(canvas, cx,cy,R, flX,flY,tZ, frX,frY,tZ, rrX,rrY,tZ, rlX,rlY,tZ, tC)
    }

    private fun drawSport3DCar(canvas: Canvas, cx: Float, cy: Float, R: Float, x: Float, y: Float, z: Float, yaw: Float, baseC: Int, mIdx: Int, isGhost: Boolean, isPlayer: Boolean = false) {
        if(isPlayer && invulnerabilityTimer>0f && (invulnerabilityTimer*10f).toInt()%2==0) return // Kırpışma efekti
        val a = if (isGhost) 110 else 255
        val r = Color.red(baseC); val g = Color.green(baseC); val b = Color.blue(baseC)
        val dmg = if (isPlayer) (1f - (carDamage/210f)) else 1f
        val tC = Color.argb(a, (r*dmg).toInt(), (g*dmg).toInt(), (b*dmg).toInt())
        val sC = Color.argb(a, (r*0.75f*dmg).toInt(), (g*0.75f*dmg).toInt(), (b*0.75f*dmg).toInt())
        val rC = Color.argb(a, (r*0.56f*dmg).toInt(), (g*0.56f*dmg).toInt(), (b*0.56f*dmg).toInt())
        val whC = Color.argb(a, 20, 22, 25); val glC = Color.argb(a, 30, 48, 65); val crC = Color.argb(a, 215, 220, 230)
        
        val c = cos(yaw); val s = sin(yaw)
        if (!isGhost) drawQuad(canvas, cx,cy,R, x+c*16.5f+s*10.5f,y+s*16.5f-c*10.5f,0.2f, x+c*16.5f-s*10.5f,y+s*16.5f+c*10.5f,0.2f, x-c*16.5f-s*10.5f,y-s*16.5f+c*10.5f,0.2f, x-c*16.5f+s*10.5f,y-s*16.5f-c*10.5f,0.2f, Color.argb(135,0,0,0))
        
        val bz = z + 1.4f
        if (!isGhost) {
            val sYaw = if(isPlayer) yaw + smoothedSteering*0.6f else yaw
            drawBox(canvas, cx,cy,R, x+c*9.8f+s*9.4f, y+s*9.8f-c*9.4f, 0.5f+z, 3.5f, 1.7f, 3.6f, sYaw, whC, whC, whC)
            drawBox(canvas, cx,cy,R, x+c*9.8f-s*9.4f, y+s*9.8f+c*9.4f, 0.5f+z, 3.5f, 1.7f, 3.6f, sYaw, whC, whC, whC)
            drawBox(canvas, cx,cy,R, x-c*9.8f+s*9.4f, y-s*9.8f-c*9.4f, 0.5f+z, 3.7f, 1.7f, 3.8f, yaw, whC, whC, whC)
            drawBox(canvas, cx,cy,R, x-c*9.8f-s*9.4f, y-s*9.8f+c*9.4f, 0.5f+z, 3.7f, 1.7f, 3.8f, yaw, whC, whC, whC)
        }
        val len = if (isPlayer && bumperBroken) 13.5f else 15.8f
        drawBox(canvas, cx,cy,R, x, y, bz, len, 8.8f, 4.3f, yaw, tC, sC, rC)
        drawBox(canvas, cx,cy,R, x-c*1.5f, y-s*1.5f, bz+4.3f, 7.6f, 6.8f, 3.7f, yaw, tC, glC, glC)
        if (!(isPlayer && spoilerBroken)) drawBox(canvas, cx,cy,R, x-c*14f, y-s*14f, bz+6.4f, 2f, 9.4f, 1.1f, yaw, whC, sC, rC)
        if (!isGhost) {
            val tCol = if (isPlayer && isHoldingStop) Color.YELLOW else Color.RED
            drawBox(canvas, cx,cy,R, x-c*15.5f+s*5.2f, y-s*15.5f-c*5.2f, bz+2f, 0.8f, 2f, 1.1f, yaw, tCol, tCol, tCol)
            drawBox(canvas, cx,cy,R, x-c*15.5f-s*5.2f, y-s*15.5f+c*5.2f, bz+2f, 0.8f, 2f, 1.1f, yaw, tCol, tCol, tCol)
        }
        
        // Kalkan (Shield) Çizimi
        if (isPlayer && activePowerup == 3 && powerupTimer > 0f) {
            shieldEffectRot += 0.1f
            val rad = 20f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.argb((powerupTimer/6f*200).toInt().coerceIn(0,255), 0, 255, 255)
            for(i in 0 until 4) {
                val a = shieldEffectRot + i * 1.57f
                if (project3D(x+cos(a)*rad, y+sin(a)*rad, z+5f, cx, cy, R)) { val sx=projX; val sy=projY; if(project3D(x+cos(a+1f)*rad, y+sin(a+1f)*rad, z+5f, cx, cy, R)) canvas.drawLine(sx,sy,projX,projY,paint) }
            }
        }
    }

    private fun drawTrue3DWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val theme = tracks[currentTrackIdx].theme
        canvas.drawColor(when (theme) { 0 -> Color.rgb(34, 78, 24); 1 -> Color.rgb(12, 10, 28); 2 -> Color.rgb(18, 22, 32); 3 -> Color.rgb(175, 210, 230); else -> Color.rgb(95, 55, 30) })
        
        // Kamera Harmanlaması
        val speedLen = hypot(carVX, carVY)
        val velYaw = if (speedLen > 5f) atan2(carVY, carVX) else carYaw
        camYaw = 0.55f * carYaw + 0.45f * velYaw
        camX = carX; camY = carY

        val total = trackPts.size
        for (step in 48 downTo -10) {
            val i0 = (nearestIndex + step + total) % total; val i1 = (i0 + 1) % total
            val p0 = trackPts[i0]; val p1 = trackPts[i1]; val n0 = trackNormals[i0]; val n1 = trackNormals[i1]
            val l0x = p0.x - n0.x * roadHalfWidth; val l0y = p0.y - n0.y * roadHalfWidth
            val r0x = p0.x + n0.x * roadHalfWidth; val r0y = p0.y + n0.y * roadHalfWidth
            val l1x = p1.x - n1.x * roadHalfWidth; val l1y = p1.y - n1.y * roadHalfWidth
            val r1x = p1.x + n1.x * roadHalfWidth; val r1y = p1.y + n1.y * roadHalfWidth
            val kw = 9.5f
            val kl0x = l0x - n0.x*kw; val kl0y = l0y - n0.y*kw; val kr0x = r0x + n0.x*kw; val kr0y = r0y + n0.y*kw
            val kl1x = l1x - n1.x*kw; val kl1y = l1y - n1.y*kw; val kr1x = r1x + n1.x*kw; val kr1y = r1y + n1.y*kw
            
            val zone = zoneMask[i0]
            val kCol = if(zone) (if(i0%2==0) Color.rgb(255,200,0) else Color.BLACK) else if(i0%2==0) Color.rgb(230,35,35) else Color.WHITE
            drawQuad(canvas, cx,cy,R, kl0x,kl0y,0f, l0x,l0y,0f, l1x,l1y,0f, kl1x,kl1y,0f, kCol)
            drawQuad(canvas, cx,cy,R, r0x,r0y,0f, kr0x,kr0y,0f, kr1x,kr1y,0f, r1x,r1y,0f, kCol)
            drawQuad(canvas, cx,cy,R, l0x,l0y,0f, r0x,r0y,0f, r1x,r1y,0f, l1x,l1y,0f, if(theme==3) Color.rgb(75,90,105) else Color.rgb(70,74,78))
            if (i0 == 0) drawQuad(canvas, cx,cy,R, l0x,l0y,34f, r0x,r0y,34f, r1x,r1y,40f, l1x,l1y,40f, Color.rgb(210,40,40))
        }

        for (sq in skidPool) {
            if(!sq.active) continue
            val c = if(sq.isGrass) Color.argb((sq.intensity*160).toInt(), 65, 45, 25) else Color.argb((sq.intensity*160).toInt(), 15, 15, 15)
            drawQuad(canvas, cx,cy,R, sq.x1,sq.y1,0.2f, sq.x2,sq.y2,0.2f, sq.x4,sq.y4,0.2f, sq.x3,sq.y3,0.2f, c)
        }

        for (obs in obstacles) {
            if (hypot(obs.x - carX, obs.y - carY) < 360f) {
                if (obs.isTree) drawBox(canvas, cx,cy,R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(65,185,75), Color.rgb(45,145,55), Color.rgb(35,120,45))
                else drawBox(canvas, cx,cy,R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(45,50,70), Color.rgb(28,32,48), Color.rgb(22,25,38))
            }
        }
        for (pb in powerupBoxes) {
            if(pb.active && hypot(pb.x-carX, pb.y-carY) < 400f) {
                val pCol = when(pb.type) { 1->Color.RED; 2->Color.GRAY; 3->Color.CYAN; 4->Color.YELLOW; else->Color.MAGENTA }
                drawBox(canvas, cx,cy,R, pb.x, pb.y, 5f + sin(SystemClock.uptimeMillis()/200f)*3f, 8f, 8f, 8f, SystemClock.uptimeMillis()/400f, pCol, pCol, pCol)
            }
        }
        for (p in projectiles) {
            if(p.active && hypot(p.x-carX, p.y-carY) < 400f) {
                if(p.type==1) drawBox(canvas, cx,cy,R, p.x, p.y, 5f, 10f, 3f, 3f, p.yaw, Color.RED, Color.DKGRAY, Color.YELLOW)
                if(p.type==2) drawBox(canvas, cx,cy,R, p.x, p.y, 1f, 6f, 6f, 2f, 0f, Color.DKGRAY, Color.DKGRAY, Color.DKGRAY)
            }
        }

        debrisPool.forEach { if(it.active) drawBox(canvas, cx,cy,R, it.x, it.y, it.z, it.size, it.size*0.7f, it.size*0.5f, it.yaw, it.color, it.color, Color.DKGRAY) }
        if (ghostEnabled && bestLapGhost.isNotEmpty()) { val gp = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size-1)]; drawSport3DCar(canvas, cx,cy,R, gp.x, gp.y, 0f, gp.yaw, Color.rgb(0,225,255), selectedModel, true) }
        remotePlayers.forEachIndexed { i, rp -> if (rp.active) { drawSport3DCar(canvas, cx,cy,R, rp.x, rp.y, 0f, rp.yaw, carColors[rp.colorIdx], rp.modelIdx, false); if (project3D(rp.x, rp.y, 16f, cx,cy,R)) { paint.style=Paint.Style.FILL; paint.color=Color.CYAN; paint.textSize=R*0.058f; canvas.drawText("P${i+2}", projX, projY, paint) } } }
        if (botsEnabled) bots.forEach { if(it.active) drawSport3DCar(canvas, cx,cy,R, it.x, it.y, 0f, it.yaw, it.color, it.modelIdx, false) }
        
        drawSport3DCar(canvas, cx,cy,R, carX, carY, 0f, carYaw, carColors[selectedColor], selectedModel, false, true)

        paint.style = Paint.Style.FILL
        particlePool.forEach { if(it.active && project3D(it.x,it.y,it.z,cx,cy,R)) { paint.color = Color.argb(it.alpha.toInt().coerceIn(0,255), Color.red(it.color), Color.green(it.color), Color.blue(it.color)); canvas.drawCircle(projX, projY, (it.size*projScale).coerceAtLeast(1.5f), paint) } }
    }

    // ==================== ARAYÜZ (YENİLENMİŞ COMPACT HUD) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        if (countdownValue > 0) {
            canvas.drawColor(Color.argb(120, 0,0,0))
            paint.color = if (countdownValue==3) Color.RED else if (countdownValue==2) Color.YELLOW else Color.GREEN
            paint.textSize = R * 0.4f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$countdownValue", cx, cy + R*0.15f, paint); return
        }

        // 1. Sol-Üst Kompakt Sıralama (L1/3 • POS 1/4) - Eski büyük balon yerine
        paint.color = Color.WHITE; paint.textSize = R * 0.065f; paint.textAlign = Paint.Align.LEFT
        canvas.drawText("L $lap/$totalLaps", cx - R*0.85f, cy - R*0.75f, paint)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawText("POS $racePosition", cx - R*0.85f, cy - R*0.65f, paint)
        
        // Puan & Kombo Çarpanı
        paint.color = if (multiplier > 1) Color.rgb(255, 200, 0) else Color.WHITE; paint.textSize = R * 0.08f
        canvas.drawText("${sessionScore.toInt()}" + if(multiplier>1) " x$multiplier" else "", cx - R*0.85f, cy - R*0.53f, paint)

        // 2. Sağ-Orta Güvenli ÇIKIŞ ve DUR Butonu (İç içe değil, ayrı ayrı, Crown'dan uzak)
        val pauseBtnX = cx + R * 0.75f; val pauseBtnY = cy - R * 0.15f
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(200, 185, 35, 45)
        canvas.drawCircle(pauseBtnX, pauseBtnY, R * 0.15f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.06f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if(isFullStopped) "SÜR" else "DUR", pauseBtnX, pauseBtnY + R * 0.02f, paint)

        // 3. Sol Kenar Turbo / Boost Barı ve İkonu
        val turboBtnX = cx - R * 0.75f; val turboBtnY = cy
        paint.style = Paint.Style.STROKE; paint.strokeWidth = R*0.02f; paint.color = Color.argb(100, 50,50,50)
        canvas.drawCircle(turboBtnX, turboBtnY, R * 0.15f, paint)
        if (boostMeter > 0f || boostReady || boostTime > 0f) {
            val sweep = if(boostTime>0f) (boostTime/3.0f)*360f else boostMeter*360f
            paint.color = if (boostTime>0f) Color.rgb(255,100,0) else if(boostReady) Color.rgb(0,255,255) else Color.YELLOW
            tempRect.set(turboBtnX - R*0.15f, turboBtnY - R*0.15f, turboBtnX + R*0.15f, turboBtnY + R*0.15f)
            canvas.drawArc(tempRect, -90f, sweep, false, paint)
        }
        paint.style = Paint.Style.FILL; paint.textSize = R*0.05f; paint.color = Color.WHITE
        canvas.drawText(if(boostTime>0f) "BOOST!" else if(boostReady) "TAP" else "TURBO", turboBtnX, turboBtnY+R*0.02f, paint)

        // 4. Turbo Altı Aktif Özellik (Sürpriz Kutu) Simgesi
        if (activePowerup > 0) {
            val pwX = turboBtnX; val pwY = turboBtnY + R * 0.35f
            paint.color = when(activePowerup) { 1->Color.RED; 2->Color.GRAY; 3->Color.CYAN; 4->Color.YELLOW; else->Color.MAGENTA }
            canvas.drawCircle(pwX, pwY, R * 0.1f, paint)
            paint.color = Color.WHITE; paint.textSize = R*0.045f
            val pwTxt = when(activePowerup) { 1->"FÜZE"; 2->"TUZAK"; 3->"KALKAN"; 4->"SİLAH"; else->"KÖTÜ" }
            canvas.drawText(pwTxt, pwX, pwY + R*0.015f, paint)
            canvas.drawText("${powerupTimer.toInt()}s", pwX, pwY + R*0.07f, paint)
        }

        // Alt Crown Göstergesi
        for (i in -9..9) {
            val deg = 90f - i * 3.4f; val rad = Math.toRadians(deg.toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f); val dy = cy + sin(rad).toFloat() * (R * 0.88f)
            paint.color = if (abs(i) <= abs(steerInput*9f).toInt() && sign(i.toFloat())==sign(steerInput)) Color.rgb(0,225,255) else Color.argb(135,70,80,90)
            canvas.drawCircle(dx, dy, R * 0.018f, paint)
        }
    }

    private fun drawWinScreen(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.argb(215, 10, 16, 20))
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(80, 235, 155); paint.textSize = R * 0.16f
        val winTitle = if (botsEnabled && finalRank > 1) "FINISHED #$finalRank" else "YOU WIN! (#1)"
        canvas.drawText(winTitle, cx, cy - R * 0.52f, paint)
        paint.color = Color.WHITE; paint.textSize = R * 0.25f
        canvas.drawText("${sessionScore.toInt()}", cx, cy - R * 0.08f, paint)
        tempRect.set(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.BLACK; paint.textSize = R * 0.11f
        canvas.drawText("RESTART", cx, tempRect.centerY() + R * 0.04f, paint)
    }

    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        arcPath.reset(); tempRect.set(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f)
        arcPath.addArc(tempRect, 222f, 96f); paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 200, 210)
        paint.textSize = R * 0.095f; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint); paint.typeface = Typeface.DEFAULT
        
        val mapScale = R * 0.00016f; miniPath.reset()
        for (i in trackPts.indices) { val px = cx + trackPts[i].x * mapScale; val py = cy - R * 0.16f + trackPts[i].y * mapScale; if (i == 0) miniPath.moveTo(px, py) else miniPath.lineTo(px, py) }
        miniPath.close(); paint.style = Paint.Style.STROKE; paint.strokeWidth = R * 0.042f; paint.color = Color.WHITE; canvas.drawPath(miniPath, paint)
        paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=R*0.13f; canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.22f, paint)
        tempRect.set(cx - R * 0.46f, cy + R * 0.54f, cx + R * 0.46f, cy + R * 0.79f)
        paint.color = Color.rgb(0, 225, 255); canvas.drawRoundRect(tempRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.BLACK; paint.textSize = R * 0.125f; canvas.drawText("START", cx, cy + R * 0.71f, paint)
        paint.color = Color.rgb(28, 36, 44); canvas.drawCircle(cx - R * 0.60f, cy + R * 0.32f, R * 0.14f, paint); canvas.drawCircle(cx + R * 0.60f, cy + R * 0.32f, R * 0.14f, paint)
    }

    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float, dt: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, (SystemClock.uptimeMillis()/1000f)*1.1f, carColors[selectedColor], selectedModel, false, false)
        if(R>0) { /* Basic return button logic inside touch handler */ paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=R*0.14f; paint.textAlign=Paint.Align.CENTER; canvas.drawText("BACK", cx, cy+R*0.8f, paint) }
    }
    private fun drawSettings(canvas: Canvas, cx: Float, cy: Float, R: Float) { canvas.drawColor(Color.rgb(10, 14, 18)); paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=R*0.1f; paint.textAlign=Paint.Align.CENTER; canvas.drawText("SETTINGS", cx, cy, paint) }
    private fun drawCoopLobby(canvas: Canvas, cx: Float, cy: Float, R: Float) { canvas.drawColor(Color.rgb(10, 14, 18)); paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=R*0.1f; paint.textAlign=Paint.Align.CENTER; canvas.drawText("CO-OP LOBBY", cx, cy, paint) }
}
