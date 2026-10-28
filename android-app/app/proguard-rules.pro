# NEPSE Pulse — R8/ProGuard rules for the release build.
#
# The app is a thin WebView wrapper with no reflection-based serialization,
# no dependency injection and no model classes, so R8 can safely shrink and
# obfuscate it. Only the rules below are needed.

# Keep line numbers and source file names so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView JavaScript bridges: keep any @JavascriptInterface method if one is
# ever added. Interface methods are otherwise stripped as unused by R8.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the native library entries used by the system WebView loader if a
# native dependency is introduced later.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
