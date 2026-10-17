package com.pokelocation.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;

import java.util.Collections;

/**
 * Atalho de conversa só para a notificação (30+). O excluído do launcher (setExcludedFromSurfaces) o sistema descarta
 * sem erro; por isso vai dinâmico e sai logo depois do notify: o NMS já o guardou em cache ao postar, e o launcher só
 * mostra os dinâmicos.
 */
public final class Shortcuts {
    private Shortcuts() {}

    /** true = o atalho existe agora e o setShortcutId vale. */
    public static boolean publish(Context c, String id, String label, Intent... intents) {
        if (Build.VERSION.SDK_INT < 30) return false;
        Intent[] view = new Intent[intents.length];
        for (int i = 0; i < intents.length; i++) view[i] = new Intent(intents[i]).setAction(Intent.ACTION_VIEW);
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            sm.pushDynamicShortcut(new ShortcutInfo.Builder(c, id).setShortLabel(label).setLongLived(true)
                    .setCategories(Collections.singleton(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
                    .setIntents(view).build());
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
}
