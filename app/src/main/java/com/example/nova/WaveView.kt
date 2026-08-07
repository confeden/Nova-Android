package com.example.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class WaveView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint().apply {
        color = 0xFF00FF00.toInt() // Green color
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    
    // Wave parameters
    private var phase = 0f
    private var amplitude = 50f
    private var frequency = 0.05f
    private var speed = 0.1f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { 
            phase -= speed
            invalidate()
        }
    }

    init {
        // Initially invisible or stopped
    }

    fun startAnimation() {
        if (!animator.isRunning) {
            visibility = View.VISIBLE
            animator.start()
        }
    }

    fun stopAnimation() {
        if (animator.isRunning) {
            animator.cancel()
        }
        visibility = View.INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw multiple lines for "ripple" effect
        for (i in 0 until 5) {
             val currentAmp = amplitude - (i * 8)
             if (currentAmp <= 0) continue
             
             paint.alpha = 255 - (i * 40)
             
             // Draw user requested "lines diverging from center"
             // Interpretation: Horizontal sine waves moving outwards? 
             // Or literal lines? "diverging from center along the line"
             
             // Let's draw a nice sine wave that moves
             var startX = 0f
             val endX = width
             
             // Simple Sine Wave drawing
             var prevX = 0f
             var prevY = centerY + kotlin.math.sin(startX * frequency + phase + i) * currentAmp
             
             var x = 0f
             while (x < width) {
                 val y = centerY + kotlin.math.sin(x * frequency + phase + (i * 0.5f)) * currentAmp
                 canvas.drawLine(prevX, prevY, x, y, paint)
                 prevX = x
                 prevY = y
                 x += 5
             }
        }
        
        // Request next frame if animating
        if (animator.isRunning) {
            invalidate() // Ensure continuous draw
        }
    }
}
