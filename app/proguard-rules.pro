# Keep JNI entry points stable for the bundled tun2proxy wrapper.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.example.operaproxy.ProxyVpnService { *; }

# tor-android: нативный tor обращается к полям и методам `TorService` по именам
# через JNI — `torConfiguration`, `torControlFd`, `runMain` и соседи. R8 их
# переименовывает, и падение выглядит как `NoSuchFieldError: no "J" field
# "torConfiguration"` на первом же запуске tor, причём только в релизной сборке.
# Проверено на Pixel 4a: быстрая сборка (без R8) работала, релизная роняла
# процесс `:vpn` дважды подряд.
-keep class org.torproject.jni.TorService { *; }
-keep class org.torproject.jni.TorService$* { *; }

# jtorctl читает ответы управляющего порта отражением по именам классов событий.
-keep class net.freehaven.tor.control.** { *; }
