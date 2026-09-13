package com.example.nova

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

    fun apply(root: View) {
        // Тема решает начертание целиком: у Matrix интерфейс изображает терминал,
        // и Roboto в нём неуместен ровно так же, как скруглённые углы.
        val face = runCatching {
            NovaTheme.optionFor(NovaTheme.current(root.context)).face
        }.getOrDefault(NovaTheme.Face.DEFAULT)
        if (face != cachedFace) {
            cachedRegular = null
            cachedMedium = null
            cachedBold = null
            cachedBlack = null
            cachedFace = face
        }
        val regular = cachedRegular ?: loadRegular(root, face).also { cachedRegular = it }
        val medium = cachedMedium ?: loadMedium(root, regular, face).also { cachedMedium = it }
        val bold = cachedBold ?: loadBold(root, regular, face).also { cachedBold = it }
        val black = cachedBlack ?: loadBlack(root, bold, face).also { cachedBlack = it }
        applyRecursive(root, regular, medium, bold, black)
    }

    private fun applyRecursive(
        view: View,
        regular: Typeface,
        medium: Typeface,
        bold: Typeface,
        black: Typeface,
    ) {
        when (view) {
            is EditText -> {
                view.typeface = regular
            }

            is TextView -> {
                if (view.id !in alwaysMonospaceIds) {
                    view.typeface = when {
                        view.id in blackIds -> black
                        view.id in mediumIds -> medium
                        isBoldView(view) -> bold
                        else -> regular
                    }
                }
            }
        }

        if (view is RecyclerView) {
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), regular, medium, bold, black)
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
     * Семейство для начертания темы, или `null` — значит штатный Roboto из ресурсов.
     *
     * `sans-serif-condensed` — встроенное семейство Android, а не наш файл: оно
     * есть на любом устройстве начиная с API 16 и ничего не весит.
     */
    private fun familyOf(face: NovaTheme.Face): Typeface? = when (face) {
        NovaTheme.Face.MONOSPACE -> Typeface.MONOSPACE
        NovaTheme.Face.SERIF -> Typeface.SERIF
        NovaTheme.Face.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        NovaTheme.Face.DEFAULT, NovaTheme.Face.HEAVY -> null
    }

    // У встроенных семейств Android — моноширинного, с засечками и узкого — всего
    // два начертания, обычное и жирное: «medium» и «black» там просто нет, а
    // подменять их синтетическим утолщением нечем. Поэтому medium сходится с
    // обычным, а black — с жирным; терминалу, каменному меню и узкому гротеску
    // это идёт: у всех трёх толщина одна.
    //
    // `HEAVY` — наоборот: у него все четыре роли смещены на ступень вверх, вплоть
    // до Roboto Black. Это темы GTA, где надписи в самой игре «надутые», и
    // обычный вес рядом с их палитрой выглядит чужим.
    private fun loadRegular(root: View, face: NovaTheme.Face): Typeface {
        familyOf(face)?.let { return it }
        val res = if (face == NovaTheme.Face.HEAVY) R.font.roboto_bold else R.font.roboto_regular
        return ResourcesCompat.getFont(root.context, res) ?: Typeface.SANS_SERIF
    }

    private fun loadMedium(root: View, fallback: Typeface, face: NovaTheme.Face): Typeface {
        if (familyOf(face) != null) return fallback
        val res = if (face == NovaTheme.Face.HEAVY) R.font.roboto_black else R.font.roboto_medium
        return ResourcesCompat.getFont(root.context, res) ?: fallback
    }

    private fun loadBold(root: View, fallback: Typeface, face: NovaTheme.Face): Typeface {
        familyOf(face)?.let { return Typeface.create(it, Typeface.BOLD) }
        val res = if (face == NovaTheme.Face.HEAVY) R.font.roboto_black else R.font.roboto_bold
        return ResourcesCompat.getFont(root.context, res) ?: fallback
    }

    private fun loadBlack(root: View, fallback: Typeface, face: NovaTheme.Face): Typeface {
        if (familyOf(face) != null) return fallback
        return ResourcesCompat.getFont(root.context, R.font.roboto_black) ?: fallback
    }

    @Volatile
    private var cachedFace: NovaTheme.Face = NovaTheme.Face.DEFAULT

    @Volatile
    private var cachedRegular: Typeface? = null

    @Volatile
    private var cachedMedium: Typeface? = null

    @Volatile
    private var cachedBold: Typeface? = null

    @Volatile
    private var cachedBlack: Typeface? = null
}
