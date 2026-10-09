-keep class android.webkit.** { *; }
-keep class android.net.http.** { *; }

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
