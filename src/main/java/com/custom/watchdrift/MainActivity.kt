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

data class BotCar(
    var wpFloat: Float,
    var laneOffset: Float,
    var targetLane: Float,
    val speedFactor: Float,
    val color: Int,
    val modelIdx: Int,
    var x: Float = 0f,
    var y: Float = 0f,
    var yaw: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f
)

data class Obstacle3D(val x: Float, val y: Float, val radius: Float, val height: Float, val isTree: Boolean)

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
    // 4 Farklı Spor Araç Modeli
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

    private val trackPts = ArrayList<Vec2>(450)
    private val trackNormals = ArrayList<Vec2>(450)
    private val trackTangents = ArrayList<Float>(450)
    private val obstacles = ArrayList<Obstacle3D>(40)
    private val roadHalfWidth = 58f

    // Oyuncu Araç Fiziği (Sadece İleri + Tam Durdurma + Crown Direksiyon)
    private var carX = 0f
    private var carY = 0f
    private var carZ = 0f
    private var carVZ = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var speed = 0f
    private var camYaw = 0f
    private var isOffRoad = false

    private var isFullStopped = false // Tam durdurma butonu durumu
    private var isHoldingStop = false
    private var steerInput = 0f
    private var crownSteer = 0f

    // Dokunmatik Çerçeve (Touch Crown) Açı Takibi (Sadece en dış çemberde dairesel kaydırma)
    private var isBezelDragging = false
    private var lastBezelAngle = 0f

    // Hasar ve Olduğu Yerde Yeniden Doğma Değişkenleri
    private var carDamage = 0f
    private var spoilerBroken = false
    private var bumperBroken = false
    private var isCrashing = false
    private var crashTimer = 0f
    private var crashSpotX = 0f
    private var crashSpotY = 0f
    private var respawnBannerTimer = 0f

    private val debrisPool = Array(32) { DebrisPart() }
    private var debrisHead = 0
    private val particlePool = Array(36) { Particle3D() }
    private var particleHead = 0
    private val tirePool = Array(130) { TireMark() }
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

    // Ghost ve Botlar
    private val currentLapRecording = ArrayList<GhostPose>(1200)
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
    private val bots = ArrayList<BotCar>(3)

    // ==================== EŞLEŞME GEREKTİRMEYEN BLUETOOTH TARAMA VE ODA SİSTEMİ ====================
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

    // Çevredeki Eşleşmemiş Saatleri Otomatik Bulan Alıcı (BroadcastReceiver)
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
    private var currentFps = 60
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

    // ==================== ÖNCEDEN EŞLEŞME GEREKTİRMEYEN BLUETOOTH ODA MOTORU ====================
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

        // Saati Çevredeki Diğer Saatlere Görünür Yap (Eşleşmesiz Bulunabilmesi İçin)
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
                        // Co-op'ta Herkese Farklı Spor Araç Modeli Verilir
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
                // İlk Bağlantıda İstemciye Özel Farklı Araç Modelini Gönder
                val assignedClientModel = (selectedModel + slot + 1) % 4
                while (btRunning && lobbyRole == 1 && sock.isConnected) {
                    out.writeInt(state)
                    out.writeInt(currentTrackIdx)
                    out.writeInt(hostClientSockets.size + 1)
                    out.writeInt(assignedClientModel)

                    // Host (Oyuncu 1) Verisi
                    out.writeFloat(carX)
                    out.writeFloat(carY)
                    out.writeFloat(carYaw)
                    out.writeInt(selectedColor)
                    out.writeInt(selectedModel)

                    // Diğer İstemcilerin Verisi
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

                // 1. Önceden Eşleşmiş Saat Varsa Hemen Dene
                val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
                if (!bonded.isNullOrEmpty()) {
                    for (dev in bonded) {
                        if (!btRunning || lobbyRole != 2 || clientSocket != null) break
                        triedAddresses.add(dev.address)
                        tryConnectToDiscoveredDevice(dev)
                    }
                }

                // 2. Eşleşme Gerektirmeden Çevredeki Tüm Saatleri Aktif Tara (startDiscovery)
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

                // Host Verisi
                remotePlayers[0].active = true
                remotePlayers[0].x = inp.readFloat()
                remotePlayers[0].y = inp.readFloat()
                remotePlayers[0].yaw = inp.readFloat()
                remotePlayers[0].colorIdx = inp.readInt().coerceIn(0, 7)
                remotePlayers[0].modelIdx = inp.readInt().coerceIn(0, 3)

                // Diğer 2 Oyuncu
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

    private fun vibeClick() = triggerHaptic(22L, 190)

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
                triggerHaptic(12L, 115)
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

            if (i % 18 == 0) {
                val side = if ((i / 18) % 2 == 0) 1f else -1f
                val distFromRoad = roadHalfWidth + 70f + (i % 35)
                val ox = trackPts[i].x + nx * side * distFromRoad
                val oy = trackPts[i].y + ny * side * distFromRoad
                val isTree = (i / 18) % 3 != 0
                obstacles.add(Obstacle3D(ox, oy, if (isTree) 16f else 26f, if (isTree) 34f else 48f, isTree))
            }
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
        speed = 110f
        isOffRoad = false

        isFullStopped = false
        isHoldingStop = false
        steerInput = 0f
        crownSteer = 0f
        isBezelDragging = false

        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false
        isCrashing = false
        crashTimer = 0f
        respawnBannerTimer = 0f

        for (d in debrisPool) d.active = false
        for (p in particlePool) p.active = false
        for (tm in tirePool) tm.active = false
        hasPrevSkid = false
        currentLapRecording.clear()
        ghostFrameIdx = 0

        // Botlara da Farklı Spor Araç Modelleri Verilir
        bots.clear()
        if (botsEnabled) {
            bots.add(BotCar(wpFloat = 4f, laneOffset = -18f, targetLane = -18f, speedFactor = 0.55f, color = Color.rgb(255, 195, 20), modelIdx = (selectedModel + 1) % 4))
            bots.add(BotCar(wpFloat = 8f, laneOffset = 18f, targetLane = 18f, speedFactor = 0.60f, color = Color.rgb(235, 45, 90), modelIdx = (selectedModel + 2) % 4))
            updateBots(0.01f)
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
            val spd = 40f + Random.nextFloat() * 95f
            d.active = true
            d.x = ix; d.y = iy; d.z = 4f + Random.nextFloat() * 6f
            d.vx = cos(ang) * spd
            d.vy = sin(ang) * spd
            d.vz = 65f + Random.nextFloat() * 90f
            d.yaw = ang
            d.size = 2.8f + Random.nextFloat() * 3.0f
            d.color = if (i % 2 == 0) baseCol else Color.rgb(40, 40, 45)
            d.life = 5.5f
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

    private fun triggerExplosionAtSpot() {
        if (isCrashing) return
        isCrashing = true
        crashTimer = 2.0f
        crashSpotX = carX
        crashSpotY = carY
        carVZ = 140f
        speed = 0f

        spawnDebris(carX, carY, 12, carColors[selectedColor])
        for (i in 0 until 14) {
            val ang = Random.nextFloat() * 6.28f
            val spd = 20f + Random.nextFloat() * 60f
            val fireCol = if (i % 2 == 0) Color.rgb(255, 110, 20) else Color.rgb(255, 210, 40)
            spawnParticle(carX, carY, 5f, cos(ang) * spd, sin(ang) * spd, 40f + Random.nextFloat() * 50f, 5.5f, fireCol, 240)
        }
        triggerHaptic(190L, 255)
    }

    private fun respawnAtCrashSpot() {
        isCrashing = false
        carZ = 0f
        carVZ = 0f
        carDamage = 0f
        spoilerBroken = false
        bumperBroken = false

        carX = crashSpotX
        carY = crashSpotY
        carYaw = trackTangents[currentWp]
        velYaw = carYaw
        speed = 80f
        isFullStopped = false
        respawnBannerTimer = 1.5f
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
                // SAĞA / SOLA DOKUNARAK YÖN VERME TAMAMEN KALDIRILDI!
                // 1. İç Bölgedeki Güvenli ÇIKIŞ Butonu (Mini Haritanın Sol Yanında - Crown'dan Uzak)
                val exitBtnX = cx + R * 0.08f
                val exitBtnY = cy - R * 0.42f
                // 2. İç Bölgedeki TAM DURDURMA (DUR) Butonu (Alt-İç Merkezde - Crown'dan Uzak)
                val stopBtnX = cx
                val stopBtnY = cy + R * 0.46f

                val distFromCenter = hypot(x - cx, y - cy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Güvenli Çıkış Butonu Kontrolü
                        if (hypot(x - exitBtnX, y - exitBtnY) < R * 0.13f) {
                            state = 0
                            vibeClick()
                            return true
                        }
                        // Tam Durdurma Butonu Kontrolü
                        if (hypot(x - stopBtnX, y - stopBtnY) < R * 0.17f) {
                            isFullStopped = !isFullStopped
                            isHoldingStop = true
                            vibeClick()
                            return true
                        }
                        // Sadece En Dış Çemberde (Crown / Bezel Hattı: > 0.74R) Dairesel Kaydırma Takibi
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
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.005f, 0.05f)
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
            paint.textSize = R * 0.068f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.87f, paint)
        }

        postInvalidateOnAnimation()
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
        if (depth < 16f || depth > 1450f) return false
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
        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, frX, frY, bz, frX, frY, topZ, flX, flY, z4 = topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, bz, rrX, rrY, bz, rrX, rrY, topZ, rlX, rlY, topZ, rearColor)
        drawQuad3D(canvas, cx, cy, R, flX, flY, topZ, frX, frY, topZ, rrX, rrY, topZ, rlX, rlY, topZ, topColor)
    }

    // ==================== 4 FARKLI 3B SPOR ARAÇ MODELİ TASARIMI ====================
    private fun drawSport3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, zOffset: Float, yaw: Float,
        baseColor: Int, modelIdx: Int, isGhost: Boolean, isPlayer: Boolean = false
    ) {
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
                // MODEL 0: JDM GT (Nissan GT-R / Silvia Tarzı Drift Kupası)
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
                // MODEL 1: SUPER V12 (Lamborghini Tarzı Basık Geniş Kama Süper Spor)
                val bodyLen = if (isPlayer && bumperBroken) 14.0f else 16.8f
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz, bodyLen * 0.85f, 9.8f, 3.4f, yaw, topCol, sideCol, rearCol)
                // Sivri Alçak Kama Burun
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 12.0f, y + s * 12.0f, bz, 5.5f, 8.8f, 2.1f, yaw, topCol, sideCol, wheelCol)
                }
                // İleriye Yatık Alçak Kokpit ve Yan Hava Kanalları
                draw3DBox(canvas, cx, cy, R, x + c * 1.0f, y + s * 1.0f, bz + 3.4f, 8.2f, 6.4f, 2.9f, yaw, topCol, glassCol, rearCol)
                draw3DBox(canvas, cx, cy, R, x - c * 4.5f, y - s * 4.5f, bz + 2.2f, 5.0f, 10.2f, 1.8f, yaw, sideCol, wheelCol, rearCol)
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 4.8f, 2.2f, 10.2f, 0.9f, yaw, rearCol, sideCol, wheelCol)
                }
            }

            2 -> {
                // MODEL 2: MUSCLE V8 (Uzun Kaput + Krom Supercharger Motor Çıkıntılı Amerikan Kaslı)
                val bodyLen = if (isPlayer && bumperBroken) 14.2f else 16.5f
                draw3DBox(canvas, cx, cy, R, x, y, bz + 0.4f, bodyLen, 9.0f, 4.6f, yaw, topCol, sideCol, rearCol)
                // Kaput Üstü Krom Supercharger (Blower) Hava Girişi
                draw3DBox(canvas, cx, cy, R, x + c * 7.5f, y + s * 7.5f, bz + 5.0f, 3.8f, 3.2f, 2.4f, yaw, chromeCol, wheelCol, chromeCol)
                // Geride Konumlanmış Fastback Kabin
                draw3DBox(canvas, cx, cy, R, x - c * 3.8f, y - s * 3.8f, bz + 5.0f, 6.8f, 7.2f, 3.6f, yaw, topCol, glassCol, glassCol)
                // Ducktail Arka Rüzgarlık
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 15.2f, y - s * 15.2f, bz + 5.0f, 1.4f, 8.8f, 1.8f, yaw, rearCol, sideCol, rearCol)
                }
            }

            else -> {
                // MODEL 3: F1 HYPER (Açık Tekerlekli Formula / LeMans Hiper Araç)
                // İnce Aerodinamik Gövde Tüpü ve Yan Pontonlar (Sidepods)
                draw3DBox(canvas, cx, cy, R, x - c * 1.5f, y - s * 1.5f, bz, 11.5f, 7.6f, 3.2f, yaw, topCol, sideCol, rearCol)
                draw3DBox(canvas, cx, cy, R, x + c * 4.5f, y + s * 4.5f, bz + 0.4f, 12.5f, 3.4f, 3.0f, yaw, topCol, sideCol, rearCol)
                // Geniş F1 Ön Kanat (Front Wing)
                if (!(isPlayer && bumperBroken)) {
                    draw3DBox(canvas, cx, cy, R, x + c * 16.2f, y + s * 16.2f, bz - 0.4f, 2.2f, 10.6f, 1.1f, yaw, chromeCol, topCol, sideCol)
                }
                // Kokpit Kask/Hava Kulesi ve Köpekbalığı Sırtı (Shark Fin)
                draw3DBox(canvas, cx, cy, R, x - c * 1.0f, y - s * 1.0f, bz + 3.2f, 4.5f, 3.2f, 3.2f, yaw, glassCol, glassCol, rearCol)
                draw3DBox(canvas, cx, cy, R, x - c * 8.0f, y - s * 8.0f, bz + 3.2f, 5.0f, 0.8f, 3.6f, yaw, topCol, sideCol, rearCol)
                // Dev F1 Arka Kanat
                if (!(isPlayer && spoilerBroken)) {
                    draw3DBox(canvas, cx, cy, R, x - c * 14.8f, y - s * 14.8f, bz + 5.8f, 2.6f, 9.8f, 1.4f, yaw, chromeCol, topCol, rearCol)
                }
            }
        }

        // Arka Stop Lambaları
        if (!isGhost) {
            val tailX = x - c * 15.5f
            val tailY = y - s * 15.5f
            val tailCol = if (isPlayer && isFullStopped) Color.rgb(255, 220, 40) else Color.rgb(255, 30, 30)
            draw3DBox(canvas, cx, cy, R, tailX - rxVec * 5.2f, tailY - ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol)
            draw3DBox(canvas, cx, cy, R, tailX + rxVec * 5.2f, tailY + ryVec * 5.2f, bz + 2.0f, 0.8f, 2.0f, 1.1f, yaw, tailCol, tailCol, tailCol)
        }
    }

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

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 55f
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

        for (step in 48 downTo -22) {
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

        // Lastik ve Çim İzleri
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.2f
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

        // Çim Üzerindeki 3B Ağaç ve Binalar
        for (obs in obstacles) {
            val dx = obs.x - carX
            val dy = obs.y - carY
            if (dx * dx + dy * dy < 360000f) {
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

        // 4 Kişilik Co-op Diğer Oyuncular (Her Biri Farklı Spor Araç Modelinde!)
        for (i in 0..2) {
            val rp = remotePlayers[i]
            if (rp.active) {
                drawSport3DCar(canvas, cx, cy, R, rp.x, rp.y, 0f, rp.yaw, carColors[rp.colorIdx], rp.modelIdx, isGhost = false)
                if (project3D(rp.x, rp.y, 16f, cx, cy, R)) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(0, 255, 220)
                    paint.textSize = R * 0.058f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("P${i + 2}", projX, projY, paint)
                }
            }
        }

        // Yapay Zeka Bot Araçları
        if (botsEnabled) {
            for (bot in bots) {
                drawSport3DCar(canvas, cx, cy, R, bot.x, bot.y, 0f, bot.yaw, bot.color, bot.modelIdx, isGhost = false)
            }
        }

        // Oyuncunun 3B Spor Aracı (Sağa-Sola Yatma Yok, Tam Dengeli)
        drawSport3DCar(canvas, cx, cy, R, carX, carY, carZ, carYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = true)

        // Patlama Alevi, Duman ve Çim Parçacıkları
        paint.style = Paint.Style.FILL
        for (p in particlePool) {
            if (!p.active) continue
            if (project3D(p.x, p.y, p.z, cx, cy, R)) {
                paint.color = Color.argb(p.alpha.coerceIn(0, 255), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(projX, projY, (p.size * projScale).coerceAtLeast(1.5f), paint)
            }
        }
    }

    // ==================== FİZİK: DENGELİ VİRAJ, İLERİ SÜRÜŞ VE TAM DURDURMA ====================
    private fun updateBots(dt: Float) {
        val total = trackPts.size
        for (bot in bots) {
            bot.wpFloat += bot.speedFactor * 11.2f * dt
            val idx0 = (bot.wpFloat.toInt() % total + total) % total
            val idx1 = (idx0 + 1) % total
            val frac = bot.wpFloat - floor(bot.wpFloat)

            if (abs(bot.laneOffset - bot.targetLane) < 2f) {
                if ((SystemClock.uptimeMillis() + bot.color) % 140L == 0L) {
                    bot.targetLane = if (bot.targetLane < 0f) 18f else -18f
                }
            } else {
                bot.laneOffset += sign(bot.targetLane - bot.laneOffset) * 12f * dt
            }

            val p0 = trackPts[idx0]
            val p1 = trackPts[idx1]
            val n0 = trackNormals[idx0]
            bot.x = p0.x + (p1.x - p0.x) * frac + n0.x * bot.laneOffset + bot.vx * dt
            bot.y = p0.y + (p1.y - p0.y) * frac + n0.y * bot.laneOffset + bot.vy * dt
            bot.vx *= 0.90f
            bot.vy *= 0.90f

            val roadYaw = trackTangents[idx0]
            val nextYaw = trackTangents[(idx0 + 5) % total]
            bot.yaw = roadYaw + wrapAngle(nextYaw - roadYaw) * 1.25f
        }
    }

    private fun updateGamePhysics(dt: Float, now: Long) {
        currentLapTime = (now - lapStartTime) / 1000f
        if (respawnBannerTimer > 0f) respawnBannerTimer -= dt

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
            spawnParticle(carX, carY, carZ + 5f, 0f, 0f, 26f, 3.5f, Color.rgb(65, 65, 70), 155)
        }
        for (p in particlePool) {
            if (!p.active) continue
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.size += 5f * dt
            p.alpha -= (210 * dt).toInt()
            if (p.alpha <= 0) p.active = false
        }

        // Patlama Durumunda Olduğu Yerde Yeniden Doğma Sayacı
        if (isCrashing) {
            crashTimer -= dt
            carZ += carVZ * dt
            carVZ -= 235f * dt
            if (carZ < 0f) {
                carZ = 0f
                carVZ = abs(carVZ) * 0.45f
                triggerHaptic(40L, 230)
            }
            if (crashTimer <= 0f) {
                respawnAtCrashSpot()
            }
            return
        }

        // Sadece Crown Direksiyon Girişi (Yumuşak Toplama)
        if (!isBezelDragging) {
            crownSteer *= 0.93f
        }
        steerInput = crownSteer

        // Sadece İleri Sürüş + Tam Durdurma (DUR Butonu Aktifse Tamamen Durur)
        val baseMaxForward = when (selectedModel) { 0 -> 265f; 1 -> 285f; 2 -> 275f; else -> 305f }
        val maxForwardSpeed = if (isOffRoad) baseMaxForward * 0.56f else baseMaxForward

        if (isFullStopped || isHoldingStop) {
            speed = (speed - 520f * dt).coerceAtLeast(0f)
        } else {
            val accelRate = if (isOffRoad) 240f else 195f
            speed += (maxForwardSpeed - speed).coerceIn(-accelRate * dt, accelRate * dt)
        }

        // Dengeli Dönüş (Çok sağ-sol yapınca devrilmez!)
        val turnRate = when (sensLevel) { 0 -> 2.4f; 1 -> 3.2f; else -> 4.1f }
        if (speed > 5f) {
            carYaw += steerInput * turnRate * dt
        }

        val driftGrip = if (isOffRoad) 1.45f else when (selectedModel) { 0 -> 2.4f; 1 -> 2.1f; 2 -> 1.8f; else -> 2.6f }
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt
        camYaw += wrapAngle(carYaw - camYaw) * 6.5f * dt

        carX += cos(velYaw) * speed * dt
        carY += sin(velYaw) * speed * dt

        currentLapRecording.add(GhostPose(carX, carY, carYaw))
        ghostFrameIdx++

        // Bot Çarpışmaları
        if (botsEnabled) {
            updateBots(dt)
            for (bot in bots) {
                val dx = carX - bot.x
                val dy = carY - bot.y
                val distSq = dx * dx + dy * dy
                if (distSq < 529f && distSq > 0.01f) {
                    val dist = sqrt(distSq)
                    val pushAng = atan2(dy, dx)
                    val overlap = 23f - dist
                    carX += cos(pushAng) * overlap * 0.65f
                    carY += sin(pushAng) * overlap * 0.65f
                    bot.vx -= cos(pushAng) * 140f
                    bot.vy -= sin(pushAng) * 140f

                    carDamage = (carDamage + 10f).coerceAtMost(100f)
                    if (carDamage > 45f) bumperBroken = true
                    spawnDebris((carX + bot.x) * 0.5f, (carY + bot.y) * 0.5f, 4, carColors[selectedColor])
                    triggerHaptic(45L, 240)
                    lastVibeTime = now
                }
            }
        }

        // Çimdeki Ağaç ve Binalara Çarpma
        for (obs in obstacles) {
            val dx = carX - obs.x
            val dy = carY - obs.y
            val minD = obs.radius + 11f
            if (dx * dx + dy * dy < minD * minD) {
                val ang = atan2(dy, dx)
                carX = obs.x + cos(ang) * minD
                carY = obs.y + sin(ang) * minD
                carDamage += speed * 0.28f
                if (carDamage > 35f) bumperBroken = true
                if (carDamage > 65f) spoilerBroken = true
                if (speed > 195f || carDamage >= 100f) {
                    triggerExplosionAtSpot()
                    return
                } else {
                    speed *= 0.4f
                    spawnDebris(carX, carY, 6, carColors[selectedColor])
                    triggerHaptic(80L, 255)
                }
            }
        }

        var bestDistSq = Float.MAX_VALUE
        var bestIdx = currentWp
        val total = trackPts.size
        for (i in 0 until total) {
            val dx = carX - trackPts[i].x
            val dy = carY - trackPts[i].y
            val dSq = dx * dx + dy * dy
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestIdx = i
            }
        }
        val bestDist = sqrt(bestDistSq)

        // Çime Çıkma ve Yavaşlatma
        isOffRoad = bestDist > roadHalfWidth + 4f
        if (isOffRoad && speed > 45f) {
            if (Random.nextInt(2) == 0) {
                spawnParticle(carX - cos(carYaw) * 12f, carY - sin(carYaw) * 12f, 2f, Random.nextFloat() * 20f - 10f, Random.nextFloat() * 20f - 10f, 35f, 3.0f, Color.rgb(65, 130, 40), 210)
            }
            if (now - lastVibeTime > 95) {
                triggerHaptic(14L, 95)
                lastVibeTime = now
            }
        }

        val outerFence = roadHalfWidth * 4.5f
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
                triggerHaptic(55L, 255)
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
                if (bot.wpFloat > playerProgress) pos++
            }
        }
        racePosition = pos

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
            }

            if (hasPrevSkid && hypot(curLX - prevBackLX, curLY - prevBackLY) < 40f) {
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

            if (!isOffRoad && now - lastVibeTime > 60) {
                val amp = (slip * 255f).toInt().coerceIn(90, 255)
                triggerHaptic(26L, amp)
                lastVibeTime = now
            }
        } else {
            hasPrevSkid = false
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    // ==================== OYUN İÇİ HUD: İÇ BÖLGE ÇIKIŞ VE TAM DURDURMA BUTONU ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // 1. Sol-Üst İç Skor Kutusu (Crown kenarından uzak)
        tempRect.set(cx - R * 0.56f, cy - R * 0.62f, cx - R * 0.06f, cy - R * 0.26f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 14, 20, 26)
        canvas.drawRoundRect(tempRect, R * 0.07f, R * 0.07f, paint)

        paint.color = Color.rgb(190, 200, 210)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.056f
        val timeStr = String.format("%.1fs", currentLapTime)
        val posStr = if (botsEnabled) " • $racePosition/3" else ""
        canvas.drawText("L$lap/$totalLaps • $timeStr$posStr", tempRect.centerX(), cy - R * 0.49f, paint)

        paint.color = if (isOffRoad) Color.rgb(255, 195, 30) else Color.rgb(0, 225, 255)
        paint.textSize = R * 0.125f
        canvas.drawText("${score.toInt()}", tempRect.centerX(), cy - R * 0.32f, paint)

        // 2. Haritanın Yanında GÜVENLİ ÇIKIŞ (ÇIK) Butonu (Merkeze yakın, Crown'a değmez!)
        val exitBtnX = cx + R * 0.08f
        val exitBtnY = cy - R * 0.42f
        paint.color = Color.argb(200, 185, 35, 45)
        canvas.drawCircle(exitBtnX, exitBtnY, R * 0.105f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.058f
        canvas.drawText("ÇIK", exitBtnX, exitBtnY + R * 0.022f, paint)

        // 3. Sağ-Üst İç Mini Harita (0.60R içinde, Crown'dan uzak)
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
        paint.strokeWidth = 2.6f
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
                canvas.drawCircle(mapCX + rp.x * miniScale, mapCY + rp.y * miniScale, R * 0.025f, paint)
            }
        }
        paint.color = Color.rgb(60, 255, 90)
        canvas.drawCircle(mapCX + carX * miniScale, mapCY + carY * miniScale, R * 0.026f, paint)

        // 4. Alt-İç Merkez TAM DURDURMA (DUR / SÜR) Butonu (Crown çerçevesinden uzak)
        val stopBtnX = cx
        val stopBtnY = cy + R * 0.46f
        paint.color = if (isFullStopped) Color.rgb(45, 195, 85) else Color.argb(190, 215, 50, 50)
        canvas.drawCircle(stopBtnX, stopBtnY, R * 0.135f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.072f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (isFullStopped) "SÜR" else "DUR", stopBtnX, stopBtnY + R * 0.026f, paint)

        // 5. Bildirimler
        if (isCrashing) {
            paint.color = Color.rgb(255, 65, 55)
            paint.textSize = R * 0.090f
            val secStr = String.format("%.1fs", crashTimer.coerceAtLeast(0f))
            canvas.drawText("PATLADI! $secStr", cx, cy - R * 0.08f, paint)
        } else if (respawnBannerTimer > 0f) {
            paint.color = Color.rgb(80, 255, 140)
            paint.textSize = R * 0.080f
            canvas.drawText("OLDUĞU YERDE DOĞDU", cx, cy - R * 0.08f, paint)
        } else if (isOffRoad) {
            paint.color = Color.rgb(255, 205, 40)
            paint.textSize = R * 0.068f
            canvas.drawText("ÇİM ALAN (YAVAŞ)", cx, cy - R * 0.12f, paint)
        }

        // 6. Alt Kenar 19 Noktalı Crown Direksiyon Göstergesi
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

    // ==================== 4 KİŞİLİK OTOMATİK TARAMALI CO-OP ODA EKRANI ====================
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

    // Garajda 4 Farklı 3B Spor Araç Modelinin Canlı Dönen Önizlemesi
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
        drawSport3DCar(canvas, cx, cy - R * 0.34f, R * 1.15f, 18f, 0f, 35f, garagePreviewYaw, carColors[selectedColor], selectedModel, isGhost = false, isPlayer = false)
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
