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
 * (так уехала отладочная `1.29.debug-2`): менять их выражением нельзя —
 * `fdroid checkupdates` читает версию регулярным выражением. По той же
 * причине в комментариях этого файла не должно быть слова versionCode
 * рядом с числом: их checkupdates нашёл такую пару в тексте выше и решил,
 * что вышла версия из комментария, а не настоящая.
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
 * Ключ релея этого выпуска — и он намеренно лежит в исходниках.
 *
 * Релей переносит в Швецию **только вызовы API**: регистрацию SurfEasy (Opera),
 * Proton, Cloudflare WARP и запрос мостов Moat у Tor. Сам туннель во всех
 * четырёх случаях набирается с адреса пользователя, поэтому страна выхода не
 * меняется, а на сервере разрешён единственный порт 443 и короткий список имён,
 * в который ни одна точка входа туннеля не внесена.
 *
 * Раньше он приходил из `local.properties`, и это делало сборку F-Droid
 * неполноценной: их сборщик удаляет `local.properties`, собирает из открытого
 * дерева и побайтово сверяет результат с нашим APK. Значение, которого нет в
 * исходниках, туда попасть не могло — то есть у пользователей F-Droid не
 * работала регистрация ни в Opera, ни в Proton, ни в Cloudflare.
 *
 * Тайной ключ и не был: он всегда ехал внутри выложенного APK, откуда его
 * достаёт кто угодно за минуту. Защиту даёт не он, а две вещи на сервере —
 * короткий список разрешённых имён (только API, только порт 443, ни одной точки
 * входа туннеля) и смена ключа на каждом выпуске.
 *
 * **Правило выпуска.** Каждый выпуск получает свой ключ; публикация выпуска
 * гасит предыдущий ключ **этой платформы** и не трогает чужую. Выдаётся он
 * командой `python deploy_opera_relay.py --rotate android <versionCode>` в
 * проекте `nova-app.eu`, после чего оба значения переносятся сюда, а конфиг
 * заливается тем же скриптом без аргументов. Клиенту со снятым ключом сервер
 * отвечает `407` с `X-Nova-Relay-Reason: outdated-client`, и приложение просит
 * обновиться (`NovaRelay.OUTDATED_MESSAGE`).
 *
 * `NOVA_OPERA_RELAY_PASSWORD` остаётся аварийной подменой — но сборка с ней
 * перестаёт быть воспроизводимой, поэтому публиковать её нельзя.
 */
val relayKeyId: String = "nova-android-155"
val relayKeyToken: String = "PkZoJVMNlwnnmDWXobQbTVGPDCT53yeH"

