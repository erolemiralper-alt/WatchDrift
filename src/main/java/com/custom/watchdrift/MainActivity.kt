package com.custom.watchdrift

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var gameView: CrownDriftView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = CrownDriftView(this)
        setContentView(gameView)
        gameView.requestFocus()
    }

    // Galaxy Watch 8 Crown / Çerçeve Hareketini Doğrudan Yakalar
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            if (gameView.handleRotaryScroll(ev)) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}

data class Vec2(val x: Float, val y: Float)
data class GhostPose(val x: Float, val y: Float, val yaw: Float)

data class BotCar(
    var wpFloat: Float,
    var laneOffset: Float,
    var targetLane: Float,
    val speedFactor: Float,
    val color: Int,
    var x: Float = 0f,
    var y: Float = 0f,
    var yaw: Float = 0f
)

data class TrackData(
    val name: String,
    val theme: Int,
    val goldScore: Int,
    val controlPoints: List<Vec2>
)

class CrownDriftView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("crowndrift_v4", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 3B Perspektif Kamera ve Matris
    private val camera3D = Camera()
    private val matrix3D = Matrix()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // Ekran Durumları: 0 = MENU, 1 = GARAGE, 2 = SETTINGS, 3 = GAME, 4 = WIN
    private var state = 0

    // Garaj Renkleri (Varsayılan 0 = Örnek Fotodaki Mavi)
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
    private val carModels = arrayOf("STREET", "SPORT", "JDM", "HYPER")
    private var selectedColor = prefs.getInt("color", 0)
    private var selectedModel = prefs.getInt("model", 1)

    // Ayarlar
    private var sensLevel = prefs.getInt("sens", 1) // 0: LOW, 1: MEDIUM, 2: HIGH
    private var gfxHigh = prefs.getBoolean("gfx", true)
    private var ghostEnabled = prefs.getBoolean("ghost", true)
    private var botsEnabled = prefs.getBoolean("bots", true)
    private var hapticEnabled = true
    private var showFps = prefs.getBoolean("fps", false)

    // 5 Pist Tasarımı
    private val tracks = listOf(
        TrackData("Classic", 0, 7150, listOf(
            Vec2(0f, -650f), Vec2(520f, -520f), Vec2(720f, 0f), Vec2(480f, 560f),
            Vec2(0f, 680f), Vec2(-520f, 480f), Vec2(-680f, -150f), Vec2(-400f, -560f)
        )),
        TrackData("Cyber", 1, 8500, listOf(
            Vec2(-400f, -700f), Vec2(550f, -700f), Vec2(800f, -350f), Vec2(650f, 50f),
            Vec2(-150f, 100f), Vec2(-280f, 400f), Vec2(400f, 450f), Vec2(520f, 750f),
            Vec2(-520f, 750f), Vec2(-760f, 250f), Vec2(-720f, -320f)
        )),
        TrackData("Tokyo", 2, 9200, listOf(
            Vec2(0f, -750f), Vec2(640f, -640f), Vec2(760f, -120f), Vec2(240f, 120f),
            Vec2(720f, 520f), Vec2(280f, 800f), Vec2(-560f, 640f), Vec2(-240f, 120f),
            Vec2(-720f, -240f), Vec2(-560f, -720f)
        )),
        TrackData("Glacier", 3, 8000, listOf(
            Vec2(0f, -720f), Vec2(600f, -560f), Vec2(800f, 120f), Vec2(400f, 720f),
            Vec2(-400f, 720f), Vec2(-800f, 120f), Vec2(-600f, -560f)
        )),
        TrackData("Canyon", 4, 10000, listOf(
            Vec2(-320f, -760f), Vec2(520f, -720f), Vec2(800f, -240f), Vec2(320f, 160f),
            Vec2(760f, 600f), Vec2(120f, 840f), Vec2(-600f, 720f), Vec2(-800f, 160f),
            Vec2(-320f, -160f), Vec2(-760f, -520f)
        ))
    )
    private var currentTrackIdx = 0

    // Pist Spline Noktaları ve Daraltılmış Pist Genişliği (İki Bordür Aynı Anda Görünür)
    private val trackPts = mutableListOf<Vec2>()
    private val trackNormals = mutableListOf<Vec2>()
    private val trackTangents = mutableListOf<Float>()
    private val roadHalfWidth = 44f

    // Oyuncu Fizik Değişkenleri
    private var carX = 0f
    private var carY = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var camYaw = 0f
    private var steerInput = 0f
    private var crownSteer = 0f
    private var touchSteer = 0f
    private var isTouchingScreen = false

    // Yarış Değişkenleri
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

    // Hayalet Araç (Ghost)
    private val currentLapRecording = mutableListOf<GhostPose>()
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0

    // Yapay Zeka Botları
    private val bots = mutableListOf<BotCar>()

    // Lastik İzleri
    private data class TireMark(val lx: Float, val ly: Float, val rx: Float, val ry: Float)
    private val tireMarks = mutableListOf<TireMark>()

    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60
    private var lastVibeTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        buildTrackSpline(0)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("color", selectedColor)
            .putInt("model", selectedModel)
            .putInt("sens", sensLevel)
            .putBoolean("gfx", gfxHigh)
            .putBoolean("ghost", ghostEnabled)
            .putBoolean("bots", botsEnabled)
            .putBoolean("fps", showFps)
            .apply()
    }

    // Hatasız ve Güçlü Saat Titreşimi
    private fun triggerHaptic(durationMs: Long, amplitude: Int) {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val safeAmp = amplitude.coerceIn(1, 255)
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, safeAmp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibeClick() = triggerHaptic(28L, 200)

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
                selectedColor = (selectedColor + if (delta > 0) 1 else 7) % 8
                saveSettings()
                vibeClick()
            }
            3 -> {
                val sensMult = when (sensLevel) {
                    0 -> 0.30f // LOW
                    1 -> 0.50f // MEDIUM
                    else -> 0.75f // HIGH
                }
                crownSteer = (crownSteer + delta.coerceIn(-1.5f, 1.5f) * sensMult).coerceIn(-1f, 1f)
                triggerHaptic(18L, 130)
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
        val cps = tracks[idx].controlPoints
        val n = cps.size
        val stepsPerSeg = 22
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
            trackNormals.add(Vec2(-dy / len, dx / len))
            trackTangents.add(atan2(dy, dx))
        }
    }

    private fun startRace() {
        buildTrackSpline(currentTrackIdx)
        val p0 = trackPts[0]
        carX = p0.x
        carY = p0.y
        carYaw = trackTangents[0]
        velYaw = carYaw
        camYaw = carYaw
        steerInput = 0f
        crownSteer = 0f
        touchSteer = 0f
        isTouchingScreen = false

        lap = 1
        currentWp = 0
        maxWpReached = 0
        playerProgress = 0f
        score = 0f
        driftCombo = 1
        driftTimer = 0f
        tireMarks.clear()
        currentLapRecording.clear()
        ghostFrameIdx = 0

        bots.clear()
        if (botsEnabled) {
            bots.add(BotCar(wpFloat = 3f, laneOffset = -15f, targetLane = -15f, speedFactor = 0.90f, color = Color.rgb(255, 195, 20)))
            bots.add(BotCar(wpFloat = 6f, laneOffset = 15f, targetLane = 15f, speedFactor = 0.96f, color = Color.rgb(235, 45, 90)))
            updateBots(0.01f)
        }

        lapStartTime = SystemClock.uptimeMillis()
        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        isNewRecord = false
        state = 3
        requestFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f
        val x = event.x
        val y = event.y

        when (state) {
            0 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.35f)..(cy + R * 0.2f)) {
                    if (x < cx - R * 0.42f) {
                        currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size
                        buildTrackSpline(currentTrackIdx); vibeClick()
                    } else if (x > cx + R * 0.42f) {
                        currentTrackIdx = (currentTrackIdx + 1) % tracks.size
                        buildTrackSpline(currentTrackIdx); vibeClick()
                    }
                }
                if (hypot(x - (cx - R * 0.56f), y - (cy + R * 0.34f)) < R * 0.22f) {
                    state = 1; vibeClick()
                } else if (hypot(x - (cx + R * 0.56f), y - (cy + R * 0.34f)) < R * 0.22f) {
                    state = 2; vibeClick()
                } else if (y > cy + R * 0.48f && abs(x - cx) < R * 0.50f) {
                    vibeClick(); startRace()
                }
            }

            1 -> if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in (cy - R * 0.12f)..(cy + R * 0.14f)) {
                    selectedModel = (selectedModel + 1) % carModels.size
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
                else if (y in (cy - R * 0.37f)..(cy - R * 0.11f)) { gfxHigh = !gfxHigh; saveSettings(); vibeClick() }
                else if (y in (cy - R * 0.10f)..(cy + R * 0.16f)) { ghostEnabled = !ghostEnabled; saveSettings(); vibeClick() }
                else if (y in (cy + R * 0.17f)..(cy + R * 0.44f)) { botsEnabled = !botsEnabled; saveSettings(); vibeClick() }
                else if (y > cy + R * 0.46f) {
                    if (x < cx) { showFps = !showFps; saveSettings() } else state = 0
                    vibeClick()
                }
            }

            3 -> {
                if (event.action == MotionEvent.ACTION_DOWN && y < cy - R * 0.45f && abs(x - cx) < R * 0.42f) {
                    state = 0; vibeClick(); return true
                }
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    isTouchingScreen = true
                    val sensMult = when (sensLevel) { 0 -> 0.80f; 1 -> 1.10f; else -> 1.45f }
                    touchSteer = (((x - cx) / (R * 0.60f)) * sensMult).coerceIn(-1f, 1f)
                } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    isTouchingScreen = false
                    touchSteer = 0f
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

        val clipPath = Path().apply { addCircle(cx, cy, R, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)

        when (state) {
            0 -> drawMainMenu(canvas, cx, cy, R)
            1 -> drawGarage(canvas, cx, cy, R)
            2 -> drawSettings(canvas, cx, cy, R)
            3 -> {
                updateGamePhysics(dt, now)
                draw3DGameWorld(canvas, cx, cy, R)
                drawGameHud(canvas, cx, cy, R)
            }
            4 -> {
                draw3DGameWorld(canvas, cx, cy, R)
                drawWinScreen(canvas, cx, cy, R)
            }
        }

        if (showFps) {
            paint.color = Color.GREEN
            paint.textSize = R * 0.075f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.86f, paint)
        }

        canvas.restore()
        postInvalidateOnAnimation()
    }

    // ==================== 1. ANA MENÜ ====================
    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))

        val arcPath = Path()
        val arcRect = RectF(cx - R * 0.76f, cy - R * 0.76f, cx + R * 0.76f, cy + R * 0.76f)
        arcPath.addArc(arcRect, 222f, 96f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(190, 200, 210)
        paint.textSize = R * 0.095f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.letterSpacing = 0.18f
        canvas.drawTextOnPath("CROWNDRIFT", arcPath, 0f, 0f, paint)
        paint.letterSpacing = 0f

        val mapScale = R * 0.00033f
        val mapPath = Path()
        val yellowPath = Path()
        val total = trackPts.size
        for (i in 0 until total) {
            val px = cx + trackPts[i].x * mapScale
            val py = cy - R * 0.14f + trackPts[i].y * mapScale
            if (i == 0) mapPath.moveTo(px, py) else mapPath.lineTo(px, py)
            if (i in (total / 6)..(total / 3)) {
                if (i == total / 6) yellowPath.moveTo(px, py) else yellowPath.lineTo(px, py)
            }
        }
        mapPath.close()

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = R * 0.045f
        paint.color = if (tracks[currentTrackIdx].theme == 1) Color.rgb(0, 225, 255) else Color.WHITE
        canvas.drawPath(mapPath, paint)
        paint.color = Color.rgb(255, 195, 20)
        canvas.drawPath(yellowPath, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(150, 160, 175)
        paint.textSize = R * 0.14f
        canvas.drawText("<", cx - R * 0.68f, cy - R * 0.08f, paint)
        canvas.drawText(">", cx + R * 0.68f, cy - R * 0.08f, paint)

        paint.color = Color.WHITE
        paint.textSize = R * 0.15f
        canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.25f, paint)

        for (i in tracks.indices) {
            val dotX = cx + (i - (tracks.size - 1) / 2f) * (R * 0.08f)
            val dotY = cy + R * 0.32f
            if (i == currentTrackIdx) {
                paint.color = Color.rgb(0, 220, 255)
                canvas.drawRoundRect(dotX - R * 0.035f, dotY - R * 0.015f, dotX + R * 0.035f, dotY + R * 0.015f, 8f, 8f, paint)
            } else {
                paint.color = Color.rgb(50, 60, 70)
                canvas.drawCircle(dotX, dotY, R * 0.014f, paint)
            }
        }

        val bestScore = prefs.getInt("best_score_$currentTrackIdx", 0)
        paint.color = Color.rgb(25, 32, 40)
        val badgeRect = RectF(cx - R * 0.24f, cy + R * 0.37f, cx + R * 0.24f, cy + R * 0.49f)
        canvas.drawRoundRect(badgeRect, R * 0.06f, R * 0.06f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.085f
        canvas.drawText("● $bestScore", cx, cy + R * 0.46f, paint)

        val gx = cx - R * 0.56f
        val gy = cy + R * 0.34f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(gx, gy, R * 0.15f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(gx - R * 0.055f, gy - R * 0.04f, gx + R * 0.055f, gy + R * 0.04f, 6f, 6f, paint)

        val sx = cx + R * 0.56f
        val sy = cy + R * 0.34f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(sx, sy, R * 0.15f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.13f
        canvas.drawText("⚙", sx, sy + R * 0.045f, paint)

        paint.color = Color.rgb(0, 225, 255)
        val startRect = RectF(cx - R * 0.46f, cy + R * 0.53f, cx + R * 0.46f, cy + R * 0.79f)
        canvas.drawRoundRect(startRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.13f
        canvas.drawText("START", cx, cy + R * 0.70f, paint)
    }

    // ==================== 2. GARAJ ====================
    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.15f
        canvas.drawText("Garage", cx, cy - R * 0.56f, paint)

        canvas.save()
        canvas.translate(cx, cy - R * 0.28f)
        val bodyColor = carColors[selectedColor]
        paint.color = Color.rgb(20, 20, 20)
        canvas.drawRoundRect(-R * 0.24f, R * 0.02f, -R * 0.12f, R * 0.13f, 8f, 8f, paint)
        canvas.drawRoundRect(R * 0.06f, R * 0.05f, R * 0.18f, R * 0.16f, 8f, 8f, paint)
        paint.color = bodyColor
        canvas.drawRoundRect(-R * 0.30f, -R * 0.06f, R * 0.30f, R * 0.09f, 12f, 12f, paint)
        paint.color = Color.rgb(140, 225, 255)
        canvas.drawRoundRect(-R * 0.14f, -R * 0.16f, R * 0.14f, -R * 0.04f, 8f, 8f, paint)
        paint.color = bodyColor
        canvas.drawRoundRect(-R * 0.12f, -R * 0.18f, R * 0.08f, -R * 0.12f, 6f, 6f, paint)
        if (selectedModel > 0) {
            canvas.drawRect(-R * 0.28f, -R * 0.16f, -R * 0.20f, -R * 0.06f, paint)
            canvas.drawRoundRect(-R * 0.32f, -R * 0.19f, -R * 0.16f, -R * 0.14f, 4f, 4f, paint)
        }
        paint.color = Color.WHITE
        canvas.drawRect(R * 0.22f, -R * 0.02f, R * 0.29f, R * 0.04f, paint)
        canvas.restore()

        val leftRect = RectF(cx - R * 0.56f, cy - R * 0.07f, cx - R * 0.04f, cy + R * 0.11f)
        val rightRect = RectF(cx + R * 0.04f, cy - R * 0.07f, cx + R * 0.56f, cy + R * 0.11f)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawRoundRect(leftRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.BLACK
        paint.textSize = R * 0.085f
        canvas.drawText(carModels[selectedModel], leftRect.centerX(), leftRect.centerY() + R * 0.03f, paint)

        paint.color = Color.rgb(24, 30, 38)
        canvas.drawRoundRect(rightRect, R * 0.09f, R * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawText(carModels[(selectedModel + 1) % carModels.size], rightRect.centerX(), rightRect.centerY() + R * 0.03f, paint)

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

    // ==================== 3. AYARLAR ====================
    private fun drawSettings(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        val sensNames = arrayOf("LOW", "MEDIUM", "HIGH")

        drawSettingRow(canvas, cx, cy - R * 0.52f, R, 0.64f, "SENS", sensNames[sensLevel], false)
        drawSettingRow(canvas, cx, cy - R * 0.24f, R, 0.76f, "GFX", if (gfxHigh) "HIGH" else "LOW", false)
        drawSettingRow(canvas, cx, cy + R * 0.04f, R, 0.76f, "GHOST", "", ghostEnabled)
        drawSettingRow(canvas, cx, cy + R * 0.32f, R, 0.68f, "AI BOTS", "", botsEnabled)

        val fpsRect = RectF(cx - R * 0.48f, cy + R * 0.50f, cx - R * 0.03f, cy + R * 0.70f)
        val backRect = RectF(cx + R * 0.03f, cy + R * 0.50f, cx + R * 0.48f, cy + R * 0.70f)
        paint.color = Color.rgb(22, 28, 36)
        canvas.drawRoundRect(fpsRect, R * 0.1f, R * 0.1f, paint)
        canvas.drawRoundRect(backRect, R * 0.1f, R * 0.1f, paint)

        paint.color = if (showFps) Color.rgb(0, 225, 255) else Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.075f
        canvas.drawText("SHOW FPS", fpsRect.centerX(), fpsRect.centerY() + R * 0.025f, paint)
        paint.color = Color.WHITE
        canvas.drawText("BACK", backRect.centerX(), backRect.centerY() + R * 0.025f, paint)
    }

    private fun drawSettingRow(canvas: Canvas, cx: Float, y: Float, R: Float, wRatio: Float, label: String, valueText: String, toggleVal: Boolean) {
        val rect = RectF(cx - R * wRatio, y - R * 0.11f, cx + R * wRatio, y + R * 0.11f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 26, 34)
        canvas.drawRoundRect(rect, R * 0.11f, R * 0.11f, paint)

        paint.color = Color.WHITE
        paint.textSize = R * 0.082f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, rect.left + R * 0.10f, y + R * 0.03f, paint)

        if (valueText.isNotEmpty()) {
            paint.color = Color.rgb(0, 200, 230)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(valueText, rect.right - R * 0.10f, y + R * 0.03f, paint)
        } else {
            val swRight = rect.right - R * 0.08f
            val swLeft = swRight - R * 0.20f
            paint.color = if (toggleVal) Color.rgb(0, 180, 215) else Color.rgb(45, 55, 65)
            canvas.drawRoundRect(swLeft, y - R * 0.055f, swRight, y + R * 0.055f, R * 0.055f, R * 0.055f, paint)
            paint.color = Color.WHITE
            val knobX = if (toggleVal) swRight - R * 0.055f else swLeft + R * 0.055f
            canvas.drawCircle(knobX, y, R * 0.042f, paint)
        }
    }

    // ==================== 4. FİZİK, BOTLAR, GHOST VE BORDÜR YÖNLENDİRMESİ ====================
    private fun updateBots(dt: Float) {
        val total = trackPts.size
        for (bot in bots) {
            bot.wpFloat += bot.speedFactor * 18.0f * dt
            val idx0 = (bot.wpFloat.toInt() % total + total) % total
            val idx1 = (idx0 + 1) % total
            val frac = bot.wpFloat - floor(bot.wpFloat)

            if (abs(bot.laneOffset - bot.targetLane) < 2f) {
                if ((SystemClock.uptimeMillis() + bot.color) % 120L == 0L) {
                    bot.targetLane = if (bot.targetLane < 0f) 15f else -15f
                }
            } else {
                bot.laneOffset += sign(bot.targetLane - bot.laneOffset) * 14f * dt
            }

            val p0 = trackPts[idx0]
            val p1 = trackPts[idx1]
            val n0 = trackNormals[idx0]
            bot.x = p0.x + (p1.x - p0.x) * frac + n0.x * bot.laneOffset
            bot.y = p0.y + (p1.y - p0.y) * frac + n0.y * bot.laneOffset

            val roadYaw = trackTangents[idx0]
            val nextYaw = trackTangents[(idx0 + 4) % total]
            bot.yaw = roadYaw + wrapAngle(nextYaw - roadYaw) * 1.3f
        }
    }

    private fun updateGamePhysics(dt: Float, now: Long) {
        currentLapTime = (now - lapStartTime) / 1000f

        if (isTouchingScreen) {
            steerInput = touchSteer
            crownSteer = touchSteer
        } else {
            crownSteer *= 0.96f
            steerInput = crownSteer
        }

        val topSpeed = when (selectedModel) { 0 -> 285f; 1 -> 310f; 2 -> 295f; else -> 330f }
        val driftGrip = when (selectedModel) { 0 -> 2.4f; 1 -> 2.1f; 2 -> 1.7f; else -> 2.6f }
        val turnRate = when (sensLevel) { 0 -> 2.5f; 1 -> 3.3f; else -> 4.2f }

        carYaw += steerInput * turnRate * dt
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt

        // Kamera Aracın İlerleme Açısını Takip Eder
        camYaw += wrapAngle(velYaw - camYaw) * 7.5f * dt

        carX += cos(velYaw) * topSpeed * dt
        carY += sin(velYaw) * topSpeed * dt

        currentLapRecording.add(GhostPose(carX, carY, carYaw))
        ghostFrameIdx++

        if (botsEnabled) {
            updateBots(dt)
            for (bot in bots) {
                val distToBot = hypot(carX - bot.x, carY - bot.y)
                if (distToBot < 22f && distToBot > 0.1f) {
                    val pushAng = atan2(carY - bot.y, carX - bot.x)
                    carX += cos(pushAng) * (22f - distToBot) * 0.6f
                    carY += sin(pushAng) * (22f - distToBot) * 0.6f
                    if (now - lastVibeTime > 70) {
                        triggerHaptic(35L, 240)
                        lastVibeTime = now
                    }
                }
            }
        }

        var bestDist = Float.MAX_VALUE
        var bestIdx = currentWp
        val total = trackPts.size
        for (i in 0 until total) {
            val d = hypot(carX - trackPts[i].x, carY - trackPts[i].y)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        // Bordür Sınırlandırması + Arabanın Çime Dik Saplanmasını Önleyen Yönlendirme
        val maxRoadOffset = roadHalfWidth * 0.78f
        if (bestDist > maxRoadOffset) {
            val tp = trackPts[bestIdx]
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * maxRoadOffset
            carY = tp.y + sin(angToCar) * maxRoadOffset

            val tangentYaw = trackTangents[bestIdx]
            velYaw += wrapAngle(tangentYaw - velYaw) * 8.0f * dt
            carYaw += wrapAngle(tangentYaw - carYaw) * 4.5f * dt

            if (now - lastVibeTime > 55) {
                triggerHaptic(32L, 255)
                lastVibeTime = now
            }
        }

        if (bestIdx > maxWpReached && bestIdx - maxWpReached < total / 3) {
            maxWpReached = bestIdx
        }
        if (bestIdx < total / 8 && maxWpReached > (total * 3) / 4) {
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

        // Drift Puanlama, Çift Tekerlek İzi ve Drift Titreşimi
        val slip = abs(wrapAngle(carYaw - velYaw))
        if (slip > 0.10f) {
            driftTimer += dt
            driftCombo = when {
                driftTimer > 3.2f -> 4
                driftTimer > 1.6f -> 3
                driftTimer > 0.5f -> 2
                else -> 1
            }
            score += slip * 520f * driftCombo * dt

            val backX = carX - cos(carYaw) * 11f
            val backY = carY - sin(carYaw) * 11f
            val perpX = -sin(carYaw) * 6.5f
            val perpY = cos(carYaw) * 6.5f
            tireMarks.add(TireMark(backX - perpX, backY - perpY, backX + perpX, backY + perpY))
            if (tireMarks.size > 180) tireMarks.removeAt(0)

            if (now - lastVibeTime > 60) {
                val amp = (slip * 260f).toInt().coerceIn(95, 255)
                triggerHaptic(32L, amp)
                lastVibeTime = now
            }
        } else {
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    // ==================== 5. 3B PERSPEKTİF DÜNYA VE VOXEL ARAÇ ÇİZİMİ ====================
    private fun draw3DGameWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val theme = tracks[currentTrackIdx].theme
        val bgColor = when (theme) {
            0 -> Color.rgb(34, 78, 24)
            1 -> Color.rgb(12, 10, 28)
            2 -> Color.rgb(18, 22, 32)
            3 -> Color.rgb(175, 210, 230)
            else -> Color.rgb(95, 55, 30)
        }
        canvas.drawColor(bgColor)

        canvas.save()

        // 3B Eğimli Kamera Matrisi (Örnek Fotodaki Perspektif Açısı)
        val anchorX = cx
        val anchorY = cy + R * 0.35f
        camera3D.save()
        camera3D.setLocation(0f, 0f, -12f)
        camera3D.rotateX(44f)
        camera3D.getMatrix(matrix3D)
        camera3D.restore()

        matrix3D.preTranslate(-anchorX, -anchorY)
        matrix3D.postTranslate(anchorX, anchorY)
        canvas.concat(matrix3D)

        val camScale = R / 155f
        canvas.translate(anchorX, anchorY)
        canvas.scale(camScale, camScale)
        canvas.rotate(-Math.toDegrees(camYaw.toDouble()).toFloat() - 90f)
        canvas.translate(-carX, -carY)

        // 1. Zemin Izgarası (Grid)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = when (theme) {
            0 -> Color.rgb(45, 95, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 34f
        val minGX = ((carX - 420f) / gridStep).toInt() * gridStep
        val maxGX = carX + 420f
        val minGY = ((carY - 420f) / gridStep).toInt() * gridStep
        val maxGY = carY + 420f
        var gx = minGX
        while (gx <= maxGX) {
            canvas.drawLine(gx, minGY, gx, maxGY, paint)
            gx += gridStep
        }
        var gy = minGY
        while (gy <= maxGY) {
            canvas.drawLine(minGX, gy, maxGX, gy, paint)
            gy += gridStep
        }

        if (theme == 1 && gfxHigh) {
            paint.style = Paint.Style.FILL
            gx = minGX
            while (gx <= maxGX) {
                gy = minGY
                while (gy <= maxGY) {
                    val hash = abs((gx.toInt() * 73856093) xor (gy.toInt() * 19349663)) % 100
                    if (hash < 13) {
                        paint.color = Color.argb(145, 145, 35, 235)
                        canvas.drawRect(gx + 4f, gy + 4f, gx + gridStep - 4f, gy + gridStep - 4f, paint)
                    }
                    gy += gridStep
                }
                gx += gridStep
            }
        }

        // 2. Kırmızı - Beyaz - Sarı Viraj Bordürleri (Kerbs)
        val total = trackPts.size
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = roadHalfWidth * 2f + 13f
        for (i in 0 until total) {
            val p1 = trackPts[i]
            val p2 = trackPts[(i + 1) % total]
            paint.color = when {
                i % 2 == 0 -> Color.rgb(230, 35, 35)
                (i / 2) % 6 == 0 -> Color.rgb(250, 210, 20)
                else -> Color.WHITE
            }
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
        }

        // 3. Asfalt Yol Gövdesi
        val roadPath = Path()
        for (i in 0 until total) {
            val p = trackPts[i]
            if (i == 0) roadPath.moveTo(p.x, p.y) else roadPath.lineTo(p.x, p.y)
        }
        roadPath.close()
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = roadHalfWidth * 2f
        paint.color = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(72, 76, 80)
        canvas.drawPath(roadPath, paint)

        // 4. Başlangıç Çizgisi
        val sp = trackPts[0]
        val sn = trackNormals[0]
        paint.strokeWidth = 10f
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.WHITE
        canvas.drawLine(sp.x - sn.x * roadHalfWidth, sp.y - sn.y * roadHalfWidth, sp.x + sn.x * roadHalfWidth, sp.y + sn.y * roadHalfWidth, paint)

        // 5. Çift Tekerlek Lastik İzleri
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.8f
        paint.color = Color.argb(165, 18, 18, 18)
        for (i in 1 until tireMarks.size) {
            val prev = tireMarks[i - 1]
            val curr = tireMarks[i]
            if (hypot(curr.lx - prev.lx, curr.ly - prev.ly) < 35f) {
                canvas.drawLine(prev.lx, prev.ly, curr.lx, curr.ly, paint)
                canvas.drawLine(prev.rx, prev.ry, curr.rx, curr.ry, paint)
            }
        }

        // 3B Yükseklik Vektörü
        val upX = cos(camYaw)
        val upY = sin(camYaw)

        // 6. Yol Kenarı 3B Bina ve Ağaçlar
        if (theme == 0 || theme == 2) {
            val bldX = sp.x - sn.x * (roadHalfWidth + 55f)
            val bldY = sp.y - sn.y * (roadHalfWidth + 55f)
            paint.style = Paint.Style.FILL
            for (layer in 0..7) {
                val ox = upX * layer * 3.5f
                val oy = upY * layer * 3.5f
                paint.color = if (layer == 7) Color.rgb(42, 45, 62) else Color.rgb(26, 28, 40)
                canvas.drawRect(bldX - 30f + ox, bldY - 30f + oy, bldX + 30f + ox, bldY + 30f + oy, paint)
                if (layer == 3 || layer == 5) {
                    paint.color = Color.rgb(245, 220, 45)
                    canvas.drawRect(bldX - 20f + ox, bldY - 20f + oy, bldX - 8f + ox, bldY - 8f + oy, paint)
                    canvas.drawRect(bldX + 8f + ox, bldY - 20f + oy, bldX + 20f + ox, bldY - 8f + oy, paint)
                }
            }

            val treeX = sp.x + sn.x * (roadHalfWidth + 50f)
            val treeY = sp.y + sn.y * (roadHalfWidth + 50f)
            for (layer in 0..5) {
                val ox = upX * layer * 3f
                val oy = upY * layer * 3f
                val rad = 22f - layer * 3f
                paint.color = Color.rgb(40 + layer * 6, 145 + layer * 8, 50 + layer * 5)
                canvas.drawCircle(treeX + ox, treeY + oy, rad, paint)
            }
        }

        // 7. Hayalet Araç (Ghost)
        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val gPose = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            draw3DVoxelCar(canvas, gPose.x, gPose.y, gPose.yaw, upX, upY, Color.rgb(0, 225, 255), selectedModel, isGhost = true, drawHeadlights = false)
        }

        // 8. Bot Araçları
        if (botsEnabled) {
            for (bot in bots) {
                draw3DVoxelCar(canvas, bot.x, bot.y, bot.yaw, upX, upY, bot.color, 1, isGhost = false, drawHeadlights = (theme == 1 || theme == 2))
            }
        }

        // 9. Oyuncu 3B Voxel Aracı
        draw3DVoxelCar(canvas, carX, carY, carYaw, upX, upY, carColors[selectedColor], selectedModel, isGhost = false, drawHeadlights = (theme == 1 || theme == 2))

        canvas.restore()
    }

    private fun draw3DVoxelCar(
        canvas: Canvas, x: Float, y: Float, yaw: Float,
        upX: Float, upY: Float, baseColor: Int, modelIdx: Int,
        isGhost: Boolean, drawHeadlights: Boolean
    ) {
        val alpha = if (isGhost) 115 else 255
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        val darkBody = Color.argb(alpha, (r * 0.70f).toInt(), (g * 0.70f).toInt(), (b * 0.70f).toInt())
        val mainBody = Color.argb(alpha, r, g, b)
        val roofBody = Color.argb(alpha, (r * 1.12f).toInt().coerceAtMost(255), (g * 1.12f).toInt().coerceAtMost(255), (b * 1.12f).toInt().coerceAtMost(255))

        if (drawHeadlights && !isGhost) {
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(yaw.toDouble()).toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 220, 245, 255)
            val lightPath = Path().apply {
                moveTo(12f, -6f); lineTo(95f, -38f); lineTo(95f, 38f); lineTo(12f, 6f); close()
            }
            canvas.drawPath(lightPath, paint)
            canvas.restore()
        }

        if (!isGhost) {
            canvas.save()
            canvas.translate(x - upX * 1.5f, y - upY * 1.5f)
            canvas.rotate(Math.toDegrees(yaw.toDouble()).toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRoundRect(-14f, -9.5f, 14f, 9.5f, 4f, 4f, paint)
            canvas.restore()
        }

        for (layer in 1..3) {
            canvas.save()
            canvas.translate(x + upX * (layer * 1.3f), y + upY * (layer * 1.3f))
            canvas.rotate(Math.toDegrees(yaw.toDouble()).toFloat())
            paint.style = Paint.Style.FILL

            if (layer == 1 && !isGhost) {
                paint.color = Color.rgb(18, 18, 18)
                canvas.drawRect(-10.5f, -10f, -4f, -7f, paint)
                canvas.drawRect(4.5f, -10f, 11f, -7f, paint)
                canvas.drawRect(-10.5f, 7f, -4f, 10f, paint)
                canvas.drawRect(4.5f, 7f, 11f, 10f, paint)
            }

            paint.color = if (layer < 3) darkBody else mainBody
            canvas.drawRoundRect(-13f, -8f, 13f, 8f, 3.5f, 3.5f, paint)

            if (layer == 2 && !isGhost) {
                paint.color = Color.WHITE
                canvas.drawRect(11f, -6.5f, 13f, -3f, paint)
                canvas.drawRect(11f, 3f, 13f, 6.5f, paint)
                paint.color = Color.rgb(255, 40, 40)
                canvas.drawRect(-13f, -6.5f, -11f, -3f, paint)
                canvas.drawRect(-13f, 3f, -11f, 6.5f, paint)
            }
            canvas.restore()
        }

        for (layer in 4..5) {
            canvas.save()
            canvas.translate(x + upX * (layer * 1.3f), y + upY * (layer * 1.3f))
            canvas.rotate(Math.toDegrees(yaw.toDouble()).toFloat())
            paint.style = Paint.Style.FILL

            paint.color = Color.WHITE
            canvas.drawRoundRect(-7f, -6.2f, 6f, 6.2f, 2.5f, 2.5f, paint)

            if (layer == 5) {
                paint.color = roofBody
                canvas.drawRoundRect(-5f, -4.8f, 3.5f, 4.8f, 2f, 2f, paint)
                if (modelIdx > 0) {
                    canvas.drawRect(-14f, -7.5f, -10.5f, 7.5f, paint)
                }
            }
            canvas.restore()
        }
    }

    // ==================== 6. OYUN İÇİ HUD ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val hudRect = RectF(cx - R * 0.48f, cy - R * 0.82f, cx + R * 0.48f, cy - R * 0.40f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 16, 22, 28)
        canvas.drawRoundRect(hudRect, R * 0.09f, R * 0.09f, paint)

        paint.color = Color.rgb(190, 200, 210)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.076f
        val timeStr = String.format("%.1fs", currentLapTime)
        val posStr = if (botsEnabled) " • POS $racePosition/3" else ""
        canvas.drawText("LAP $lap/$totalLaps • $timeStr$posStr", cx, cy - R * 0.66f, paint)

        paint.color = Color.rgb(0, 225, 255)
        paint.textSize = R * 0.165f
        val scoreOffsetX = if (driftCombo > 1) -R * 0.09f else 0f
        canvas.drawText("${score.toInt()}", cx + scoreOffsetX, cy - R * 0.46f, paint)

        if (driftCombo > 1) {
            val multRect = RectF(cx + R * 0.14f, cy - R * 0.59f, cx + R * 0.34f, cy - R * 0.45f)
            paint.color = Color.rgb(235, 45, 115)
            canvas.drawRoundRect(multRect, R * 0.06f, R * 0.06f, paint)
            paint.color = Color.WHITE
            paint.textSize = R * 0.085f
            canvas.drawText("x$driftCombo", multRect.centerX(), multRect.centerY() + R * 0.03f, paint)
        }

        // Alt Kenar 19 Noktalı Direksiyon Yay Göstergesi
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

    // ==================== 7. BİTİŞ EKRANI ====================
    private fun drawWinScreen(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.argb(215, 10, 16, 20))

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(80, 235, 155)
        paint.textSize = R * 0.16f
        val winTitle = if (botsEnabled && racePosition > 1) "FINISHED #$racePosition" else "YOU WIN!"
        canvas.drawText(winTitle, cx, cy - R * 0.52f, paint)

        if (isNewRecord) {
            val recRect = RectF(cx - R * 0.36f, cy - R * 0.45f, cx + R * 0.36f, cy - R * 0.32f)
            paint.color = Color.rgb(245, 190, 25)
            canvas.drawRoundRect(recRect, R * 0.065f, R * 0.065f, paint)
            paint.color = Color.rgb(20, 20, 20)
            paint.textSize = R * 0.075f
            canvas.drawText("NEW RECORD!", cx, recRect.centerY() + R * 0.026f, paint)
        }

        val finalScore = score.toInt()
        paint.color = Color.WHITE
        paint.textSize = R * 0.25f
        canvas.drawText("$finalScore", cx, cy - R * 0.08f, paint)

        paint.color = Color.rgb(150, 165, 175)
        paint.textSize = R * 0.075f
        canvas.drawText("SCORE", cx, cy + R * 0.01f, paint)

        val goldReq = tracks[currentTrackIdx].goldScore
        val medals = intArrayOf(Color.rgb(215, 135, 65), Color.rgb(210, 215, 220), Color.rgb(250, 200, 30))
        val reqs = intArrayOf((goldReq * 0.5f).toInt(), (goldReq * 0.75f).toInt(), goldReq)
        for (i in 0..2) {
            val mx = cx + (i - 1) * (R * 0.13f)
            val my = cy + R * 0.09f
            if (finalScore >= reqs[i]) {
                paint.style = Paint.Style.FILL
                paint.color = medals[i]
                canvas.drawCircle(mx, my, R * 0.038f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = medals[i]
                canvas.drawCircle(mx, my, R * 0.035f, paint)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(175, 185, 195)
        paint.textSize = R * 0.07f
        val bestStr = String.format("%.2fs", bestLapTime)
        canvas.drawText("GOLD $goldReq • BEST LAP $bestStr", cx, cy + R * 0.20f, paint)

        val resRect = RectF(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawRoundRect(resRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.11f
        canvas.drawText("RESTART", cx, resRect.centerY() + R * 0.04f, paint)

        paint.color = Color.rgb(160, 175, 190)
        paint.textSize = R * 0.085f
        canvas.drawText("MENU", cx, cy + R * 0.68f, paint)
    }
}
