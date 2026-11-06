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
    var wpIdx: Int,
    var laneOffset: Float,
    var targetLane: Float,
    val maxPerf: Float,
    val color: Int,
    val modelIdx: Int
) {
    var x = 0f; var y = 0f; var z = 0f
    var yaw = 0f; var velYaw = 0f
    var speed = 0f
    var mistakeTimer = 0f
    var mistakeSteer = 0f
    var crashTimer = 0f
    var laps = 1
    var totalProgress = 0f
}

data class Obstacle3D(val x: Float, val y: Float, val radius: Float, val height: Float, val isTree: Boolean)
data class MysteryBox(var wpIdx: Int, var laneOffset: Float, var x: Float, var y: Float, var respawnTimer: Float = 0f)
data class RoadBarrier(var wpIdx: Int, var laneOffset: Float, var x: Float, var y: Float, var yaw: Float)

class Rocket {
    var active = false
    var x = 0f; var y = 0f; var z = 4f
    var yaw = 0f; var life = 0f
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
    private val prefs = context.getSharedPreferences("crowndrift_v8", Context.MODE_PRIVATE)
    private val paint = Paint() // Termal optimizasyon: 3B çizimde AntiAlias kapalı tutulur
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

    // Optimize Edilmiş Pist Noktaları (Daha az poligon = Soğuk Saat & Uzun Pil Ömrü)
    private val trackPts = ArrayList<Vec2>(300)
    private val trackNormals = ArrayList<Vec2>(300)
    private val trackTangents = ArrayList<Float>(300)
    private val obstacles = ArrayList<Obstacle3D>(30)
    private val mysteryBoxes = ArrayList<MysteryBox>(10)
    private val roadBarriers = ArrayList<RoadBarrier>(12)
    private val roadHalfWidth = 60f

    // Oyuncu Fizik Değişkenleri
    private var carX = 0f
    private var carY = 0f
    private var carZ = 0f
    private var carVZ = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var speed = 0f
    private var camYaw = 0f
    private var isOffRoad = false

    // 3-2-1 Turnuva Başlangıç Geri Sayımı
    private var startCountdown = 0f // 3.9f -> 0f

    // Turbo & Gizemli Kutu Güçlendirmeleri (Power-ups)
    private var turboCharge = 0f // 0f..100f (Drift yaptıkça dolar)
    private var turboActiveTimer = 0f // >0f ise Turbo aktif
    // 0=Yok, 1=FÜZE (Missile), 2=ÖN DİKEN (Spikes), 3=YAVAŞLATMA (Slow Trap), 4=ETKİSİZ (No-Damage Trap)
    private var activePowerUp = 0
    private var powerUpTimer = 0f
    private var rocketFireCooldown = 0f
    private val rockets = Array(4) { Rocket() }

    private var isFullStopped = false
    private var steerInput = 0f
    private var crownSteer = 0f
    private var isBezelDragging = false
    private var lastBezelAngle = 0f

    // Hasar, 5 Saniye Yeniden Doğma ve Dokunulmazlık (Döngüye Girmeyi Önler!)
    private var carDamage = 0f
    private var spoilerBroken = false
    private var bumperBroken = false
    private var isCrashing = false
    private var crashTimer = 0f
    private var invulnerableTimer = 0f // Doğduktan sonra 3 sn bina/engel içinden geçebilme
    private var respawnBannerTimer = 0f

    // Sabit Boyutlu Havuzlar (Garbage Collector Isınmasını Önler)
    private val debrisPool = Array(18) { DebrisPart() }
    private var debrisHead = 0
    private val particlePool = Array(20) { Particle3D() }
    private var particleHead = 0
    private val tirePool = Array(75) { TireMark() }
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

    // Ghost ve 3 AI Bot (Toplam 4 Araç Yan Yana)
    private val currentLapRecording = ArrayList<GhostPose>(900)
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
    private val bots = ArrayList<BotCar>(3)

    // Eşleşmesiz Bluetooth 4 Kişilik Oda Sistemi
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

    private var projX = 0f
    private var projY = 0f
    private var projScale = 0f

    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 33
    private var lastVibeTime = 0L

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

    // ==================== BLUETOOTH ODA SİSTEMİ ====================
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

    private fun vibeClick() = triggerHaptic(20L, 180)

    // ==================== CROWN (DİREKSİYON) KONTROLÜ ====================
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
                triggerHaptic(10L, 100)
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
        mysteryBoxes.clear()
        roadBarriers.clear()

        val cps = tracks[idx].controlPoints
        val n = cps.size
        // Termal Optimizasyon: 18 adım ile aynı akıcılıkta %35 daha az poligon
        val stepsPerSeg = 18
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

            // Çim Üzerindeki Bina ve Ağaçlar
            if (i % 14 == 0 && i > 10) {
                val side = if ((i / 14) % 2 == 0) 1f else -1f
                val distFromRoad = roadHalfWidth + 68f + (i % 30)
                val ox = trackPts[i].x + nx * side * distFromRoad
                val oy = trackPts[i].y + ny * side * distFromRoad
                val isTree = (i / 14) % 3 != 0
                obstacles.add(Obstacle3D(ox, oy, if (isTree) 16f else 26f, if (isTree) 34f else 48f, isTree))
            }

