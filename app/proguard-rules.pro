# Keep JNI entry points stable for the bundled tun2proxy wrapper.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.example.operaproxy.ProxyVpnService { *; }
