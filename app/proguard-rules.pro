# Serialization and dependency injection keep rules.
# Shrinking is currently disabled in the release build.

# --- kotlinx.serialization ---
# 序列化器由编译器插件生成，名字必须保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.netscope.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.netscope.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.netscope.core.model.**$$serializer { *; }

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
