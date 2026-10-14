# PokeLocation: regras mínimas. Componentes do manifest são mantidos pelo AGP.
-dontwarn org.osmdroid.**

# Debug: Manter logs para debug em tempo de execução
# -keepclassmembers class * { android.util.Log.*; }

# Manter classes MainForm para debugging
-keep class com.pokelocation.app.ui.main.MainForm { *; }

# Manter chamadas a Log.d em todas as classes
# -assumenosideeffects class android.util.Log { public static *** d(...); }

# libsignal: o nativo chama por JNI, por nome, os métodos dos stores; o jar só faz -keepnames nas interfaces → sem call
# site Java o R8 renomeia/remove (NoSuchMethodError getIdentityKeyPair no qa).
-keepclassmembers,includedescriptorclasses class * implements org.signal.libsignal.**.*Store {
    public <methods>;
}
