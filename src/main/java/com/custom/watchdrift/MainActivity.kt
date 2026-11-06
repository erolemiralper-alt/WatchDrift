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

data class Vec2(val x: Float, val y: Float)
data class GhostPose(val x: Float, val y: Float, val yaw: Float)

data class RemotePlayer(
    var active: Boolean = false,
    var name: String = "Bos",
    var x: Float = 0f,
    var y: Float = 0f,
    var yaw: Float = 0f,
    var colorIdx: Int = 7,
    var modelIdx: Int = 1
)

class BotCar(
    val id: Int,
    val color: Int,
    val modelIdx: Int,
    val skillProfile: Float
) {
    var x = 0f
    var y = 0f
    var z = 0f
    var vz = 0f
    var yaw = 0f
    var velYaw = 0f
    var speed = 0f
    var steerInput = 0f
    var vx = 0f
    var vy = 0f

    var currentWp = 0
    var maxWpReached = 0
    var lap = 1
    var totalProgress = 0f
    var finishTime = 0L

    var preferredLane = 0f
    var targetLane = 0f
    var errorTimer = 0f
    var errorOffset = 0f

    var damage = 0f
    var isCrashing = false
    var crashTimer = 0f
    var flipRoll = 0f
    var shieldTimer = 0f
    var spinTimer = 0f
    var slowTimer = 0f
    var turboTimer = 0f
    var turboMeter = 0f
}

data class Obstacle3D(
    var x: Float,
    var y: Float,
    val radius: Float,
    val height: Float,
    val isTree: Boolean,
    val isRoadBarrier: Boolean = false,
    var wpIdx: Int = 0,
    var active: Boolean = true,
    var respawnTimer: Float = 0f
)

class PowerUpBox {
    var active = false
    var wpIdx = 0
    var x = 0f
    var y = 0f
    var respawnTimer = 0f
}

class Missile3D {
    var active = false
    var x = 0f
    var y = 0f
    var z = 4f
    var yaw = 0f
    var speed = 440f
    var life = 0f
    var targetBotIdx = -1
    var isBullet = false
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

class TireMark {
    var active = false
    var lx1 = 0f; var ly1 = 0f; var lx2 = 0f; var ly2 = 0f
    var rx1 = 0f; var ry1 = 0f; var rx2 = 0f; var ry2 = 0f
    var color = 0
}

data class TrackData(
    val name: String,
    val theme: Int,
    val goldScore: Int,
    val controlPoints: List<Vec2>
)

class CrownDriftView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("crowndrift_v9", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val polyPath = Path()
    private val arcPath = Path()
    private val miniPath = Path()
    private val tempRect = RectF()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // 0=MENU, 1=GARAGE, 2=SETTINGS, 3=GAME, 4=WIN, 5=COOP_LOBBY
    @Volatile private var state = 0

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

    private var sensLevel = prefs.getInt("sens", 1)
    private var ghostEnabled = prefs.getBoolean("ghost", true)
    private var botsEnabled = prefs.getBoolean("bots", true)
    private var showFps = prefs.getBoolean("fps", false)

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
    private val obstacles = ArrayList<Obstacle3D>(48)
    private val roadHalfWidth = 58f
    private var dynamicBarrierShiftTimer = 0f

    // Oyuncu Fizik Değişkenleri
    private var carX = 0f
    private var carY = 0f
    private var carZ = 0f
    private var carVZ = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var speed = 0f
    private var knockVX = 0f
    private var knockVY = 0f
    private var camYaw = 0f
    private var isOffRoad = false

    private var isFullStopped = false
    private var steerInput = 0f
    private var crownSteer = 0f
    private var isBezelDragging = false
    private var lastBezelAngle = 0f

    // Turnuva Geri Sayımı (3, 2, 1, BAŞLA!)
    private var countdownTimer = 0f
    private var lastCountdownBeep = 4

    // Kademeli Hasar, Alev Alma, Takla Atma ve Güvenli Doğma Sistemi
    private var carDamage = 0f
    private var spoilerBroken = false
    private var bumperBroken = false
    private var isCrashing = false
    private var crashTimer = 0f
    private var flipRollAngle = 0f
    private var impactTiltAngle = 0f
    private var shieldTimer = 0f
    private var respawnBannerTimer = 0f
    private var playerSlowTimer = 0f

    // Turbo Sistemi
    private var turboMeter = 0f
    private var turboActiveTimer = 0f

    // Güç Kutuları ve Silah Sistemi
    private var activeWeaponType = 0
    private var weaponTimer = 0f
    private var weaponMaxTimer = 1f
    private var weaponAmmo = 0
    private var weaponFireCooldown = 0f
    private var shockwaveRadius = 0f

    // Nesne Havuzları
    private val powerUpPool = Array(8) { PowerUpBox() }
    private val missilePool = Array(8) { Missile3D() }
    private val debrisPool = Array(28) { DebrisPart() }
    private var debrisHead = 0
    private val particlePool = Array(32) { Particle3D() }
    private var particleHead = 0
    private val tirePool = Array(95) { TireMark() }
    private var tireHead = 0

    private var prevBackLX = 0f; private var prevBackLY = 0f
    private var prevBackRX = 0f; private var prevBackRY = 0f
    private var hasPrevSkid = false

    // Yarış Bilgileri
    private var lap = 1
    private val totalLaps = 3
    private var currentWp = 0
    private var maxWpReached = 0
    private var playerProgress = 0f
    private var racePosition = 1
    private var lapStartTime = 0L
    private var currentLapTime = 0f
    private var bestLapTime = 99.99f

    private var score = 0f
    private var driftCombo = 1
    private var driftTimer = 0f
    private var isNewRecord = false

    private val currentLapRecording = ArrayList<GhostPose>(900)
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
    private val bots = ArrayList<BotCar>(3)

    // Bluetooth 4 Kişilik Oda Sistemi
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

    private var camCosYaw = 1f
    private var camSinYaw = 0f
    private var projX = 0f
    private var projY = 0f
    private var projScale = 0f

    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60
    private var lastVibeTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        paint.typeface = boldTypeface
        myAssignedModel = selectedModel
        buildTrackSpline(0)
        registerBtReceiver()
    }

