package com.pokelocation.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Atalho de conversa só para a notificação (30+). O excluído do launcher (setExcludedFromSurfaces) o sistema descarta
 * sem erro; por isso vai dinâmico e sai logo depois do notify: o NMS já o guardou em cache ao postar, e o launcher só
 * mostra os dinâmicos.
 */
public final class Shortcuts {
    private Shortcuts() {}

    /** true = o atalho existe agora e o setShortcutId vale. Sem icon, o SystemUI usa o ícone do remetente. */
    public static boolean publish(Context c, String id, String label, Icon icon, Intent... intents) {
        if (Build.VERSION.SDK_INT < 30) return false;
        Intent[] view = new Intent[intents.length];
        for (int i = 0; i < intents.length; i++) view[i] = new Intent(intents[i]).setAction(Intent.ACTION_VIEW);
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            ShortcutInfo.Builder info = new ShortcutInfo.Builder(c, id).setShortLabel(label).setLongLived(true)
                    .setCategories(Collections.singleton(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
                    .setIntents(view);
            if (icon != null) info.setIcon(icon);
            sm.pushDynamicShortcut(info.build());
            for (ShortcutInfo s : sm.getDynamicShortcuts()) if (s.getId().equals(id)) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public static void release(Context c, String id) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            c.getSystemService(ShortcutManager.class).removeDynamicShortcuts(Collections.singletonList(id));
        } catch (RuntimeException ignored) {
        }
    }

    /** Tira os atalhos com o prefixo que o NMS guardou e que nenhuma notificação à vista (keep) usa mais. */
    public static void prune(Context c, String prefix, Set<String> keep) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            List<String> ids = new ArrayList<>();
            for (ShortcutInfo s : sm.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_CACHED))
                ids.add(s.getId());
            ids = stale(ids, prefix, keep);
            if (!ids.isEmpty()) sm.removeLongLivedShortcuts(ids);
        } catch (RuntimeException ignored) {
        }
    }

    static List<String> stale(List<String> ids, String prefix, Set<String> keep) {
        List<String> out = new ArrayList<>();
        for (String id : ids) if (id.startsWith(prefix) && !keep.contains(id)) out.add(id);
        return out;
    }
}
