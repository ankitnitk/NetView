# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
