package com.example.nova

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.core.content.getSystemService

object TvFocusHelper {

    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService<UiModeManager>()
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun install(context: Context, vararg views: View) {
        if (!isTelevision(context)) return
        views.forEach { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.defaultFocusHighlightEnabled = false
            }
            view.setOnFocusChangeListener { target, hasFocus ->
                target.animate().cancel()
                target.animate()
                    .scaleX(if (hasFocus) 1.04f else 1f)
                    .scaleY(if (hasFocus) 1.04f else 1f)
                    .alpha(if (hasFocus) 1f else 0.96f)
                    .translationZ(if (hasFocus) 10f else 0f)
                    .setDuration(if (hasFocus) 130L else 110L)
                    .start()
            }
        }
    }
}
