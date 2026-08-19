package com.example.nova

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.File

/**
 * Перехват падения на самом раннем этапе — для диагностических сборок.
 *
 * `ContentProvider` создаётся системой до `Application.onCreate` и до любой
 * активности, поэтому обработчик успевает встать раньше кода, который падает.
 * Причина пишется в файл: увидеть её на месте невозможно (на приставке экран
 * гаснет или остаётся белым), но следующий запуск покажет её на экране
 * диагностики.
 *
 * В обычных сборках провайдер выключен через `android:enabled` в манифесте.
 */
class DiagnosticsCrashCatcher : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(File(appContext.filesDir, CRASH_FILE), thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
        return true
    }

    private fun writeCrash(file: File, threadName: String, error: Throwable) {
        val text = buildString {
            append("thread=").append(threadName).append('\n')
            append(describe(error))
        }
        file.writeText(text)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val CRASH_FILE = "nova_last_crash.txt"

        /**
         * Короткое описание ошибки: класс, сообщение и первые кадры стека, которые
         * принадлежат приложению. Полный стек в кадр фотографии не поместится, а
         * этих строк хватает, чтобы понять место.
         */
        fun describe(error: Throwable): String = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 3) {
                if (depth > 0) append("причина: ")
                append(current.javaClass.name).append(": ").append(current.message ?: "-").append('\n')
                current.stackTrace.take(6).forEach { frame ->
                    append("  at ").append(frame.className).append('.').append(frame.methodName)
                        .append(':').append(frame.lineNumber).append('\n')
                }
                current = current.cause
                depth++
            }
        }
    }
}
