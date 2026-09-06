package com.example.nova

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object MainBackgroundPolicy {
    const val MODE_IMAGE = "image"
    const val MODE_ANIMATION = "animation"
    const val MODE_NONE = "none"

    fun normalize(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            MODE_IMAGE -> MODE_IMAGE
            MODE_NONE -> MODE_NONE
            else -> MODE_ANIMATION
        }
    }

    fun isAnimationSupported(context: Context): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val lowRam = activityManager?.isLowRamDevice == true
        return !lowRam &&
            Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            Runtime.getRuntime().availableProcessors() > 4 &&
            android.os.Process.is64Bit()
    }

    /**
     * Во что превращается запрошенный режим на этом устройстве.
     *
     * Замена анимации — **пустой фон**, а не картинка. Устройства, которые не
     * тянут анимацию, — это старые телефоны, и полноэкранная картинка стоит им
     * заметно дороже пустоты: она держит в памяти растр размером с экран и
     * перерисовывается под всем остальным. Картинка при этом никуда не делась,
     * она остаётся третьим вариантом в настройках — просто её теперь выбирают, а
     * не получают молча вместо анимации.
     */
    fun effectiveMode(context: Context, requested: String?): String {
        val normalized = normalize(requested)
        return if (normalized == MODE_ANIMATION && !isAnimationSupported(context)) {
            MODE_NONE
        } else {
            normalized
        }
    }
}
