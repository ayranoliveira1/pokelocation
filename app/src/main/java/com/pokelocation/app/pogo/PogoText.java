package com.pokelocation.app.pogo;

import android.content.Context;

import com.pokelocation.app.R;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Textos de um Pokémon para o mapa e o painel do joystick (sem osmdroid: o :overlay usa sem carregar o mapa). */
final class PogoText {
    private PogoText() {}

    /** "♂ · IV 98% · PC 1234 · Nv 30": a parte da linha que não muda (o tempo entra a cada tick, sem formatar). */
    static String fixedStats(Context c, Pogo.Mon m) {
        StringBuilder sb = new StringBuilder();
        if ("Male".equals(m.gender)) sb.append("♂");
        else if ("Female".equals(m.gender)) sb.append("♀");
        if (m.iv >= 0) dot(sb).append(c.getString(R.string.pogo_map_iv, (int) m.iv));
        if (m.cp > 0) dot(sb).append(c.getString(R.string.pogo_map_cp, m.cp));
        if (m.level > 0) dot(sb).append(c.getString(R.string.pogo_map_level, level(m.level)));
        return sb.toString();
    }

    /** "PC 492 · Nv 23": a linha de números da lista (o IV vai na pílula, o tempo entra a cada tick). */
    static String cpLevel(Context c, Pogo.Mon m) {
        StringBuilder sb = new StringBuilder();
        if (m.cp > 0) sb.append(c.getString(R.string.pogo_map_cp, m.cp));
        if (m.level > 0) dot(sb).append(c.getString(R.string.pogo_map_level, level(m.level)));
        return sb.toString();
    }

    /** A pílula é um selo com texto: vermelho mais escuro que o halo (branco ~5,6:1) e laranja com texto escuro (~8:1). */
    static final int PILL_RED = 0xFFC62828, PILL_ORANGE = 0xFFFB8C00;

    /** Fundo da pílula de IV da lista (igual nos dois temas); 0 = neutro. */
    static int ivPill(Pogo.Mon m) {
        return m.iv >= 100 ? PILL_RED : m.iv >= 90 ? PILL_ORANGE : 0;
    }

    /** {antes, depois} do %s de uma string de recurso: lida uma vez, o tick só concatena (getString com args formata). */
    static String[] around(Context c, int res) {
        String raw = c.getString(res);
        int i = raw.indexOf("%s");
        return i < 0 ? new String[]{raw + " ", ""} : new String[]{raw.substring(0, i), raw.substring(i + 2)};
    }

    static StringBuilder dot(StringBuilder sb) {
        return sb.length() == 0 ? sb : sb.append(" · ");
    }

