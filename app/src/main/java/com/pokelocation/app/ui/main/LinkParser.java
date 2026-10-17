package com.pokelocation.app.ui.main;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrai coordenadas de links externos. Java puro: testável na JVM. */
public final class LinkParser {
    private LinkParser() {}

    private static final String N = "([-+]?[0-9]*\\.?[0-9]*)";   // grupos de R1–R3 (podem vir vazios)
    private static final String D = "([-+]?[0-9]+\\.?[0-9]*)";   // número com ao menos um dígito
    private static final Pattern R1 = Pattern.compile(".*addr=" + N + "," + N + "?.*");
    private static final Pattern R2 = Pattern.compile(".*loc:" + N + "," + N + "?.*");
    private static final Pattern R3 = Pattern.compile(".*q=" + N + "," + N + "?.*");
    private static final Pattern R4 = Pattern.compile(D + "[,;][ ]*" + D);
    private static final Pattern YANDEX = Pattern.compile("[?&](?:ll|pt|whatshere\\[point\\])=" + D + "," + D);
    private static final Pattern MLAT = Pattern.compile("[?&]mlat=" + D);
    private static final Pattern MLON = Pattern.compile("[?&]mlon=" + D);
    private static final Pattern OSM_MAP = Pattern.compile("#map=[0-9.]+/" + D + "/" + D);
    private static final Pattern POINTX = Pattern.compile("[?&]pointx=" + D);   // QQ: pointx = lng
    private static final Pattern POINTY = Pattern.compile("[?&]pointy=" + D);   // QQ: pointy = lat

    /** true se o link aponta para um arquivo .gpx/.kml (ignora query e fragmento). */
    public static boolean isTrackFile(String dataString) {
        if (dataString == null) return false;
        String s = dataString;
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        s = s.toLowerCase(Locale.ROOT);
        return s.endsWith(".gpx") || s.endsWith(".kml");
    }

    /** Preenche out2 = {lat, lng}. false se não achar coordenada válida. */
    public static boolean parse(String dataString, double[] out2) {
        if (dataString == null) return false;
        String s = decode(dataString);
        String low = s.toLowerCase(Locale.ROOT);
        // Yandex usa lng,lat em ll/pt.
        if (low.contains("yandex.") && first(YANDEX, s, out2, true)) return true;
        Matcher la = MLAT.matcher(s), lo = MLON.matcher(s);
        if (la.find() && lo.find() && set(la.group(1), lo.group(1), out2)) return true;
        if (first(OSM_MAP, s, out2, false)) return true;
        Matcher px = POINTX.matcher(s), py = POINTY.matcher(s);
        if (px.find() && py.find() && set(py.group(1), px.group(1), out2)) return true;
        String key = low.contains("addr=") ? "addr=" : low.contains("loc:") ? "loc:" : low.contains("q=") ? "q=" : null;
        if (key == null) return first(R4, s, out2, false);
        if (first(key.equals("addr=") ? R1 : key.equals("loc:") ? R2 : R3, s, out2, false)) return true;
        // R1–R3 falharam (ex.: Apple "?ll=lat,lng&q=Nome") → par genérico só ANTES da chave,
        // para não ler números de um endereço em texto ("q=Rua Augusta 12, 3").
        return first(R4, s.substring(0, low.lastIndexOf(key)), out2, false);
    }

    private static boolean first(Pattern p, String s, double[] out2, boolean swap) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            if (swap ? set(m.group(2), m.group(1), out2) : set(m.group(1), m.group(2), out2)) return true;
        }
        return false;
    }

    private static boolean set(String a, String b, double[] out2) {
        if (a == null || b == null) return false;
        double lat, lng;
        try {
            lat = Double.parseDouble(a);
            lng = Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
        // 0,0 = "geo:0,0?q=endereço" (busca por texto), nunca um destino real.
        if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180) || (lat == 0 && lng == 0)) return false;
        out2[0] = lat;
        out2[1] = lng;
        return true;
    }

    /** %xx decodificado uma vez; "+" preservado (é sinal de número, não espaço). */
    private static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
