# PokeLocation: regras mínimas. Componentes do manifest são mantidos pelo AGP.
-dontwarn org.osmdroid.**

# Debug: Manter logs para debug em tempo de execução
# -keepclassmembers class * { android.util.Log.*; }


# Manter chamadas a Log.d em todas as classes
# -assumenosideeffects class android.util.Log { public static *** d(...); }

# libsignal: o nativo chama por JNI, por nome, os métodos dos stores; o jar só mantém os nomes das interfaces.
-keepclassmembers,includedescriptorclasses class * implements org.signal.libsignal.**.*Store {
    public <methods>;
}

# Métricas de entrega do FCM excluídas no build.
-dontwarn com.google.android.datatransport.runtime.**
-dontwarn com.google.android.datatransport.cct.**

# Ponte do mapa para a camada Pokémon: sem inline/merge o RouteMapView não carrega o pacote pogo com a camada desligada.
-keep,allowobfuscation interface com.pokelocation.app.ui.map.MapPoiLayer { *; }
-keep,allowobfuscation interface com.pokelocation.app.ui.map.MapPoiLayer$OnGo { *; }
-keepclassmembers,allowobfuscation class com.pokelocation.app.ui.map.PoiLayers { static *** pogo(...); }
