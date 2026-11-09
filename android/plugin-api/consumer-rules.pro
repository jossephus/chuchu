# Plugin APKs link against these by name at runtime, so R8 must not rename or strip them.
-keep class com.jossephus.chuchu.plugin.api.** { *; }
-keep interface com.jossephus.chuchu.plugin.api.** { *; }

-keep public class androidx.compose.runtime.** { public protected *; }
-keep public class androidx.compose.ui.** { public protected *; }
-keep public class androidx.compose.foundation.** { public protected *; }
-keep public class androidx.compose.animation.** { public protected *; }

-keep public class kotlin.** { public protected *; }
-keep public class kotlinx.coroutines.** { public protected *; }
