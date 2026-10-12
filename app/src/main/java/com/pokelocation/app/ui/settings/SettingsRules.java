package com.pokelocation.app.ui.settings;

/** Lógica pura dos campos numéricos da Settings (testável na JVM). */
final class SettingsRules {
    private SettingsRules() {}

    /** Número digitado; vírgula vale como ponto. NaN se vazio, não numérico, NaN ou infinito (entrada ignorada). */
    static double parse(String s) {
        if (s == null) return Double.NaN;
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
