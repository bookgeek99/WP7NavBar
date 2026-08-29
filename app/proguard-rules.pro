# ProGuard rules (当前 minifyEnabled=false，占位)
-keep class com.wp7.navbar.** { *; }
# LSPosed 的类由框架注入，不需要混淆
-dontwarn de.robv.android.xposed.**
