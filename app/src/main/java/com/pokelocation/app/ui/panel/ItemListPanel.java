package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.model.Folder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lista com filtro de pasta (opcional), filtro de texto em memória e linha extra atualizada a cada 1 s só nas linhas
 * visíveis (LineProvider), entre attach/detach. Toque → fecha e onPick. Vazio = "There are no items in your list!".
 */
public final class ItemListPanel {
    public static final String ALL = "\u0000all";       // item "All" do spinner de pasta

    public static final class Item {
        public final Object tag;
        public final CharSequence title, subtitle;

        public Item(Object tag, CharSequence title, CharSequence subtitle) {
            this.tag = tag;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    public interface Source { List<Item> load(String folderIdOrAll); }   // roda no Io

    public interface Callback {
        void onPick(Object tag);
        default void onAction() {}
    }

    public interface LineProvider {
        CharSequence line(Object tag, long now);
        int color(Object tag, long now);   // ARGB
    }

    private PanelHost host;
    private Io.Task task, folderTask;
    private final List<Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private String filter = "";

    private ItemListPanel() {}

    public static ItemListPanel show(Context c, CharSequence title, String folderTypeOrNull, Source source,
                                     Callback cb, CharSequence actionLabelOrNull, LineProvider linesOrNull) {
        ItemListPanel p = new ItemListPanel();
        View v = LayoutInflater.from(c).inflate(R.layout.panel_item_list, null);
        TextView t = v.findViewById(R.id.panel_title);
        t.setText(title);
        t.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        ListView list = v.findViewById(R.id.panel_list);
        View empty = v.findViewById(R.id.panel_empty), progress = v.findViewById(R.id.panel_progress);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return p.shown.size(); }
            @Override public Object getItem(int i) { return p.shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View row, ViewGroup parent) {
                if (row == null) row = LayoutInflater.from(c).inflate(R.layout.panel_item_row, parent, false);
                Item it = p.shown.get(i);
                ((TextView) row.findViewById(R.id.row_title)).setText(it.title);
                TextView sub = row.findViewById(R.id.row_subtitle);
                sub.setText(it.subtitle);
                sub.setVisibility(TextUtils.isEmpty(it.subtitle) ? View.GONE : View.VISIBLE);
                TextView line = row.findViewById(R.id.row_line);
                row.setTag(it);
                if (linesOrNull != null) bindLine(line, linesOrNull, it, System.currentTimeMillis());
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, row, pos, id) -> {
            Object tag = p.shown.get(pos).tag;
            p.dismiss();
            if (cb != null) cb.onPick(tag);
        });

        Runnable applyFilter = () -> {
            p.shown.clear();
            String f = p.filter.toLowerCase(Locale.ROOT);
            for (Item it : p.all) {
                if (f.isEmpty() || contains(it.title, f) || contains(it.subtitle, f)) p.shown.add(it);
            }
            adapter.notifyDataSetChanged();
            empty.setVisibility(p.shown.isEmpty() && progress.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        };
        ((EditText) v.findViewById(R.id.panel_filter)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                p.filter = s.toString().trim();
                applyFilter.run();
            }
        });

        java.util.function.Consumer<String> load = folder -> {
            if (p.task != null) p.task.cancel();
            progress.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            p.task = Io.run(() -> source.load(folder), items -> {
                p.all.clear();
                if (items != null) p.all.addAll(items);
                progress.setVisibility(View.GONE);
                applyFilter.run();
            });
        };

        Spinner spinner = v.findViewById(R.id.panel_folder);
        if (folderTypeOrNull == null) {
            spinner.setVisibility(View.GONE);
            load.accept(ALL);
        } else {
            List<String> ids = new ArrayList<>();
            ids.add(ALL);
            ids.add(null);
            ArrayAdapter<String> ad = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, new ArrayList<>());
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ad.add(c.getString(R.string.all));
            ad.add(c.getString(R.string.none_root));
            spinner.setAdapter(ad);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { load.accept(ids.get(pos)); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            Context app = c.getApplicationContext();
            p.folderTask = Io.run(() -> FolderDao.tree(app, folderTypeOrNull), tree -> {
                for (Folder f : tree) {
                    ids.add(f.id);
                    ad.add(NameFolderPanel.indent(f));
                }
                ad.notifyDataSetChanged();
            });
        }

        Button action = v.findViewById(R.id.panel_action);
        if (actionLabelOrNull != null) {
            action.setVisibility(View.VISIBLE);
            action.setText(actionLabelOrNull);
            action.setOnClickListener(x -> {
                p.dismiss();
                if (cb != null) cb.onAction();
            });
        }
        v.findViewById(R.id.panel_negative).setOnClickListener(x -> p.dismiss());

        if (linesOrNull != null) {
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View row = list.getChildAt(i);
                        if (row.getTag() instanceof Item) bindLine(row.findViewById(R.id.row_line), linesOrNull, (Item) row.getTag(), now);
                    }
                    Io.main().postDelayed(this, 1000);
                }
            };
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View x) { Io.main().postDelayed(tick, 1000); }
                @Override public void onViewDetachedFromWindow(View x) { Io.main().removeCallbacks(tick); }
            });
        }

        p.host = PanelHost.show(c, v, true);
        p.host.setOnDismiss(() -> {
            if (p.task != null) p.task.cancel();
            if (p.folderTask != null) p.folderTask.cancel();
            p.all.clear();
            p.shown.clear();
            PanelHost.releaseDbIfOverlay();
        });
        return p;
    }

    private static void bindLine(TextView line, LineProvider lp, Item it, long now) {
        CharSequence s = lp.line(it.tag, now);
        if (TextUtils.isEmpty(s)) {
            line.setVisibility(View.GONE);
            return;
        }
        line.setVisibility(View.VISIBLE);
        line.setText(s);
        line.setTextColor(lp.color(it.tag, now));
    }

    private static boolean contains(CharSequence s, String lowerNeedle) {
        return s != null && s.toString().toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    public void dismiss() {
        if (host != null) host.dismiss();
    }
}
