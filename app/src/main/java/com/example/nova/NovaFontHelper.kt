package com.example.nova

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView

object NovaFontHelper {

    private val blackIds = setOf(
        R.id.tvStatus,
        R.id.btnConnect,
    )

    private val mediumIds = setOf(
        R.id.btn_settings,
        R.id.btn_language,
        R.id.tv_country_badge,
        R.id.tvAttemptProgress,
        R.id.tv_version,
        R.id.tv_title,
        R.id.row_share_release,
        R.id.btn_create_new,
        R.id.btn_paste_config,
        R.id.tv_configs_source_label,
        R.id.tv_discovery_status,
        R.id.tv_discovery_progress,
        R.id.tv_empty,
        R.id.tv_config_title,
        R.id.tv_config_current,
        R.id.btn_delete_config,
        R.id.btn_copy_config,
        R.id.tv_footer,
        R.id.tv_qs_tile_note,
        R.id.tv_exit_last,
        R.id.tv_warp_configs_note,
        R.id.tv_split_section_title,
        R.id.tv_next_profile_caption,
        R.id.tv_profile_issue_progress,
        R.id.tv_exit_sub_region_label,
        R.id.tv_tor_bridges_status,
    )

    /**
     * Виды, у которых моноширинность — часть смысла, а не оформления.
     *
     * Журнал и тело конфигурации выравниваются по колонкам, и пропорциональный
     * шрифт их ломает. Раньше их узнавали по уже выставленному `Typeface.MONOSPACE`,
     * и на теме Matrix это стало ловушкой: после её включения моноширинным
     * становится **всё** дерево, а значит при переходе на любую другую тему
     * проверка «уже моноширинный» пропускала каждый вид, и терминальное начертание
     * оставалось навсегда. Признак обязан быть по идентификатору.
     */
    private val alwaysMonospaceIds = setOf(
        R.id.tv_config_body,
        R.id.tv_logs_preview,
    )

    /**
     * Метка на виде, который уже выбрал своё начертание сам (например, конфиг
     * в диалоге — моноширинный на любой теме). Обход дерева его не трогает.
     */
    const val KEEP_FACE_TAG = "nova:keep-face"

    fun apply(root: View) {
        val set = setFor(root.context, themeFace(root.context))
        val mono = if (set.face == NovaTheme.Face.MONOSPACE) set.regular else Typeface.MONOSPACE
        applyRecursive(root, set, mono)
    }

    /**
     * Моноширинное начертание для текста, который ставится вручную (журнал,
     * тело конфига): на Matrix — его собственный шрифт, на остальных темах —
     * системный `Typeface.MONOSPACE`, как и раньше.
     */
    fun monospace(context: Context): Typeface {
        val face = themeFace(context)
        return if (face == NovaTheme.Face.MONOSPACE) setFor(context, face).regular else Typeface.MONOSPACE
    }

    private fun themeFace(context: Context): NovaTheme.Face =
        runCatching { NovaTheme.optionFor(NovaTheme.current(context)).face }
            .getOrDefault(NovaTheme.Face.DEFAULT)

    private fun setFor(context: Context, face: NovaTheme.Face): FaceSet {
        cached?.takeIf { it.face == face }?.let { return it }
        return loadSet(context, face).also { cached = it }
    }

    private fun applyRecursive(view: View, set: FaceSet, mono: Typeface) {
        when (view) {
            is EditText -> {
                view.typeface = set.regular
            }

            is TextView -> {
                when {
                    // Своё начертание, выбранное при создании.
                    view.tag == KEEP_FACE_TAG -> Unit
                    view.id in alwaysMonospaceIds -> view.typeface = mono
                    else -> view.typeface = when {
                        view.id in blackIds -> set.black
                        view.id in mediumIds -> set.medium
                        isBoldView(view) -> set.bold
                        else -> set.regular
                    }
                }
            }
        }

        if (view is RecyclerView) {
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), set, mono)
            }
        }
    }

    private fun isBoldView(view: TextView): Boolean {
        return when (view.typeface?.style ?: Typeface.NORMAL) {
            Typeface.BOLD, Typeface.BOLD_ITALIC -> true
            else -> false
        }
    }

    /**
     * Набор из четырёх начертаний для роли: обычное, среднее, жирное и «чёрное».
     * Если у семейства один файл, он идёт во все роли, а жирное получается
     * синтетическим утолщением (как у встроенных семейств Android раньше).
     *
     * `DEFAULT` — штатный Roboto. `HEAVY` — самое жирное в проекте (GTA): обычный
     * и средний вес сдвинуты на ступень вверх, а «чёрное» — Rubik Mono One, и
     * идёт только на статусе и кнопке подключения (blackIds), где её ширина
     * уместна.
     */
    private fun loadSet(context: Context, face: NovaTheme.Face): FaceSet {
        fun font(id: Int, fallback: Typeface): Typeface =
            ResourcesCompat.getFont(context, id) ?: fallback
        val sans = Typeface.SANS_SERIF
        return when (face) {
            NovaTheme.Face.DEFAULT -> FaceSet(
                face,
                regular = font(R.font.roboto_regular, sans),
                medium = font(R.font.roboto_medium, sans),
                bold = font(R.font.roboto_bold, sans),
                black = font(R.font.roboto_black, sans),
            )
            NovaTheme.Face.HEAVY -> {
                val bold = font(R.font.roboto_bold, sans)
                val black = font(R.font.roboto_black, sans)
                FaceSet(
                    face,
                    regular = bold,
                    medium = black,
                    bold = black,
                    black = font(R.font.rubik_mono_one, black),
                )
            }
            NovaTheme.Face.MONOSPACE -> single(face, font(R.font.ptmono_regular, Typeface.MONOSPACE))
            NovaTheme.Face.SERIF_SC -> pair(face, font(R.font.cormorant_sc_regular, Typeface.SERIF), font(R.font.cormorant_sc_bold, Typeface.SERIF))
            NovaTheme.Face.ROMAN -> single(face, font(R.font.forum_regular, Typeface.SERIF))
            NovaTheme.Face.OLD_STYLE -> pair(face, font(R.font.old_standard_regular, Typeface.SERIF), font(R.font.old_standard_bold, Typeface.SERIF))
            NovaTheme.Face.TECHNO -> pair(face, font(R.font.play_regular, sans), font(R.font.play_bold, sans))
        }
    }

    private fun single(face: NovaTheme.Face, file: Typeface): FaceSet {
        val bold = Typeface.create(file, Typeface.BOLD)
        return FaceSet(face, regular = file, medium = file, bold = bold, black = bold)
    }

    private fun pair(face: NovaTheme.Face, regular: Typeface, bold: Typeface): FaceSet =
        FaceSet(face, regular = regular, medium = bold, bold = bold, black = bold)

    private class FaceSet(
        val face: NovaTheme.Face,
        val regular: Typeface,
        val medium: Typeface,
        val bold: Typeface,
        val black: Typeface,
    )

    @Volatile
    private var cached: FaceSet? = null
}
