# kotlinx-serialization：保留生成的 serializer 与 Companion
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.jlu.schedule.**$$serializer { *; }
-keepclassmembers class cn.jlu.schedule.** {
    *** Companion;
}
-keepclasseswithmembers class cn.jlu.schedule.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView JS 桥
-keepclassmembers class cn.jlu.schedule.ui.importer.ImportBrowserActivity$CacheJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
