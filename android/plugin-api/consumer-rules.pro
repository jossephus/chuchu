# Applied to chuchu's release build. Plugin APKs are compiled separately and link against
# these classes by name at runtime, so R8 must not rename or strip them: a plugin calling a
# Compose or coroutines function chuchu itself never used would otherwise crash with
# NoSuchMethodError. This is the surface plugins may rely on without bundling anything.
#
# Only public/protected members are kept: that's all separately compiled code can call.
# Kotlin `internal` and `@PublishedApi` members (reached by inlined Compose code) are public
# in bytecode, so they're covered too.

# The plugin contract.
-keep class com.jossephus.chuchu.plugin.api.** { *; }
-keep interface com.jossephus.chuchu.plugin.api.** { *; }

# Compose: runtime, ui and foundation (and animation, which foundation uses). material3 is
# not part of chuchu, so plugins that want it must bundle it.
-keep public class androidx.compose.runtime.** { public protected *; }
-keep public class androidx.compose.ui.** { public protected *; }
-keep public class androidx.compose.foundation.** { public protected *; }
-keep public class androidx.compose.animation.** { public protected *; }

# Kotlin and coroutines, which plugin APKs exclude from their runtime classpath.
-keep public class kotlin.** { public protected *; }
-keep public class kotlinx.coroutines.** { public protected *; }
