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
        val view = CrownDriftView(this)
        setContentView(view)
        view.requestFocus()
    }
}

data class Vec2(val x: Float, val y: Float)

data class TrackData(
    val name: String,
    val theme: Int, // 0: Classic Grass, 1: Cyber Grid, 2: Tokyo Night, 3: Snow, 4: Canyon
    val goldScore: Int,
    val controlPoints: List<Vec2>
)

class CrownDriftView(context: Context) : View(context) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val prefs = context.getSharedPreferences("crowndrift_save", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Ekran Durumları: 0 = MENU, 1 = GARAGE, 2 = SETTINGS, 3 = GAME, 4 = WIN
    private var state = 0

    // Garaj Ayarları (8 Renk ve 4 Kasa Tipi)
    private val carColors = intArrayOf(
        Color.rgb(30, 90, 255),   // Mavi
        Color.rgb(230, 50, 50),   // Kırmızı
        Color.rgb(255, 200, 30),  // Sarı
        Color.rgb(60, 190, 75),   // Yeşil
        Color.rgb(255, 135, 20),  // Turuncu
        Color.rgb(160, 50, 210),  // Mor
        Color.rgb(240, 240, 240), // Beyaz
        Color.rgb(0, 225, 255)    // Turkuaz
    )
    private val carModels = arrayOf("STREET", "SPORT", "JDM", "HYPER")
    private var selectedColor = 1 // Varsayılan Kırmızı
    private var selectedModel = 0 // 0: Street, 1: Sport, 2: JDM, 3: Hyper
    private var garagePreviewAngle = 0.6f

    // Ayarlar (Settings)
    private var sensLevel = 1 // 0: LOW, 1: MEDIUM, 2: HIGH
    private var gfxHigh = true
    private var ghostEnabled = false
    private var hapticEnabled = true
    private var showFps = false

    // 5 Farklı Pist Tasarımı
    private val tracks = listOf(
        TrackData("Classic", 0, 7150, listOf(
            Vec2(0f, -550f), Vec2(480f, -450f), Vec2(650f, 0f), Vec2(450f, 500f),
            Vec2(0f, 620f), Vec2(-480f, 420f), Vec2(-580f, -150f), Vec2(-350f, -500f)
        )),
        TrackData("Cyber", 1, 8500, listOf(
            Vec2(-400f, -600f), Vec2(500f, -600f), Vec2(750f, -350f), Vec2(600f, 0f),
            Vec2(-150f, 50f), Vec2(-250f, 350f), Vec2(350f, 400f), Vec2(450f, 680f),
            Vec2(-450f, 680f), Vec2(-700f, 200f), Vec2(-650f, -300f)
        )),
        TrackData("Tokyo", 2, 9200, listOf(
            Vec2(0f, -700f), Vec2(600f, -600f), Vec2(700f, -100f), Vec2(200f, 100f),
            Vec2(650f, 450f), Vec2(250f, 750f), Vec2(-500f, 600f), Vec2(-200f, 100f),
            Vec2(-650f, -200f), Vec2(-500f, -650f)
        )),
        TrackData("Glacier", 3, 8000, listOf(
            Vec2(0f, -650f), Vec2(550f, -500f), Vec2(750f, 100f), Vec2(350f, 650f),
            Vec2(-350f, 650f), Vec2(-750f, 100f), Vec2(-550f, -500f)
        )),
        TrackData("Canyon", 4, 10000, listOf(
            Vec2(-300f, -700f), Vec2(450f, -650f), Vec2(750f, -200f), Vec2(300f, 150f),
            Vec2(700f, 550f), Vec2(100f, 780f), Vec2(-550f, 650f), Vec2(-750f, 150f),
            Vec2(-300f, -150f), Vec2(-700f, -450f)
        ))
    )
    private var currentTrackIdx = 0

    // Hesaplanmış Spline Pist Noktaları
    private val trackPts = mutableListOf<Vec2>()
    private val trackNormals = mutableListOf<Vec2>()
    private val roadWidth = 92f

    // Oyun İçi Fizik ve Skor Değişkenleri
    private var carX = 0f
    private var carY = 0f
    private var carYaw = 0f
    private var velYaw = 0f
    private var camYaw = 0f
    private var steerAngle = 0f
    private var touchSteer = 0f

    private var lap = 1
    private val totalLaps = 3
    private var currentWp = 0
    private var maxWpReached = 0
    private var lapStartTime = 0L
    private var currentLapTime = 0f
    private var bestLapTime = 0f

    private var score = 0f
    private var driftCombo = 1
    private var driftTimer = 0f
    private var isNewRecord = false

    // Lastik İzi Noktaları
    private data class TireMark(val lx: Float, val ly: Float, val rx: Float, val ry: Float)
    private val tireMarks = mutableListOf<TireMark>()

    // FPS Sayacı ve Zamanlama
    private var lastFrameTime = SystemClock.uptimeMillis()
    private var currentFps = 60
    private var lastVibeTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        buildTrackSpline(0)
    }

    // Catmull-Rom Spline ile Pürüzsüz Pist Oluşturma
    private fun buildTrackSpline(idx: Int) {
        trackPts.clear()
        trackNormals.clear()
        val cps = tracks[idx].controlPoints
        val n = cps.size
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
            trackNormals.add(Vec2(-dy / len, dx / len))
        }
    }

    private fun startRace() {
        buildTrackSpline(currentTrackIdx)
        val p0 = trackPts[0]
        val p1 = trackPts[1]
        carX = p0.x
        carY = p0.y
        carYaw = atan2(p1.y - p0.y, p1.x - p0.x)
        velYaw = carYaw
        camYaw = carYaw
        steerAngle = 0f
        touchSteer = 0f
        lap = 1
        currentWp = 0
        maxWpReached = 0
        score = 0f
        driftCombo = 1
        driftTimer = 0f
        tireMarks.clear()
        lapStartTime = SystemClock.uptimeMillis()
        bestLapTime = prefs.getFloat("best_lap_$currentTrackIdx", 99.99f)
        isNewRecord = false
        state = 3
    }

    // Saat Çerçevesi / Tepe Düğmesi (Crown) Girişi
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (state == 3) {
                val sensMult = when (sensLevel) { 0 -> 0.08f; 1 -> 0.13f; else -> 0.19f }
                steerAngle = (steerAngle + delta * sensMult).coerceIn(-1f, 1f)
            } else if (state == 0) {
                if (delta > 0) currentTrackIdx = (currentTrackIdx + 1) % tracks.size
                else if (delta < 0) currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size
                buildTrackSpline(currentTrackIdx)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Dokunmatik Ekran Kontrolleri
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f
        val x = event.x
        val y = event.y

        when (state) {
            0 -> if (event.action == MotionEvent.ACTION_DOWN) {
                // Sol / Sağ Oklar (Pist Değiştirme)
                if (y in (cy - R * 0.3f)..(cy + R * 0.2f)) {
                    if (x < cx - R * 0.45f) {
                        currentTrackIdx = (currentTrackIdx - 1 + tracks.size) % tracks.size
                        buildTrackSpline(currentTrackIdx)
                        vibeClick()
                    } else if (x > cx + R * 0.45f) {
                        currentTrackIdx = (currentTrackIdx + 1) % tracks.size
                        buildTrackSpline(currentTrackIdx)
                        vibeClick()
                    }
                }
                // Garaj Butonu (Sol Alt)
                if (hypot(x - (cx - R * 0.56f), y - (cy + R * 0.34f)) < R * 0.22f) {
                    state = 1; vibeClick()
                }
                // Ayarlar Butonu (Sağ Alt)
                else if (hypot(x - (cx + R * 0.56f), y - (cy + R * 0.34f)) < R * 0.22f) {
                    state = 2; vibeClick()
                }
                // START Butonu (Alt Orta)
                else if (y > cy + R * 0.48f && abs(x - cx) < R * 0.48f) {
                    vibeClick(); startRace()
                }
            }

            1 -> if (event.action == MotionEvent.ACTION_DOWN) {
                // Kasa Tipi Seçimi (STREET / SPORT / JDM / HYPER)
                if (y in (cy - R * 0.12f)..(cy + R * 0.12f)) {
                    selectedModel = if (x < cx) (selectedModel % 2) else ((selectedModel + 1) % 4)
                    vibeClick()
                }
                // 8 Renk Seçimi (4x2 Izgara)
                val gridTop = cy + R * 0.18f
                val gridBot = cy + R * 0.62f
                if (y in gridTop..gridBot) {
                    val row = if (y < (gridTop + gridBot) / 2f) 0 else 1
                    val col = ((x - (cx - R * 0.56f)) / (R * 1.12f) * 4f).toInt().coerceIn(0, 3)
                    selectedColor = row * 4 + col
                    vibeClick()
                }
                // BACK Butonu
                if (y > cy + R * 0.65f) {
                    state = 0; vibeClick()
                }
            }

            2 -> if (event.action == MotionEvent.ACTION_DOWN) {
                // Ayarlar Satırları
                if (y in (cy - R * 0.65f)..(cy - R * 0.38f)) { sensLevel = (sensLevel + 1) % 3; vibeClick() }
                else if (y in (cy - R * 0.36f)..(cy - R * 0.10f)) { gfxHigh = !gfxHigh; vibeClick() }
                else if (y in (cy - R * 0.08f)..(cy + R * 0.18f)) { ghostEnabled = !ghostEnabled; vibeClick() }
                else if (y in (cy + R * 0.20f)..(cy + R * 0.46f)) { hapticEnabled = !hapticEnabled; vibeClick() }
                else if (y > cy + R * 0.48f) {
                    if (x < cx) showFps = !showFps else state = 0
                    vibeClick()
                }
            }

            3 -> {
                // Oyun İçi Dokunmatik Direksiyon + Üst Kutuya Basınca Menüye Dönüş
                if (event.action == MotionEvent.ACTION_DOWN && y < cy - R * 0.55f && abs(x - cx) < R * 0.35f) {
                    state = 0; vibeClick(); return true
                }
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    touchSteer = ((x - cx) / (R * 0.65f)).coerceIn(-1f, 1f)
                } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    touchSteer = 0f
                }
            }

            4 -> if (event.action == MotionEvent.ACTION_DOWN) {
                // RESTART Butonu
                if (y in (cy + R * 0.22f)..(cy + R * 0.56f) && abs(x - cx) < R * 0.48f) {
                    vibeClick(); startRace()
                }
                // MENU Butonu
                else if (y > cy + R * 0.58f) {
                    vibeClick(); state = 0
                }
            }
        }
        return true
    }

    private fun vibeClick() {
        if (!hapticEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, 120))
        }
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

        // Yuvarlak Saat Ekranı Sınırlandırması
        val clipPath = Path().apply { addCircle(cx, cy, R, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)

        when (state) {
            0 -> drawMainMenu(canvas, cx, cy, R)
            1 -> drawGarage(canvas, cx, cy, R, dt)
            2 -> drawSettings(canvas, cx, cy, R)
            3 -> {
                updateGamePhysics(dt, now)
                drawGameWorld(canvas, cx, cy, R)
                drawGameHud(canvas, cx, cy, R)
            }
            4 -> {
                drawGameWorld(canvas, cx, cy, R)
                drawWinScreen(canvas, cx, cy, R)
            }
        }

        if (showFps) {
            paint.color = Color.GREEN
            paint.textSize = R * 0.08f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$currentFps FPS", cx, cy - R * 0.86f, paint)
        }

        canvas.restore()
        postInvalidateOnAnimation()
    }

    // ==================== 1. ANA MENÜ EKRANI ====================
    private fun drawMainMenu(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))

        // Kavisli CROWNDRIFT Başlığı
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

        // Ortadaki Pist Minimap Önizlemesi
        val mapScale = R * 0.00038f
        val mapPath = Path()
        val yellowPath1 = Path()
        val total = trackPts.size
        for (i in 0 until total) {
            val px = cx + trackPts[i].x * mapScale
            val py = cy - R * 0.14f + trackPts[i].y * mapScale
            if (i == 0) mapPath.moveTo(px, py) else mapPath.lineTo(px, py)
            if (i in (total / 6)..(total / 3)) {
                if (i == total / 6) yellowPath1.moveTo(px, py) else yellowPath1.lineTo(px, py)
            }
        }
        mapPath.close()

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = R * 0.045f
        paint.color = if (tracks[currentTrackIdx].theme == 1) Color.rgb(0, 225, 255) else Color.WHITE
        canvas.drawPath(mapPath, paint)

        paint.color = Color.rgb(255, 195, 20)
        canvas.drawPath(yellowPath1, paint)

        // Sol (<) ve Sağ (>) Oklar
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(150, 160, 175)
        paint.textSize = R * 0.14f
        canvas.drawText("<", cx - R * 0.68f, cy - R * 0.08f, paint)
        canvas.drawText(">", cx + R * 0.68f, cy - R * 0.08f, paint)

        // Pist İsmi (Classic, Cyber vb.)
        paint.color = Color.WHITE
        paint.textSize = R * 0.15f
        canvas.drawText(tracks[currentTrackIdx].name, cx, cy + R * 0.25f, paint)

        // Sayfa Noktaları (Pagination Dots)
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

        // En Yüksek Skor Rozeti
        val bestScore = prefs.getInt("best_score_$currentTrackIdx", 0)
        paint.color = Color.rgb(25, 32, 40)
        val badgeRect = RectF(cx - R * 0.24f, cy + R * 0.37f, cx + R * 0.24f, cy + R * 0.49f)
        canvas.drawRoundRect(badgeRect, R * 0.06f, R * 0.06f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.085f
        canvas.drawText("● $bestScore", cx, cy + R * 0.46f, paint)

        // Sol Garaj Dairesel Butonu
        val gx = cx - R * 0.56f
        val gy = cy + R * 0.34f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(gx, gy, R * 0.15f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(gx - R * 0.055f, gy - R * 0.04f, gx + R * 0.055f, gy + R * 0.04f, 6f, 6f, paint)

        // Sağ Ayarlar Dairesel Butonu
        val sx = cx + R * 0.56f
        val sy = cy + R * 0.34f
        paint.color = Color.rgb(28, 36, 44)
        canvas.drawCircle(sx, sy, R * 0.15f, paint)
        paint.color = Color.WHITE
        paint.textSize = R * 0.13f
        canvas.drawText("⚙", sx, sy + R * 0.045f, paint)

        // Alt Turkuaz START Butonu
        paint.color = Color.rgb(0, 225, 255)
        val startRect = RectF(cx - R * 0.46f, cy + R * 0.53f, cx + R * 0.46f, cy + R * 0.79f)
        canvas.drawRoundRect(startRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.13f
        canvas.drawText("START", cx, cy + R * 0.70f, paint)
    }

    // ==================== 2. GARAJ (GARAGE) EKRANI ====================
    private fun drawGarage(canvas: Canvas, cx: Float, cy: Float, R: Float, dt: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        garagePreviewAngle += dt * 0.8f

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.15f
        canvas.drawText("Garage", cx, cy - R * 0.56f, paint)

        // 3B İzometrik Araç Önizlemesi
        canvas.save()
        canvas.translate(cx, cy - R * 0.28f)
        val bodyColor = carColors[selectedColor]
        // Tekerlekler
        paint.color = Color.rgb(20, 20, 20)
        canvas.drawRoundRect(-R * 0.24f, R * 0.02f, -R * 0.12f, R * 0.13f, 8f, 8f, paint)
        canvas.drawRoundRect(R * 0.06f, R * 0.05f, R * 0.18f, R * 0.16f, 8f, 8f, paint)
        // Alt Gövde
        paint.color = bodyColor
        canvas.drawRoundRect(-R * 0.30f, -R * 0.06f, R * 0.30f, R * 0.09f, 12f, 12f, paint)
        // Kabin & Cam
        paint.color = Color.rgb(140, 225, 255)
        canvas.drawRoundRect(-R * 0.14f, -R * 0.16f, R * 0.14f, -R * 0.04f, 8f, 8f, paint)
        // Tavan
        paint.color = bodyColor
        canvas.drawRoundRect(-R * 0.12f, -R * 0.18f, R * 0.08f, -R * 0.12f, 6f, 6f, paint)
        // Spoiler (SPORT, JDM ve HYPER modellerinde)
        if (selectedModel > 0) {
            canvas.drawRect(-R * 0.28f, -R * 0.16f, -R * 0.20f, -R * 0.06f, paint)
            canvas.drawRoundRect(-R * 0.32f, -R * 0.19f, -R * 0.16f, -R * 0.14f, 4f, 4f, paint)
        }
        // Ön Farlar
        paint.color = Color.WHITE
        canvas.drawRect(R * 0.22f, -R * 0.02f, R * 0.29f, R * 0.04f, paint)
        canvas.restore()

        // STREET / SPORT / JDM / HYPER Seçim Butonları
        val leftRect = RectF(cx - R * 0.56f, cy - R * 0.07f, cx - R * 0.04f, cy + R * 0.11f)
        val rightRect = RectF(cx + R * 0.04f, cy - R * 0.07f, cx + R * 0.56f, cy + R * 0.11f)

        paint.color = if (selectedModel % 2 == 0) Color.rgb(0, 225, 255) else Color.rgb(24, 30, 38)
        canvas.drawRoundRect(leftRect, R * 0.09f, R * 0.09f, paint)
        paint.color = if (selectedModel % 2 == 0) Color.BLACK else Color.WHITE
        paint.textSize = R * 0.085f
        canvas.drawText(carModels[selectedModel], leftRect.centerX(), leftRect.centerY() + R * 0.03f, paint)

        paint.color = if (selectedModel % 2 == 1) Color.rgb(0, 225, 255) else Color.rgb(24, 30, 38)
        canvas.drawRoundRect(rightRect, R * 0.09f, R * 0.09f, paint)
        paint.color = if (selectedModel % 2 == 1) Color.BLACK else Color.WHITE
        canvas.drawText(carModels[(selectedModel + 1) % 4], rightRect.centerX(), rightRect.centerY() + R * 0.03f, paint)

        // 8 Renk Seçenekleri (4x2 Izgara)
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

        // BACK Butonu
        paint.color = Color.rgb(150, 165, 180)
        paint.textSize = R * 0.09f
        canvas.drawText("BACK", cx, cy + R * 0.78f, paint)
    }

    // ==================== 3. AYARLAR (SETTINGS) EKRANI ====================
    private fun drawSettings(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.rgb(10, 14, 18))
        val sensNames = arrayOf("LOW", "MEDIUM", "HIGH")

        drawSettingRow(canvas, cx, cy - R * 0.52f, R, 0.64f, "SENS", sensNames[sensLevel], false)
        drawSettingRow(canvas, cx, cy - R * 0.24f, R, 0.76f, "GFX", if (gfxHigh) "HIGH" else "LOW", false)
        drawSettingRow(canvas, cx, cy + R * 0.04f, R, 0.76f, "GHOST", "", ghostEnabled)
        drawSettingRow(canvas, cx, cy + R * 0.32f, R, 0.68f, "HAPTIC", "", hapticEnabled)

        // Alt Butonlar: SHOW FPS & BACK
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
        paint.textSize = R * 0.085f
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

    // ==================== 4. OYUN FİZİĞİ VE TAKİP KAMERASI ====================
    private fun updateGamePhysics(dt: Float, now: Long) {
        currentLapTime = (now - lapStartTime) / 1000f

        // Dokunmatik veya Crown Girişi Birleştirme
        if (abs(touchSteer) > 0.05f) {
            steerAngle = touchSteer
        } else {
            steerAngle *= 0.92f // Elini çekince direksiyon yavaşça toplar
        }

        // Kasa Tipine Göre Hız ve Drift Karakteri
        val topSpeed = when (selectedModel) { 0 -> 310f; 1 -> 345f; 2 -> 330f; else -> 370f }
        val driftGrip = when (selectedModel) { 0 -> 3.2f; 1 -> 2.8f; 2 -> 2.1f; else -> 3.5f }

        // Aracın Burnunun Dönüşü ve Kayma (Slip Angle) Hesabı
        carYaw += steerAngle * 2.9f * dt
        val diff = wrapAngle(carYaw - velYaw)
        velYaw += diff * driftGrip * dt

        // Kamera Takibi (Yumuşak Açısal Takip)
        camYaw += wrapAngle(velYaw - camYaw) * 6.5f * dt

        carX += cos(velYaw) * topSpeed * dt
        carY += sin(velYaw) * topSpeed * dt

        // En Yakın Pist Noktasını Bulma ve Pist Sınırında Tutma
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

        // Bordürlere (Kerbs) Çarpma ve Yolda Tutma
        if (bestDist > roadWidth * 0.82f) {
            val tp = trackPts[bestIdx]
            val angToCar = atan2(carY - tp.y, carX - tp.x)
            carX = tp.x + cos(angToCar) * roadWidth * 0.82f
            carY = tp.y + sin(angToCar) * roadWidth * 0.82f
            // Bordür Titreşimi
            if (hapticEnabled && now - lastVibeTime > 55) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, 200))
                }
                lastVibeTime = now
            }
        }

        // Tur (Lap) Sayacı Kontrolü
        if (bestIdx > maxWpReached && bestIdx - maxWpReached < total / 3) {
            maxWpReached = bestIdx
        }
        if (bestIdx < total / 8 && maxWpReached > (total * 3) / 4) {
            maxWpReached = 0
            if (currentLapTime < bestLapTime) {
                bestLapTime = currentLapTime
                prefs.edit().putFloat("best_lap_$currentTrackIdx", bestLapTime).apply()
            }
            if (lap < totalLaps) {
                lap++
                lapStartTime = now
                vibeClick()
            } else {
                // Yarış Bitti -> YOU WIN Ekranı
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

        // Drift Puanı, Çarpan (x2, x3) ve Lastik İzi Üretimi
        val slip = abs(diff)
        if (slip > 0.22f) {
            driftTimer += dt
            driftCombo = when {
                driftTimer > 4.0f -> 4
                driftTimer > 2.0f -> 3
                driftTimer > 0.7f -> 2
                else -> 1
            }
            score += slip * 420f * driftCombo * dt

            // Arka Sol ve Sağ Tekerlek İzleri
            val backX = carX - cos(carYaw) * 16f
            val backY = carY - sin(carYaw) * 16f
            val perpX = -sin(carYaw) * 9f
            val perpY = cos(carYaw) * 9f
            tireMarks.add(TireMark(backX - perpX, backY - perpY, backX + perpX, backY + perpY))
            if (tireMarks.size > 160) tireMarks.removeAt(0)

            // Drift Titreşimi
            if (hapticEnabled && now - lastVibeTime > 65) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amp = (slip * 150f).toInt().coerceIn(35, 220)
                    vibrator.vibrate(VibrationEffect.createOneShot(16, amp))
                }
                lastVibeTime = now
            }
        } else {
            driftTimer = (driftTimer - dt * 2f).coerceAtLeast(0f)
            if (driftTimer == 0f) driftCombo = 1
        }
    }

    private fun drawGameWorld(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val theme = tracks[currentTrackIdx].theme
        val camScale = R / 230f

        // Zemin Rengi
        val bgColor = when (theme) {
            0 -> Color.rgb(34, 78, 24)  // Classic Çim Yeşili
            1 -> Color.rgb(12, 10, 28)  // Cyber Gece Moru
            2 -> Color.rgb(18, 22, 32)  // Tokyo Gece
            3 -> Color.rgb(180, 215, 235) // Buzul
            else -> Color.rgb(95, 55, 30) // Kanyon Toprağı
        }
        canvas.drawColor(bgColor)

        canvas.save()
        // Arabayı Ekranın Alt-Orta Kısmına Sabitleyen Takip Kamerası Dönüşümü
        canvas.translate(cx, cy + R * 0.42f)
        canvas.scale(camScale, camScale)
        canvas.rotate(-Math.toDegrees(camYaw.toDouble()).toFloat() - 90f)
        canvas.translate(-carX, -carY)

        // 1. Zemin Izgarası (Grid) ve Cyber Efektleri
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = when (theme) {
            0 -> Color.rgb(44, 92, 32)
            1 -> Color.rgb(38, 24, 75)
            2 -> Color.rgb(30, 38, 55)
            3 -> Color.rgb(155, 195, 220)
            else -> Color.rgb(115, 70, 42)
        }
        val gridStep = 45f
        val minGX = ((carX - 450f) / gridStep).toInt() * gridStep
        val maxGX = carX + 450f
        val minGY = ((carY - 450f) / gridStep).toInt() * gridStep
        val maxGY = carY + 450f
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

        // Cyber Haritası Parlayan Mor Kareler
        if (theme == 1 && gfxHigh) {
            paint.style = Paint.Style.FILL
            gx = minGX
            while (gx <= maxGX) {
                gy = minGY
                while (gy <= maxGY) {
                    val hash = abs((gx.toInt() * 73856093) xor (gy.toInt() * 19349663)) % 100
                    if (hash < 14) {
                        paint.color = Color.argb(140, 145, 35, 235)
                        canvas.drawRect(gx + 6f, gy + 6f, gx + gridStep - 6f, gy + gridStep - 6f, paint)
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
        paint.strokeWidth = roadWidth * 2f + 22f
        for (i in 0 until total) {
            val p1 = trackPts[i]
            val p2 = trackPts[(i + 1) % total]
            paint.color = when {
                i % 2 == 0 -> Color.rgb(225, 35, 35) // Kırmızı
                (i / 2) % 7 == 0 -> Color.rgb(250, 210, 20) // Sarı Viraj Bordürü
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
        paint.strokeWidth = roadWidth * 2f
        paint.color = if (theme == 3) Color.rgb(75, 90, 105) else Color.rgb(68, 72, 75)
        canvas.drawPath(roadPath, paint)

        // 4. Başlangıç Çizgisi ve Kırmızı Başlangıç Kemeri (Gantry)
        val sp = trackPts[0]
        val sn = trackNormals[0]
        paint.strokeWidth = 14f
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.WHITE
        canvas.drawLine(sp.x - sn.x * roadWidth, sp.y - sn.y * roadWidth, sp.x + sn.x * roadWidth, sp.y + sn.y * roadWidth, paint)

        // Kırmızı Üst Kemer
        paint.strokeWidth = 10f
        paint.color = Color.rgb(200, 40, 40)
        canvas.drawLine(sp.x - sn.x * (roadWidth + 15f), sp.y - sn.y * (roadWidth + 15f), sp.x + sn.x * (roadWidth + 15f), sp.y + sn.y * (roadWidth + 15f), paint)

        // 5. Yol Kenarı Binalar ve Ağaçlar (Classic & Tokyo)
        if (theme == 0 || theme == 2) {
            paint.style = Paint.Style.FILL
            val bldX = sp.x - sn.x * (roadWidth + 95f)
            val bldY = sp.y - sn.y * (roadWidth + 95f)
            paint.color = Color.rgb(30, 32, 45)
            canvas.drawRect(bldX - 45f, bldY - 45f, bldX + 45f, bldY + 45f, paint)
            paint.color = Color.rgb(240, 220, 50) // Sarı Pencereler
            canvas.drawRect(bldX - 30f, bldY - 30f, bldX - 12f, bldY - 12f, paint)
            canvas.drawRect(bldX + 12f, bldY - 30f, bldX + 30f, bldY - 12f, paint)
            canvas.drawRect(bldX - 30f, bldY + 12f, bldX - 12f, bldY + 30f, paint)

            // Yeşil Çam Ağaçları
            val treeX = sp.x + sn.x * (roadWidth + 85f)
            val treeY = sp.y + sn.y * (roadWidth + 85f)
            paint.color = Color.rgb(45, 155, 55)
            canvas.drawCircle(treeX, treeY, 36f, paint)
            paint.color = Color.rgb(65, 185, 75)
            canvas.drawCircle(treeX - 6f, treeY - 6f, 22f, paint)
        }

        // 6. Çift Tekerlek Lastik İzleri (Skidmarks)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5.5f
        paint.color = Color.argb(145, 18, 18, 18)
        for (i in 1 until tireMarks.size) {
            val prev = tireMarks[i - 1]
            val curr = tireMarks[i]
            if (hypot(curr.lx - prev.lx, curr.ly - prev.ly) < 40f) {
                canvas.drawLine(prev.lx, prev.ly, curr.lx, curr.ly, paint)
                canvas.drawLine(prev.rx, prev.ry, curr.rx, curr.ry, paint)
            }
        }

        // 7. Aracın Çizimi (Gölge, Tekerlekler, Gövde, Cam, Spoiler, Far Işığı)
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(Math.toDegrees(carYaw.toDouble()).toFloat())

        // Gece Haritalarında Ön Far Işık Konisi
        if (theme == 1 || theme == 2) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 220, 245, 255)
            val lightPath = Path().apply {
                moveTo(18f, -10f)
                lineTo(135f, -55f)
                lineTo(135f, 55f)
                lineTo(18f, 10f)
                close()
            }
            canvas.drawPath(lightPath, paint)
        }

        // Araç Gölgesi
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(-23f, -15f, 21f, 17f, 6f, 6f, paint)

        // 4 Tekerlek
        paint.color = Color.rgb(15, 15, 15)
        canvas.drawRect(-16f, -15f, -6f, -11f, paint)
        canvas.drawRect(8f, -15f, 18f, -11f, paint)
        canvas.drawRect(-16f, 11f, -6f, 15f, paint)
        canvas.drawRect(8f, 11f, 18f, 15f, paint)

        // Ana Gövde
        paint.color = carColors[selectedColor]
        canvas.drawRoundRect(-20f, -12.5f, 20f, 12.5f, 5f, 5f, paint)

        // Ön ve Arka Cam + Tavan
        paint.color = Color.rgb(160, 230, 255)
        canvas.drawRoundRect(-10f, -9.5f, 10f, 9.5f, 3f, 3f, paint)
        paint.color = carColors[selectedColor]
        canvas.drawRect(-7f, -7.5f, 5f, 7.5f, paint)

        // Spoiler
        if (selectedModel > 0) {
            canvas.drawRect(-21f, -11f, -16f, 11f, paint)
        }

        // Ön Beyaz Farlar & Arka Kırmızı Stoplar
        paint.color = Color.WHITE
        canvas.drawRect(17f, -10f, 20f, -5f, paint)
        canvas.drawRect(17f, 5f, 20f, 10f, paint)
        paint.color = Color.rgb(255, 40, 40)
        canvas.drawRect(-20f, -10f, -17f, -5f, paint)
        canvas.drawRect(-20f, 5f, -17f, 10f, paint)

        canvas.restore()
        canvas.restore()
    }

    // ==================== 5. OYUN İÇİ GÖSTERGELER (HUD) ====================
    private fun drawGameHud(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        // Üst Yarı Saydam Skor ve Tur Kutusu
        val hudRect = RectF(cx - R * 0.46f, cy - R * 0.82f, cx + R * 0.46f, cy - R * 0.40f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(175, 16, 22, 28)
        canvas.drawRoundRect(hudRect, R * 0.09f, R * 0.09f, paint)

        // LAP 1/3 • 7.4s
        paint.color = Color.rgb(185, 195, 205)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = R * 0.082f
        val timeStr = String.format("%.1fs", currentLapTime)
        canvas.drawText("LAP $lap/$totalLaps • $timeStr", cx, cy - R * 0.66f, paint)

        // Büyük Turkuaz Skor ve Pembe Çarpan (x2, x3, x4)
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

        // Alt Kenar Kavisli Direksiyon Noktaları (Crown Göstergesi)
        val totalDots = 19
        val centerDot = totalDots / 2
        val activeDotOffset = (steerAngle * centerDot).toInt()
        for (i in 0 until totalDots) {
            val offset = i - centerDot
            val deg = 90f - offset * 3.4f
            val rad = Math.toRadians(deg.toDouble())
            val dx = cx + cos(rad).toFloat() * (R * 0.88f)
            val dy = cy + sin(rad).toFloat() * (R * 0.88f)

            val isLit = (activeDotOffset >= 0 && offset in 0..activeDotOffset) ||
                        (activeDotOffset < 0 && offset in activeDotOffset..0) || offset == 0
            paint.color = if (isLit) Color.rgb(0, 220, 255) else Color.argb(120, 60, 70, 80)
            canvas.drawCircle(dx, dy, R * 0.017f, paint)
        }
    }

    // ==================== 6. BİTİŞ (YOU WIN!) EKRANI ====================
    private fun drawWinScreen(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        canvas.drawColor(Color.argb(215, 10, 16, 20))

        // YOU WIN!
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(80, 235, 155)
        paint.textSize = R * 0.17f
        canvas.drawText("YOU WIN!", cx, cy - R * 0.52f, paint)

        // Sarı NEW RECORD! Kutusu
        if (isNewRecord) {
            val recRect = RectF(cx - R * 0.36f, cy - R * 0.45f, cx + R * 0.36f, cy - R * 0.32f)
            paint.color = Color.rgb(245, 190, 25)
            canvas.drawRoundRect(recRect, R * 0.065f, R * 0.065f, paint)
            paint.color = Color.rgb(20, 20, 20)
            paint.textSize = R * 0.075f
            canvas.drawText("NEW RECORD!", cx, recRect.centerY() + R * 0.026f, paint)
        }

        // Dev Beyaz Skor
        val finalScore = score.toInt()
        paint.color = Color.WHITE
        paint.textSize = R * 0.25f
        canvas.drawText("$finalScore", cx, cy - R * 0.08f, paint)

        paint.color = Color.rgb(150, 165, 175)
        paint.textSize = R * 0.075f
        canvas.drawText("SCORE", cx, cy + R * 0.01f, paint)

        // 3 Madalya Noktası (Bronz, Gümüş, Altın)
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

        // GOLD & BEST LAP Bilgisi
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(175, 185, 195)
        paint.textSize = R * 0.07f
        val bestStr = String.format("%.2fs", bestLapTime)
        canvas.drawText("GOLD $goldReq • BEST LAP $bestStr", cx, cy + R * 0.20f, paint)

        // RESTART Butonu
        val resRect = RectF(cx - R * 0.48f, cy + R * 0.26f, cx + R * 0.48f, cy + R * 0.52f)
        paint.color = Color.rgb(0, 225, 255)
        canvas.drawRoundRect(resRect, R * 0.13f, R * 0.13f, paint)
        paint.color = Color.rgb(10, 20, 28)
        paint.textSize = R * 0.11f
        canvas.drawText("RESTART", cx, resRect.centerY() + R * 0.04f, paint)

        // MENU Butonu
        paint.color = Color.rgb(160, 175, 190)
        paint.textSize = R * 0.085f
        canvas.drawText("MENU", cx, cy + R * 0.68f, paint)
    }
}
