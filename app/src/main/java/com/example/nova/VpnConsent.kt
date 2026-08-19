package com.example.nova

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher

/**
 * Запрос системного согласия на VPN и внятный отказ, если его не показать.
 *
 * Окно согласия принадлежит системе (`com.android.vpndialogs`), приложение его не
 * рисует. В урезанных прошивках — на ТВ-приставках и спутниковых ресиверах его
 * вырезают регулярно — этого компонента нет вовсе, и тогда `launch` бросает
 * `ActivityNotFoundException`.
 *
 * Раньше это убивало процесс без единого слова: снаружи выглядело как белый экран
 * сразу после запуска (автоподключение дёргает согласие через ~0,7 с). Причина при
 * этом не попадала ни на экран, ни в лог, то есть отказ был невидим — прямое
 * нарушение правила «отказ должен быть виден». Здесь отказ превращается в `false`
 * и запись в лог, а вызывающая сторона показывает человеку причину.
 */
object VpnConsent {

    /** Короткая строка для места, где помещается только одна фраза. */
    const val UNAVAILABLE_SHORT = "Прошивка не показала окно согласия на VPN"

    /**
     * Подсказка с обходным путём. `appops` работает без root, если на устройстве
     * включена отладка по ADB, и выдаёт то же разрешение, что и окно согласия.
     */
    const val UNAVAILABLE_HINT =
        "Прошивка не показывает системное окно согласия на VPN. " +
            "Обходной путь по ADB: appops set com.brent.nova ACTIVATE_VPN allow"

    /**
     * Просит систему показать окно согласия.
     *
     * @return `true`, если запрос ушёл системе и результат придёт в launcher;
     *         `false`, если показать окно не удалось — причина уже в логе.
     */
    fun request(launcher: ActivityResultLauncher<Intent>, intent: Intent): Boolean =
        runCatching { launcher.launch(intent) }
            .onFailure { error ->
                LogManager.log(
                    "Система не смогла показать запрос разрешения на VPN: " +
                        "${error.javaClass.simpleName}: ${error.message}. " +
                        "Похоже, в прошивке нет com.android.vpndialogs.",
                )
            }
            .isSuccess
}
