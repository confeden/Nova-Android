// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowScope
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}

/**
 * Уборка того, что Gradle дописывает на каждом запуске и сам не удаляет никогда.
 *
 * - `build/reports/configuration-cache/`: на каждый промах кэша конфигурации Gradle
 *   кладёт новый HTML-отчёт о входах сборки, даже когда проблем в нём ноль. За два
 *   месяца их набралось 92 каталога. Остаётся самый свежий; после промаха — два,
 *   потому что отчёт текущей сборки Gradle дописывает уже после уборки.
 * - `daemon/<версия>/daemon-*.out.log` в каталоге Gradle: по журналу на каждый запуск
 *   демона. Старейшим было полгода, вместе 1,1 ГБ. Журналы старше 14 дней удаляются;
 *   открытый журнал работающего демона Windows удалить не даст, и он просто остаётся.
 *
 * Кэш сборки, кэш конфигурации и промежуточные файлы в `app/build` не трогаются: у
 * них своя очистка по давности использования, и повторную сборку ускоряют именно они.
 *
 * Работает после сборки, а не при конфигурации: чтение этих каталогов на этапе
 * конфигурации стало бы входом кэша конфигурации и сбрасывало бы его на каждом запуске.
 * Сбой уборки сборку не роняет — только печатает предупреждение.
 */
abstract class PruneGradleLeftovers : FlowAction<PruneGradleLeftovers.Parameters> {
    interface Parameters : FlowParameters {
        @get:Input
        val configurationCacheReports: Property<File>

        @get:Input
        val daemonLogs: Property<File>
    }

    override fun execute(parameters: Parameters) {
        try {
            pruneReports(parameters.configurationCacheReports.get())
            pruneDaemonLogs(parameters.daemonLogs.get())
        } catch (e: Exception) {
            Logging.getLogger(PruneGradleLeftovers::class.java)
                .warn("Nova: уборка после сборки не удалась: $e")
        }
    }

    private fun pruneReports(root: File) {
        if (!root.isDirectory) return
        val reportDirs = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".html") }
            .map { it.parentFile }
            .distinct()
            .sortedByDescending { dir -> dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L }
            .toList()
        reportDirs.drop(1).forEach { it.deleteRecursively() }
        root.walkBottomUp()
            .filter { it != root && it.isDirectory && it.list().isNullOrEmpty() }
            .forEach { it.delete() }
    }

    private fun pruneDaemonLogs(root: File) {
        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        root.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
            versionDir.listFiles()
                ?.filter { it.name.startsWith("daemon-") && it.name.endsWith(".out.log") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }
}

abstract class GradleLeftoversCleanup @Inject constructor(val flowScope: FlowScope)

objects.newInstance<GradleLeftoversCleanup>().flowScope.always(PruneGradleLeftovers::class.java) {
    parameters.configurationCacheReports.set(layout.buildDirectory.dir("reports/configuration-cache").map { it.asFile })
    parameters.daemonLogs.set(gradle.gradleUserHomeDir.resolve("daemon"))
}