    private fun registerBtReceiver() {
        if (!receiverRegistered) {
            try {
                context.registerReceiver(btDiscoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
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

    // ==================== BLUETOOTH 4 KİŞİLİK ODA MOTORU ====================
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
                    Thread.sleep(55)
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
                    Thread.sleep(55)
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

    private fun triggerHaptic(durationMs: Long, amplitude: Int) {
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

    // ==================== SADECE CROWN (DİREKSİYON) KONTROLÜ ====================
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
                val sensMult = when (sensLevel) {
                    0 -> 0.26f
                    1 -> 0.44f
                    else -> 0.65f
                }
                crownSteer = (crownSteer + delta.coerceIn(-1.5f, 1.5f) * sensMult).coerceIn(-1f, 1f)
                triggerHaptic(10L, 105)
            }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && handleRotaryScroll(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun buildTrackSpline(idx: Int) {
        trackPts.clear()
        trackNormals.clear()
        trackTangents.clear()
        obstacles.clear()

        val cps = tracks[idx].controlPoints
        val n = cps.size
        val stepsPerSeg = 24
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
        for (i in 0 until total) {
            val next = trackPts[(i + 1) % total]
            val prev = trackPts[(i - 1 + total) % total]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = -dy / len
            val ny = dx / len
            trackNormals.add(Vec2(nx, ny))
            trackTangents.add(atan2(dy, dx))

            if (i > 14 && i % 16 == 0) {
                val side = if ((i / 16) % 2 == 0) 1f else -1f
                val distFromRoad = roadHalfWidth + 68f + (i % 30)
                val ox = trackPts[i].x + nx * side * distFromRoad
                val oy = trackPts[i].y + ny * side * distFromRoad
                val isTree = (i / 16) % 3 != 0
                obstacles.add(Obstacle3D(ox, oy, if (isTree) 15f else 24f, if (isTree) 32f else 46f, isTree, isRoadBarrier = false, wpIdx = i))
            }

            if (i > 28 && i % 40 == 0) {
                val laneSide = if ((i / 40) % 2 == 0) -22f else 22f
                val bx = trackPts[i].x + nx * laneSide
                val by = trackPts[i].y + ny * laneSide
                obstacles.add(Obstacle3D(bx, by, 10.5f, 11f, isTree = false, isRoadBarrier = true, wpIdx = i, active = true))
            }
        }

        for (k in powerUpPool.indices) {
            val wp = ((k + 1) * (total / (powerUpPool.size + 1))) % total
            val lane = when (k % 3) { 0 -> -18f; 1 -> 18f; else -> 0f }
            val pt = trackPts[wp]
            val nm = trackNormals[wp]
            powerUpPool[k].active = true
            powerUpPool[k].wpIdx = wp
            powerUpPool[k].x = pt.x + nm.x * lane
            powerUpPool[k].y = pt.y + nm.y * lane
            powerUpPool[k].respawnTimer = 0f
        }
    }

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
        velYaw = carYaw
        camYaw = carYaw
        speed = 0f
        knockVX = 0f
        knockVY = 0f
        isOffRoad = false

        isFullStopped = false
        steerInput = 0f
        crownSteer = 0f
        isBezelDragging = false

        countdownTimer = 3.4f
        lastCountdownBeep = 4

        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false
        isCrashing = false
        crashTimer = 0f
        flipRollAngle = 0f
        impactTiltAngle = 0f
        shieldTimer = 0f
        respawnBannerTimer = 0f
        playerSlowTimer = 0f

        turboMeter = 0f
        turboActiveTimer = 0f
        activeWeaponType = 0
        weaponTimer = 0f
        weaponAmmo = 0
        weaponFireCooldown = 0f
        shockwaveRadius = 0f

        for (m in missilePool) m.active = false
        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (tm in tirePool) tm.active = false
        hasPrevSkid = false
        currentLapRecording.clear()
        ghostFrameIdx = 0

        bots.clear()
        if (botsEnabled) {
            val startWps = intArrayOf(4, 8, 12)
            val startLanes = floatArrayOf(-18f, 18f, -18f)
            val botColors = intArrayOf(Color.rgb(255, 195, 20), Color.rgb(235, 45, 90), Color.rgb(55, 215, 95))
            val skills = floatArrayOf(0.96f, 0.99f, 1.02f)

            for (bIdx in 0..2) {
                val wp = startWps[bIdx]
                val lane = startLanes[bIdx]
                val bot = BotCar(
                    id = bIdx,
                    color = botColors[bIdx],
                    modelIdx = (selectedModel + bIdx + 1) % 4,
                    skillProfile = skills[bIdx]
                )
                bot.x = trackPts[wp].x + trackNormals[wp].x * lane
                bot.y = trackPts[wp].y + trackNormals[wp].y * lane
                bot.yaw = trackTangents[wp]
                bot.velYaw = bot.yaw
                bot.speed = 0f
                bot.preferredLane = lane
                bot.targetLane = lane
                bot.currentWp = wp
                bot.maxWpReached = wp
                bot.lap = 1
                bot.finishTime = 0L
                bot.totalProgress = wp.toFloat()
                bots.add(bot)
            }
        }

        lap = 1
        currentWp = 0
        maxWpReached = 0
        playerProgress = 0f
        racePosition = 1
        score = 0f
        driftCombo = 1
        driftTimer = 0f

        lapStartTime = SystemClock.uptimeMillis()
        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        isNewRecord = false
        state = 3
        if (!fromNetwork) vibeClick()
        requestFocus()
    }

    private fun findNearestWaypointLocal(x: Float, y: Float, hintWp: Int): Int {
        val total = trackPts.size
        var bestDistSq = Float.MAX_VALUE
        var bestIdx = hintWp
        for (offset in -10..16) {
            val idx = (hintWp + offset + total) % total
            val dx = x - trackPts[idx].x
            val dy = y - trackPts[idx].y
            val dSq = dx * dx + dy * dy
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestIdx = idx
            }
        }
        if (bestDistSq > 95000f) {
            for (i in 0 until total step 2) {
                val dx = x - trackPts[i].x
                val dy = y - trackPts[i].y
                val dSq = dx * dx + dy * dy
                if (dSq < bestDistSq) {
                    bestDistSq = dSq
                    bestIdx = i
                }
            }
        }
        return bestIdx
    }

    // ==================== GERÇEK VE TUTARLI YARIŞ İLERLEME HESABI ====================
    private fun computeTrackProgress(x: Float, y: Float, wp: Int, lapNum: Int, maxWp: Int): Float {
        val total = trackPts.size
        val relWp = when {
            maxWp < total / 2 && wp > (total * 3) / 4 -> (wp - total).toFloat()
            maxWp > (total * 3) / 4 && wp < total / 10 -> (wp + total).toFloat()
            else -> wp.toFloat()
        }
        val p0 = trackPts[wp]
        val p1 = trackPts[(wp + 1) % total]
        val sx = p1.x - p0.x
        val sy = p1.y - p0.y
        val segLenSq = (sx * sx + sy * sy).coerceAtLeast(1f)
        val frac = (((x - p0.x) * sx + (y - p0.y) * sy) / segLenSq).coerceIn(0f, 0.99f)
        return (lapNum - 1) * total.toFloat() + relWp + frac
    }

    // ==================== KADEMELİ HASAR, PARÇALANMA, ALEV VE TAKLA MEKANİĞİ ====================
    private fun applyGradualCarDamage(amount: Float, impactX: Float, impactY: Float) {
        if (shieldTimer > 0f || isCrashing) return
        val prevDamage = carDamage
        carDamage = (carDamage + amount).coerceAtMost(100f)

        if (prevDamage <= 35f && carDamage > 35f) {
            bumperBroken = true
            spawnDebris(carX + cos(carYaw) * 14f, carY + sin(carYaw) * 14f, 6, carColors[selectedColor])
        }
        if (prevDamage <= 65f && carDamage > 65f) {
            spoilerBroken = true
            spawnDebris(carX - cos(carYaw) * 14f, carY - sin(carYaw) * 14f, 6, carColors[selectedColor])
        }
        spawnDebris(impactX, impactY, 4, carColors[selectedColor])

        if (carDamage >= 100f) {
            triggerExplosionAndRollover()
        }
    }

    private fun triggerExplosionAndRollover() {
        if (isCrashing || shieldTimer > 0f) return
        isCrashing = true
        crashTimer = 1.85f
        flipRollAngle = 0f
        carVZ = 145f
        speed *= 0.35f

        spawnDebris(carX, carY, 12, carColors[selectedColor])
        for (i in 0 until 12) {
            val ang = Random.nextFloat() * 6.28f
            val spd = 25f + Random.nextFloat() * 60f
            val fireCol = if (i % 2 == 0) Color.rgb(255, 110, 20) else Color.rgb(255, 210, 40)
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 45f + Random.nextFloat() * 50f, 5.5f, fireCol, 240)
        }
        triggerHaptic(180L, 255)
    }

    private fun respawnPlayerOnSafeRoad() {
        isCrashing = false
        flipRollAngle = 0f
        impactTiltAngle = 0f
        carZ = 0f
        carVZ = 0f
        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false
        playerSlowTimer = 0f
        knockVX = 0f
        knockVY = 0f

        val total = trackPts.size
        var safeWp = findNearestWaypointLocal(carX, carY, currentWp)
        for (step in 0..8) {
            val candidate = (safeWp + step) % total
            val cp = trackPts[candidate]
            var blocked = false
            for (obs in obstacles) {
                if (!obs.active) continue
                val dx = cp.x - obs.x
                val dy = cp.y - obs.y
                if (dx * dx + dy * dy < (obs.radius + 24f) * (obs.radius + 24f)) {
                    blocked = true
                    break
                }
            }
            if (!blocked) {
                safeWp = candidate
                break
            }
        }

        val safePt = trackPts[safeWp]
        carX = safePt.x
        carY = safePt.y
        carYaw = trackTangents[safeWp]
        velYaw = carYaw
        camYaw = carYaw
        currentWp = safeWp
        speed = 95f
        isFullStopped = false

        shieldTimer = 3.0f
        respawnBannerTimer = 1.5f
        triggerHaptic(55L, 200)
    }

    // ==================== TURBO VE GÜÇ KUTUSU SİSTEMİ ====================
    private fun activateTurbo() {
        if (turboMeter < 100f || turboActiveTimer > 0f || countdownTimer > 0f) return
        turboMeter = 0f
        turboActiveTimer = 3.2f
        triggerHaptic(65L, 255)
    }

    private fun giveRandomPowerUp() {
        val type = Random.nextInt(1, 5)
        activeWeaponType = type
        when (type) {
            1 -> {
                weaponAmmo = 2
                weaponMaxTimer = 10f
                weaponTimer = 10f
                weaponFireCooldown = 0.4f
            }
            2 -> {
                weaponAmmo = 2
                weaponMaxTimer = 8.0f
                weaponTimer = 8.0f
                triggerShockwavePulse()
            }
            3 -> {
                weaponAmmo = 1
                weaponMaxTimer = 5.0f
                weaponTimer = 5.0f
                for (bot in bots) {
                    bot.slowTimer = 5.0f
                }
                spawnParticle(carX, carY, 8f, 0f, 0f, 20f, 14f, Color.rgb(0, 210, 255), 220)
            }
            4 -> {
                weaponAmmo = 12
                weaponMaxTimer = 6.5f
                weaponTimer = 6.5f
                weaponFireCooldown = 0.2f
            }
        }
        triggerHaptic(45L, 230)
    }

    private fun triggerShockwavePulse() {
        if (weaponAmmo <= 0) return
        weaponAmmo--
        shockwaveRadius = 12f
        carDamage = (carDamage - 30f).coerceAtLeast(0f)
        if (carDamage < 35f) bumperBroken = false
        if (carDamage < 65f) spoilerBroken = false

        for (bot in bots) {
            if (bot.isCrashing) continue
            val dx = bot.x - carX
            val dy = bot.y - carY
            val dSq = dx * dx + dy * dy
            if (dSq < 18500f && dSq > 1f) {
                val pushAng = atan2(dy, dx)
                bot.vx += cos(pushAng) * 210f
                bot.vy += sin(pushAng) * 210f
                bot.spinTimer = 0.9f
                bot.speed *= 0.55f
            }
        }

        for (obs in obstacles) {
            if (obs.active && obs.isRoadBarrier) {
                val dx = obs.x - carX
                val dy = obs.y - carY
                if (dx * dx + dy * dy < 22500f) {
                    obs.active = false
                    obs.respawnTimer = 14f
                    spawnDebris(obs.x, obs.y, 7, Color.rgb(255, 125, 20))
                }
            }
        }

        triggerHaptic(55L, 250)
        if (weaponAmmo <= 0 && activeWeaponType == 2) {
            weaponTimer = 1.0f
        }
    }

    private fun tryAutoFireWeapon(dt: Float) {
        if (activeWeaponType == 2 && weaponAmmo > 0) {
            for (bot in bots) {
                val dx = bot.x - carX
                val dy = bot.y - carY
                if (dx * dx + dy * dy < 6400f && shockwaveRadius <= 0f) {
                    triggerShockwavePulse()
                    break
                }
            }
            return
        }

        if (activeWeaponType != 1 && activeWeaponType != 4) return
        if (weaponAmmo <= 0) {
            activeWeaponType = 0
            weaponTimer = 0f
            return
        }
        weaponFireCooldown -= dt
        if (weaponFireCooldown > 0f) return

        val maxRangeSq = if (activeWeaponType == 1) 115000f else 75000f
        val minCosCone = if (activeWeaponType == 1) 0.90f else 0.86f
        val fwdX = cos(carYaw)
        val fwdY = sin(carYaw)

        var bestBotIdx = -1
        var bestDistSq = maxRangeSq

        for (i in bots.indices) {
            val bot = bots[i]
            if (bot.isCrashing) continue
            val dx = bot.x - carX
            val dy = bot.y - carY
            val dSq = dx * dx + dy * dy
            if (dSq in 100f..bestDistSq) {
                val dist = sqrt(dSq)
                val dot = (dx * fwdX + dy * fwdY) / dist
                if (dot >= minCosCone) {
                    bestDistSq = dSq
                    bestBotIdx = i
                }
            }
        }

        if (bestBotIdx != -1) {
            for (m in missilePool) {
                if (!m.active) {
                    m.active = true
                    m.x = carX + fwdX * 18f
                    m.y = carY + fwdY * 18f
                    m.z = 4.2f
                    m.yaw = carYaw
                    m.isBullet = (activeWeaponType == 4)
                    m.speed = if (m.isBullet) 540f else 430f
                    m.life = if (m.isBullet) 1.4f else 3.2f
                    m.targetBotIdx = bestBotIdx
                    weaponAmmo--
                    weaponFireCooldown = if (m.isBullet) 0.32f else 1.1f
                    triggerHaptic(22L, 190)
                    if (weaponAmmo <= 0) {
                        weaponTimer = 0.5f
                    }
                    break
                }
            }
        }
    }

    private fun spawnDebris(ix: Float, iy: Float, count: Int, baseCol: Int) {
        for (i in 0 until count) {
            val d = debrisPool[debrisHead]
            debrisHead = (debrisHead + 1) % debrisPool.size
            val ang = Random.nextFloat() * 6.28f
            val spd = 38f + Random.nextFloat() * 90f
            d.active = true
            d.x = ix; d.y = iy; d.z = 4f + Random.nextFloat() * 5f
            d.vx = cos(ang) * spd
            d.vy = sin(ang) * spd
            d.vz = 65f + Random.nextFloat() * 85f
            d.yaw = ang
            d.size = 2.7f + Random.nextFloat() * 2.8f
            d.color = if (i % 2 == 0) baseCol else Color.rgb(220, 225, 230)
            d.life = 4.2f
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
                // YENİ KONUMLAR:
                // 1. Çıkış Düğmesi: Mini Map Balonunun Hemen Üstünde
                val exitBtnX = cx + R * 0.36f
                val exitBtnY = cy - R * 0.66f
                // 2. Dur Düğmesi: Sol-Alt İç Bölgede
                val stopBtnX = cx - R * 0.44f
                val stopBtnY = cy + R * 0.28f
                // 3. Turbo Kutucuğu: Biraz Daha Aşağıda
                val turboBtnX = cx + R * 0.40f
                val turboBtnY = cy + R * 0.32f

                val distFromCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.11f) {
                            state = 0
                            vibeClick()
                            return true
                        }
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.15f) {
                            isFullStopped = !isFullStopped
                            vibeClick()
                            return true
                        }
                        if (hypot(x - turboBtnX, y - turboBtnY) < R * 0.15f) {
                            if (turboMeter >= 100f) {
                                activateTurbo()
                            } else if (activeWeaponType == 2 && weaponAmmo > 0) {
                                triggerShockwavePulse()
                            }
                            return true
                        }
                        if (distFromCenter > R * 0.74f) {
                            isBezelDragging = true
                            lastBezelAngle = atan2(y - cy, x - cx)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isBezelDragging && distFromCenter > R * 0.68f) {
                            val curAng = atan2(y - cy, x - cx)
                            val dAng = wrapAngle(curAng - lastBezelAngle)
                            lastBezelAngle = curAng
                            val sensMult = when (sensLevel) { 0 -> 0.85f; 1 -> 1.25f; else -> 1.75f }
                            crownSteer = (crownSteer + dAng * sensMult).coerceIn(-1f, 1f)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.008f, 0.045f)
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
                updateGamePhysics(dt, now)
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
            paint.textSize = R * 0.065f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.87f, paint)
        }

