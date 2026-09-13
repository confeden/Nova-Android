package com.example.nova

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.ContextThemeWrapper

/**
 * Фон главного экрана в режиме «изображение».
 *
 * ## Что здесь было
 *
 * Полноэкранный `background.webp` на 810 КБ, единственный на все темы. Владелец
 * убрал его совсем: фоном теперь служит **та же фактура**, что выбрана для
 * экранов настроек, а «без фактуры» означает чёрный экран.
 *
 * Выигрыш не только в размере APK. Главный экран темы не применяет и жил со
 * своей картинкой, не связанной ни с одной из двенадцати палитр; теперь он
 * меняется вместе с ними и красится тем же акцентом.
 *
 * ## Почему растр, а не слои
 *
 * Отдать `ImageView` стопку «чёрная заливка + узор» было бы дешевле по памяти на
 * один растр, но [BackdropRevealImageView] раскрывает фон кругом: он рисует
 * **drawable** внутрь `saveLayer` и маскирует его. Фон вида в эту маску не
 * попадает — он рисуется раньше `onDraw` и появился бы сразу и целиком, без
 * раскрытия. Поэтому чёрный обязан быть частью самой картинки.
 *
 * ## Размер
 *
 * Узор берётся из общего кэша ([NovaTextureCache]) тем же ключом, что и у
 * настроек, поэтому второй раз он не строится и второй копии в памяти не
 * заводит. Сведённый растр выходит той же стороны — до 1440 точек, — и
 * растягивается `centerCrop`; прежний `background.webp` декодировался с запасом
 * в 1.35 экрана, то есть дороже.
 */
internal object NovaMainBackdrop {

    /**
     * Прозрачность фактуры на главном экране.
     *
     * Вдвое больше, чем 56/255 на экранах настроек, и это замерено на телефоне, а
     * не выбрано на глаз: под фактурой здесь нет ни карточек, ни списков — только
     * кнопка и пара надписей по центру, — и при настроечной вуали фон читался как
     * грязь на матрице. Прежняя картинка была полноцветной и занимала экран
     * целиком; 112/255 возвращает тот же вес, не мешая неоновым надписям.
     */
    private const val TEXTURE_ALPHA = 112

    /**
     * Готовый фон или `null`, если узор ещё строится.
     *
     * `onReady` зовётся **из фонового потока** — виды в нём трогать нельзя.
     */
    fun request(context: Context, onReady: (Bitmap) -> Unit): Bitmap? {
        val texture = NovaAppearance.texture(context)
        // «Без фактуры» — это чёрный экран, а не прежняя картинка: так попросил
        // владелец, и так же ведёт себя пункт «Без фона» в самих настройках.
        if (texture == NovaAppearance.Texture.NONE) return black()
        val accent = accent(context)
        val size = displaySize(context)
        val key = NovaTextureCache.keyFor(texture, size[0], size[1], NovaAppearance.variant(context))
        val ready = NovaTextureCache.request(key) { mask -> onReady(compose(mask, accent)) }
        return if (ready != null) compose(ready, accent) else null
    }

    /**
     * Подпись текущего выбора.
     *
     * Главный экран сверяет её на возврате: фактуру и тему могли поменять в
     * настройках, пока он лежал в стопке, и без сверки фон остался бы прежним до
     * перезапуска приложения. Обход дерева видов тут ни при чём — сравниваются
     * четыре значения.
     */
    fun signature(context: Context): String =
        "${NovaAppearance.texture(context).key}|${NovaAppearance.variant(context)}|" +
            "${NovaTheme.current(context)}|${NovaAppearance.temperature(context)}"

    /**
     * Размер экрана целиком, вместе с системными полосами.
     *
     * Не `resources.displayMetrics`: тот отдаёт окно приложения, то есть высоту
     * **без** системных полос. Слой настроек строит узор по границам своего окна,
     * а оно развёрнуто во весь экран (`NovaTheme.applyEdgeToEdge`), — и на Pixel
     * 4a две мерки разошлись: 1080×2340 против 1080×2139, то есть 664×1440 против
     * 727×1440 после урезания. Разные размеры — разные ключи, и общий кэш
     * переставал быть общим: узор строился дважды, занимал вдвое больше памяти, а
     * при пределе в два растра оба экрана ещё и вытесняли друг друга.
     */
    private fun displaySize(context: Context): IntArray {
        val manager = context.getSystemService(android.view.WindowManager::class.java)
            ?: return intArrayOf(1080, 2340)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            return intArrayOf(bounds.width(), bounds.height())
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Один чёрный пиксель.
     *
     * `centerCrop` растянет его на весь экран, и это ровно чёрный фон — но без
     * полноэкранного растра под него.
     */
    private fun black(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun compose(mask: Bitmap?, accent: Int): Bitmap {
        if (mask == null || mask.isRecycled) return black()
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Растр хранит только прозрачность: цвет ему даёт акцент темы.
        paint.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        paint.alpha = TEXTURE_ALPHA
        canvas.drawBitmap(mask, 0f, 0f, paint)
        return out
    }

    /**
     * Акцент темы настроек.
     *
     * Через `ContextThemeWrapper`: сам главный экран объявлен не темой
     * `Theme.Nova.Settings`, и `?attr/novaAccent` у его контекста не разрешается.
     * Тот же приём уже стоит в `MainActivity.applySelectionGlowAccent`.
     */
    private fun accent(context: Context): Int {
        val themed = ContextThemeWrapper(
            context,
            NovaTheme.optionFor(NovaTheme.current(context)).styleRes,
        )
        return NovaTheme.color(themed, R.attr.novaAccent)
    }
}
