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
        R.id.btn_imported_only,
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
    )

    fun apply(root: View) {
        val regular = cachedRegular ?: loadRegular(root).also { cachedRegular = it }
        val medium = cachedMedium ?: loadMedium(root, regular).also { cachedMedium = it }
        val bold = cachedBold ?: loadBold(root, regular).also { cachedBold = it }
        val black = cachedBlack ?: loadBlack(root, bold).also { cachedBlack = it }
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
                if (view.id != R.id.tv_config_body && view.typeface != Typeface.MONOSPACE) {
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

    private fun loadRegular(root: View): Typeface {
        return ResourcesCompat.getFont(root.context, R.font.roboto_regular) ?: Typeface.SANS_SERIF
    }

    private fun loadMedium(root: View, fallback: Typeface): Typeface {
        return ResourcesCompat.getFont(root.context, R.font.roboto_medium) ?: fallback
    }

    private fun loadBold(root: View, fallback: Typeface): Typeface {
        return ResourcesCompat.getFont(root.context, R.font.roboto_bold) ?: fallback
    }

    private fun loadBlack(root: View, fallback: Typeface): Typeface {
        return ResourcesCompat.getFont(root.context, R.font.roboto_black) ?: fallback
    }

    @Volatile
    private var cachedRegular: Typeface? = null

    @Volatile
    private var cachedMedium: Typeface? = null

    @Volatile
    private var cachedBold: Typeface? = null

    @Volatile
    private var cachedBlack: Typeface? = null
}
