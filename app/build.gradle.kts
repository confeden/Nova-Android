import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val appVersionCode = 135
val appVersionName = "1.25"

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

android {
    namespace = "com.example.nova"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.brent.nova"
        minSdk = 24 // Android 7.0; более новые API вызываются под проверкой SDK_INT
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    applicationVariants.all {
        outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output -> output.outputFileName = "Nova_${appVersionName}.apk" }
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "TG_CF_WS_SECRET", "\"$tgCfWsSecret\"")
        }
        debug {
            // Для отладочных сборок пустой секрет допустим: подпись просто не
            // добавляется, и клиент работает по публичным доменам Cloudflare.
            buildConfigField("String", "TG_CF_WS_SECRET", "\"$tgCfWsSecret\"")
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

// Релиз без секрета собираться не должен: такая сборка выглядит работоспособной,
// но потеряет доступ к nova-app.eu, как только воркер включит обязательную проверку.
// Проверка выполняется на этапе конфигурации, а не в doFirst: замыкание задачи
// утащило бы в кэш конфигурации ссылку на объект скрипта сборки, а такие ссылки
// Gradle сериализовать не умеет.
if (
    tgCfWsSecret.isEmpty() &&
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
) {
    throw GradleException(
        "Не задан секрет подписи WSS. Укажите переменную окружения NOVA_TG_CF_SECRET " +
            "или novaTgCfSecret в local.properties. Значение берётся из общего файла " +
            "tgrelay/cf_ws.key проекта Nova PC и в репозиторий не коммитится."
    )
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Local .aar library from Go will be added here later
    implementation(files("libs/nova-core-api24-stripped.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