        if (state == 3 || state == 1) {
            postInvalidateOnAnimation()
        } else {
            postInvalidateDelayed(32L)
        }
    }

    // ==================== 3B KAMERA PROJEKSİYONU ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - carX
        val dy = wy - carY

        val forward = dx * camCosYaw + dy * camSinYaw
        val right = -dx * camSinYaw + dy * camCosYaw

        val relF = forward + 98f
        val relH = wz - 138f
        val depth = relF * 0.682f - relH * 0.731f
        if (depth < 8f || depth > 1450f) return false
        val vert = relF * 0.731f + relH * 0.682f

        val fov = R * 1.48f
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

    private fun drawTilted3DBox(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        bx: Float, by: Float, bz: Float,
        halfL: Float, halfW: Float, height: Float,
        yaw: Float, roll: Float,
        topColor: Int, sideColor: Int, rearColor: Int
    ) {
        val dx = bx - carX
        val dy = by - carY
        val fwd = dx * camCosYaw + dy * camSinYaw
        if (fwd < -115f || fwd > 1100f) return

        val c = cos(yaw)
        val s = sin(yaw)
        val fx = c * halfL; val fy = s * halfL
        val rx = -s * halfW; val ry = c * halfW

        val sinR = sin(roll)
        val leftLift = (-sinR * halfW).coerceAtLeast(-bz)
        val rightLift = (sinR * halfW).coerceAtLeast(-bz)

        val flX = bx + fx - rx; val flY = by + fy - ry
        val frX = bx + fx + rx; val frY = by + fy + ry
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry

        val zFL0 = bz + leftLift; val zFL1 = zFL0 + height
        val zRL0 = bz + leftLift; val zRL1 = zRL0 + height
        val zFR0 = bz + rightLift; val zFR1 = zFR0 + height
        val zRR0 = bz + rightLift; val zRR1 = zRR0 + height

        drawQuad3D(canvas, cx, cy, R, flX, flY, zFL0, rlX, rlY, zRL0, rlX, rlY, zRL1, flX, flY, zFL1, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, zFR0, rrX, rrY, zRR0, rrX, rrY, zRR1, frX, frY, zFR1, sideColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, zFL0, frX, frY, zFR0, frX, frY, zFR1, flX, flY, zFL1, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, zRL0, rrX, rrY, zRR0, rrX, rrY, zRR1, rlX, rlY, zRL1, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, zFL1, frX, frY, zFR1, rrX, rrY, zRR1, rlX, rlY, zRL1, topColor)
    }

    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float, roll: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false,
        hasShield: Boolean = false, isSlowed: Boolean = false
    ) {
        val dx = x - carX
        val dy = y - carY
        if (!isPlayer && (dx * dx + dy * dy > 780000f)) return

        val shieldBlink = hasShield && ((SystemClock.uptimeMillis() / 90) % 2L == 0L)
        val alpha = if (isGhost) 105 else if (shieldBlink) 175 else 255
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val damageDarken = if (isPlayer) (1f - (carDamage / 200f)) else 1f
        val topCol = Color.argb(alpha, (r * damageDarken).toInt(), (g * damageDarken).toInt(), (b * damageDarken).toInt())
        val sideCol = Color.argb(alpha, (r * 0.75f * damageDarken).toInt(), (g * 0.75f * damageDarken).toInt(), (b * 0.75f * damageDarken).toInt())
        val rearCol = Color.argb(alpha, (r * 0.56f * damageDarken).toInt(), (g * 0.56f * damageDarken).toInt(), (b * 0.56f * damageDarken).toInt())
        val glassCol = Color.argb(alpha, 30, 48, 65)
        val wheelCol = Color.argb(alpha, 20, 22, 25)
        val chromeCol = Color.argb(alpha, 215, 220, 230)

        val effectiveYaw = if (isPlayer && isCrashing) yaw + roll * 0.5f else yaw
        val c = cos(effectiveYaw)
        val s = sin(effectiveYaw)
        val rxVec = -s
        val ryVec = c

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

        val bz = zOffset + 1.4f + abs(sin(roll)) * 3.0f

        if (!isGhost) {
            val wxF = 9.8f; val wxR = -9.8f
            val wyOff = if (modelIdx == 3) 10.4f else 9.4f
            val wWidth = if (modelIdx == 3) 2.2f else 1.7f
            val steerVisual = if (isPlayer) steerInput * 0.35f else 0f
            drawTilted3DBox(canvas, cx, cy, R, x + c * wxF - rxVec * wyOff, y + s * wxF - ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, effectiveYaw + steerVisual, roll * 0.5f, wheelCol, wheelCol, wheelCol)
            drawTilted3DBox(canvas, cx, cy, R, x + c * wxF + rxVec * wyOff, y + s * wxF + ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, effectiveYaw + steerVisual, roll * 0.5f, wheelCol, wheelCol, wheelCol)
            drawTilted3DBox(canvas, cx, cy, R, x + c * wxR - rxVec * wyOff, y + s * wxR - ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, effectiveYaw, roll * 0.5f, wheelCol, wheelCol, wheelCol)
            drawTilted3DBox(canvas, cx, cy, R, x + c * wxR + rxVec * wyOff, y + s * wxR + ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, effectiveYaw, roll * 0.5f, wheelCol, wheelCol, wheelCol)
        }

        when (modelIdx % 4) {
            0 -> {
                val bodyLen = if (isPlayer && bumperBroken) 13.5f else 15.8f
                drawTilted3DBox(canvas, cx, cy, R, x, y, bz, bodyLen, 8.8f, 4.3f, effectiveYaw, roll, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x + c * 11.2f, y + s * 11.2f, bz + 0.4f, 4.8f, 8.2f, 2.8f, effectiveYaw, roll, topCol, sideCol, wheelCol)
                }
                drawTilted3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz + 4.3f, 7.6f, 6.8f, 3.7f, effectiveYaw, roll, topCol, glassCol, glassCol)
                if (!(isPlayer && spoilerBroken)) {
                    val spX = x - c * 14.0f; val spY = y - s * 14.0f
                    drawTilted3DBox(canvas, cx, cy, R, spX, spY, bz + 6.4f, 2.0f, 9.4f, 1.1f, effectiveYaw, roll, wheelCol, sideCol, rearCol)
                }
            }
            1 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.0f else 16.8f
                drawTilted3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz, bodyLen * 0.85f, 9.8f, 3.4f, effectiveYaw, roll, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x + c * 12.0f, y + s * 12.0f, bz, 5.5f, 8.8f, 2.1f, effectiveYaw, roll, topCol, sideCol, wheelCol)
                }
                drawTilted3DBox(canvas, cx, cy, R, x + c * 1.0f, y + s * 1.0f, bz + 3.4f, 8.2f, 6.4f, 2.9f, effectiveYaw, roll, topCol, glassCol, rearCol)
                drawTilted3DBox(canvas, cx, cy, R, x - c * 4.5f, y - s * 4.5f, bz + 2.2f, 5.0f, 10.2f, 1.8f, effectiveYaw, roll, sideCol, wheelCol, rearCol)
                if (!(isPlayer && spoilerBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, effectiveYaw, roll, rearCol, sideCol, wheelCol)
                }
            }
            2 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.2f else 16.5f
                drawTilted3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, bodyLen, 9.0f, 4.6f, effectiveYaw, roll, topCol, sideCol, rearCol)
                drawTilted3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, effectiveYaw, roll, chromeCol, wheelCol, chromeCol)
                drawTilted3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, effectiveYaw, roll, topCol, glassCol, glassCol)
                if (!(isPlayer && spoilerBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x - c * 15.2f, y - s * 15.2f, bz + 5.0f, 1.4f, 8.8f, 1.8f, effectiveYaw, roll, rearCol, sideCol, rearCol)
                }
            }
            else -> {
                drawTilted3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, effectiveYaw, roll, topCol, sideCol, rearCol)
                drawTilted3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, effectiveYaw, roll, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x + c * 16.2f, y + s * 16.2f, bz - 0.4f, 2.2f, 10.6f, 1.1f, effectiveYaw, roll, chromeCol, topCol, sideCol)
                }
                drawTilted3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, effectiveYaw, roll, glassCol, glassCol, rearCol)
                drawTilted3DBox(canvas, cx, cy, R, x - c * 8.0f, y - s * 8.0f, bz + 3.2f, 5.0f, 0.8f, 3.6f, effectiveYaw, roll, topCol, sideCol, rearCol)
                if (!(isPlayer && spoilerBroken)) {
                    drawTilted3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, effectiveYaw, roll, chromeCol, topCol, rearCol)
                }
            }
        }

        if (isPlayer && weaponTimer > 0f) {
            if (activeWeaponType == 1 && weaponAmmo > 0) {
                val podX = x + c * 10.5f
                val podY = y + s * 10.5f
                val missileRed = Color.rgb(255, 60, 40)
                drawTilted3DBox(canvas, cx, cy, R, podX - rxVec * 6.5f, podY - ryVec * 6.5f, bz + 4.2f, 5.2f, 1.4f, 1.6f, effectiveYaw, roll, chromeCol, missileRed, wheelCol)
                if (weaponAmmo >= 2) {
                    drawTilted3DBox(canvas, cx, cy, R, podX + rxVec * 6.5f, podY + ryVec * 6.5f, bz + 4.2f, 5.2f, 1.4f, 1.6f, effectiveYaw, roll, chromeCol, missileRed, wheelCol)
                }
            } else if (activeWeaponType == 4) {
                val gunX = x + c * 11.8f
                val gunY = y + s * 11.8f
                val gunCol = Color.rgb(50, 55, 65)
                val barrelCol = Color.rgb(0, 235, 255)
                drawTilted3DBox(canvas, cx, cy, R, gunX, gunY, bz + 3.8f, 3.2f, 3.0f, 2.2f, effectiveYaw, roll, gunCol, gunCol, wheelCol)
                drawTilted3DBox(canvas, cx, cy, R, gunX + c * 4.5f - rxVec * 1.4f, gunY + s * 4.5f - ryVec * 1.4f, bz + 4.4f, 4.2f, 0.7f, 0.8f, effectiveYaw, roll, barrelCol, chromeCol, gunCol)
                drawTilted3DBox(canvas, cx, cy, R, gunX + c * 4.5f + rxVec * 1.4f, gunY + s * 4.5f + ryVec * 1.4f, bz + 4.4f, 4.2f, 0.7f, 0.8f, effectiveYaw, roll, barrelCol, chromeCol, gunCol)
            }
        }

        if (!isGhost) {
            val tailX = x - c * 15.5f
            val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.rgb(255, 220, 40) else Color.rgb(255, 30, 30)
            drawTilted3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, effectiveYaw, roll, tailCol, tailCol, tailCol)
            drawTilted3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, effectiveYaw, roll, tailCol, tailCol, tailCol)

            if (isPlayer && turboActiveTimer > 0f) {
                val flX = tailX - c * 3.5f
                val flY = tailY - s * 3.5f
                val turboFlameCol = Color.rgb(0, 225, 255)
                drawTilted3DBox(canvas, cx, cy, R, flX - rxVec * 3.5f, flY - ryVec * 3.5f, bz + 0.8f, 4.2f, 1.3f, 1.2f, effectiveYaw, roll, turboFlameCol, Color.WHITE, turboFlameCol)
                drawTilted3DBox(canvas, cx, cy, R, flX + rxVec * 3.5f, flY + ryVec * 3.5f, bz + 0.8f, 4.2f, 1.3f, 1.2f, effectiveYaw, roll, turboFlameCol, Color.WHITE, turboFlameCol)
            } else if (isPlayer && driftCombo >= 2 && (SystemClock.uptimeMillis() / 60) % 2L == 0L) {
                val flX = tailX - c * 2.5f
                val flY = tailY - s * 2.5f
                val driftFlameCol = Color.rgb(255, 140, 20)
                drawTilted3DBox(canvas, cx, cy, R, flX - rxVec * 3.5f, flY - ryVec * 3.5f, bz + 0.8f, 2.6f, 1.0f, 1.0f, effectiveYaw, roll, driftFlameCol, driftFlameCol, driftFlameCol)
                drawTilted3DBox(canvas, cx, cy, R, flX + rxVec * 3.5f, flY + ryVec * 3.5f, bz + 0.8f, 2.6f, 1.0f, 1.0f, effectiveYaw, roll, driftFlameCol, driftFlameCol, driftFlameCol)
            }
        }

        if (isPlayer && shockwaveRadius > 0f && project3D(x, y, bz + 2f, cx, cy, R)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4.5f
            paint.color = Color.argb(220, 255, 215, 30)
            canvas.drawCircle(projX, projY, shockwaveRadius * projScale, paint)
        }

        if (hasShield && project3D(x, y, bz + 4f, cx, cy, R)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = Color.argb(210, 0, 240, 255)
            canvas.drawCircle(projX, projY, 22f * projScale, paint)
        }

        if (isSlowed && project3D(x, y, bz + 5f, cx, cy, R)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.0f
            paint.color = Color.argb(200, 190, 50, 255)
            canvas.drawCircle(projX, projY, 19f * projScale, paint)
        }
    }

    private fun drawTrue3DWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        camCosYaw = cos(camYaw)
        camSinYaw = sin(camYaw)

        val theme = tracks[currentTrackIdx].theme
        val bgColor = when (theme) {
            0 -> Color.rgb(34, 78, 24)
            1 -> Color.rgb(12, 10, 28)
            2 -> Color.rgb(18, 22, 32)
            3 -> Color.rgb(175, 210, 230)
            else -> Color.rgb(95, 55, 30)
        }
        canvas.drawColor(bgColor)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 60f
        val baseGX = ((carX / gridStep).toInt()) * gridStep
        val baseGY = ((carY / gridStep).toInt()) * gridStep
        for (i in -6..6) {
            val gx = baseGX + i * gridStep
            for (j in -6..5) {
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

        val total = trackPts.size
        val kerbW = 9.5f
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(70, 74, 78)

        for (step in 46 downTo -26) {
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

            val kl0x = l0x - n0.x * kerbW; val kl0y = l0y - n0.y * kerbW
            val kl1x = l1x - n1.x * kerbW; val kl1y = l1y - n1.y * kerbW
            val kr0x = r0x + n0.x * kerbW; val kr0y = r0y + n0.y * kerbW
            val kr1x = r1x + n1.x * kerbW; val kr1y = r1y + n1.y * kerbW

            val kerbColor = when {
                i0 % 2 == 0 -> Color.rgb(230, 35, 35)
                (i0 / 2) % 6 == 0 -> Color.rgb(250, 210, 20)
                else -> Color.WHITE
            }

            drawQuad3D(canvas, cx, cy, R, kl0x, kl0y, 0f, l0x, l0y, 0f, l1x, l1y, 0f, kl1x, kl1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, r0x, r0y, 0f, kr0x, kr0y, 0f, kr1x, kr1y, 0f, r1x, r1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0f, r0x, r0y, 0f, r1x, r1y, 0f, l1x, l1y, 0f, roadCol)

            if (i0 == 0) {
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0.3f, r0x, r0y, 0.3f, r1x, r1y, 0.3f, l1x, l1y, 0.3f, Color.WHITE)
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 34f, r0x, r0y, 34f, r1x, r1y, 40f, l1x, l1y, 40f, Color.rgb(210, 40, 40))
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.0f
        for (mark in tirePool) {
            if (!mark.active) continue
            paint.color = mark.color
            if (project3D(mark.lx1, mark.ly1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(mark.lx2, mark.ly2, 0.2f, cx, cy, R)) {
                    canvas.drawLine(x1, y1, projX, projY, paint)
                }
            }
            if (project3D(mark.rx1, mark.ry1, 0.2f, cx, cy, R)) {
                val x1 = projX; val y1 = projY
                if (project3D(mark.rx2, mark.ry2, 0.2f, cx, cy, R)) {
                    canvas.drawLine(x1, y1, projX, projY, paint)
                }
            }
        }

        val boxSpinYaw = (SystemClock.uptimeMillis() % 3600L) / 573f
        for (pb in powerUpPool) {
            if (!pb.active) continue
            drawTilted3DBox(canvas, cx, cy, R, pb.x, pb.y, 3.0f, 6.5f, 6.5f, 9.0f, boxSpinYaw, 0f, Color.rgb(0, 235, 255), Color.rgb(255, 195, 20), Color.rgb(235, 50, 140))
        }

        for (obs in obstacles) {
            if (!obs.active) continue
            val dx = obs.x - carX
            val dy = obs.y - carY
            if (dx * dx + dy * dy < 360000f) {
                if (obs.isRoadBarrier) {
                    val bYaw = trackTangents[obs.wpIdx]
                    drawTilted3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, 5.5f, 12.5f, obs.height, bYaw, 0f, Color.rgb(255, 125, 20), Color.WHITE, Color.rgb(220, 50, 30))
                } else if (obs.isTree) {
                    drawTilted3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
                } else {
                    drawTilted3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, 0f, Color.rgb(45, 50, 70), Color.rgb(28, 32, 48), Color.rgb(22, 25, 38))
                }
            }
        }

        for (m in missilePool) {
            if (!m.active) continue
            val mCol = if (m.isBullet) Color.rgb(0, 245, 255) else Color.rgb(255, 70, 30)
            drawTilted3DBox(canvas, cx, cy, R, m.x, m.y, m.z, if (m.isBullet) 4.5f else 7.5f, 2.0f, 2.0f, m.yaw, 0f, Color.WHITE, mCol, Color.YELLOW)
        }

        for (d in debrisPool) {
            if (!d.active) continue
            drawTilted3DBox(canvas, cx, cy, R, d.x, d.y, d.z, d.size, d.size * 0.7f, d.size * 0.5f, d.yaw, d.yaw * 0.5f, d.color, d.color, Color.DKGRAY)
        }

        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val gPose = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            drawSport3DCar(canvas, cx, cy, R, gPose.x, gPose.y, 0f, gPose.yaw, 0f, Color.rgb(0, 225, 255), selectedModel, isGhost = true)
        }

        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) {
                drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, 0f, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
                if (project3D(rp.x, rp.y, 16f, cx, cy, R)) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(0, 255, 220)
                    paint.textSize = R * 0.056f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("P${i + 2}", projX, projY, paint)
                }
            }
        }

        if (botsEnabled) {
            for (bot in bots) {
                val bRoll = if (bot.isCrashing) bot.flipRoll else 0f
                drawSport3DCar(
                    canvas, cx, cy, R, bot.x, bot.y, bot.z, bot.yaw, bRoll,
                    bot.color, bot.modelIdx, isGhost = false, isPlayer = false,
                    hasShield = (bot.shieldTimer > 0f), isSlowed = (bot.slowTimer > 0f)
                )
            }
        }

        val activeRoll = if (isCrashing) flipRollAngle else impactTiltAngle
        drawSport3DCar(
            canvas, cx, cy, R, carX, carY, carZ, carYaw, activeRoll,
            carColors[selectedColor], selectedModel, isGhost = false, isPlayer = true,
            hasShield = (shieldTimer > 0f), isSlowed = (playerSlowTimer > 0f)
        )

        paint.style = Paint.Style.FILL
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== REKABETÇİ AI BOT FİZİĞİ ====================
    private fun updateBotsPhysics(dt: Float, now: Long) {
        val total = trackPts.size
        val baseMaxForward = when (selectedModel) { 0 -> 265f; 1 -> 285f; 2 -> 275f; else -> 305f }

        for (bot in bots) {
            if (bot.shieldTimer > 0f) bot.shieldTimer -= dt
            if (bot.slowTimer > 0f) bot.slowTimer -= dt
            if (bot.turboTimer > 0f) bot.turboTimer -= dt

            if (bot.isCrashing) {
                bot.crashTimer -= dt
                bot.flipRoll += 10.5f * dt
                bot.z += bot.vz * dt
                bot.vz -= 235f * dt
                if (bot.z < 0f) {
                    bot.z = 0f
                    bot.vz = abs(bot.vz) * 0.45f
                }
                if (bot.crashTimer <= 0f) {
                    bot.isCrashing = false
                    bot.flipRoll = 0f
                    bot.damage = 0f
                    bot.z = 0f
                    val safeWp = findNearestWaypointLocal(bot.x, bot.y, bot.currentWp)
                    bot.x = trackPts[safeWp].x + trackNormals[safeWp].x * bot.preferredLane
                    bot.y = trackPts[safeWp].y + trackNormals[safeWp].y * bot.preferredLane
                    bot.yaw = trackTangents[safeWp]
                    bot.velYaw = bot.yaw
                    bot.speed = 90f
                    bot.shieldTimer = 2.5f
                }
                continue
            }

            if (bot.spinTimer > 0f) {
                bot.spinTimer -= dt
                bot.yaw += 9.5f * dt
                bot.speed *= (1f - 2.2f * dt)
                bot.x += (cos(bot.velYaw) * bot.speed + bot.vx) * dt
                bot.y += (sin(bot.velYaw) * bot.speed + bot.vy) * dt
                bot.vx *= 0.88f
                bot.vy *= 0.88f
                continue
            }

            bot.currentWp = findNearestWaypointLocal(bot.x, bot.y, bot.currentWp)
            val wp = bot.currentWp
            val pt = trackPts[wp]
            val distFromCenterSq = (bot.x - pt.x) * (bot.x - pt.x) + (bot.y - pt.y) * (bot.y - pt.y)
            val botOffRoad = distFromCenterSq > (roadHalfWidth + 4f) * (roadHalfWidth + 4f)

            bot.errorTimer -= dt
            if (bot.errorTimer <= 0f) {
                bot.errorTimer = 2.0f + Random.nextFloat() * 2.5f
                bot.errorOffset = if (Random.nextInt(4) == 0) (Random.nextFloat() * 26f - 13f) else 0f
            }

            val lookCheckWp = (wp + 10) % total
            for (obs in obstacles) {
                if (obs.active && obs.isRoadBarrier && abs(obs.wpIdx - lookCheckWp) <= 6) {
                    val ox = obs.x - trackPts[obs.wpIdx].x
                    val oy = obs.y - trackPts[obs.wpIdx].y
                    val obsLane = ox * trackNormals[obs.wpIdx].x + oy * trackNormals[obs.wpIdx].y
                    if (abs(bot.targetLane - obsLane) < 22f) {
                        bot.targetLane = if (obsLane < 0f) 22f else -22f
                    }
                }
            }
            bot.preferredLane += (bot.targetLane + bot.errorOffset - bot.preferredLane).coerceIn(-35f * dt, 35f * dt)

            val targetWp = (wp + 7) % total
            val farWp = (wp + 15) % total
            val cornerSeverity = abs(wrapAngle(trackTangents[farWp] - trackTangents[wp]))

            val targetPt = trackPts[targetWp]
            val targetN = trackNormals[targetWp]
            val aimX = targetPt.x + targetN.x * bot.preferredLane
            val aimY = targetPt.y + targetN.y * bot.preferredLane

            val desiredYaw = atan2(aimY - bot.y, aimX - bot.x)
            val angleErr = wrapAngle(desiredYaw - bot.yaw)
            bot.steerInput = (angleErr * 1.85f).coerceIn(-1f, 1f)

            var botMaxSpd = baseMaxForward * bot.skillProfile
            if (cornerSeverity > 0.55f) botMaxSpd *= 0.82f
            if (botOffRoad) botMaxSpd *= 0.56f
            if (bot.slowTimer > 0f) botMaxSpd *= 0.65f
            if (bot.turboTimer > 0f) botMaxSpd *= 1.24f

            val accel = if (botOffRoad) 230f else if (bot.turboTimer > 0f) 320f else 195f
            bot.speed += (botMaxSpd - bot.speed).coerceIn(-accel * dt, accel * dt)

            bot.yaw += bot.steerInput * 3.25f * dt
            val driftGrip = if (botOffRoad) 1.45f else 2.15f
            val slipDiff = wrapAngle(bot.yaw - bot.velYaw)
            bot.velYaw += slipDiff * driftGrip * dt

            if (abs(slipDiff) > 0.14f && !botOffRoad) {
                bot.turboMeter = (bot.turboMeter + 18f * dt).coerceAtMost(100f)
                if (bot.turboMeter >= 100f && cornerSeverity < 0.25f) {
                    bot.turboMeter = 0f
                    bot.turboTimer = 2.6f
                }
            }

            bot.x += (cos(bot.velYaw) * bot.speed + bot.vx) * dt
            bot.y += (sin(bot.velYaw) * bot.speed + bot.vy) * dt
            bot.vx *= 0.88f
            bot.vy *= 0.88f

            if (bot.shieldTimer <= 0f) {
                for (obs in obstacles) {
                    if (!obs.active) continue
                    val dx = bot.x - obs.x
                    val dy = bot.y - obs.y
                    val minD = obs.radius + 10.5f
                    if (dx * dx + dy * dy < minD * minD) {
                        if (obs.isRoadBarrier) {
                            obs.active = false
                            obs.respawnTimer = 12f
                            bot.speed *= 0.65f
                            bot.damage += 18f
                            spawnDebris(obs.x, obs.y, 5, Color.rgb(255, 125, 20))
                        } else {
                            val pushAng = atan2(dy, dx)
                            bot.x = obs.x + cos(pushAng) * minD
                            bot.y = obs.y + sin(pushAng) * minD
                            bot.speed *= 0.45f
                            bot.damage += 32f
                            bot.targetLane = -bot.targetLane
                        }
                        if (bot.damage >= 100f) {
                            bot.isCrashing = true
                            bot.crashTimer = 1.6f
                            bot.vz = 130f
                        }
                        break
                    }
                }
            }

            if (bot.currentWp > bot.maxWpReached && bot.currentWp - bot.maxWpReached < total / 3) {
                bot.maxWpReached = bot.currentWp
            }
            if (bot.currentWp < total / 10 && bot.maxWpReached > (total * 3) / 4) {
                bot.maxWpReached = 0
                bot.lap++
                if (bot.lap > totalLaps && bot.finishTime == 0L) {
                    bot.finishTime = now
                }
            }
            bot.totalProgress = computeTrackProgress(bot.x, bot.y, bot.currentWp, bot.lap, bot.maxWpReached)
        }

        for (i in 0 until bots.size) {
            for (j in i + 1 until bots.size) {
                val b1 = bots[i]; val b2 = bots[j]
                val dx = b1.x - b2.x; val dy = b1.y - b2.y
                val dSq = dx * dx + dy * dy
                if (dSq < 441f && dSq > 0.01f) {
                    val d = sqrt(dSq)
                    val overlap = (21f - d) * 0.5f
                    val nx = dx / d; val ny = dy / d
                    b1.x += nx * overlap; b1.y += ny * overlap
                    b2.x -= nx * overlap; b2.y -= ny * overlap
                }
            }
        }
    }

    // ==================== ANA FİZİK DÖNGÜSÜ ====================
    private fun updateGamePhysics(dt: Float, now: Long) {
        if (countdownTimer > 0f) {
            countdownTimer -= dt
            val sec = ceil(countdownTimer - 0.4f).toInt()
            if (sec in 1..3 && sec < lastCountdownBeep) {
                lastCountdownBeep = sec
                triggerHaptic(35L, 210)
            } else if (sec == 0 && lastCountdownBeep > 0) {
                lastCountdownBeep = 0
                triggerHaptic(80L, 255)
            }
            if (countdownTimer > 0.4f) {
                lapStartTime = now
                return
            }
        }

        currentLapTime = (now - lapStartTime) / 1000f
        if (shieldTimer > 0f) shieldTimer = (shieldTimer - dt).coerceAtLeast(0f)
        if (respawnBannerTimer > 0f) respawnBannerTimer = (respawnBannerTimer - dt).coerceAtLeast(0f)
        if (playerSlowTimer > 0f) playerSlowTimer = (playerSlowTimer - dt).coerceAtLeast(0f)
        if (turboActiveTimer > 0f) turboActiveTimer = (turboActiveTimer - dt).coerceAtLeast(0f)
        impactTiltAngle *= (1f - 6.5f * dt)

        if (shockwaveRadius > 0f) {
            shockwaveRadius += 280f * dt
            if (shockwaveRadius > 145f) shockwaveRadius = 0f
        }

        if (activeWeaponType != 0) {
            weaponTimer -= dt
            if (weaponTimer <= 0f) {
                activeWeaponType = 0
                weaponTimer = 0f
                weaponAmmo = 0
            } else {
                tryAutoFireWeapon(dt)
            }
        }

        dynamicBarrierShiftTimer += dt
        for (obs in obstacles) {
            if (obs.isRoadBarrier && !obs.active) {
                obs.respawnTimer -= dt
                if (obs.respawnTimer <= 0f) {
                    obs.active = true
                }
            }
        }
        if (dynamicBarrierShiftTimer > 6.0f) {
            dynamicBarrierShiftTimer = 0f
            val total = trackPts.size
            for (obs in obstacles) {
                if (obs.isRoadBarrier && obs.active) {
                    val aheadDist = (obs.wpIdx - currentWp + total) % total
                    if (aheadDist in (total - 35)..(total - 10)) {
                        val newLane = if (Random.nextBoolean()) -22f else 22f
                        val pt = trackPts[obs.wpIdx]
                        val nm = trackNormals[obs.wpIdx]
                        obs.x = pt.x + nm.x * newLane
                        obs.y = pt.y + nm.y * newLane
                    }
                }
            }
        }

        for (pb in powerUpPool) {
            if (!pb.active) {
                pb.respawnTimer -= dt
                if (pb.respawnTimer <= 0f) pb.active = true
            } else {
                val dx = carX - pb.x
                val dy = carY - pb.y
                if (dx * dx + dy * dy < 484f) {
                    pb.active = false
                    pb.respawnTimer = 8.0f
                    giveRandomPowerUp()
                }
            }
        }

        for (m in missilePool) {
            if (!m.active) continue
            m.life -= dt
            if (m.life <= 0f) {
                m.active = false
                continue
            }
            if (!m.isBullet && m.targetBotIdx in bots.indices) {
                val target = bots[m.targetBotIdx]
                val desired = atan2(target.y - m.y, target.x - m.x)
                m.yaw += wrapAngle(desired - m.yaw).coerceIn(-6.5f * dt, 6.5f * dt)
            }
            m.x += cos(m.yaw) * m.speed * dt
            m.y += sin(m.yaw) * m.speed * dt

            for (bot in bots) {
                if (bot.isCrashing || bot.shieldTimer > 0f) continue
                val dx = m.x - bot.x
                val dy = m.y - bot.y
                if (dx * dx + dy * dy < 360f) {
                    m.active = false
                    bot.spinTimer = if (m.isBullet) 0.55f else 1.45f
                    bot.speed *= 0.35f
                    bot.damage += if (m.isBullet) 22f else 48f
                    spawnDebris(bot.x, bot.y, 5, bot.color)
                    spawnParticle(bot.x, bot.y, 6f, 0f, 0f, 35f, 6.0f, Color.rgb(255, 130, 20), 230)
                    triggerHaptic(35L, 220)
                    break
                }
            }
        }

        for (d in debrisPool) {
            if (!d.active) continue
            d.life -= dt
            if (d.life <= 0f) {
                d.active = false
            } else {
                d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
                d.vz -= 240f * dt
                if (d.z < 0.5f) {
                    d.z = 0.5f; d.vz = -d.vz * 0.45f
                    d.vx *= 0.75f; d.vy *= 0.75f
                }
            }
        }

        if (carDamage > 25f && Random.nextInt(3) == 0) {
            val smokeCol = if (carDamage > 65f) Color.rgb(35, 35, 40) else Color.rgb(75, 75, 80)
            spawnParticle(
                carX + Random.nextFloat() * 5f - 2.5f,
                carY + Random.nextFloat() * 5f - 2.5f,
                carZ + 5f, 0f, 0f, 26f, 3.6f, smokeCol, 165
            )
            if (carDamage > 70f) {
                val fireCol = if (Random.nextBoolean()) Color.rgb(255, 110, 15) else Color.rgb(255, 205, 30)
                spawnParticle(
                    carX + cos(carYaw) * 9f + Random.nextFloat() * 4f - 2f,
                    carY + sin(carYaw) * 9f + Random.nextFloat() * 4f - 2f,
                    carZ + 4.5f, 0f, 0f, 38f, 3.2f, fireCol, 235
                )
            }
        }

        for (p in particlePool) {
            if (!p.active) continue
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.size += 5f * dt
            p.alpha -= (215 * dt).toInt()
            if (p.alpha <= 0) p.active = false
        }

        if (botsEnabled) {
            updateBotsPhysics(dt, now)
        }

        if (isCrashing) {
            crashTimer -= dt
            flipRollAngle += 11.2f * dt
            carZ += carVZ * dt
            carVZ -= 235f * dt
            if (carZ < 0f) {
                carZ = 0f
                carVZ = abs(carVZ) * 0.52f
                spawnDebris(carX, carY, 3, carColors[selectedColor])
                triggerHaptic(40L, 235)
            }
            carX += cos(velYaw) * speed * dt
            carY += sin(velYaw) * speed * dt
            if (crashTimer <= 0f) {
                respawnPlayerOnSafeRoad()
            }
            return
        }

        if (!isBezelDragging) {
            crownSteer *= 0.93f
        }
        steerInput = crownSteer

        val baseMaxForward = when (selectedModel) { 0 -> 265f; 1 -> 285f; 2 -> 275f; else -> 305f }
        var maxForwardSpeed = if (isOffRoad) baseMaxForward * 0.56f else baseMaxForward
        if (turboActiveTimer > 0f) maxForwardSpeed *= 1.28f
        if (playerSlowTimer > 0f) maxForwardSpeed *= 0.68f

        if (isFullStopped) {
            speed = (speed - 520f * dt).coerceAtLeast(0f)
        } else {
            val accelRate = if (turboActiveTimer > 0f) 360f else if (isOffRoad) 235f else 195f
            speed += (maxForwardSpeed - speed).coerceIn(-accelRate * dt, accelRate * dt)
        }

        val turnRate = when (sensLevel) { 0 -> 2.4f; 1 -> 3.2f; else -> 4.1f }
        if (speed > 5f) {
            carYaw += steerInput * turnRate * dt
        }

        val driftGrip = if (isOffRoad) 1.45f else when (selectedModel) { 0 -> 2.4f; 1 -> 2.1f; 2 -> 1.8f; else -> 2.6f }
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt
        camYaw += wrapAngle(carYaw - camYaw) * 6.5f * dt

        carX += (cos(velYaw) * speed + knockVX) * dt
        carY += (sin(velYaw) * speed + knockVY) * dt
        knockVX *= (1f - 6.0f * dt)
        knockVY *= (1f - 6.0f * dt)

        if (ghostEnabled && currentLapRecording.size < 1200) {
            currentLapRecording.add(GhostPose(carX, carY, carYaw))
        }
        ghostFrameIdx++

        if (botsEnabled) {
            for (bot in bots) {
                if (bot.isCrashing) continue
                val dx = carX - bot.x
                val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 529f && distSq > 0.01f) {
                    val dist = sqrt(distSq)
                    val pushAng = atan2(dy, dx)
                    val overlap = 23f - dist
                    carX += cos(pushAng) * overlap * 0.65f
                    carY += sin(pushAng) * overlap * 0.65f

                    knockVX += cos(pushAng) * 95f
                    knockVY += sin(pushAng) * 95f
                    bot.vx -= cos(pushAng) * 145f
                    bot.vy -= sin(pushAng) * 145f
                    impactTiltAngle = sign(sin(pushAng - carYaw)) * 0.32f

                    if (now - lastVibeTime > 95) {
                        val relImpact = (abs(speed - bot.speed) * 0.08f + 9f).coerceIn(8f, 22f)
                        applyGradualCarDamage(relImpact, (carX + bot.x) * 0.5f, (carY + bot.y) * 0.5f)
                        triggerHaptic(45L, 235)
                        lastVibeTime = now
                        if (isCrashing) return
                    }
                }
            }
        }

        for (obs in obstacles) {
            if (!obs.active) continue
            val dx = carX - obs.x
            val dy = carY - obs.y
            val minD = obs.radius + 11f
            if (dx * dx + dy * dy < minD * minD) {
                val pushAng = atan2(dy, dx)

                if (obs.isRoadBarrier) {
                    obs.active = false
                    obs.respawnTimer = 12f
                    speed *= 0.62f
                    knockVX += cos(pushAng) * 80f
                    knockVY += sin(pushAng) * 80f
                    velYaw += (Random.nextFloat() - 0.5f) * 0.35f
                    impactTiltAngle = (Random.nextFloat() - 0.5f) * 0.38f

                    spawnDebris(obs.x, obs.y, 8, Color.rgb(255, 125, 20))
                    val barrierDmg = (speed * 0.06f + 8f).coerceIn(8f, 18f)
                    applyGradualCarDamage(barrierDmg, obs.x, obs.y)
                    triggerHaptic(55L, 240)
                    lastVibeTime = now
                    if (isCrashing) return
                } else {
                    carX = obs.x + cos(pushAng) * minD
                    carY = obs.y + sin(pushAng) * minD

                    val impactSeverity = (speed / 280f).coerceIn(0.2f, 1.1f)
                    speed *= 0.45f
                    knockVX += cos(pushAng) * 130f * impactSeverity
                    knockVY += sin(pushAng) * 130f * impactSeverity
                    velYaw = wrapAngle(velYaw + wrapAngle(pushAng - velYaw) * 0.45f)
                    impactTiltAngle = sign(sin(pushAng - carYaw)) * 0.42f

                    if (now - lastVibeTime > 95) {
                        val obsDmg = (14f + impactSeverity * 22f)
                        applyGradualCarDamage(obsDmg, carX, carY)
                        triggerHaptic(75L, 255)
                        lastVibeTime = now
                        if (isCrashing) return
                    }
                }
                break
            }
        }

        currentWp = findNearestWaypointLocal(carX, carY, currentWp)
        val tp = trackPts[currentWp]
        val bestDist = hypot(carX - tp.x, carY - tp.y)

        isOffRoad = bestDist > roadHalfWidth + 4f
        if (isOffRoad && speed > 50f) {
            if (Random.nextInt(3) == 0) {
                spawnParticle(carX - cos(carYaw) * 12f, carY - sin(carYaw) * 12f, 2f, 0f, 0f, 30f, 2.8f, Color.rgb(65, 130, 40), 200)
            }
            if (now - lastVibeTime > 110) {
                triggerHaptic(12L, 85)
                lastVibeTime = now
            }
        }

        val outerFence = roadHalfWidth * 4.2f
        if (bestDist > outerFence) {
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * outerFence
            carY = tp.y + sin(angToCar) * outerFence
            speed *= 0.65f
        }

        // ==================== DÜZELTİLEN TUR VE YARIŞ BİTİŞ SIRALAMASI HESABI ====================
        val total = trackPts.size
        if (currentWp > maxWpReached && currentWp - maxWpReached < total / 3) {
            maxWpReached = currentWp
        }
        if (currentWp < total / 10 && maxWpReached > (total * 3) / 4) {
            maxWpReached = 0
            if (currentLapTime < bestLapTime || bestLapGhost.isEmpty()) {
                bestLapTime = currentLapTime
                bestLapGhost = currentLapRecording.toList()
                prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply()
            }
            currentLapRecording.clear()
            ghostFrameIdx = 0

            if (lap < totalLaps) {
                lap++
                lapStartTime = now
                triggerHaptic(50L, 250)
            } else {
                // Oyuncu 3. Turu Tamamladı -> Bitiş Çizgisini Oyuncudan Önce Geçmiş Bot Sayısına Göre Gerçek Sıralamayı Kilitle!
                var finalFinishPos = 1
                if (botsEnabled) {
                    for (bot in bots) {
                        if (bot.finishTime in 1L until now) {
                            finalFinishPos++
                        }
                    }
                }
                racePosition = finalFinishPos
                playerProgress = totalLaps * total.toFloat()

                val finalScore = score.toInt()
                val oldBest = prefs.getInt("best_score_$currentTrackIdx", 0)
                if (finalScore > oldBest) {
                    isNewRecord = true
                    prefs.edit().putInt("best_score_$currentTrackIdx", finalScore).apply()
                }
                triggerHaptic(70L, 255)
                state = 4
                return
            }
        }

        playerProgress = computeTrackProgress(carX, carY, currentWp, lap, maxWpReached)

        var pos = 1
        if (botsEnabled) {
            for (bot in bots) {
                if (bot.finishTime > 0L || bot.totalProgress > playerProgress) {
                    pos++
                }
            }
        }
        racePosition = pos

        // Drift Puanlama, Turbo Doldurma ve Lastik İzleri
        val slip = abs(wrapAngle(carYaw - velYaw))
        val backX = carX - cos(carYaw) * 12f
        val backY = carY - sin(carYaw) * 12f
        val perpX = -sin(carYaw) * 7.0f
        val perpY = cos(carYaw) * 7.0f
        val curLX = backX - perpX; val curLY = backY - perpY
        val curRX = backX + perpX; val curRY = backY + perpY

        if ((slip > 0.12f || isOffRoad) && speed > 70f) {
            if (!isOffRoad) {
                driftTimer += dt
                driftCombo = when {
                    driftTimer > 3.0f -> 4
                    driftTimer > 1.5f -> 3
                    driftTimer > 0.4f -> 2
                    else -> 1
                }
                score += slip * 540f * driftCombo * dt

                if (turboActiveTimer <= 0f && turboMeter < 100f) {
                    turboMeter = (turboMeter + slip * 36f * dt).coerceAtMost(100f)
                }
            }

            if (hasPrevSkid && hypot(curLX - prevBackLX, curLY - prevBackLY) < 42f) {
                val tm = tirePool[tireHead]
                tireHead = (tireHead + 1) % tirePool.size
                tm.active = true
                tm.lx1 = prevBackLX; tm.ly1 = prevBackLY; tm.lx2 = curLX; tm.ly2 = curLY
                tm.rx1 = prevBackRX; tm.ry1 = prevBackRY; tm.rx2 = curRX; tm.ry2 = curRY
                tm.color = if (isOffRoad) Color.argb(165, 65, 45, 25) else Color.argb(160, 15, 15, 15)
            }
            prevBackLX = curLX; prevBackLY = curLY
            prevBackRX = curRX; prevBackRY = curRY
            hasPrevSkid = true

            if (!isOffRoad && now - lastVibeTime > 70) {
                val amp = (slip * 245f).toInt().coerceIn(85, 250)
                triggerHaptic(22L, amp)
                lastVibeTime = now
            }
        } else {
            hasPrevSkid = false
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    // ==================== DÜZELTİLEN ARAYÜZ KONUMLARI (HUD) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // 1. Sağ-Üst Mini Harita Balonu
        val mapCX = cx + R * 0.38f
        val mapCY = cy - R * 0.38f
        val mapRad = R * 0.175f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(175, 12, 18, 24)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(120, 0, 225, 255)
        canvas.drawCircle(mapCX, mapCY, mapRad, paint)

        val miniScale = mapRad / 1850f
        miniPath.reset()
        for (i in trackPts.indices step 4) {
            val mx = mapCX + trackPts[i].x * miniScale
            val my = mapCY + trackPts[i].y * miniScale
            if (i == 0) miniPath.moveTo(mx, my) else miniPath.lineTo(mx, my)
        }
        miniPath.close()
        paint.strokeWidth = 2.5f
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
                canvas.drawCircle(mapCX + rp.x * miniScale, mapCY + rp.y * miniScale, R * 0.023f, paint)
            }
        }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + carX * miniScale, mapCY + carY * miniScale, R * 0.025f, paint)

        // 2. ÇIKIŞ (ÇIK) DÜĞMESİ: Mini Map Balonunun Hemen Üstünde
        val exitBtnX = cx + R * 0.36f
        val exitBtnY = cy - R * 0.66f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 185, 35, 45)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.085f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.050f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.018f, paint)

        // 3. DRIFT / SKOR GÖSTERGESİ TABLOSU: Mini Map Balonunun Hemen Altında
        tempRect.set(cx + R * 0.14f, cy - R * 0.18f, cx + R * 0.62f, cy + R * 0.10f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 14, 20, 26)
        canvas.drawRoundRect(tempRect, R * 0.065f, R * 0.065f, paint)

        paint.color = Color.rgb(190, 200, 210)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.048f
        val tenths = (currentLapTime * 10f).toInt()
        val posStr = if (botsEnabled) " • $racePosition/4" else ""
        canvas.drawText("L$lap/$totalLaps • ${tenths / 10}.${tenths % 10}s$posStr", tempRect.centerX(), cy - R * 0.09f, paint)

        paint.color = if (isOffRoad) Color.rgb(255, 195, 30) else Color.rgb(0, 225, 255)
        paint.textSize = R * 0.10f
        val scoreOffsetX = if (driftCombo > 1) -R * 0.055f else 0f
        canvas.drawText("${score.toInt()}", tempRect.centerX() + scoreOffsetX, cy + R * 0.035f, paint)

        if (driftCombo > 1) {
            val multLeft = tempRect.right - R * 0.16f
            val multRight = tempRect.right - R * 0.025f
            val multTop = cy - R * 0.055f
            val multBot = cy + R * 0.045f
            paint.color = Color.rgb(235, 45, 115)
            canvas.drawRoundRect(multLeft, multTop, multRight, multBot, R * 0.04f, R * 0.04f, paint)
            paint.color = Color.WHITE
            paint.textSize = R * 0.052f
            canvas.drawText("x$driftCombo", (multLeft + multRight) * 0.5f, (multTop + multBot) * 0.5f + R * 0.018f, paint)
        }

        if (carDamage > 0f) {
            val barW = (tempRect.width() - R * 0.08f) * (carDamage / 100f)
            paint.color = when {
                carDamage > 70f -> Color.rgb(255, 45, 45)
                carDamage > 35f -> Color.rgb(255, 165, 20)
                else -> Color.rgb(255, 220, 40)
            }
            canvas.drawRoundRect(tempRect.left + R * 0.04f, tempRect.bottom - R * 0.028f, tempRect.left + R * 0.04f + barW, tempRect.bottom - R * 0.008f, 4f, 4f, paint)
        }

        // 4. SOL-ALT İÇ BÖLGE DUR / SÜR BUTONU
        val stopBtnX = cx - R * 0.44f
        val stopBtnY = cy + R * 0.28f
        val stopRad = R * 0.125f
        paint.style = Paint.Style.FILL
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(185, 210, 45, 45)
        canvas.drawCircle(stopBtnX, stopBtnY, stopRad, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.064f
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.024f, paint)

        // 5. TURBO KUTUCUĞU: Mevcut Konumundan Biraz Daha Aşağıya Taşındı
        val turboBtnX = cx + R * 0.40f
        val turboBtnY = cy + R * 0.32f
        val turboRad = R * 0.12f
        val isTurboReady = turboMeter >= 100f
        val isTurboActive = turboActiveTimer > 0f

        paint.style = Paint.Style.FILL
        paint.color = when {
            isTurboActive -> Color.rgb(0, 225, 255)
            isTurboReady -> if ((SystemClock.uptimeMillis() / 150) % 2L == 0L) Color.rgb(255, 200, 20) else Color.rgb(255, 120, 0)
            else -> Color.argb(180, 22, 32, 42)
        }
        canvas.drawCircle(turboBtnX, turboBtnY, turboRad, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = R * 0.022f
        paint.color = if (isTurboReady || isTurboActive) Color.WHITE else Color.rgb(0, 215, 255)
        val sweep = if (isTurboActive) (turboActiveTimer / 3.2f) * 360f else (turboMeter / 100f) * 360f
        tempRect.set(turboBtnX - turboRad, turboBtnY - turboRad, turboBtnX + turboRad, turboBtnY + turboRad)
        canvas.drawArc(tempRect, -90f, sweep, false, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (isTurboReady || isTurboActive) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.050f
        val turboLabel = when {
            isTurboActive -> "AKTİF"
            isTurboReady -> "TURBO!"
            else -> "%${turboMeter.toInt()}"
        }
        canvas.drawText(turboLabel, turboBtnX, turboBtnY + R * 0.018f, paint)

        // 6. Turbo Butonunun Altında Aktif Silah ve Özellik Göstergeleri
        var badgeY = turboBtnY + turboRad + R * 0.055f
        if (activeWeaponType != 0 && weaponTimer > 0f) {
            val wName = when (activeWeaponType) {
                1 -> "FÜZE x$weaponAmmo"
                2 -> "ŞOK DALGASI"
                3 -> "YAVAŞLATMA"
                else -> "TARET Aktif"
            }
            val wCol = when (activeWeaponType) {
                1 -> Color.rgb(255, 70, 50)
                2 -> Color.rgb(255, 205, 25)
                3 -> Color.rgb(185, 65, 255)
                else -> Color.rgb(0, 225, 255)
            }
            tempRect.set(turboBtnX - R * 0.21f, badgeY - R * 0.042f, turboBtnX + R * 0.21f, badgeY + R * 0.050f)
            paint.color = Color.argb(200, 16, 22, 30)
            canvas.drawRoundRect(tempRect, R * 0.04f, R * 0.04f, paint)

            val ratio = (weaponTimer / weaponMaxTimer).coerceIn(0f, 1f)
            paint.color = wCol
            canvas.drawRoundRect(tempRect.left + 4f, tempRect.bottom - 5f, tempRect.left + 4f + (tempRect.width() - 8f) * ratio, tempRect.bottom - 1f, 3f, 3f, paint)

            paint.color = Color.WHITE
            paint.textSize = R * 0.044f
            canvas.drawText(wName, turboBtnX, badgeY + R * 0.014f, paint)
            badgeY += R * 0.105f
        }

        if (shieldTimer > 0f) {
            tempRect.set(turboBtnX - R * 0.19f, badgeY - R * 0.038f, turboBtnX + R * 0.19f, badgeY + R * 0.042f)
            paint.color = Color.argb(195, 0, 140, 180)
            canvas.drawRoundRect(tempRect, R * 0.04f, R * 0.04f, paint)
            paint.color = Color.WHITE
            paint.textSize = R * 0.042f
            val shSec = (shieldTimer * 10f).toInt()
            canvas.drawText("KALKAN ${shSec / 10}.${shSec % 10}s", turboBtnX, badgeY + R * 0.013f, paint)
        }

        // 7. Turnuva Başlangıç Geri Sayımı ve Bildirimler
        if (countdownTimer > 0f) {
            val countNum = ceil(countdownTimer - 0.4f).toInt()
            val text = if (countNum > 0) "$countNum" else "BAŞLA!"
            paint.color = if (countNum > 0) Color.rgb(255, 210, 30) else Color.rgb(60, 255, 110)
            paint.textSize = if (countNum > 0) R * 0.28f else R * 0.18f
            canvas.drawText(text, cx, cy - R * 0.02f, paint)
        } else if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55)
            paint.textSize = R * 0.082f
            val sec = (crashTimer * 10f).toInt().coerceAtLeast(0)
            canvas.drawText("PATLADI! ${sec / 10}.${sec % 10}s", cx, cy - R * 0.08f, paint)
        } else if (respawnBannerTimer > 0f) {
            paint.color = Color.rgb(80, 255, 140)
            paint.textSize = R * 0.075f
            canvas.drawText("GÜVENLİ YOLDA DOĞDU", cx, cy - R * 0.08f, paint)
        }

        // 8. Alt Kenar 19 Noktalı Crown Direksiyon Göstergesi
        val totalDots = 19
        val centerDot = totalDots / 2
        val activeDotOffset = (steerInput * centerDot).toInt().coerceIn(-centerDot, centerDot)
        for (i in 0 until totalDots) {
            val offset = i - centerDot
            val deg = 90f - offset * 3.4f
            val rad = Math.toRadians(deg.toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f)
            val dy = cy + sin(rad).toFloat() * (R * 0.88f)

            val isLit = (activeDotOffset >= 0 && offset in 0..activeDotOffset) ||
                        (activeDotOffset < 0 && offset in activeDotOffset..0) || offset == 0
            paint.color = if (isLit) Color.rgb(0, 225, 255) else Color.argb(135, 70, 80, 90)
            canvas.drawCircle(dx, dy, R * 0.018f, paint)
        }
    }

    // ==================== 4 KİŞİLİK CO-OP ODA EKRANI ====================
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

    // ==================== ANA MENÜ, 3B GARAJ VE AYARLAR ====================
    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))

        arcPath.reset()
        tempRect.set(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f)
        arcPath.addArc(tempRect, 222f, 96f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(190, 200, 210)
        paint.textSize = R * 0.095f
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = 0.18f
        canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint)
        paint.letterSpacing = 0f

        val mapScale = R * 0.00016f
        miniPath.reset()
        val total = trackPts.size
        for (i in 0 until total step 2) {
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
        val savedX = carX; val savedY = carY; val savedCam = camYaw
        carX = 0f; carY = 0f; camYaw = 0f
        camCosYaw = 1f; camSinYaw = 0f
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, 0f, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = false)
        carX = savedX; carY = savedY; camYaw = savedCam

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
        paint.color = if (racePosition == 1) Color.rgb(80, 235, 155) else Color.rgb(255, 200, 40)
        paint.textSize = R * 0.15f
        val winTitle = if (botsEnabled && racePosition > 1) "FINISHED #$racePosition" else "1st - YOU WIN!"
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
        val bestTenths = (bestLapTime * 10f).toInt()
        canvas.drawText("GOLD $goldReq • BEST ${bestTenths / 10}.${bestTenths % 10}s", cx, cy + R * 0.20f, paint)

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
