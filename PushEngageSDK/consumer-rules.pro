# Consumer rules shipped with the PushEngage SDK AAR and applied to every host
# app that minifies with R8/ProGuard. AGP 8+ runs R8 in full mode by default,
# which strips generic signatures and renames/removes members that are only
# reached through reflection. Without these rules a minified host app crashed on
# the first FCM token refresh ("Unable to create call adapter ... for method
# RTApiInterface.upgradeSubscriber").

# Retrofit and Gson read these reflectively.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Generic signatures of Retrofit's built-in types. Retrofit 2.10+ bundles these
# rules itself; the SDK is on 2.9.0 because 2.10+ ships Kotlin 1.9 metadata the
# SDK's Kotlin 1.6 toolchain can't read, so they are declared here instead.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit service interfaces: annotated methods plus their generic return and
# parameter types (e.g. Call<IAMEnvelope<List<IAMMessageResponse>>>).
-keep interface com.pushengage.pushengage.RestClient.RestClient$RTApiInterface { *; }
-keep interface com.pushengage.pushengage.iam.network.IAMApi { *; }

# Gson request/response/payload models. Fields are matched to JSON by name and
# instances are created reflectively, so neither may be renamed or removed.
-keep class com.pushengage.pushengage.model.** { *; }
-keep class com.pushengage.pushengage.iam.model.** { *; }

# Any other SDK class serialized by Gson (e.g. IAM wire DTOs in iam.network).
-keepclassmembers,allowobfuscation class com.pushengage.pushengage.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-if class com.pushengage.pushengage.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class <1> { <init>(...); }

# Gson TypeToken subclasses (e.g. IAMTypeConverters) need their generic
# superclass signature to resolve the target type.
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

# In-app message WebView bridge: JS calls these by name.
-keepclassmembers class com.pushengage.pushengage.** {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp's optional TLS providers (BouncyCastle, Conscrypt, OpenJSSE). OkHttp
# probes for them reflectively and uses them only if the host app ships one;
# they are not dependencies of the SDK. OkHttp 4.8.0 (pinned for the Kotlin 1.6
# toolchain, see above) does not bundle these -dontwarn rules, and since AGP 8
# R8 fails the host app's minified build on missing classes without them.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
