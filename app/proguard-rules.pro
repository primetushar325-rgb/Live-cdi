# Mihad Live
# Minification is disabled by default (isMinifyEnabled = false) so the streaming
# core behaves exactly like the debug build. These rules keep the build safe if
# R8 is ever enabled.

-keep class com.pedro.** { *; }
-keep class com.mihad.live.** { *; }

# Play services / Google auth reflection
-keep class com.google.android.gms.auth.** { *; }
-dontwarn com.google.android.gms.**
