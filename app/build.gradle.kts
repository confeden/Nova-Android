import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

/**
 * Диагностическая сборка: `./gradlew :app:assembleGithubRelease -PnovaDiagnostics`.
 *
 * Включает [com.example.nova.DiagnosticsActivity] (отдельный вход в лаунчере и
 * перенаправление с главного экрана) и ранний перехват падений. Нужна там, где
 * приложение показывает белый экран, а подключить ADB нельзя: отчёт выводится на
 * экран крупным текстом и QR-кодом, человек фотографирует.
 *
 * В обычных сборках и в сборке F-Droid флага нет: компоненты выключены в
 * манифесте, `BuildConfig.DIAGNOSTICS` равен `false`, zxing не подключается.
 *
 * Версию диагностической сборки задают правкой литералов ниже на время сборки
 * (так уехала `1.29.debug-2`, versionCode 144): менять их выражением нельзя —
 * `fdroid checkupdates` читает версию регулярным выражением.
 */
val novaDiagnostics = providers.gradleProperty("novaDiagnostics").isPresent

/**
 * Быстрая сборка для проверок: `./gradlew :app:assembleGithubRelease -PnovaFastBuild`.
 *
 * Замер профиля на правке одного файла: вся сборка 31 с, из них
 * `lintVitalAnalyzeGithubRelease` 26,3 с и `minifyGithubReleaseWithR8` 23,0 с
 * (идут параллельно), а компиляция Kotlin — 0,7 с. То есть время съедают ровно
 * два релизных шага, и в быстром режиме они выключаются.
 *
 * Флаг ничего не меняет без явной передачи: релизы и сборки F-Droid идут с R8 и
 * lint как раньше. APK быстрого режима **нельзя публиковать** — он не обфусцирован
 * и не прошёл проверку lint.
 */
