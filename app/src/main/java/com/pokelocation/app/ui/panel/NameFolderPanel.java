package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.model.Folder;

import java.util.ArrayList;
import java.util.List;

/** Nome + (opcional) pasta. Save só com trim(name) ≠ "". */
public final class NameFolderPanel {
    public interface Callback { void onSave(String name, String folderId); }

    private NameFolderPanel() {}

    /** folderTypeOrNull = Folder.FAVORITES/ROUTES mostra o spinner "None (Root)" + árvore indentada. */
    public static PanelHost show(Context c, CharSequence title, String initialName,
                                 String folderTypeOrNull, String initialFolderId, Callback cb) {
        View v = LayoutInflater.from(c).inflate(R.layout.panel_name_folder, null);
        TextView t = v.findViewById(R.id.panel_title);
        t.setText(title);
        t.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        EditText name = v.findViewById(R.id.panel_name);
        Button save = v.findViewById(R.id.panel_positive);
        Spinner spinner = v.findViewById(R.id.panel_folder);
        List<String> ids = new ArrayList<>();
        ids.add(null);
        if (initialName != null) {
            name.setText(initialName);
            name.setSelection(0, initialName.length());
        }
        save.setEnabled(name.getText().toString().trim().length() > 0);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) { save.setEnabled(s.toString().trim().length() > 0); }
        });
        PanelHost[] host = new PanelHost[1];
        Io.Task[] task = new Io.Task[1];
        if (folderTypeOrNull == null) {
            v.findViewById(R.id.panel_folder_label).setVisibility(View.GONE);
            spinner.setVisibility(View.GONE);
        } else {
            ArrayAdapter<String> ad = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, new ArrayList<>());
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ad.add(c.getString(R.string.none_root));
            spinner.setAdapter(ad);
            Context app = c.getApplicationContext();
            task[0] = Io.run(() -> FolderDao.tree(app, folderTypeOrNull), tree -> {
                int sel = 0;
                for (Folder f : tree) {
                    ids.add(f.id);
                    ad.add(indent(f));
                    if (f.id.equals(initialFolderId)) sel = ids.size() - 1;
                }
                ad.notifyDataSetChanged();
                spinner.setSelection(sel);
            });
        }
        save.setOnClickListener(x -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) return;
            int pos = spinner.getVisibility() == View.VISIBLE ? spinner.getSelectedItemPosition() : 0;
            String folder = pos >= 0 && pos < ids.size() ? ids.get(pos) : null;
            host[0].dismiss();
            if (cb != null) cb.onSave(n, folder);
        });
        v.findViewById(R.id.panel_negative).setOnClickListener(x -> host[0].dismiss());
        host[0] = PanelHost.show(c, v, true);
        host[0].setOnDismiss(() -> {
            if (task[0] != null) task[0].cancel();
            if (folderTypeOrNull != null) PanelHost.releaseDbIfOverlay();
        });
        name.requestFocus();
        return host[0];
    }

    /** "    " × depth + nome. */
    static String indent(Folder f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.depth; i++) sb.append("    ");
        return sb.append(f.name).toString();
    }
}
