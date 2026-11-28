# QA build type only (never release). The instrumentation APK resolves
# libraries it shares with the app from the app APK; keep those so the
# black-box smoke test can load. App code itself stays fully optimized.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class androidx.annotation.** { *; }
