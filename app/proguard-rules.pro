# Compose
-keepattributes *Annotation*
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# DataStore — uses reflection to access generated classes
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Glance widget — receiver and callbacks are resolved by class name at runtime
-keep class com.netview.app.widget.** { *; }

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
