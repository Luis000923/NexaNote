# =============================================================================
# NexaNote - Reglas de R8 / ProGuard para el build de Release
# =============================================================================
#
# El nucleo de la logica vive en Rust (libnexanote_core.so) y se invoca por JNI.
# JNI resuelve los simbolos POR NOMBRE: el nombre de la clase puente, su paquete
# y los nombres de los metodos `external` deben sobrevivir intactos a R8, o el
# runtime fallara con UnsatisfiedLinkError al primer acceso al nucleo.

# --- Puente JNI hacia el nucleo Rust -----------------------------------------
# `com.nexanote.core.NativeBridge` es el UNICO punto que hace System.loadLibrary
# y declara metodos native. Se conserva la clase, su nombre y todos sus miembros
# (incluidos los metodos native y el campo `isLoaded`).
-keep class com.nexanote.core.NativeBridge {
    *;
}
-keepclassmembernames class com.nexanote.core.NativeBridge {
    native <methods>;
}

# El contrato que la UI consume; se conserva el tipo para no romper la relacion
# interfaz/implementacion que exige claude.md (la UI depende de NativeCore).
-keep interface com.nexanote.core.NativeCore {
    *;
}

# --- Salvaguarda general para cualquier metodo native ------------------------
# Regla defensiva: si en el futuro se anaden clases con metodos native fuera del
# puente, sus nombres siguen protegidos.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Los nombres de excepciones que el adaptador JNI lanza por nombre de clase
# (`java/lang/IllegalStateException`, `java/lang/RuntimeException`) son de la
# plataforma y R8 no los toca; no se requiere regla adicional.

# --- Dependencias de androidx.security-crypto (Google Tink) ------------------
# Tink referencia anotaciones que solo existen en tiempo de compilacion
# (Error Prone, JSR-305). No estan en el classpath de runtime y no afectan al
# comportamiento; se silencian los avisos de clase ausente de R8.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
