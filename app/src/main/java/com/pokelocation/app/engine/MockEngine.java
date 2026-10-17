package com.pokelocation.app.engine;

/** Roda na main thread do :overlay. */
public interface MockEngine {
    int STATE_JOYSTICK = 1, STATE_ROUTE = 2;
    int ROUTE_STOP = 0, ROUTE_LOOP = 1, ROUTE_REVERSE = 2;
    int GEN_RANDOM = 0, GEN_CIRCLE = 1;

    interface Listener {
        void onRouteProgress(int index1Based, int total);       // waypoint alcançado, NEXT, PREV, start
        void onRouteStateChanged(boolean hasRoute, boolean playing);
        default void onAutoSuspendCountdown(int secondsLeft) {}  // Suspended: 1×/s, só com autoSuspendActive
    }

    void setListener(Listener l);
    void start(double lat, double lng, double alt);   // registra providers, inicia ticks
    void shutdown();                                  // para ticks, remove providers
    void setStick(float angleDeg, float magnitude);   // ângulo matemático (0 = leste, anti-horário), 0..1
    void setSpeedKmh(float kmh);
    float getSpeedKmh();
    void teleport(double lat, double lng, double alt); // pos e rep ← destino; alt NaN mantém a atual; freeze de speed
    void setAltitude(double alt);                      // resultado assíncrono da elevação
    void startRoute(double[] pts, int routeMode, boolean teleportToStart);
    void resumeRoute(double[] pts, int routeMode, boolean reversed, int nextIndex); // retorno MAP_RESUME
    void setRoutePlaying(boolean playing);
    boolean next();                                    // fast-forward; false sem rota
    boolean previous();                                // Stop trava em 0
    void stopRoute();
    boolean hasRoute();
    boolean isRoutePlaying();
    int routeIndex();     // cur (0-based) do último waypoint alcançado
    int routeTotal();
    int routeRemaining();
    int routeMode();
    boolean isReversed();
    int nextIndex();      // índice do próximo alvo (-1 sem rota)
    double[] routePoints();
    double lat();
    double lng();
    double alt();
    float bearing();
    float speedMs();
    float accuracy();
    long lastTickTime();

    // Sem efeito se EngineConfig.suspended = false.
    interface SuspendedPublisher {          // implementado pelo OverlayService: abre/atualiza a MockingActivity
        void publishSuspended(double lat, double lng, double alt, float speedMs, float accuracy, float bearing);
    }

    void setSuspendedPublisher(SuspendedPublisher p);
    void injectNow();                        // botão "suspend": tick() imediato
    void setAutoSuspendActive(boolean active); // "X" da caixa: false só na sessão
}