val novaFastBuild = providers.gradleProperty("novaFastBuild").isPresent
if (novaFastBuild) {
    logger.lifecycle("novaFastBuild: R8 и lint выключены — сборка только для проверок")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists().also { exists ->
    if (exists) {
        keystorePropertiesFile.inputStream().use(keystoreProperties::load)
    }
}

/**
 * Секрет для подписи рукопожатий WSS к собственным поддоменам nova-app.eu.
 *
 * Единственный источник истины — общий для клиентов и воркера файл. В репозиторий
 * секрет не попадает: он приходит из переменной окружения или из local.properties,
 * который не коммитится. Релизная сборка без секрета не собирается — тихий откат
 * на пустое значение дал бы внешне рабочую сборку, теряющую домен в тот момент,
 * когда воркер включит обязательную проверку.
 */
val tgCfWsSecret: String = run {
    System.getenv("NOVA_TG_CF_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
        ?: rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
                    .getProperty("novaTgCfSecret")
                    ?.trim()
            }
            ?.takeIf { it.isNotEmpty() }
        ?: ""
}

/**
 * Пароль к своим релеям API SurfEasy — тем, через которые поднимается EU/US.
 *
 * SurfEasy отдаёт разный набор endpoint'ов в зависимости от того, откуда пришёл
 * запрос discover, и набор для российских клиентов из России недостижим. Релей
 * переносит в Швецию только вызовы API; сам туннель по-прежнему набирается с
 * адреса пользователя.
 *
 * Хранится так же, как в Nova PC: сами адреса релеев лежат в исходниках (они не
 * секрет), а пароль приходит из переменной окружения или из local.properties.
 * Пусто — релеи не используются вовсе, и discover идёт напрямую; подставлять
 * заглушку вместо пароля значило бы потратить попытку и получить 407, чтобы
 * узнать то же самое.
 */
val operaRelayPassword: String = run {
    System.getenv("NOVA_OPERA_RELAY_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
        ?: rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
                    .getProperty("novaOperaRelayPassword")
                    ?.trim()
            }
            ?.takeIf { it.isNotEmpty() }
        ?: ""
}

/** Значение уезжает в строковый литерал Kotlin, поэтому кавычки и слеши экранируем. */
fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.nova"
    compileSdk = 35

    // Версия закреплена намеренно: этой же сборкой собирается Go-ядро
    // (tools/build_nova_core_aar.sh), и расхождение NDK между JNI-слоем и ядром
    // — источник несовместимостей, которые проявляются только на устройстве.
    ndkVersion = "27.2.12479018"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.brent.nova"
        minSdk = 24 // Android 7.0; более новые API вызываются под проверкой SDK_INT
        targetSdk = 34
        buildConfigField("boolean", "DIAGNOSTICS", novaDiagnostics.toString())
        manifestPlaceholders["novaDiagnosticsEnabled"] = novaDiagnostics.toString()
        // Главный экран выключается только в диагностической сборке — чтобы в
        // лаунчере остался один значок и человек не открыл падающий экран.
        manifestPlaceholders["novaMainEnabled"] = (!novaDiagnostics).toString()
        // Числа стоят литералами намеренно: F-Droid читает версию из этого файла
        // регулярным выражением (`fdroid checkupdates`, режим `Tags`) и переменную
        // не раскрывает — со `versionCode = appVersionCode` он не находит версию
        // вовсе и не видит новых релизов. Единственный источник версии — здесь.
        versionCode = 148
        versionName = "1.29.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Собираем ровно те архитектуры, для которых в репозитории есть
        // нативные зависимости. Без этого CMake пошёл бы собирать x86/x86_64 и
        // упал бы на импортируемом libtun2proxy.so, которого для них нет.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * JNI-слой к tun2proxy собирается из исходников (`src/main/cpp`), а не лежит
     * готовым файлом.
     *
     * Раньше в репозитории хранились два бинарника одного и того же слоя под
     * разными именами — `libnative-lib.so` для arm64 и `libtun2proxy_jni.so`
     * для armeabi-v7a, — хотя `native-lib.cpp` лежал рядом и просто не был
     * подключён к сборке. Правила F-Droid запрещают готовые бинарники, а здесь
     * они к тому же были лишними.
     */
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    /**
     * Откуда приложение получает обновления.
     *
     * `github` — обычная сборка, которую владелец выкладывает в релизы: встроенный
     * механизм обновления работает как работал. `fdroid` — сборка для каталога
     * F-Droid, где обновления выдаёт сам каталог, а собственный загрузчик обязан
     * молчать: их правило запрещает приложению самому тянуть исполняемые файлы.
     *
     * Различие ровно одно — флаг [BuildConfig.UPDATER_ENABLED]. Кода апдейтера
     * это не удаляет и на сборку для GitHub не влияет: варианты собираются из
     * одних и тех же исходников.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
    }

    applicationVariants.all {
        // Имя файла не зависит от варианта: у владельца в релизах лежит
        // `Nova_<версия>.apk`, и менять это из-за появления flavor нельзя.
        outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output -> output.outputFileName = "Nova_$versionName.apk" }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    /**
     * Проверка релизной сборки после lint не запускается в быстром режиме: по
     * замерам профиля `lintVitalAnalyze` — самая долгая задача сборки (26 с из 31).
     */
    lint {
        checkReleaseBuilds = !novaFastBuild
    }

    buildTypes {
        release {
            // AGP кладёт в APK `META-INF/version-control-info.textproto` — снимок
            // состояния git той машины, где шла сборка. Для воспроизводимой
            // сборки это тупик: у F-Droid свой клон, и файл заведомо другой
            // (поймано сравнением с их сборкой). Приложению он не нужен.
            vcsInfo {
                include = false
            }
            // В быстром режиме R8 и сжатие ресурсов выключены: вторая по времени
            // задача (23 с). Итог — APK крупнее и без обфускации, годится только
            // для проверок, не для публикации.
            isMinifyEnabled = !novaFastBuild
            isShrinkResources = !novaFastBuild
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "TG_CF_WS_SECRET", "\"$tgCfWsSecret\"")
            buildConfigField("String", "OPERA_RELAY_PASSWORD", operaRelayPassword.asBuildConfigString())
        }
        debug {
            // Для отладочных сборок пустой секрет допустим: подпись просто не
            // добавляется, и клиент работает по публичным доменам Cloudflare.
            buildConfigField("String", "TG_CF_WS_SECRET", "\"$tgCfWsSecret\"")
            buildConfigField("String", "OPERA_RELAY_PASSWORD", operaRelayPassword.asBuildConfigString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Сборка со стороны: у постороннего секрета нет и быть не может, и запрет релиза
// превращал бы «соберите сами и проверьте» в «соберите только debug». Флаг ставится
// осознанно и ровно один раз — мы его не ставим никогда, так что защита от забытого
// секрета у нас остаётся прежней.
val allowUnsignedRelease: Boolean =
    System.getenv("NOVA_ALLOW_UNSIGNED_RELEASE") == "1" ||
        // Свойство Gradle нужно сборщикам, которые не управляют окружением и не
        // правят local.properties: F-Droid передаёт флаги только через
        // `gradleprops`, то есть как -PnovaAllowUnsignedRelease=true.
        providers.gradleProperty("novaAllowUnsignedRelease").orNull
            ?.trim().equals("true", ignoreCase = true) ||
        rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
                    .getProperty("novaAllowUnsignedRelease")
                    ?.trim()
                    .equals("true", ignoreCase = true)
            } == true

// Релиз без секрета собираться не должен: такая сборка выглядит работоспособной,
// но потеряет доступ к nova-app.eu, как только воркер включит обязательную проверку.
// Проверка выполняется на этапе конфигурации, а не в doFirst: замыкание задачи
// утащило бы в кэш конфигурации ссылку на объект скрипта сборки, а такие ссылки
// Gradle сериализовать не умеет.
if (
    tgCfWsSecret.isEmpty() &&
    !allowUnsignedRelease &&
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
) {
    throw GradleException(
        "Не задан секрет подписи WSS. Укажите переменную окружения NOVA_TG_CF_SECRET " +
            "или novaTgCfSecret в local.properties. Значение берётся из общего файла " +
            "tgrelay/cf_ws.key проекта Nova PC и в репозиторий не коммитится.\n" +
            "  Собираете Nova из исходников не как владелец домена nova-app.eu? " +
            "Задайте NOVA_ALLOW_UNSIGNED_RELEASE=1 — соберётся всё, кроме релея Telegram " +
            "через собственные поддомены."
    )
}

if (tgCfWsSecret.isEmpty() && allowUnsignedRelease) {
    logger.warn(
        "Nova: релиз собирается без секрета подписи WSS. Транспорты работают, " +
            "релей Telegram через nova-app.eu — нет."
    )
}

// Пустой секрет ломает сборку, но устаревший — нет, а вреда от него столько же:
// APK выглядит рабочим и теряет nova-app.eu ровно тогда, когда воркер включает
// обязательную проверку. Ровно это и случилось при смене секрета — ПК обновили,
// Android остался на прежнем.
//
// Поэтому: если рядом лежит Nova PC, сверяемся с её общим файлом. Проверка
// намеренно мягкая — при другой раскладке каталогов файла просто не будет, и
// сборка продолжится, — но при найденном расхождении релиз не собирается.
// Обойти осознанно: NOVA_SKIP_CF_SECRET_PARITY=1.
//
// Читается на этапе конфигурации, как и проверка выше, и по той же причине.
if (
    tgCfWsSecret.isNotEmpty() &&
    System.getenv("NOVA_SKIP_CF_SECRET_PARITY") != "1" &&
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
) {
    val shared = rootProject.file("../Nova PC/tgrelay/cf_ws.key")
    if (shared.exists()) {
        val expected = shared.readText().trim()
        if (expected.isNotEmpty() && expected != tgCfWsSecret) {
            throw GradleException(
                "Секрет подписи WSS не совпадает с ${shared.path}.\n" +
                    "  Android подписывался бы отозванным секретом и потерял бы nova-app.eu.\n" +
                    "  Приведите novaTgCfSecret в local.properties к значению из общего файла.\n" +
                    "  Осознанно пропустить: NOVA_SKIP_CF_SECRET_PARITY=1"
            )
        }
    }
}

// Без релеев EU/US собирается и работает, но только там, где discover проходит
// напрямую. Сборку не роняем — предупреждаем: молча уехать на прямой discover
// значит вернуть ровно тот отказ, ради которого релеи и появились.
if (operaRelayPassword.isEmpty()) {
    logger.warn(
        "Nova: не задан пароль релеев API SurfEasy (NOVA_OPERA_RELAY_PASSWORD или " +
            "novaOperaRelayPassword в local.properties). Регионы EU/US будут искать endpoint'ы " +
            "прямым discover, а из России этот набор недостижим. Значение лежит в " +
            "awg/opera_relay.key проекта Nova PC."
    )
}

dependencies {
    // QR нужен только экрану самодиагностики. В обычной сборке библиотека не
    // подключается вовсе, а код зовёт её через рефлексию и молча обходится без
    // неё — размер APK и состав зависимостей F-Droid при этом не меняются.
    if (novaDiagnostics) {
        implementation("com.google.zxing:core:3.5.3")
    }
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // Local .aar library from Go will be added here later
    implementation(files("libs/nova-core-api24-stripped.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
