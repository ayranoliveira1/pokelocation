# PokeLocation: regras mínimas. Componentes do manifest são mantidos pelo AGP.
-dontwarn org.osmdroid.**

# Debug: Manter logs para debug em tempo de execução
# -keepclassmembers class * { android.util.Log.*; }

# Manter classes MainForm para debugging
-keep class com.pokelocation.app.ui.main.MainForm { *; }

# Manter chamadas a Log.d em todas as classes
# -assumenosideeffects class android.util.Log { public static *** d(...); }
