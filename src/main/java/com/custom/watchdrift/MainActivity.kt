package com.custom.watchdrift

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import kotlin.math.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = CrownDriftView(this)
        setContentView(view)
        view.requestFocus()
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
    private val prefs = context.getSharedPreferences("crowndrift_3d", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val polyPath = Path()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // 0 = MENU, 1 = GARAGE, 2 = SETTINGS, 3 = GAME, 4 = WIN
    private var state = 0

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
    private var showFps = prefs.getBoolean("fps", false)

    // 5 Pist
    private val tracks = listOf(
        TrackData("Classic", 0, 7150, listOf(
            Vec2(0f, -600f), Vec2(480f, -480f), Vec2(660f, 0f), Vec2(440f, 520f),
            Vec2(0f, 620f), Vec2(-480f, 440f), Vec2(-620f, -140f), Vec2(-360f, -520f)
        )),
        TrackData("Cyber", 1, 8500, listOf(
            Vec2(-380f, -640f), Vec2(500f, -640f), Vec2(740f, -320f), Vec2(600f, 40f),
            Vec2(-140f, 90f), Vec2(-260f, 360f), Vec2(360f, 400f), Vec2(480f, 680f),
            Vec2(-480f, 680f), Vec2(-700f, 220f), Vec2(-660f, -300f)
        )),
        TrackData("Tokyo", 2, 9200, listOf(
            Vec2(0f, -680f), Vec2(580f, -580f), Vec2(700f, -100f), Vec2(220f, 100f),
            Vec2(660f, 480f), Vec2(260f, 720f), Vec2(-520f, 580f), Vec2(-220f, 100f),
            Vec2(-660f, -220f), Vec2(-520f, -660f)
        )),
        TrackData("Glacier", 3, 8000, listOf(
            Vec2(0f, -660f), Vec2(550f, -520f), Vec2(740f, 100f), Vec2(360f, 660f),
            Vec2(-360f, 660f), Vec2(-740f, 100f), Vec2(-550f, -520f)
        )),
        TrackData("Canyon", 4, 10000, listOf(
            Vec2(-300f, -700f), Vec2(480f, -660f), Vec2(740f, -220f), Vec2(300f, 150f),
            Vec2(700f, 550f), Vec2(100f, 760f), Vec2(-550f, 660f), Vec2(-740f, 150f),
            Vec2(-300f, -150f), Vec2(-700f, -480f)
        ))
    )
    private var currentTrackIdx = 0

    private val trackPts = mutableListOf<Vec2>()
    private val trackNormals = mutableListOf<Vec2>()
    private val trackTangents = mutableListOf<Float>()
    private val roadHalfWidth = 52f

    // Fizik Değişkenleri
    private var carX = 0f
    private var carY = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var camYaw = 0f
    private var steerInput = 0f
    private var crownSteer = 0f
    private var touchSteer = 0f
    private var isTouchingScreen = false

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
    private val currentLapRecording = mutableListOf<GhostPose>()
    private var bestLapGhost = listOf<GhostPose>()
    private var ghostFrameIdx = 0
    private val bots = mutableListOf<BotCar>()

    private data class TireMark(val lx1: Float, val ly1: Float, val lx2: Float, val ly2: Float, val rx1: Float, val ry1: Float, val rx2: Float, val ry2: Float)
    private val tireMarks = mutableListOf<TireMark>()
    private var prevBackLX = 0f
    private var prevBackLY = 0f
    private var prevBackRX = 0f
    private var prevBackRY = 0f
    private var hasPrevSkid = false

    // 3B Projeksiyon Geçici Değişkenleri
    private var projX = 0f
    private var projY = 0f
    private var projScale = 0f

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

    private fun vibeClick() = triggerHaptic(28L, 200)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            var delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (delta != 0f) {
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
                            0 -> 0.32f
                            1 -> 0.52f
                            else -> 0.78f
                        }
                        crownSteer = (crownSteer + delta.coerceIn(-1.5f, 1.5f) * sensMult).coerceIn(-1f, 1f)
                        triggerHaptic(16L, 140)
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun buildTrackSpline(idx: Int) {
        trackPts.clear()
        trackNormals.clear()
        trackTangents.clear()
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
        hasPrevSkid = false
        currentLapRecording.clear()
        ghostFrameIdx = 0

        bots.clear()
        if (botsEnabled) {
            bots.add(BotCar(wpFloat = 3f, laneOffset = -18f, targetLane = -18f, speedFactor = 0.90f, color = Color.rgb(255, 195, 20)))
            bots.add(BotCar(wpFloat = 6f, laneOffset = 18f, targetLane = 18f, speedFactor = 0.96f, color = Color.rgb(235, 45, 90)))
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
            paint.textSize = R * 0.075f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.86f, paint)
        }

        canvas.restore()
        postInvalidateOnAnimation()
    }

    // ==================== GERÇEK 3B PERSPEKTİF PROJEKSİYON MOTORU ====================
    private fun project3D(wx: Float, wy: Float, wz: Float, cx: Float, cy: Float, R: Float): Boolean {
        val dx = wx - carX
        val dy = wy - carY
        val cosYaw = cos(camYaw)
        val sinYaw = sin(camYaw)

        // Kameraya göre İleri (forward) ve Sağ (right) eksenleri
        val forward = dx * cosYaw + dy * sinYaw
        val right = -dx * sinYaw + dy * cosYaw

        // Kamera aracın 95 birim gerisinde ve 135 birim yüksekte, 47 derece aşağı bakıyor
        val relF = forward + 95f
        val relH = wz - 135f
        val cosP = 0.682f
        val sinP = 0.731f

        val depth = relF * cosP - relH * sinP
        if (depth < 18f) return false
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

        // 4 Alt Köşe: FL(0), FR(1), RR(2), RL(3)
        val flX = bx + fx - rx; val flY = by + fy - ry
        val frX = bx + fx + rx; val frY = by + fy + ry
        val rrX = bx - fx + rx; val rrY = by - fy + ry
        val rlX = bx - fx - rx; val rlY = by - fy - ry
        val topZ = bz + height

        // Yan Yüzeyler (Sol ve Sağ)
        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, rlX, rlY, bz, rlX, rlY, topZ, flX, flY, topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, frX, frY, bz, rrX, rrY, bz, rrX, rrY, topZ, frX, frY, topZ, sideColor)
        // Ön ve Arka Yüzeyler
        drawQuad3D(canvas, cx, cy, R, flX, flY, bz, frX, frY, bz, frX, frY, topZ, flX, flY, topZ, sideColor)
        drawQuad3D(canvas, cx, cy, R, rlX, rlY, bz, rrX, rrY, bz, rrX, rrY, topZ, rlX, rlY, topZ, rearColor)
        // Üst Yüzey (Tavan / Kaput)
        drawQuad3D(canvas, cx, cy, R, flX, flY, topZ, frX, frY, topZ, rrX, rrY, topZ, rlX, rlY, topZ, topColor)
    }

    private fun drawTrue3DCar(
        canvas: Canvas, cx: Float, cy: Float, R: Float,
        x: Float, y: Float, yaw: Float, baseColor: Int, modelIdx: Int, isGhost: Boolean
    ) {
        val alpha = if (isGhost) 110 else 255
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val topCol = Color.argb(alpha, r, g, b)
        val sideCol = Color.argb(alpha, (r * 0.78f).toInt(), (g * 0.78f).toInt(), (b * 0.78f).toInt())
        val rearCol = Color.argb(alpha, (r * 0.62f).toInt(), (g * 0.62f).toInt(), (b * 0.62f).toInt())
        val glassCol = Color.argb(alpha, 180, 235, 255)

        val c = cos(yaw)
        val s = sin(yaw)

        // Zemin Gölgesi
        if (!isGhost) {
            val fx = c * 15f; val fy = s * 15f
            val rx = -s * 10f; val ry = c * 10f
            drawQuad3D(
                canvas, cx, cy, R,
                x + fx - rx, y + fy - ry, 0.2f,
                x + fx + rx, y + fy + ry, 0.2f,
                x - fx + rx, y - fy + ry, 0.2f,
                x - fx - rx, y - fy - ry, 0.2f,
                Color.argb(130, 0, 0, 0)
            )
        }

        // 1. Ana Alt Gövde (3B Kutu)
        draw3DBox(canvas, cx, cy, R, x, y, 1.5f, 14.5f, 8.8f, 5.5f, yaw, topCol, sideCol, rearCol)

        // 2. Üst Cam Kabin ve Tavan (3B Kutu)
        val cabX = x - c * 1.5f
        val cabY = y - s * 1.5f
        draw3DBox(canvas, cx, cy, R, cabX, cabY, 7.0f, 7.5f, 6.8f, 4.5f, yaw, topCol, glassCol, glassCol)

        // 3. Arka Spoiler (Kanat)
        if (modelIdx > 0 && !isGhost) {
            val spX = x - c * 13.0f
            val spY = y - s * 13.0f
            draw3DBox(canvas, cx, cy, R, spX, spY, 7.0f, 2.0f, 8.5f, 2.2f, yaw, rearCol, sideCol, rearCol)
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

        // 1. 3B Zemin Kare Izgarası (Grid)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(42, 26, 82)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(150, 190, 215)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 45f
        val baseGX = ((carX / gridStep).toInt()) * gridStep
        val baseGY = ((carY / gridStep).toInt()) * gridStep
        for (i in -8..8) {
            val gx = baseGX + i * gridStep
            for (j in -8..7) {
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
        for (j in -8..8) {
            val gy = baseGY + j * gridStep
            for (i in -8..7) {
                val gx1 = baseGX + i * gridStep
                val gx2 = gx1 + gridStep
                if (project3D(gx1, gy, 0f, cx, cy, R)) {
                    val x1 = projX; val y1 = projY
                    if (project3D(gx2, gy, 0f, cx, cy, R)) {
                        canvas.drawLine(x1, y1, projX, projY, paint)
                    }
                }
            }
        }

        // 2. 3B Pist Yolu ve Kırmızı-Beyaz-Sarı Bordürler (İleriden Geriye Doğru Çizim)
        val total = trackPts.size
        val kerbW = 8.5f
        val roadCol = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(72, 76, 80)

        for (step in 52 downTo -10) {
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

            // Sol ve Sağ Bordür Poligonları
            drawQuad3D(canvas, cx, cy, R, kl0x, kl0y, 0f, l0x, l0y, 0f, l1x, l1y, 0f, kl1x, kl1y, 0f, kerbColor)
            drawQuad3D(canvas, cx, cy, R, r0x, r0y, 0f, kr0x, kr0y, 0f, kr1x, kr1y, 0f, r1x, r1y, 0f, kerbColor)

            // Asfalt Poligonu
            drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0f, r0x, r0y, 0f, r1x, r1y, 0f, l1x, l1y, 0f, roadCol)

            // Başlangıç Çizgisi ve 3B Kırmızı Başlangıç Kemeri
            if (i0 == 0) {
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 0.3f, r0x, r0y, 0.3f, r1x, r1y, 0.3f, l1x, l1y, 0.3f, Color.WHITE)
                drawQuad3D(canvas, cx, cy, R, l0x, l0y, 32f, r0x, r0y, 32f, r1x, r1y, 38f, l1x, l1y, 38f, Color.rgb(210, 40, 40))
            }
        }

        // 3. Çift Tekerlek Lastik İzleri
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.2f
        paint.color = Color.argb(150, 15, 15, 15)
        for (mark in tireMarks) {
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

        // 4. Yol Kenarı 3B Bina ve Ağaçlar
        if (theme == 0 || theme == 2) {
            val sp = trackPts[0]
            val sn = trackNormals[0]
            val bldX = sp.x - sn.x * (roadHalfWidth + 55f)
            val bldY = sp.y - sn.y * (roadHalfWidth + 55f)
            draw3DBox(canvas, cx, cy, R, bldX, bldY, 0f, 26f, 26f, 45f, 0f, Color.rgb(42, 45, 65), Color.rgb(28, 30, 45), Color.rgb(22, 24, 36))

            val treeX = sp.x + sn.x * (roadHalfWidth + 50f)
            val treeY = sp.y + sn.y * (roadHalfWidth + 50f)
            draw3DBox(canvas, cx, cy, R, treeX, treeY, 0f, 16f, 16f, 32f, 0f, Color.rgb(65, 185, 75), Color.rgb(45, 145, 55), Color.rgb(35, 120, 45))
        }

        // 5. Hayalet Araç (Ghost)
        if (ghostEnabled && bestLapGhost.isNotEmpty()) {
            val gPose = bestLapGhost[ghostFrameIdx.coerceAtMost(bestLapGhost.size - 1)]
            drawTrue3DCar(canvas, cx, cy, R, gPose.x, gPose.y, gPose.yaw, Color.rgb(0, 225, 255), selectedModel, isGhost = true)
        }

        // 6. Yapay Zeka Bot Araçları
        if (botsEnabled) {
            for (bot in bots) {
                drawTrue3DCar(canvas, cx, cy, R, bot.x, bot.y, bot.yaw, bot.color, 1, isGhost = false)
            }
        }

        // 7. Oyuncunun 3B Aracı
        drawTrue3DCar(canvas, cx, cy, R, carX, carY, carYaw, carColors[selectedColor], selectedModel, isGhost = false)
    }

    // ==================== OYUN FİZİĞİ ====================
    private fun updateBots(dt: Float) {
        val total = trackPts.size
        for (bot in bots) {
            bot.wpFloat += bot.speedFactor * 17.5f * dt
            val idx0 = (bot.wpFloat.toInt() % total + total) % total
            val idx1 = (idx0 + 1) % total
            val frac = bot.wpFloat - floor(bot.wpFloat)

            if (abs(bot.laneOffset - bot.targetLane) < 2f) {
                if ((SystemClock.uptimeMillis() + bot.color) % 120L == 0L) {
                    bot.targetLane = if (bot.targetLane < 0f) 18f else -18f
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
            val nextYaw = trackTangents[(idx0 + 5) % total]
            bot.yaw = roadYaw + wrapAngle(nextYaw - roadYaw) * 1.35f
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

        val topSpeed = when (selectedModel) { 0 -> 270f; 1 -> 295f; 2 -> 285f; else -> 315f }
        val driftGrip = when (selectedModel) { 0 -> 2.4f; 1 -> 2.0f; 2 -> 1.65f; else -> 2.5f }
        val turnRate = when (sensLevel) { 0 -> 2.5f; 1 -> 3.3f; else -> 4.2f }

        carYaw += steerInput * turnRate * dt
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt
        camYaw += wrapAngle(velYaw - camYaw) * 7.2f * dt

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

        val maxRoadOffset = roadHalfWidth * 0.80f
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

        val slip = abs(wrapAngle(carYaw - velYaw))
        val backX = carX - cos(carYaw) * 12f
        val backY = carY - sin(carYaw) * 12f
        val perpX = -sin(carYaw) * 6.5f
        val perpY = cos(carYaw) * 6.5f
        val curLX = backX - perpX; val curLY = backY - perpY
        val curRX = backX + perpX; val curRY = backY + perpY

        if (slip > 0.10f) {
            driftTimer += dt
            driftCombo = when {
                driftTimer > 3.2f -> 4
                driftTimer > 1.6f -> 3
                driftTimer > 0.5f -> 2
                else -> 1
            }
            score += slip * 520f * driftCombo * dt

            if (hasPrevSkid && hypot(curLX - prevBackLX, curLY - prevBackLY) < 35f) {
                tireMarks.add(TireMark(prevBackLX, prevBackLY, curLX, curLY, prevBackRX, prevBackRY, curRX, curRY))
                if (tireMarks.size > 140) tireMarks.removeAt(0)
            }
            prevBackLX = curLX; prevBackLY = curLY
            prevBackRX = curRX; prevBackRY = curRY
            hasPrevSkid = true

            if (now - lastVibeTime > 60) {
                val amp = (slip * 260f).toInt().coerceIn(95, 255)
                triggerHaptic(32L, amp)
                lastVibeTime = now
            }
        } else {
            hasPrevSkid = false
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    // ==================== MENÜLER VE ARAYÜZ ====================
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

        val mapScale = R * 0.00036f
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
