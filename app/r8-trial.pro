# R8 for Prism's own code, Compose and Kotlin only (1.7.4 trial, `-Pr8`; releases stay unminified until the owner says).
# Every other library stays exactly as it ships: NewPipe Extractor, Rhino, jsoup, Media3, ONNX Runtime, CameraX,
# Haze... are kept whole, since NewPipe Extractor and Rhino find their classes by reflection. Nothing is renamed
# (stack traces in crash logs stay readable).
-dontobfuscate
-keepattributes *
-keep class !com.prism.**,!androidx.compose.**,!kotlin.**,!kotlinx.coroutines.** { *; }
-keep interface !com.prism.**,!androidx.compose.**,!kotlin.**,!kotlinx.coroutines.** { *; }

# Prism's settings keys are listed by reflection (SettingsRepository.allKeyNames, used by LookTest).
-keepclassmembers class com.prism.music.data.prefs.K { *; }

# Kept libraries refer to classes that aren't on Android (desktop Java, optional extras); they're never loaded.
-ignorewarnings