            // Yoldaki Rastgele Gizemli Kutular (?)
            if (i % 28 == 14) {
                val lane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.1f)
                val bx = trackPts[i].x + nx * lane
                val by = trackPts[i].y + ny * lane
                mysteryBoxes.add(MysteryBox(i, lane, bx, by, 0f))
            }

            // Yolun Ortasında Rastgele Doğan Bariyer Engelleri
            if (i % 24 == 18 && i > 16) {
                val lane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.25f)
                val rx = trackPts[i].x + nx * lane
                val ry = trackPts[i].y + ny * lane
                roadBarriers.add(RoadBarrier(i, lane, rx, ry, trackTangents[i]))
            }
        }
    }

    private fun relocateBarrierRandomly(b: RoadBarrier) {
        val total = trackPts.size
        val newWp = Random.nextInt(15, total - 5)
        val lane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.3f)
        val n = trackNormals[newWp]
        b.wpIdx = newWp
        b.laneOffset = lane
        b.x = trackPts[newWp].x + n.x * lane
        b.y = trackPts[newWp].y + n.y * lane
        b.yaw = trackTangents[newWp]
    }

    private fun startRace(fromNetwork: Boolean = false) {
        buildTrackSpline(currentTrackIdx)
        val p0 = trackPts[0]
        val n0 = trackNormals[0]

        // 4 ARAÇ YAN YANA BAŞLANGIÇ DİZİLİMİ (-27, -9, +9, +27)
        val playerLane = -27f
        carX = p0.x + n0.x * playerLane
        carY = p0.y + n0.y * playerLane
        carZ = 0f
        carVZ = 0f
        carYaw = trackTangents[0]
        velYaw = carYaw
        camYaw = carYaw
        speed = 0f // 3-2-1 bitince yavaşça hızlanacak!
        isOffRoad = false

        // 3 - 2 - 1 Turnuva Geri Sayımı Başlat
        startCountdown = 3.9f

        turboCharge = 0f
        turboActiveTimer = 0f
        activePowerUp = 0
        powerUpTimer = 0f
        rocketFireCooldown = 0f
        for (r in rockets) r.active = false

        isFullStopped = false
        steerInput = 0f
        crownSteer = 0f
        isBezelDragging = false

        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false
        isCrashing = false
        crashTimer = 0f
        invulnerableTimer = 0f
        respawnBannerTimer = 0f

        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (tm in tirePool) tm.active = false
        hasPrevSkid = false
        currentLapRecording.clear()
        ghostFrameIdx = 0

        // 3 AI Bot (Seninle Yan Yana -9, +9, +27 Şeritlerinde Başlar)
        bots.clear()
        if (botsEnabled) {
            val botLanes = floatArrayOf(-9f, 9f, 27f)
            val botPerfs = floatArrayOf(0.95f, 1.00f, 1.04f)
            val botCols = intArrayOf(Color.rgb(255, 195, 20), Color.rgb(235, 45, 90), Color.rgb(60, 220, 110))
            for (bIdx in 0..2) {
                val lane = botLanes[bIdx]
                val bot = BotCar(
                    wpIdx = 0,
                    laneOffset = lane,
                    targetLane = lane,
                    maxPerf = botPerfs[bIdx],
                    color = botCols[bIdx],
                    modelIdx = (selectedModel + bIdx + 1) % 4
                )
                bot.x = p0.x + n0.x * lane
                bot.y = p0.y + n0.y * lane
                bot.yaw = trackTangents[0]
                bot.velYaw = bot.yaw
                bot.speed = 0f
                bots.add(bot)
            }
        }

        lap = 1
        currentWp = 0
        maxWpReached = 0
        playerProgress = 0f
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

    private fun spawnDebris(ix: Float, iy: Float, count: Int, baseCol: Int) {
        for (i in 0 until count) {
            val d = debrisPool[debrisHead]
            debrisHead = (debrisHead + 1) % debrisPool.size
            val ang = Random.nextFloat() * 6.28f
            val spd = 40f + Random.nextFloat() * 90f
            d.active = true
            d.x = ix; d.y = iy; d.z = 4f + Random.nextFloat() * 6f
            d.vx = cos(ang) * spd
            d.vy = sin(ang) * spd
            d.vz = 60f + Random.nextFloat() * 80f
            d.yaw = ang
            d.size = 2.8f + Random.nextFloat() * 2.8f
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

    // 5 SANİYE SAYIMLI PATLAMA -> EN YAKIN YOLUN ORTASINDA DOĞAR (DÖNGÜYE GİRMEZ!)
    private fun triggerExplosionWith5SecTimer() {
        if (isCrashing || invulnerableTimer > 0f) return
        isCrashing = true
        crashTimer = 5.0f // İstediğin gibi 5 saniye yeniden doğma süresi!
        carVZ = 135f
        speed = 0f
        turboActiveTimer = 0f

        spawnDebris(carX, carY, 10, carColors[selectedColor])
        for (i in 0 until 10) {
            val ang = Random.nextFloat() * 6.28f
            val spd = 20f + Random.nextFloat() * 55f
            val fireCol = if (i % 2 == 0) Color.rgb(255, 110, 20) else Color.rgb(255, 210, 40)
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 40f + Random.nextFloat() * 45f, 5.5f, fireCol, 240)
        }
        triggerHaptic(180L, 255)
    }

    private fun respawnOnNearestRoad() {
        isCrashing = false
        carZ = 0f
        carVZ = 0f
        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false

        // Binanın içinde değil, EN YAKIN YOLUN TAM ORTASINDA doğar!
        val safeWp = currentWp.coerceIn(0, trackPts.size - 1)
        val roadCenter = trackPts[safeWp]
        carX = roadCenter.x
        carY = roadCenter.y
        carYaw = trackTangents[safeWp]
        velYaw = carYaw
        camYaw = carYaw
        speed = 40f // Yavaş başlar, sürdükçe hızlanır
        isFullStopped = false
        invulnerableTimer = 3.0f // 3 saniye koruma
        respawnBannerTimer = 1.8f
        triggerHaptic(55L, 200)
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
                // 1. Güvenli ÇIKIŞ Butonu (Mini Haritanın Sol Yanında)
                val exitBtnX = cx + R * 0.08f
                val exitBtnY = cy - R * 0.42f
                // 2. TURBO Butonu (Sol-Orta İç Bölgede)
                val turboBtnX = cx - R * 0.46f
                val turboBtnY = cy - R * 0.06f
                // 3. Yana Alınmış TAM DURDURMA (DUR) Butonu (Sol-Alt İç Bölgede - Arabanın Üstünü Kapatmaz!)
                val stopBtnX = cx - R * 0.44f
                val stopBtnY = cy + R * 0.36f

                val distFromCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.12f) {
                            state = 0
                            vibeClick()
                            return true
                        }
                        if (hypot(x - turboBtnX, y - turboBtnY) < R * 0.15f) {
                            if (turboCharge >= 35f && turboActiveTimer <= 0f) {
                                turboActiveTimer = 3.2f * (turboCharge / 100f).coerceAtLeast(0.4f)
                                turboCharge = 0f
                                triggerHaptic(45L, 250)
                            }
                            return true
                        }
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.15f) {
                            isFullStopped = !isFullStopped
                            vibeClick()
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
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.01f, 0.05f)
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
            paint.isAntiAlias = true
            paint.color = Color.GREEN
            paint.textSize = R * 0.066f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS (ECO)", cx, cy - R * 0.87f, paint)
        }

        // TERMAL VE PİL KORUMASI: Saniyede ~33 kare (30ms) ile işlemcinin ısınmasını %50 azaltır!
        postInvalidateDelayed(30L)
    }

    // ==================== GERÇEK 3B PERSPEKTİF MOTORU ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - carX
        val dy = wy - carY
        val cosYaw = cos(camYaw)
        val sinYaw = sin(camYaw)

        val forward = dx * cosYaw + dy * sinYaw
        val right = -dx * sinYaw + dy * cosYaw

        val relF = forward + 98f
        val relH = wz - 138f
        val cosP = 0.682f
        val sinP = 0.731f

        val depth = relF * cosP - relH * sinP
        if (depth < 16f || depth > 1150f) return false
        val vert = relF * sinP + relH * cosP

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

    private fun draw3DBox(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        bx: Float, by: Float, bz: Float,
        halfL: Float, halfW: Float, height: Float,
        yaw: Float, topColor: Int, sideColor: Int, rearColor: Int
    ) {
        val c = cos(yaw)
        val s = sin(yaw)
        val fx = c * halfL; val fy = s * halfL
        val rx = -s * halfW; val ry = c * halfW

        val flX = bx + fx - rx; val flY = by + fy - ry
        val frX = bx + fx + rx; val frY = by + fy + ry
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry
        val topZ = bz + height

        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, rlX, rlY, bz, rlX, rlY, topZ, flX, flY, topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, bz, rrX, rrY, bz, rrX, rrY, topZ, frX, frY, topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, frX, frY, bz, frX, frY, topZ, flX, flY, topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, bz, rrX, rrY, bz, rrX, rrY, topZ, rlX, rlY, topZ, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, topZ, frX, frY, topZ, rrX, rrY, topZ, rlX, rlY, topZ, topColor)
    }

    // ==================== 4 SPOR ARAÇ + ÖN SİLAH (FÜZE / DİKEN) VE TURBO ALEVİ ====================
    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false
    ) {
        // Dokunulmazlık (Respawn sonrası) yanıp sönme efekti
        if (isPlayer && invulnerableTimer > 0f && ((SystemClock.uptimeMillis() / 100) % 2L == 0L)) {
            return
        }

        val alpha = if (isGhost) 110 else 255
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

        // 4 Tekerlek
        if (!isGhost) {
            val wxF = 9.8f; val wxR = -9.8f
            val wyOff = if (modelIdx == 3) 10.4f else 9.4f
            val wWidth = if (modelIdx == 3) 2.2f else 1.7f
            val steerVisual = if (isPlayer) steerInput * 0.35f else 0f
            draw3DBox(canvas, cx, cy, R, x + c * wxF - rxVec * wyOff, y + s * wxF - ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + steerVisual, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxF + rxVec * wyOff, y + s * wxF + ryVec * wyOff, 0.5f + zOffset, 3.5f, wWidth, 3.6f, yaw + steerVisual, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR - rxVec * wyOff, y + s * wxR - ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
            draw3DBox(canvas, cx, cy, R, x + c * wxR + rxVec * wyOff, y + s * wxR + ryVec * wyOff, 0.5f + zOffset, 3.7f, wWidth, 3.8f, yaw, wheelCol, wheelCol, wheelCol)
        }

        when (modelIdx % 4) {
            0 -> {
                val bodyLen = if (isPlayer && bumperBroken) 13.5f else 15.8f
                draw3DBox(canvas, cx, cy, R, x, y, bz, bodyLen, 8.8f, 4.3f, yaw, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 11.2f, y + s * 11.2f, bz + 0.4f, 4.8f, 8.2f, 2.8f, yaw, topCol, sideCol, wheelCol)
                }
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz + 4.3f, 7.6f, 6.8f, 3.7f, yaw, topCol, glassCol, glassCol)
                if (!(isPlayer && spoilerBroken)) {
                    val spX = x - c * 14.0f; val spY = y - s * 14.0f
                    draw3DBox(canvas, cx, cy, R, spX, spY, bz + 6.4f, 2.0f, 9.4f, 1.1f, yaw, wheelCol, sideCol, rearCol)
                }
            }
            1 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.0f else 16.8f
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz, bodyLen * 0.85f, 9.8f, 3.4f, yaw, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 12.0f, y + s * 12.0f, bz, 5.5f, 8.8f, 2.1f, yaw, topCol, sideCol, wheelCol)
                }
                draw3DBox(canvas, cx, cy, R, x + c * 1.0f, y + s * 1.0f, bz + 3.4f, 8.2f, 6.4f, 2.9f, yaw, topCol, glassCol, rearCol)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, yaw, rearCol, sideCol, wheelCol)
                }
            }
            2 -> {
                val bodyLen = if (isPlayer && bumperBroken) 14.2f else 16.5f
                draw3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, bodyLen, 9.0f, 4.6f, yaw, topCol, sideCol, rearCol)
                draw3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, yaw, chromeCol, wheelCol, chromeCol)
                draw3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, yaw, topCol, glassCol, glassCol)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 15.2f, y - s * 15.2f, bz + 5.0f, 1.4f, 8.8f, 1.8f, yaw, rearCol, sideCol, rearCol)
                }
            }
            else -> {
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, yaw, topCol, sideCol, rearCol)
                draw3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, yaw, topCol, sideCol, rearCol)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 16.2f, y + s * 16.2f, bz - 0.4f, 2.2f, 10.6f, 1.1f, yaw, chromeCol, topCol, sideCol)
                }
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, yaw, glassCol, glassCol, rearCol)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, yaw, chromeCol, topCol, rearCol)
                }
            }
        }

        // KUTUDAN ÇIKAN ÖN SİLAH & ÖN DİKEN MODELLERİ (Süreli Aktifken Arabanın Önünde Görünür!)
        if (isPlayer && powerUpTimer > 0f) {
            when (activePowerUp) {
                1 -> {
                    // 1. ÖN FÜZE RAMPASI (Kaputun Üstünde Kırmızı Uçlu Çift Füze Namlu)
                    val gunX = x + c * 10.5f
                    val gunY = y + s * 10.5f
                    draw3DBox(canvas, cx, cy, R, gunX, gunY, bz + 4.2f, 5.5f, 3.2f, 2.2f, yaw, Color.DKGRAY, wheelCol, Color.RED)
                    draw3DBox(canvas, cx, cy, R, gunX + c * 4.5f, gunY + s * 4.5f, bz + 4.6f, 2.0f, 2.6f, 1.6f, yaw, Color.RED, Color.RED, Color.YELLOW)
                }
                2 -> {
                    // 2. ÖN TAMPON DİKENLERİ (3 Adet Sivri Parlak Metalik/Turkuaz Diken)
                    val spkFrontX = x + c * 19.5f
                    val spkFrontY = y + s * 19.5f
                    val spkCol = Color.rgb(0, 245, 255)
                    for (sIdx in -1..1) {
                        val offX = rxVec * (sIdx * 5.2f)
                        val offY = ryVec * (sIdx * 5.2f)
                        draw3DBox(canvas, cx, cy, R, spkFrontX + offX, spkFrontY + offY, bz + 0.8f, 4.2f, 1.1f, 1.4f, yaw, spkCol, chromeCol, wheelCol)
                    }
                }
            }
        }

        // Arka Stoplar ve TURBO Mavi Alevi
        if (!isGhost) {
            val tailX = x - c * 15.5f
            val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.rgb(255, 220, 40) else Color.rgb(255, 30, 30)
            draw3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol)
            draw3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol)

            if (isPlayer && turboActiveTimer > 0f) {
                val flameX = tailX - c * 4.0f
                val flameY = tailY - s * 4.0f
                val turboCol = Color.rgb(0, 225, 255)
                draw3DBox(canvas, cx, cy, R, flameX - rxVec * 3.5f, flameY - ryVec * 3.5f, bz + 0.8f, 4.5f, 1.3f, 1.3f, yaw, turboCol, Color.WHITE, turboCol)
                draw3DBox(canvas, cx, cy, R, flameX + rxVec * 3.5f, flameY + ryVec * 3.5f, bz + 0.8f, 4.5f, 1.3f, 1.3f, yaw, turboCol, Color.WHITE, turboCol)
            }
        }
    }

    private fun drawTrue3DWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // TERMAL OPTİMİZASYON: 3B Poligon çiziminde AntiAlias kapatılarak GPU ısınması önlenir
        paint.isAntiAlias = false

        val theme = tracks[currentTrackIdx].theme
        val bgColor = when (theme) {
            0 -> Color.rgb(34, 78, 24)
            1 -> Color.rgb(12, 10, 28)
            2 -> Color.rgb(18, 22, 32)
            3 -> Color.rgb(175, 210, 230)
            else -> Color.rgb(95, 55, 30)
        }
        canvas.drawColor(bgColor)

        // Hafifletilmiş Zemin Izgarası (-3..3 = Çok Düşük CPU Kullanımı)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 75f
        val baseGX = ((carX / gridStep).toInt()) * gridStep
        val baseGY = ((carY / gridStep).toInt()) * gridStep
        for (i in -3..3) {
            val gx = baseGX + i * gridStep
            for (j in -3..2) {
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

        // Optimize Edilmiş Pist Çizimi (32 İleri, 6 Geri Segment)
        val total = trackPts.size
        val kerbW = 9.5f
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(70, 74, 78)

        for (step in 32 downTo -6) {
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

        // Lastik İzleri
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

        // Yoldaki Rastgele Bariyer Engelleri (Sarı-Siyah / Turuncu Şeritli Bloklar)
        for (bar in roadBarriers) {
            val dx = bar.x - carX
            val dy = bar.y - carY
            if (dx * dx + dy * dy < 220000f) {
                draw3DBox(canvas, cx, cy, R, bar.x, bar.y, 0f, 5.5f, 13.0f, 8.5f, bar.yaw, Color.rgb(255, 160, 20), Color.rgb(220, 40, 30), Color.DKGRAY)
            }
        }

        // Yoldaki Rastgele Gizemli Kutular (?)
        for (box in mysteryBoxes) {
            if (box.respawnTimer > 0f) continue
            val dx = box.x - carX
            val dy = box.y - carY
            if (dx * dx + dy * dy < 220000f) {
                draw3DBox(canvas, cx, cy, R, box.x, box.y, 2.5f, 5.5f, 5.5f, 9.0f, garagePreviewYaw, Color.rgb(255, 215, 30), Color.rgb(0, 215, 255), Color.rgb(220, 50, 180))
            }
        }

        // Ateşlenen Füzeler (Dimdik İlerleyen Roketler)
        for (rk in rockets) {
            if (!rk.active) continue
            draw3DBox(canvas, cx, cy, R, rk.x, rk.y, rk.z, 7.0f, 2.2f, 2.2f, rk.yaw, Color.RED, Color.WHITE, Color.YELLOW)
        }

        // Çim Üzerindeki 3B Ağaç ve Binalar
        for (obs in obstacles) {
            val dx = obs.x - carX
            val dy = obs.y - carY
            if (dx * dx + dy * dy < 260000f) {
                if (obs.isTree) {
                    draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
                } else {
                    draw3DBox(canvas, cx, cy, R, obs.x, obs.y, 0f, obs.radius, obs.radius, obs.height, 0f, Color.rgb(45, 50, 70), Color.rgb(28, 32, 48), Color.rgb(22, 25, 38))
                }
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
            drawSport3DCar(canvas, cx, cy, R, gPose.x, gPose.y, 0f, gPose.yaw, Color.rgb(0, 225, 255), selectedModel, isGhost = true)
        }

        // 4 Kişilik Co-op Diğer Oyuncular
        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) {
                drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
            }
        }

        // Yapay Zeka Bot Araçları
        if (botsEnabled) {
            for (bot in bots) {
                drawSport3DCar(canvas, cx, cy, R, bot.x, bot.y, bot.z, bot.yaw, bot.color, bot.modelIdx, isGhost = false)
            }
        }

        // Oyuncunun 3B Spor Aracı
        drawSport3DCar(canvas, cx, cy, R, carX, carY, carZ, carYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = true)

        // Efekt Parçacıkları
        paint.style = Paint.Style.FILL
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== HIZLANDIRILMIŞ VE HATA YAPABİLEN AI BOT FİZİĞİ ====================
    private fun updateBots(dt: Float) {
        val total = trackPts.size
        for (bot in bots) {
            // Bot फ्यूze veya dikenle vurulup devrildiyse 5 saniye bekler ve yolda doğar
            if (bot.crashTimer > 0f) {
                bot.crashTimer -= dt
                bot.z = (bot.z - 40f * dt).coerceAtLeast(0f)
                bot.yaw += 8f * dt
                if (bot.crashTimer <= 0f) {
                    bot.z = 0f
                    val rp = trackPts[bot.wpIdx]
                    bot.x = rp.x; bot.y = rp.y
                    bot.yaw = trackTangents[bot.wpIdx]
                    bot.velYaw = bot.yaw
                    bot.speed = 50f
                }
                continue
            }

            // Rastgele İnsan Benzeri Hata Yapma (Virajı geniş alma / çime taşma / savrulma)
            bot.mistakeTimer -= dt
            if (bot.mistakeTimer <= 0f) {
                if (Random.nextInt(100) < 22) {
                    // Hata yap: Çime doğru savrul veya ani şerit değiştir
                    bot.targetLane = if (Random.nextBoolean()) roadHalfWidth * 1.18f else -roadHalfWidth * 1.18f
                    bot.mistakeSteer = (Random.nextFloat() - 0.5f) * 0.45f
                    bot.mistakeTimer = 1.4f + Random.nextFloat() * 1.2f
                } else {
                    // Normal yarış çizgisi
                    bot.targetLane = (Random.nextFloat() - 0.5f) * (roadHalfWidth * 1.1f)
                    bot.mistakeSteer = 0f
                    bot.mistakeTimer = 2.0f + Random.nextFloat() * 2.0f
                }
            }

            bot.laneOffset += (bot.targetLane - bot.laneOffset) * 2.2f * dt
            val botOffRoad = abs(bot.laneOffset) > roadHalfWidth

            // Botlar da Bizimle Aynı Kademeli Hızlanma Fiziğini Kullanır!
            val botMaxSpd = (if (botOffRoad) 165f else 282f) * bot.maxPerf
            val isBotDrifting = abs(bot.mistakeSteer) > 0.15f
            if (!isBotDrifting) {
                bot.speed = (bot.speed + 68f * dt).coerceAtMost(botMaxSpd)
            } else {
                bot.speed = (bot.speed - 25f * dt).coerceAtLeast(120f)
            }

            // İlerideki Waypoint Hedefini Takip Etme
            val lookAheadWp = (bot.wpIdx + 4) % total
            val tp = trackPts[lookAheadWp]
            val tn = trackNormals[lookAheadWp]
            val tx = tp.x + tn.x * bot.laneOffset
            val ty = tp.y + tn.y * bot.laneOffset

            val desiredYaw = atan2(ty - bot.y, tx - bot.x) + bot.mistakeSteer
            bot.yaw += wrapAngle(desiredYaw - bot.yaw) * 4.5f * dt
            bot.velYaw += wrapAngle(bot.yaw - bot.velYaw) * 3.2f * dt

            bot.x += cos(bot.velYaw) * bot.speed * dt
            bot.y += sin(bot.velYaw) * bot.speed * dt

            // Botun Hangi Waypoint'te Olduğunu Yerel Pencere İle Bul
            var bestD = Float.MAX_VALUE
            var bWp = bot.wpIdx
            for (step in -4..8) {
                val cand = (bot.wpIdx + step + total) % total
                val dx = bot.x - trackPts[cand].x
                val dy = bot.y - trackPts[cand].y
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    bWp = cand
                }
            }
            if (bWp < total / 8 && bot.wpIdx > (total * 3) / 4) {
                bot.laps++
            }
            bot.wpIdx = bWp
            bot.totalProgress = (bot.laps - 1) * total + bot.wpIdx.toFloat()

            // Botlar da Yoldaki Rastgele Bariyerlere Çarpıp Hata Yapabilir!
            for (bar in roadBarriers) {
                val dx = bot.x - bar.x
                val dy = bot.y - bar.y
                if (dx * dx + dy * dy < 360f) {
                    bot.speed *= 0.45f
                    bot.mistakeSteer = 0.5f
                    relocateBarrierRandomly(bar)
                }
            }
        }
    }

    // ==================== OYUNCU FİZİĞİ, TURBO, KUTULAR VE OTO-FÜZE ====================
    private fun updateGamePhysics(dt: Float, now: Long) {
        garagePreviewYaw += dt * 1.8f
        if (respawnBannerTimer > 0f) respawnBannerTimer -= dt
        if (invulnerableTimer > 0f) invulnerableTimer -= dt
        if (rocketFireCooldown > 0f) rocketFireCooldown -= dt

        // 1. TURNUVA BAŞLANGIÇ 3 - 2 - 1 GERİ SAYIMI (4 Araç Yan Yana Bekler)
        if (startCountdown > 0f) {
            val prevSec = ceil(startCountdown).toInt()
            startCountdown -= dt
            val newSec = ceil(startCountdown).toInt()
            if (newSec != prevSec && newSec >= 0) {
                triggerHaptic(40L, if (newSec == 0) 255 else 180)
            }
            lapStartTime = now
            return
        }

        currentLapTime = (now - lapStartTime) / 1000f

        // Kırık Parçalar ve Efektler
        for (d in debrisPool) {
            if (!d.active) continue
            d.life -= dt
            if (d.life <= 0f) d.active = false
            else {
                d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
                d.vz -= 240f * dt
                if (d.z < 0.5f) { d.z = 0.5f; d.vz = -d.vz * 0.4f }
            }
        }
        for (p in particlePool) {
            if (!p.active) continue
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.size += 5f * dt
            p.alpha -= (220 * dt).toInt()
            if (p.alpha <= 0) p.active = false
        }

        // Gizemli Kutuların Yeniden Doğma Sayacı
        for (box in mysteryBoxes) {
            if (box.respawnTimer > 0f) box.respawnTimer -= dt
        }

        // Aktif Kutu Özelliği Süre Sayacı ve Otomatik Füze Ateşleme
        if (powerUpTimer > 0f) {
            powerUpTimer -= dt
            if (powerUpTimer <= 0f) {
                activePowerUp = 0
            } else if (activePowerUp == 1 && rocketFireCooldown <= 0f) {
                // FÜZE ÖZELLİĞİ: Rakip Görüş Açımıza ve Doğrultumuza Girince Otomatik Ateşlenir!
                checkAndAutoFireRocket()
            }
        }

        // Ateşlenen Füzelerin Dimdik İlerlemesi ve Rakibi Vurma Kontrolü
        for (rk in rockets) {
            if (!rk.active) continue
            rk.life -= dt
            if (rk.life <= 0f) {
                rk.active = false
                continue
            }
            rk.x += cos(rk.yaw) * 480f * dt
            rk.y += sin(rk.yaw) * 480f * dt

            for (bot in bots) {
                if (bot.crashTimer <= 0f) {
                    val dx = rk.x - bot.x
                    val dy = rk.y - bot.y
                    if (dx * dx + dy * dy < 480f) {
                        // Füze Rakibi Vurdu! Rakip Devrilir!
                        rk.active = false
                        bot.crashTimer = 5.0f
                        bot.z = 26f
                        bot.speed = 0f
                        spawnDebris(bot.x, bot.y, 8, bot.color)
                        triggerHaptic(70L, 255)
                        break
                    }
                }
            }
        }

        // 5 Saniye Patlama Geri Sayımı -> En Yakın Yolda Doğar!
        if (isCrashing) {
            crashTimer -= dt
            carZ += carVZ * dt
            carVZ -= 235f * dt
            if (carZ < 0f) {
                carZ = 0f
                carVZ = abs(carVZ) * 0.45f
            }
            if (botsEnabled) updateBots(dt)
            if (crashTimer <= 0f) {
                respawnOnNearestRoad()
            }
            return
        }

        // Sadece Crown Direksiyon Girişi
        if (!isBezelDragging) {
            crownSteer *= 0.93f
        }
        steerInput = crownSteer

        val turnRate = when (sensLevel) { 0 -> 2.4f; 1 -> 3.2f; else -> 4.1f }
        if (speed > 5f) {
            carYaw += steerInput * turnRate * dt
        }

        val driftGrip = if (isOffRoad) 1.45f else when (selectedModel) { 0 -> 2.4f; 1 -> 2.1f; 2 -> 1.8f; else -> 2.6f }
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt
        camYaw += wrapAngle(carYaw - camYaw) * 6.5f * dt

        val slip = abs(diff)
        val isDriftingNow = slip > 0.13f

        // KADEMELİ HIZLANMA MEKANİĞİ:
        // Başta yavaş başlar, düz sürdükçe sınır değere kadar hızlanır, DRİFT ATARKEN HIZLANMAZ!
        val baseMaxForward = when (selectedModel) { 0 -> 275f; 1 -> 290f; 2 -> 280f; else -> 300f }
        var currentMaxSpeed = if (isOffRoad) baseMaxForward * 0.56f else baseMaxForward

        if (activePowerUp == 3 && powerUpTimer > 0f) {
            // Tuzak Kutu: Yavaşlatma Cezası
            currentMaxSpeed *= 0.58f
        }
        if (turboActiveTimer > 0f) {
            turboActiveTimer -= dt
            currentMaxSpeed *= 1.38f // Turbo Hız Artışı!
        }

        if (isFullStopped) {
            speed = (speed - 520f * dt).coerceAtLeast(0f)
        } else if (turboActiveTimer > 0f) {
            speed = (speed + 320f * dt).coerceAtMost(currentMaxSpeed)
        } else if (isDriftingNow) {
            // Drift atarken hızlanma durur, mevcut hızı korur / çok hafif sürtünme olur
            if (speed > currentMaxSpeed) {
                speed = (speed - 140f * dt).coerceAtLeast(currentMaxSpeed)
            } else {
                speed = (speed - 12f * dt).coerceAtLeast(95f)
            }
        } else {
            // Düz sürerken kademeli olarak maksimum hız sınırına kadar hızlanır
            if (speed < currentMaxSpeed) {
                speed = (speed + 64f * dt).coerceAtMost(currentMaxSpeed)
            } else {
                speed = (speed - 120f * dt).coerceAtLeast(currentMaxSpeed)
            }
        }

        carX += cos(velYaw) * speed * dt
        carY += sin(velYaw) * speed * dt

        currentLapRecording.add(GhostPose(carX, carY, carYaw))
        ghostFrameIdx++

        // Yoldaki Gizemli Kutuları (?) Toplama Kontrolü
        for (box in mysteryBoxes) {
            if (box.respawnTimer <= 0f) {
                val dx = carX - box.x
                val dy = carY - box.y
                if (dx * dx + dy * dy < 420f) {
                    box.respawnTimer = 10.0f
                    // Rastgele Özellik Ver: 1=FÜZE, 2=ÖN DİKEN, 3=YAVAŞLATMA (Tuzak), 4=ETKİSİZ (Tuzak)
                    val roll = Random.nextInt(100)
                    activePowerUp = when {
                        roll < 38 -> 1 // %38 Füze
                        roll < 72 -> 2 // %34 Ön Diken
                        roll < 86 -> 3 // %14 Yavaşlatma Tuzağı
                        else -> 4      // %14 Etkisiz Kalkan Tuzağı
                    }
                    powerUpTimer = 9.0f // 9 saniye sürer, sonra yok olur
                    vibeClick()
                }
            }
        }

        // Yoldaki Rastgele Bariyerlere Çarpma Kontrolü
        if (invulnerableTimer <= 0f) {
            for (bar in roadBarriers) {
                val dx = carX - bar.x
                val dy = carY - bar.y
                if (dx * dx + dy * dy < 380f) {
                    carDamage += 28f
                    speed *= 0.45f
                    if (carDamage > 40f) bumperBroken = true
                    spawnDebris(bar.x, bar.y, 5, Color.rgb(255, 140, 20))
                    triggerHaptic(70L, 250)
                    relocateBarrierRandomly(bar) // Çarpılan bariyer başka rastgele bir yerde doğar
                    if (carDamage >= 100f) {
                        triggerExplosionWith5SecTimer()
                        return
                    }
                }
            }
        }

        // Bot Güncellemesi ve Botlarla Çarpışma (Ön Diken Varsa Rakibi Anında Devirir!)
        if (botsEnabled) {
            updateBots(dt)
            for (bot in bots) {
                if (bot.crashTimer > 0f) continue
                val dx = carX - bot.x
                val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 529f && distSq > 0.01f) {
                    if (activePowerUp == 2 && powerUpTimer > 0f) {
                        // ÖN DİKEN AKTİF: Rakibe değdiğin an rakip devrilir!
                        bot.crashTimer = 5.0f
                        bot.z = 26f
                        bot.speed = 0f
                        spawnDebris(bot.x, bot.y, 8, bot.color)
                        triggerHaptic(65L, 255)
                    } else if (activePowerUp == 4 && powerUpTimer > 0f) {
                        // ETKİSİZ TUZAK AKTİF: Rakibe hiç zarar veremezsin
                        val dist = sqrt(distSq)
                        val pushAng = atan2(dy, dx)
                        carX += cos(pushAng) * (23f - dist)
                        carY += sin(pushAng) * (23f - dist)
                    } else {
                        val dist = sqrt(distSq)
                        val pushAng = atan2(dy, dx)
                        val overlap = 23f - dist
                        carX += cos(pushAng) * overlap * 0.6f
                        carY += sin(pushAng) * overlap * 0.6f
                        bot.x -= cos(pushAng) * overlap * 0.6f
                        bot.y -= sin(pushAng) * overlap * 0.6f
                        bot.speed *= 0.75f
                        triggerHaptic(40L, 220)
                    }
                }
            }
        }

        // Çimdeki Ağaç ve Binalara Çarpma Kontrolü (3 Sn Dokunulmazlık Varken Çarpmaz)
        if (invulnerableTimer <= 0f) {
            for (obs in obstacles) {
                val dx = carX - obs.x
                val dy = carY - obs.y
                val minD = obs.radius + 11f
                if (dx * dx + dy * dy < minD * minD) {
                    carDamage += speed * 0.35f
                    if (speed > 155f || carDamage >= 100f) {
                        triggerExplosionWith5SecTimer()
                        return
                    } else {
                        val ang = atan2(dy, dx)
                        carX = obs.x + cos(ang) * (minD + 4f)
                        carY = obs.y + sin(ang) * (minD + 4f)
                        speed *= 0.4f
                        spawnDebris(carX, carY, 5, carColors[selectedColor])
                        triggerHaptic(75L, 250)
                    }
                }
            }
        }

        // YEREL PENCERE ARAMASI (300 noktayı taramak yerine sadece yakın 24 noktayı tarar -> Sıfır Isınma!)
        var bestDistSq = Float.MAX_VALUE
        var bestIdx = currentWp
        val total = trackPts.size
        for (step in -8..16) {
            val idx = (currentWp + step + total) % total
            val dx = carX - trackPts[idx].x
            val dy = carY - trackPts[idx].y
            val dSq = dx * dx + dy * dy
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestIdx = idx
            }
        }
        val bestDist = sqrt(bestDistSq)

        // Çime Çıkma ve Yavaşlatma
        isOffRoad = bestDist > roadHalfWidth + 4f
        if (isOffRoad && speed > 50f && now - lastVibeTime > 120) {
            triggerHaptic(12L, 85)
            lastVibeTime = now
        }

        val outerFence = roadHalfWidth * 4.2f
        if (bestDist > outerFence) {
            val tp = trackPts[bestIdx]
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * outerFence
            carY = tp.y + sin(angToCar) * outerFence
            speed *= 0.6f
        }

        // Tur Sayacı
        if (bestIdx > maxWpReached && bestIdx - maxWpReached < total / 3) {
            maxWpReached = bestIdx
        }
        if (bestIdx < total / 10 && maxWpReached > (total * 3) / 4) {
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
                triggerHaptic(50L, 255)
            } else {
                val finalScore = score.toInt()
                val oldBest = prefs.getInt("best_score_$currentTrackIdx", 0)
                if (finalScore > oldBest) {
                    isNewRecord = true
                    prefs.edit().putInt("best_score_$currentTrackIdx", finalScore).apply()
                }
                state = 4
            }
        }
        currentWp = bestIdx
        playerProgress = (lap - 1) * total + currentWp.toFloat()

        var pos = 1
        if (botsEnabled) {
            for (bot in bots) {
                if (bot.totalProgress > playerProgress) pos++
            }
        }
        racePosition = pos

        // Drift Puanlama ve TURBO DOLDURMA
        val backX = carX - cos(carYaw) * 12f
        val backY = carY - sin(carYaw) * 12f
        val perpX = -sin(carYaw) * 7.0f
        val perpY = cos(carYaw) * 7.0f
        val curLX = backX - perpX; val curLY = backY - perpY
        val curRX = backX + perpX; val curRY = backY + perpY

        if ((isDriftingNow || isOffRoad) && speed > 70f) {
            if (!isOffRoad) {
                driftTimer += dt
                driftCombo = when {
                    driftTimer > 3.0f -> 4
                    driftTimer > 1.5f -> 3
                    driftTimer > 0.4f -> 2
                    else -> 1
                }
                score += slip * 540f * driftCombo * dt
                // Drift Yaptıkça Turbo Barı Dolar!
                if (turboActiveTimer <= 0f) {
                    turboCharge = (turboCharge + slip * 36f * dt).coerceAtMost(100f)
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

            if (!isOffRoad && now - lastVibeTime > 75) {
                val amp = (slip * 240f).toInt().coerceIn(85, 245)
                triggerHaptic(22L, amp)
                lastVibeTime = now
            }
        } else {
            hasPrevSkid = false
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    // Rakip Görüş Açımıza ve Doğrultumuza Girince Otomatik Füze Ateşleyici
    private fun checkAndAutoFireRocket() {
        val fwdX = cos(carYaw)
        val fwdY = sin(carYaw)
        var targetFound = false

        for (bot in bots) {
            if (bot.crashTimer > 0f) continue
            val dx = bot.x - carX
            val dy = bot.y - carY
            val forwardDist = dx * fwdX + dy * fwdY
            val sideDist = abs(-dx * fwdY + dy * fwdX)
            // Rakip önümüzde 25..270 birim mesafedeyse ve namlu doğrultusundaysa ateşle!
            if (forwardDist in 25f..270f && sideDist < 26f) {
                targetFound = true
                break
            }
        }

        if (targetFound) {
            for (rk in rockets) {
                if (!rk.active) {
                    rk.active = true
                    rk.x = carX + fwdX * 18f
                    rk.y = carY + fwdY * 18f
                    rk.z = 4.5f
                    rk.yaw = carYaw // Dimdik ileri gider!
                    rk.life = 1.8f
                    rocketFireCooldown = 1.1f
                    triggerHaptic(35L, 230)
                    break
                }
            }
        }
    }

    // ==================== OYUN İÇİ HUD: YANA ALINMIŞ DUR BUTONU, TURBO VE AKTİF ÖZELLİK SİMGESİ ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        paint.isAntiAlias = true

        // 1. Sol-Üst İç Skor Kutusu
        tempRect.set(cx - R * 0.56f, cy - R * 0.62f, cx - R * 0.06f, cy - R * 0.26f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 14, 20, 26)
        canvas.drawRoundRect(tempRect, R * 0.07f, R * 0.07f, paint)

        paint.color = Color.rgb(190, 200, 210)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.054f
        val timeStr = String.format("%.1fs", currentLapTime)
        val posStr = if (botsEnabled) " • $racePosition/4" else ""
        canvas.drawText("L$lap/$totalLaps • $timeStr$posStr", tempRect.centerX(), cy - R * 0.49f, paint)

        paint.color = if (isOffRoad) Color.rgb(255, 195, 30) else Color.rgb(0, 225, 255)
        paint.textSize = R * 0.120f
        canvas.drawText("${score.toInt()}", tempRect.centerX(), cy - R * 0.32f, paint)

        // 2. Haritanın Yanında GÜVENLİ ÇIKIŞ (ÇIK) Butonu
        val exitBtnX = cx + R * 0.08f
        val exitBtnY = cy - R * 0.42f
        paint.color = Color.argb(200, 185, 35, 45)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.10f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.055f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.020f, paint)

        // 3. Sağ-Üst İç Mini Harita
        val mapCX = cx + R * 0.40f
        val mapCY = cy - R * 0.40f
        val mapRad = R * 0.19f
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
        paint.strokeWidth = 2.5f
        paint.color = Color.rgb(120, 135, 150)
        canvas.drawPath(miniPath, paint)

        paint.style = Paint.Style.FILL
        if (botsEnabled) {
            for (bot in bots) {
                paint.color = bot.color
                canvas.drawCircle(mapCX + bot.x * miniScale, mapCY + bot.y * miniScale, R * 0.018f, paint)
            }
        }
        for (rp in remotePlayers) {
            if (rp.active) {
                paint.color = Color.rgb(0, 255, 220)
                canvas.drawCircle(mapCX + rp.x * miniScale, mapCY + rp.y * miniScale, R * 0.024f, paint)
            }
        }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + carX * miniScale, mapCY + carY * miniScale, R * 0.026f, paint)

        // 4. SOL-ORTA TURBO İKONU VE DOLuluk HALKASI
        val turboBtnX = cx - R * 0.46f
        val turboBtnY = cy - R * 0.06f
        val turboRad = R * 0.115f
        paint.style = Paint.Style.FILL
        paint.color = if (turboActiveTimer > 0f) Color.rgb(0, 200, 255) else Color.argb(185, 20, 30, 42)
        canvas.drawCircle(turboBtnX, turboBtnY, turboRad, paint)

        // Turbo Doluluk Yay Çizgisi
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = R * 0.022f
        paint.color = if (turboCharge >= 35f) Color.rgb(0, 245, 255) else Color.rgb(90, 110, 130)
        tempRect.set(turboBtnX - turboRad, turboBtnY - turboRad, turboBtnX + turboRad, turboBtnY + turboRad)
        canvas.drawArc(tempRect, -90f, (turboCharge / 100f) * 360f, false, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (turboActiveTimer > 0f) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.050f
        canvas.drawText("TURBO", turboBtnX, turboBtnY + R * 0.018f, paint)

        // 5. TURBO BUTONUNUN ALTINDA AKTİF KUTU ÖZELLİĞİ SİMGESİ (FÜZE / DİKEN / YAVAŞ / KALKAN)
        val pwrX = cx - R * 0.46f
        val pwrY = cy + R * 0.14f
        tempRect.set(pwrX - R * 0.14f, pwrY - R * 0.065f, pwrX + R * 0.14f, pwrY + R * 0.065f)
        paint.color = Color.argb(180, 18, 24, 32)
        canvas.drawRoundRect(tempRect, R * 0.03f, R * 0.03f, paint)

        if (activePowerUp != 0 && powerUpTimer > 0f) {
            val (pName, pCol) = when (activePowerUp) {
                1 -> "FÜZE" to Color.rgb(255, 80, 60)
                2 -> "DİKEN" to Color.rgb(0, 240, 255)
                3 -> "YAVAŞ" to Color.rgb(255, 180, 30)
                else -> "ETKİSİZ" to Color.LTGRAY
            }
            paint.color = pCol
            paint.textSize = R * 0.048f
            val pSec = String.format("%.0fs", powerUpTimer)
            canvas.drawText("$pName $pSec", pwrX, pwrY + R * 0.018f, paint)
        } else {
            paint.color = Color.rgb(95, 105, 115)
            paint.textSize = R * 0.045f
            canvas.drawText("KUTU: YOK", pwrX, pwrY + R * 0.016f, paint)
        }

        // 6. YANA ALINMIŞ TAM DURDURMA (DUR / SÜR) BUTONU (Arabanın Üstünü Hiç Kapatmaz!)
        val stopBtnX = cx - R * 0.44f
        val stopBtnY = cy + R * 0.36f
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(190, 215, 50, 50)
        canvas.drawCircle(stopBtnX, stopBtnY, R * 0.115f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.062f
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.022f, paint)

        // 7. 3 - 2 - 1 Turnuva Başlangıç Geri Sayımı ve Bildirimler
        if (startCountdown > 0f) {
            val countNum = ceil(startCountdown).toInt()
            val countText = if (countNum > 0) "$countNum" else "BAŞLA!"
            paint.color = if (countNum > 0) Color.rgb(255, 215, 30) else Color.rgb(75, 255, 130)
            paint.textSize = R * 0.24f
            canvas.drawText(countText, cx, cy - R * 0.02f, paint)
        } else if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55)
            paint.textSize = R * 0.082f
            val secStr = String.format("%.1fs", crashTimer.coerceAtLeast(0f))
            canvas.drawText("PATLADI! $secStr", cx, cy - R * 0.08f, paint)
        } else if (respawnBannerTimer > 0f) {
            paint.color = Color.rgb(80, 255, 140)
            paint.textSize = R * 0.075f
            canvas.drawText("YOLDA DOĞDU", cx, cy - R * 0.08f, paint)
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

    // ==================== 4 KİŞİLİK CO-OP ODA, ANA MENÜ, GARAJ VE AYARLAR ====================
    private fun drawCoopLobby(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        paint.isAntiAlias = true
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
        paint.isAntiAlias = true
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
        paint.isAntiAlias = true
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.14f
        canvas.drawText("Garage", cx, cy - R * 0.58f, paint)

        garagePreviewYaw += dt * 1.1f
        val savedX = carX; val savedY = carY; val savedCam = camYaw
        carX = 0f; carY = 0f; camYaw = 0f
        paint.isAntiAlias = false
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = false)
        paint.isAntiAlias = true
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
        paint.isAntiAlias = true
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
        paint.isAntiAlias = true
        canvas.drawColor(Color.argb(215, 10, 16, 20))

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(80, 235, 155)
        paint.textSize = R * 0.16f
        val winTitle = if (botsEnabled && racePosition > 1) "FINISHED #$racePosition" else "YOU WIN!"
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
