# FolioVault R8 / ProGuard Rules

# Preserve Firestore data models annotated with @Keep
-keep @androidx.annotation.Keep class * { *; }
-keep class com.example.models.** { *; }

# Preserve Retrofit / Moshi request & response DTOs
-keep class com.example.services.BindDeviceRequest { *; }
-keep class com.example.services.BindDeviceResponse { *; }
-keep class com.example.services.ResetDeviceRequest { *; }
-keep class com.example.services.ResetDeviceResponse { *; }
-keep class com.example.services.FolioVaultBackendApi { *; }
-keep class * extends com.squareup.moshi.JsonAdapter { *; }

# Strip verbose debug logging in minified release builds (no token logging anywhere)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
