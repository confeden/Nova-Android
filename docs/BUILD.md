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

## Секреты, которых в репозитории нет

Два значения принадлежат владельцу инфраструктуры и в исходники не входят. Оба
читаются из переменной окружения или из `local.properties`:

| Значение | Переменная окружения | Ключ в `local.properties` | Без него |
| --- | --- | --- | --- |
| Подпись рукопожатий WSS к `nova-app.eu` | `NOVA_TG_CF_SECRET` | `novaTgCfSecret` | не работает релей Telegram через собственные поддомены |
| Пароль релеев API SurfEasy | `NOVA_OPERA_RELAY_PASSWORD` | `novaOperaRelayPassword` | регионы EU/US ищут endpoint'ы прямым discover |

Релизная сборка **у владельца** намеренно падает без первого значения: молчаливый откат
на пустой секрет дал бы внешне рабочий APK, теряющий домен. Стороннему сборщику этот
запрет не нужен — снимается явным флагом:

```bash
NOVA_ALLOW_UNSIGNED_RELEASE=1 ./gradlew assembleRelease
```

или `novaAllowUnsignedRelease=true` в `local.properties`. Всё остальное — WARP, MASQUE,
VLESS, шлюз, обновления — собирается и работает без обоих секретов.

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

`nova-core/go.mod` подключает пять проектов через `replace`, то есть по путям на диске.
Три из них у нас пропатчены — клонировать upstream «как есть» значит собрать другое ядро.
Пины коммитов и сами патчи лежат в `tools/deps/`, раскладывает всё один скрипт:

```bash
tools/deps/fetch_go_deps.sh
```

| Зависимость | Путь | Патч |
| --- | --- | --- |
| `amneziawg-go` | `tools/amneziawg-go` | да — `device/`, `tun/netstack/` |
| `warp-plus` | `tools/warp-plus` | да — `wireguard/tun/netstack/`, `wiresocks/` |
| `gvisor` | `build/deps/gvisor` | да — снят `pkg/sync/runtime_constants_go125.go` (не собирается на Go 1.26) |
| `usque` | `build/deps/usque` | нет |
| `connect-ip-go` | `tools/connect-ip-go` | наш форк, лежит прямо в репозитории |

Каталоги зависимостей в репозиторий не кладутся: один gvisor весит больше всех исходников
вместе взятых. В репозитории — пины, патчи и скрипт; результат воспроизводим побайтово.

Дальше сборку делает скрипт: он вызывает `gomobile bind`, снимает символы с `libgojni.so`
и раскладывает результат по обоим местам сразу.

```bash
tools/build_nova_core_aar.sh
```

> **Важно.** Файлы `app/src/main/jniLibs/<abi>/libgojni.so` имеют приоритет над `jni/`
> внутри `.aar`. Если обновить только `.aar`, на ARM-устройствах продолжит работать
> старая библиотека — при изменении `nova-core` обновляйте оба места, иначе новый
> экспортированный метод даст `UnsatisfiedLinkError` на реальных устройствах. Скрипт
> обновляет оба; при ручной сборке про это нужно помнить.

## Готовые нативные библиотеки

В `app/src/main/jniLibs/<abi>/` лежат собранные `.so`. Три из них собираются из этого
репозитория, остальные — внешние. Что откуда:

| Библиотека | Происхождение |
| --- | --- |
| `libgojni.so` | `nova-core/`, собирается `tools/build_nova_core_aar.sh` |
| `libnovaxray.so`, `libnovaxrayjni.so` | `nova-xray/`, собирается `nova-xray/build.sh`; в репозиторий не коммитятся |
| `libnative-lib.so` | `app/src/main/cpp/native-lib.cpp`, собирается CMake в составе проекта |
| `libtun2proxy.so` | [tun2proxy](https://github.com/tun2proxy/tun2proxy), сборка под Android NDK |
| `liboperaproxy.so` | [opera-proxy](https://github.com/Snawoot/opera-proxy), сборка под Android |
| `libtgwsproxy.so` | наш Go-слой релея Telegram, часть `nova-core` |

Внешние библиотеки собраны заранее и лежат в репозитории готовыми — иначе для обычной
сборки APK понадобились бы Rust и Go со всеми их цепочками. Если это неприемлемо для
аудита, каждую можно пересобрать из указанного источника и подменить файл: код Nova
обращается к ним только через объявленные JNI-имена.

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
