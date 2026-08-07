package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class LatencyGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val latencyValues = LinkedList<Int>()
    private val maxBars = 40 // How many bars to show
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maxLatency = 500f // Max latency for scaling (500ms)

    init {
        barPaint.style = Paint.Style.FILL
    }

    fun addLatency(ms: Int) {
        if (latencyValues.size >= maxBars) {
            latencyValues.removeFirst()
        }
        latencyValues.add(ms)
        invalidate()
    }

    fun clearLatencies() {
        latencyValues.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / maxBars
        val gap = 2f

        var x = width - barWidth // Start drawing from right

        // Iterate backwards from the latest value
        for (i in latencyValues.size - 1 downTo 0) {
            val ms = latencyValues[i]
            
            // Calculate height relative to maxLatency
            // Clamp ms between 0 and maxLatency
            val clampedMs = ms.coerceIn(0, 500)
            val barHeight = (clampedMs / maxLatency) * height
            
            // Determine color (calm palette)
            val color = colorForLatency(ms)
            barPaint.color = color

            // Draw bar (bottom aligned)
            // Left, Top, Right, Bottom
            // Top = height - barHeight
            canvas.drawRect(
                x, 
                height - barHeight.coerceAtLeast(5f), // Ensure at least 5px visible
                x + barWidth - gap, 
                height, 
                barPaint
            )

            x -= barWidth
        }
    }
    
    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = SavedState(superState)
        savedState.latencyValues = latencyValues.toIntArray()
        return savedState
    }
    
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            latencyValues.clear()
            latencyValues.addAll(state.latencyValues.toList())
            invalidate()
        } else {
            super.onRestoreInstanceState(state)
        }
    }
    
    private class SavedState : BaseSavedState {
        var latencyValues: IntArray = intArrayOf()
        
        constructor(superState: Parcelable?) : super(superState)
        
        constructor(source: android.os.Parcel) : super(source) {
            latencyValues = source.createIntArray() ?: intArrayOf()
        }
        
        override fun writeToParcel(out: android.os.Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeIntArray(latencyValues)
        }
        
        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: android.os.Parcel) = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {
        fun colorForLatency(ms: Int): Int {
            return when {
                ms < 0 -> Color.parseColor("#808080")
                ms < 100 -> Color.parseColor("#50C878")
                ms < 400 -> Color.parseColor("#FFB347")
                else -> Color.parseColor("#FF6B6B")
            }
        }
    }
}
