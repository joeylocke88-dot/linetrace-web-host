package com.linetrace.app.presentation
import com.linetrace.app.core.FusedState
import com.linetrace.app.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class StabilityDashboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 28f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val history = FloatArray(150) { 1f }
    private var historyIndex = 0
    private var lastState: FusedState? = null
    private var tracking = false
    private var isRecording = false
    private var distanceTravelled = 0f
    private var totalPoints = 0
    
    private val graphPath = Path()
    private val backgroundPaint = Paint().apply {
        color = Color.argb(180, 5, 15, 25) // Deeper tactical navy
    }
    private val graphBgPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
    }
    
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private var safePaddingTop = 100f // Tactical offset for S21 Punch-hole/Status bar

    private var engagementLock = false

    fun updateState(state: FusedState?, isTracking: Boolean, distance: Float = 0f, points: Int = 0, recording: Boolean = false, locked: Boolean = false) {
        tracking = isTracking
        lastState = state
        distanceTravelled = distance
        totalPoints = points
        isRecording = recording
        engagementLock = locked
        if (state != null) {
            history[historyIndex] = state.stability.coerceIn(0f, 1f)
            historyIndex = (historyIndex + 1) % history.size
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Tactical HUD Background - Reduced to a minimal top bar
        canvas.drawRect(0f, 0f, width.toFloat(), 180f + safePaddingTop, backgroundPaint)
        
        // Corner Brackets (Tactical Aesthetic) - More subtle
        val bracketSize = 30f
        val margin = 20f
        cornerPaint.strokeWidth = 2f
        // Top Left
        canvas.drawLine(margin, margin + safePaddingTop, margin + bracketSize, margin + safePaddingTop, cornerPaint)
        canvas.drawLine(margin, margin + safePaddingTop, margin, margin + bracketSize + safePaddingTop, cornerPaint)
        // Top Right
        canvas.drawLine(width - margin, margin + safePaddingTop, width - margin - bracketSize, margin + safePaddingTop, cornerPaint)
        canvas.drawLine(width - margin, margin + safePaddingTop, width - margin, margin + bracketSize + safePaddingTop, cornerPaint)

        val state = lastState
        val score = state?.stability?.coerceIn(0f, 1f) ?: 0f
        
        // Smooth color interpolation (Red -> Yellow -> Green)
        val r = if (score > 0.5f) (255 * (1f - score) * 2f).toInt() else 255
        val g = if (score > 0.5f) 255 else (255 * score * 2f).toInt()
        scorePaint.color = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), 50)

        // Stability Indicator Ring - Smaller and moved to top right
        val centerX = width - 80f
        val centerY = 60f + safePaddingTop
        canvas.drawCircle(centerX, centerY, 30f, scorePaint)
        
        val quality = when {
            score > 0.8f -> "OPT"
            score > 0.5f -> "NOM"
            score > 0.25f -> "DEG"
            else -> "CRI"
        }
        
        canvas.drawText(quality, centerX - textPaint.measureText(quality)/2, centerY + 60f, textPaint.apply { textSize = 22f; isFakeBoldText = true })
        textPaint.textSize = 32f // Reset

        // Top Info Row (instead of Column)
        val yPos = 50f + safePaddingTop
        var xPos = 60f
        
        textPaint.textSize = 28f
        labelPaint.textSize = 20f

        drawLabelValueCompact(canvas, "STABILITY", String.format("%.0f%%", score * 100), xPos, yPos)
        xPos += 240f
        drawLabelValueCompact(canvas, "VIO", if (tracking) "LOCK" else "SEARCH", xPos, yPos, if (tracking) Color.GREEN else Color.RED)
        xPos += 240f
        drawLabelValueCompact(canvas, "DIST", String.format("%.1fm", distanceTravelled), xPos, yPos)
        
        // Secondary Row for coordinates
        val yPos2 = 100f + safePaddingTop
        xPos = 60f
        state?.let {
            drawLabelValueCompact(canvas, "POS", String.format("%.1f, %.1f, %.1f", it.x, it.y, it.z), xPos, yPos2)
        }
        
        // Recording Indicator
        if (isRecording) {
            val blink = (System.currentTimeMillis() / 500) % 2 == 0L
            if (blink) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(width - 40f, 30f, 10f, paint)
            }
        }

        // Tracking Quality Warning - Centered but higher up
        drawQualityWarning(canvas, score)

        drawHistoryGraph(canvas)
    }

    private fun drawQualityWarning(canvas: Canvas, score: Float) {
        val warningText = when {
            !tracking -> "VIO LOSS - STEADY DEVICE"
            score < 0.25f -> "LOW LIGHT / HIGH VELOCITY"
            else -> null
        }

        warningText?.let {
            val blink = (System.currentTimeMillis() / 400) % 2 == 0L
            if (blink) {
                warningPaint.color = if (!tracking) Color.RED else Color.YELLOW
                warningPaint.textSize = 28f
                canvas.drawText(it, width / 2f, 220f + safePaddingTop, warningPaint)
            }
        }
    }
    
    private fun drawLabelValueCompact(canvas: Canvas, label: String, value: String, x: Float, y: Float, valueColor: Int = Color.WHITE) {
        canvas.drawText(label, x, y, labelPaint)
        textPaint.color = valueColor
        canvas.drawText(value, x, y + 35f, textPaint)
        textPaint.color = Color.WHITE
    }

    private fun drawHistoryGraph(canvas: Canvas) {
        val left = width - 450f
        val bottom = 140f + safePaddingTop
        val graphHeight = 40f
        val graphWidth = 350f
        val step = graphWidth / (history.size - 1)

        // Graph Background
        canvas.drawRect(left, bottom - graphHeight, left + graphWidth, bottom, graphBgPaint)
        
        // Grid Lines
        for (i in 0..2) {
            val y = bottom - (i * graphHeight / 2)
            canvas.drawLine(left, y, left + graphWidth, y, gridPaint)
        }

        graphPath.reset()
        var started = false
        
        for (i in 0 until history.size) {
            val idx = (historyIndex + i) % history.size
            val x = left + i * step
            val y = bottom - (history[idx] * graphHeight)
            
            if (!started) {
                graphPath.moveTo(x, y)
                started = true
            } else {
                graphPath.lineTo(x, y)
            }
        }
        
        canvas.drawPath(graphPath, linePaint)
        canvas.drawText("RELIABILITY", left, bottom - graphHeight - 15f, labelPaint)
    }
}
