# R8 keep rules for MindChat's release build (ROADMAP 6.4).
#
# The only reflection-driven code in the app is the UniFFI-generated Kotlin
# binding layer (com.mindchat.core) and the JNA runtime it sits on. Everything
# else is plain Compose/Kotlin and is minified normally. Do not add blanket
# androidx keeps or -dontwarn rules unless R8 reports a concrete error.

# --- UniFFI bindings (com.mindchat.core) ---
# JNA resolves native symbols from the method names on the registration
# object (Native.register(UniffiLib::class.java, "mindchat_core")), and
# Structure fields (@JvmField + @Structure.FieldOrder) are read reflectively,
# so the whole generated package must survive: classes, fields, and the
# internal object/interface declarations the bindings use for FFI callbacks.
-keep class com.mindchat.core.** { *; }

# --- JNA runtime (com.sun.jna) ---
# JNA itself is reflection-driven end to end: Native registers objects and
# resolves symbols by method name, Structure reads field order reflectively,
# Callback creates runtime proxies, and com.sun.jna.internal.Cleaner is
# reached through reflection. This matches JNA's own Android guidance.
#
# Note: the generated bindings (uniffi 0.32 Kotlin) register an object via
# Native.register and declare no `interface ... : com.sun.jna.Library`, so no
# `-keep interface * extends com.sun.jna.Library` rule is needed today; the
# `com.mindchat.core.**` rule above already pins the callbacks that implement
# com.sun.jna.Callback.
-keep class com.sun.jna.** { *; }

# JNA 5.x embeds a desktop AWT integration (com.sun.jna.Native$AWT) that
# references java.awt.* classes which do not exist on Android and are never
# reachable there. R8 reported these as real missing classes; the whole
# java.awt package is absent from the platform, so this is a targeted,
# documented suppression (JNA's own Android guidance), not a blanket one.
-dontwarn java.awt.**
