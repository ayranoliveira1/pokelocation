package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.model.Folder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Escolhe a pasta de destino ("None (Root)" + árvore), excluindo as subárvores de excludeIds. */
public final class FolderPicker {
    public interface Callback { void onPick(String folderIdOrNull); }   // null = "None (Root)"

    private FolderPicker() {}

    public static PanelHost show(Context c, String type, Collection<String> excludeIds, Callback cb) {
        View v = LayoutInflater.from(c).inflate(R.layout.panel_list, null);
        ((TextView) v.findViewById(R.id.panel_title)).setText(R.string.select_folder);
        ListView list = v.findViewById(R.id.panel_list);
        List<String> ids = new ArrayList<>();
        ids.add(null);
        ArrayAdapter<String> ad = new ArrayAdapter<>(c, R.layout.panel_option_row, new ArrayList<>());
        ad.add(c.getString(R.string.none_root));
        list.setAdapter(ad);
        PanelHost[] host = new PanelHost[1];
        list.setOnItemClickListener((p, view, pos, id) -> {
            host[0].dismiss();
            if (cb != null) cb.onPick(ids.get(pos));
        });
        v.findViewById(R.id.panel_negative).setOnClickListener(x -> host[0].dismiss());
        Context app = c.getApplicationContext();
        Io.Task task = Io.run(() -> FolderDao.tree(app, type), tree -> {
            int skipDepth = -1;
            for (Folder f : tree) {
                if (skipDepth >= 0 && f.depth > skipDepth) continue;
                skipDepth = -1;
                if (excludeIds != null && excludeIds.contains(f.id)) {
                    skipDepth = f.depth;
                    continue;
                }
                ids.add(f.id);
                ad.add(NameFolderPanel.indent(f));
            }
            ad.notifyDataSetChanged();
        });
        host[0] = PanelHost.show(c, v, false);
        host[0].setOnDismiss(() -> {
            task.cancel();
            PanelHost.releaseDbIfOverlay();
        });
        return host[0];
    }
}