val operaRelayPassword: String = run {
    val override = System.getenv("NOVA_OPERA_RELAY_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
        ?: rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
                    .getProperty("novaOperaRelayPassword")
                    ?.trim()
            }
            ?.takeIf { it.isNotEmpty() }
    if (override != null && override != relayKeyToken) {
        logger.warn(
            "Nova: ключ релея подменён из окружения. Сборка перестала быть воспроизводимой — " +
                "F-Droid соберёт из исходников другое значение и сверка APK не сойдётся. " +
                "Для публикации уберите NOVA_OPERA_RELAY_PASSWORD / novaOperaRelayPassword."
        )
    }
    override ?: relayKeyToken
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
        versionCode = 155
        versionName = "1.32.0"

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
            buildConfigField("String", "RELAY_KEY_ID", relayKeyId.asBuildConfigString())
        }
        debug {
            // Для отладочных сборок пустой секрет допустим: подпись просто не
            // добавляется, и клиент работает по публичным доменам Cloudflare.
            buildConfigField("String", "TG_CF_WS_SECRET", "\"$tgCfWsSecret\"")
            buildConfigField("String", "OPERA_RELAY_PASSWORD", operaRelayPassword.asBuildConfigString())
            buildConfigField("String", "RELAY_KEY_ID", relayKeyId.asBuildConfigString())
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

// Ключ выпуска теперь литерал, так что пустым он бывает только если его стёрли
// намеренно. Сборку не роняем — предупреждаем: без ключа регистрация Opera,
// Proton и Cloudflare остаётся только на прямых путях, а из России они закрыты.
if (operaRelayPassword.isEmpty() || relayKeyId.isEmpty()) {
    logger.warn(
        "Nova: ключ релея пуст. Регистрация Opera, Proton и Cloudflare пойдёт только " +
            "напрямую, а из России эти хосты закрыты. Выдать новый ключ: " +
            "python deploy_opera_relay.py --rotate android <versionCode> в проекте nova-app.eu."
    )
}

// Логин обязан нести версию: сервер по нему решает, погашен ключ или нет, и
// именно поэтому отвечает «обновите приложение», а не «неверный пароль».
// Проверяем здесь, потому что опечатка в литерале иначе всплывёт только на
// устройстве, где ответ 407 неотличим от сетевого отказа.
if (!Regex("^nova-[a-z0-9]+-.+$").matches(relayKeyId)) {
    throw GradleException(
        "Логин релея должен иметь вид nova-<платформа>-<версия>, а сейчас это «$relayKeyId».\n" +
            "  Сервер не разберёт его и ответит обычным 407 вместо «обновите приложение»."
    )
}

/**
 * Сборка, которую можно выложить.
 *
 * Быстрая и диагностическая существуют ровно затем, чтобы проверять код: у
 * первой выключены R8 и lint, у второй подменён главный экран. Ни ту, ни другую
 * не публикуют, и требовать от них выпускной дисциплины незачем.
 */
val novaPublishableBuild = !novaFastBuild && !novaDiagnostics

/**
 * Два условия публикации, которые нельзя проверить глазами.
 *
 * Первое — воспроизводимость. Предупреждения здесь мало: оно тонет в выводе
 * Gradle, а цена ошибки — выпуск, который F-Droid не примет. Их сборщик удаляет
 * `local.properties`, собирает из открытого дерева и сверяет APK побайтово; с
 * подменённым ключом `classes.dex` расходится гарантированно, и версия просто
 * не появляется в каталоге. Замечено ровно так: `local.properties` этой машины
 * несёт ключ, отличный от литерала в исходниках.
 *
 * Второе — правило D17: каждый выпуск получает свой ключ релея, а логин несёт
 * `versionCode`. Расхождение значит, что ключ не выдавали, и на устройстве оно
 * проявится как `407` от сервера — неотличимо от сетевого отказа.
 *
 * Осознанный обход есть, потому что запрет без выхода люди снимают правкой
 * самой проверки.
 */
// Проверка висит на самой упаковке release-APK, а не на конфигурации проекта.
// Иначе она роняла бы и `testGithubDebugUnitTest`, и любой другой вызов Gradle:
// повод для отказа — выложить нечего, а не «нельзя ничего делать».
if (novaPublishableBuild) {
    val relayKeyVersion = relayKeyId.substringAfterLast('-')
    val buildVersionCode = android.defaultConfig.versionCode?.toString().orEmpty()
    val relayOverridden = operaRelayPassword != relayKeyToken
    val keyIdForMessage = relayKeyId
    tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
        doFirst {
            if (System.getenv("NOVA_ALLOW_UNPUBLISHABLE_BUILD") == "1") return@doFirst
            if (relayOverridden) {
                throw GradleException(
                    "Ключ релея подменён из окружения или local.properties — публиковать эту сборку нельзя.\n" +
                        "  F-Droid соберёт из открытого дерева значение из исходников, и побайтовая сверка\n" +
                        "  не сойдётся: выпуск не попадёт в каталог.\n" +
                        "  Уберите novaOperaRelayPassword / NOVA_OPERA_RELAY_PASSWORD, либо собирайте с\n" +
                        "  -PnovaFastBuild, либо осознанно: NOVA_ALLOW_UNPUBLISHABLE_BUILD=1"
                )
            }
            if (relayKeyVersion != buildVersionCode) {
                throw GradleException(
                    "Логин релея «$keyIdForMessage» выписан на версию $relayKeyVersion, а собирается $buildVersionCode.\n" +
                        "  По правилу выпуска (D17) ключ выдаётся на каждый выпуск, и выдаёт его сервер:\n" +
                        "    python deploy_opera_relay.py --rotate android $buildVersionCode  (проект nova-app.eu)\n" +
                        "  После этого перенесите сюда оба значения — relayKeyId и relayKeyToken.\n" +
                        "  Осознанно пропустить: NOVA_ALLOW_UNPUBLISHABLE_BUILD=1"
                )
            }
        }
    }
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
