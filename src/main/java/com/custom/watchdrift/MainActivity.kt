package com.custom.watchdrift

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import kotlin.math.*

data class CarModel(val name: String, val color: Int, val speed: Float, val driftFactor: Float)
data class TrackMap(val name: String, val roadColor: Int, val borderColor: Int, val friction: Float, val radiusX: Float, val radiusY: Float)
data class SkidMark(val x: Float, val y: Float, var alpha: Int = 180)

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameView = DriftGameView(this)
        setContentView(gameView)
        gameView.requestFocus()
    }
}

class DriftGameView(context: Context) : View(context) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Araç ve Harita Listeleri
    private val cars = listOf(
        CarModel("JDM Silvia", Color.RED, 4.2f, 0.94f),
        CarModel("Neon Cyber", Color.CYAN, 4.8f, 0.90f),
        CarModel("Muscle V8", Color.YELLOW, 4.5f, 0.96f)
    )
    private val maps = listOf(
        TrackMap("Tokyo Gece", Color.DKGRAY, Color.MAGENTA, 0.94f, 130f, 95f),
        TrackMap("Buz Pisti", Color.rgb(20, 40, 60), Color.CYAN, 0.98f, 120f, 120f),
        TrackMap("Dar Kanyon", Color.rgb(40, 30, 20), Color.YELLOW, 0.91f, 140f, 75f)
    )

    private var currentCarIdx = 0
    private var currentMapIdx = 0

    // Fizik Değişkenleri
    private var carX = 0f
    private var carY = -95f
    private var carAngle = 0f      // Arabanın burnunun baktığı açı
    private var moveAngle = 0f     // Arabanın gerçekte kaydığı yön
    private var score = 0
    private var combo = 1
    private var lastVibrateTime = 0L
    private val skidMarks = mutableListOf<SkidMark>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // Saat Çerçevesi / Tepe Düğmesi (Crown) Kontrolü
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            carAngle += delta * 0.25f
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Dokunmatik Kontrol (Sağ/Sol dönüş, Üst: Araç Değiştir, Alt: Harita Değiştir)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val cx = width / 2f
            val cy = height / 2f
            if (event.action == MotionEvent.ACTION_DOWN && event.y < height * 0.2f) {
                currentCarIdx = (currentCarIdx + 1) % cars.size
                resetCar()
                return true
            } else if (event.action == MotionEvent.ACTION_DOWN && event.y > height * 0.8f) {
                currentMapIdx = (currentMapIdx + 1) % maps.size
                resetCar()
                return true
            }
            // Sağ veya sola dokunarak direksiyon kırma
            carAngle += if (event.x > cx) 0.09f else -0.09f
        }
        return true
    }

    private fun resetCar() {
        carX = 0f
        carY = -maps[currentMapIdx].radiusY
        carAngle = 0f
        moveAngle = 0f
        skidMarks.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val car = cars[currentCarIdx]
        val map = maps[currentMapIdx]

        // 1. Drift Fiziği Hesaplaması
        val driftGrip = (car.driftFactor + map.friction) / 2f
        val angleDiff = atan2(sin(carAngle - moveAngle), cos(carAngle - moveAngle))
        moveAngle += angleDiff * (1f - driftGrip)

        carX += cos(moveAngle) * car.speed
        carY += sin(moveAngle) * car.speed

        // 2. Drift Algılama ve Titreşim (Haptics)
        val isDrifting = abs(angleDiff) > 0.35f
        if (isDrifting) {
            score += (10 * combo)
            skidMarks.add(SkidMark(carX, carY))
            if (skidMarks.size > 120) skidMarks.removeAt(0)

            val now = SystemClock.uptimeMillis()
            if (now - lastVibrateTime > 60) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Drift açısına göre titreşim şiddeti (15ms darbeli)
                    val amp = (abs(angleDiff) * 120).toInt().coerceIn(30, 255)
                    vibrator.vibrate(VibrationEffect.createOneShot(15, amp))
                }
                lastVibrateTime = now
            }
        }

        // 3. Pist ve Lastik İzlerini Çizme
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(cx, cy)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 55f
        paint.color = map.roadColor
        canvas.drawOval(-map.radiusX, -map.radiusY, map.radiusX, map.radiusY, paint)

        paint.strokeWidth = 3f
        paint.color = map.borderColor
        canvas.drawOval(-map.radiusX - 28f, -map.radiusY - 28f, map.radiusX + 28f, map.radiusY + 28f, paint)
        canvas.drawOval(-map.radiusX + 28f, -map.radiusY + 28f, map.radiusX - 28f, map.radiusY - 28f, paint)

        // Lastik İzleri
        paint.style = Paint.Style.FILL
        for (mark in skidMarks) {
            paint.color = Color.argb(mark.alpha, 20, 20, 20)
            canvas.drawCircle(mark.x, mark.y, 4f, paint)
            mark.alpha = (mark.alpha - 2).coerceAtLeast(0)
        }

        // 4. Arabayı Çizme
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(Math.toDegrees(carAngle.toDouble()).toFloat())
        paint.color = car.color
        canvas.drawRoundRect(-14f, -8f, 14f, 8f, 4f, 4f, paint)
        paint.color = Color.WHITE // Ön farlar
        canvas.drawRect(10f, -6f, 14f, -3f, paint)
        canvas.drawRect(10f, 3f, 14f, 6f, paint)
        canvas.restore()
        canvas.restore()

        // 5. Arayüz (Skor, Araç ve Harita Bilgisi)
        paint.color = Color.WHITE
        paint.textSize = 22f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Araç: ${car.name} (Üste Dokun)", cx, 45f, paint)
        canvas.drawText("SKOR: $score", cx, cy, paint)
        if (isDrifting) {
            paint.color = Color.GREEN
            canvas.drawText("DRIFT!", cx, cy + 28f, paint)
        }
        paint.color = Color.LTGRAY
        paint.textSize = 20f
        canvas.drawText("Pist: ${map.name} (Alta Dokun)", cx, height - 25f, paint)

        // 60 FPS Döngüsü
        postInvalidateOnAnimation()
    }
}
