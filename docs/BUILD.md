# Сборка Nova из исходников

Обычная сборка требует только JDK и Android SDK. Нативные библиотеки уже лежат в
репозитории — пересобирать Go-ядро нужно, лишь если вы его меняете.

## Быстрый путь

```bash
git clone https://github.com/confeden/Nova-Android.git
cd Nova-Android
./gradlew assembleDebug
```

Готовый файл: `app/build/outputs/apk/debug/`.

На Windows используйте `gradlew.bat`.

## Что должно быть установлено

| Инструмент | Версия | Зачем |
| --- | --- | --- |
| JDK | 17 | сборка Android-проекта |
| Android SDK | Platform 34 | `compileSdk = 34`, `targetSdk = 34` |
| Gradle | 9.3.1 | ставится сам через wrapper |

Gradle, Android Gradle Plugin 9.1.0 и Kotlin 2.2.10 подтягиваются автоматически, ставить
их отдельно не нужно.

Путь к SDK задаётся файлом `local.properties` в корне проекта:

```properties
sdk.dir=/путь/к/Android/Sdk
```

Android Studio создаёт его сам при первом открытии проекта.

## Минимальная версия Android

`minSdk = 24` — Android 7.0. Более новые возможности вызываются под проверкой
`Build.VERSION.SDK_INT`, поэтому одна сборка работает и на Android 7, и на Android 15.

## Подпись релиза

Для `assembleRelease` нужен файл `keystore.properties` в корне:

```properties
storeFile=/путь/к/nova.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Без него собирается только debug-вариант. Сам файл и хранилище ключей в репозиторий не
попадают — они перечислены в `.gitignore`.

## Пересборка Go-ядра

Нужна, только если вы правите Go-код. Требуется Go 1.26+ и Android NDK 27.

### Ядро Xray для VLESS + REALITY

```bash
cd nova-xray
./build.sh
```

Скрипт собирает c-shared библиотеку для `arm64-v8a` и `armeabi-v7a`, затем JNI-слой, и
раскладывает результат в `app/src/main/jniLibs/<abi>/`. Сами `.so` в репозиторий не
коммитятся: 34 МБ на архитектуру.

Если библиотеки нет, приложение честно сообщит, что VLESS недоступен, а остальные
транспорты продолжат работать.

### Ядро nova-core для AmneziaWG и MASQUE

Собирается через `gomobile bind` в `.aar`. Понадобятся внешние зависимости, подключённые
через `replace` в `nova-core/go.mod`; их нужно клонировать в `tools/` рядом с проектом:

```bash
git clone https://github.com/amnezia-vpn/amneziawg-go   tools/amneziawg-go
git clone https://github.com/amnezia-vpn/amneziawg-tools tools/amneziawg-tools
git clone https://github.com/bepass-org/warp-plus        tools/warp-plus
```

Всю сборку делает скрипт: он вызывает `gomobile bind`, снимает символы с `libgojni.so`
и раскладывает результат по обоим местам сразу.

```bash
tools/build_nova_core_aar.sh
```

> **Важно.** Файлы `app/src/main/jniLibs/<abi>/libgojni.so` имеют приоритет над `jni/`
> внутри `.aar`. Если обновить только `.aar`, на ARM-устройствах продолжит работать
> старая библиотека — при изменении `nova-core` обновляйте оба места, иначе новый
> экспортированный метод даст `UnsatisfiedLinkError` на реальных устройствах. Скрипт
> обновляет оба; при ручной сборке про это нужно помнить.

## Тесты

```bash
./gradlew testDebugUnitTest
```

```bash
cd nova-core && go test ./cfws/
```

Часть проверок — пробы на реальных данных: они пропускаются, если рядом нет
соответствующего файла в `tools/probe/`. Это сделано намеренно, чтобы не хранить в
репозитории чужие рабочие конфигурации.

## Установка на устройство

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Для Android TV удобнее `adb connect <адрес приставки>` — навигация пультом в приложении
поддерживается.