    /** Nome + ✨/🏞️ + (forma, costume), como o card do site. */
    static String title(Pogo.Mon m) {
        StringBuilder sb = new StringBuilder(m.name != null ? m.name : "#" + m.id);
        if (m.shiny) sb.append(" ✨");
        if (m.background) sb.append(" 🏞️");
        String fl = form(m);
        boolean form = fl != null;
        String costume = m.costume == null ? null : pretty(m.costume.replace("_NOEVOLVE", ""));
        if (form || costume != null) {
            sb.append(" (");
            if (form) sb.append(fl);
            if (form && costume != null) sb.append(", ");
            if (costume != null) sb.append(costume);
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * O lookup manda o rótulo ("Alola"), o /search às vezes o enum cru ("BULBASAUR_NORMAL", "ALOLA"): tira a espécie
     * do começo, some com Normal/sem forma e põe o enum em Title Case.
     */
    static String form(Pogo.Mon m) {
        String f = m.form;
        if (f == null || f.isEmpty()) return null;
        String sp = m.name == null ? null : m.name.toUpperCase(Locale.ROOT).replace(' ', '_') + "_";
        if (sp != null && f.toUpperCase(Locale.ROOT).startsWith(sp)) f = f.substring(sp.length());
        if (f.isEmpty() || f.equalsIgnoreCase("NORMAL") || f.equalsIgnoreCase("FORM_UNSET")) return null;
        return f.equals(f.toUpperCase(Locale.ROOT)) ? pretty(f) : f;
    }

    /** "#522 · ♂ · M". */
    static String sub(Pogo.Mon m) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "#%03d", m.id));
        String g = "Male".equals(m.gender) ? "♂" : "Female".equals(m.gender) ? "♀" : "Genderless".equals(m.gender) ? "⚲" : null;
        if (g != null) sb.append(" · ").append(g);
        if (m.size != null && !m.size.isEmpty()) sb.append(" · ").append(m.size);
        return sb.toString();
    }

    /** "Liga Grande #3 · Charizard" (a evolução só quando difere); null sem PvP. */
    static String pvp(Context c, Pogo.Mon m) {
        int res = pvpRes(m);
        return res == 0 ? null : withEvolution(c.getString(res, m.pvpRank), m);
    }

    static int pvpRes(Pogo.Mon m) {
        if (m.pvpRank <= 0 || m.pvpLeague == null) return 0;
        return "ultra".equals(m.pvpLeague) ? R.string.pogo_card_pvp_ultra : R.string.pogo_card_pvp_great;
    }

    static String withEvolution(String s, Pogo.Mon m) {
        return m.pvpName != null && !m.pvpName.equals(m.name) ? s + " · " + m.pvpName : s;
    }

    /** Cor do brilho do marcador e do chip de IV, como o site; 0 = sem brilho. */
    static final int RED = 0xFFEF2B3D, ORANGE = 0xFFF5A623;

    static int glow(Pogo.Mon m) {
        if (m.iv >= 100) return RED;
        if (m.iv >= 90) return ORANGE;
        if (m.iv == 0) return 0xFF9CA3AF;
        if ("XXL".equals(m.size)) return 0xFF2B7FFF;
        if ("XXS".equals(m.size)) return 0xFF22C55E;
        return 0;
    }

    static String pretty(String e) {
        StringBuilder sb = new StringBuilder();
        for (String w : e.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Busca falhou com Pokémon da anterior ainda vivos na tela: eles ficam, e o erro vira um aviso na contagem. */
    static String stale(Context c, Throwable t) {
        return c.getString(R.string.pogo_map_stale, error(c, t));
    }

    static String count(Context c, int n, PogoFilter f, boolean world) {
        return c.getString(countRes(n, f, world), n);
    }

    /** Com filtro ativo, o vazio não é "nada nesta área": é nada que passe nos filtros. */
    static int countRes(int n, PogoFilter f, boolean world) {
        boolean on = filtered(f);
        if (n > 0) {
            if (world) return on ? R.string.pogo_map_count_world_filtered : R.string.pogo_map_count_world;
            return on ? R.string.pogo_map_count_filtered : R.string.pogo_map_count;
        }
        if (!on) return world ? R.string.pogo_map_none_world : R.string.pogo_map_none;
        return f.shinyOnly ? R.string.pogo_map_none_shiny : R.string.pogo_map_none_filtered;
    }

    /** O filtro como o servidor o recebe: sem login ou sem treinador ativo o Pogo tira os de shiny (stripShiny). */
    static PogoFilter asSent(Context c, PogoFilter f) {
        if (!Pogo.loggedIn(c) || Pogo.trainers(c).length() == 0) {
            f.shinyOnly = false;
            f.backgroundOnly = false;
            f.shinyAccount = null;
        }
        return f;
    }

    /** A mesma regra do badge (PogoFilter.activeCount): com o padrão Mundo, o "Perto" também conta. */
    static boolean filtered(PogoFilter f) {
        return f != null && f.activeCount() > 0;
    }

    static String error(Context c, Throwable t) {
        if (Pogo.slow(t)) return c.getString(R.string.pogo_slow);
        if (Pogo.unavailable(t)) return c.getString(R.string.pogo_unavailable);
        if (t instanceof Pogo.Error) {
            Pogo.Error e = (Pogo.Error) t;
            if (e.http == 404 || e.http == 410) return c.getString(R.string.pogo_map_gone);
            if (e.http == 429) return c.getString(R.string.pogo_map_wait, Math.max(1, e.retryAfterS));
            if (e.message != null) return e.message;
        }
        if (offline(t)) return c.getString(R.string.soc_err_network);
        return c.getString(R.string.unknown_error);
    }

    /** Sem chegar ao nosso servidor (sem rede, DNS, recusado). O timeout fica fora: no mapa ele é o PoGo lento. */
    static boolean offline(Throwable t) {
        return t instanceof SocketException || t instanceof UnknownHostException;
    }

    /** despawnMs 0 = desconhecido: não some. */
    static boolean gone(long despawnMs, long now) {
        return despawnMs > 0 && despawnMs <= now;
    }

    static int minutesLeft(long expiresMs, long now) {
        if (expiresMs == 0) return -1;
        return (int) ((Math.max(0, expiresMs - now) + 59_999) / 60_000);
    }

    static String level(double l) {
        return l == Math.floor(l) ? String.valueOf((long) l) : String.valueOf(l);
    }

    static String clock(long ms) {
        return clock(new StringBuilder(8), ms).toString();
    }

    /** "m:ss" sem String.format: roda a cada tick de 1 s por linha visível e no card. */
    static StringBuilder clock(StringBuilder sb, long ms) {
        long s = Math.max(0, ms) / 1000, sec = s % 60;
        sb.append(s / 60).append(':');
        if (sec < 10) sb.append('0');
        return sb.append(sec);
    }
}
