package com.pokelocation.app.ui.search;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.net.Elevation;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Busca de endereço (F21, 05 §3.8): debounce 500 ms + token (respostas velhas descartadas), query repetida ignorada,
 * até 5 resultados. Geocoder primeiro; fallback Nominatim (≥ 1 s entre chamadas). Toque → altitude (cancelável) → resultado.
 * Resultado: name, address, lat, lng, alt (§3.12).
 */
public class SearchActivity extends BaseActivity {
    private static final int MAX = 5;
    private static final long DEBOUNCE_MS = 500, NOMINATIM_GAP_MS = 1000;
    private static final int MAX_BODY = 64 * 1024;
    private static long lastNominatimAt;   // elapsedRealtime da última chamada (1 req/s por processo)

    private Context app;
    private StateView state;
    private Adapter adapter;
    private SearchView searchView;
    private String pending = "", lastQuery;
    private int token;
    private Io.Task task, altTask;

    private final Runnable debounce = this::runQuery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_activity);
        app = getApplicationContext();
        Toolbar tb = setupToolbar("", true);
        tb.setNavigationOnClickListener(v -> finish());
        applyInsets(findViewById(R.id.search_root));

        state = findViewById(R.id.search_state);
        state.setState(StateView.CONTENT);
        RecyclerView list = findViewById(R.id.search_list);
        list.setHasFixedSize(true);
        adapter = new Adapter(LayoutInflater.from(this));
        list.setAdapter(adapter);

        searchView = findViewById(R.id.search_view);
        searchView.setIconified(false);
        searchView.setMaxWidth(Integer.MAX_VALUE);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) {
                pending = q;
                Io.main().removeCallbacks(debounce);
                runQuery();
                searchView.clearFocus();
                return true;
            }

            @Override public boolean onQueryTextChange(String q) {
                pending = q;
                Io.main().removeCallbacks(debounce);
                Io.main().postDelayed(debounce, DEBOUNCE_MS);
                return true;
            }
        });
        searchView.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    @Override
    protected void onDestroy() {
        token++;
        Io.main().removeCallbacks(debounce);
        if (task != null) task.cancel();
        if (altTask != null) altTask.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ busca

    private void runQuery() {
        String q = pending == null ? "" : pending.trim();
        if (q.isEmpty()) {
            token++;
            lastQuery = null;
            if (task != null) task.cancel();
            adapter.set(Collections.emptyList());
            state.setState(StateView.CONTENT);
            return;
        }
        if (q.equals(lastQuery)) return;       // distinctUntilChanged
        lastQuery = q;
        final int my = ++token;
        if (task != null) task.cancel();
        state.setState(StateView.LOADING);
        if (!Geocoder.isPresent()) {
            nominatim(q, my);
        } else if (Build.VERSION.SDK_INT >= 33) {
            try {
                new Geocoder(app).getFromLocationName(q, MAX, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(@NonNull List<Address> list) {
                        List<Favorite> r = fromAddresses(list);
                        Io.main().post(() -> { if (my == token) geocoded(q, my, r); });
                    }

                    @Override public void onError(String msg) {
                        Io.main().post(() -> { if (my == token) nominatim(q, my); });
                    }
                });
            } catch (RuntimeException e) {
                nominatim(q, my);
            }
        } else {
            task = Io.run(() -> {
                try {
                    @SuppressWarnings("deprecation")
                    List<Address> l = new Geocoder(app).getFromLocationName(q, MAX);
                    return fromAddresses(l);
                } catch (IOException e) {
                    return Collections.<Favorite>emptyList();
                }
            }, new Io.Callback<List<Favorite>>() {
                @Override public void onResult(List<Favorite> r) { if (my == token) geocoded(q, my, r); }

                @Override public void onError(Throwable t) { if (my == token) nominatim(q, my); }
            });
        }
    }

    private void geocoded(String q, int my, List<Favorite> r) {
        if (r.isEmpty()) nominatim(q, my);
        else deliver(my, r);
    }

    /** Fallback Nominatim respeitando 1 req/s: adiado no Handler, sem sleep. */
    private void nominatim(String q, int my) {
        if (my != token || isDestroyed()) return;
        long wait = lastNominatimAt + NOMINATIM_GAP_MS - SystemClock.elapsedRealtime();
        if (lastNominatimAt != 0 && wait > 0) {
            Io.main().postDelayed(() -> nominatim(q, my), wait);
            return;
        }
        lastNominatimAt = SystemClock.elapsedRealtime();
        task = Io.run(() -> nominatimBlocking(app, q), new Io.Callback<List<Favorite>>() {
            @Override public void onResult(List<Favorite> r) { deliver(my, r); }

            @Override public void onError(Throwable t) { deliver(my, Collections.emptyList()); }
        });
    }

    private void deliver(int my, List<Favorite> r) {
        if (my != token || isDestroyed()) return;
        adapter.set(r);
        state.setState(StateView.CONTENT);
    }

    private static List<Favorite> fromAddresses(List<Address> list) {
        if (list == null) return Collections.emptyList();
        ArrayList<Favorite> out = new ArrayList<>(Math.min(list.size(), MAX));
        for (Address a : list) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                String line = a.getAddressLine(i);
                if (line == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            String address = sb.toString();
            String name = firstSegment(address);
            if (name.isEmpty() && a.getFeatureName() != null) name = a.getFeatureName();
            out.add(place(name, address, a.getLatitude(), a.getLongitude()));
            if (out.size() == MAX) break;
        }
        return out;
    }

    /** BLOQUEANTE (Io). Nominatim jsonv2, UA = packageName, resposta limitada a 64 KB. */
    private static List<Favorite> nominatimBlocking(Context c, String q) throws Exception {
        URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=0&limit=" + MAX
                + "&q=" + URLEncoder.encode(q, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", c.getPackageName());
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return Collections.emptyList();
            JSONArray arr = new JSONArray(read(conn.getInputStream()));
            ArrayList<Favorite> out = new ArrayList<>(Math.min(arr.length(), MAX));
            for (int i = 0; i < arr.length() && out.size() < MAX; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double lat, lng;
                try {
                    lat = Double.parseDouble(o.optString("lat"));
                    lng = Double.parseDouble(o.optString("lon"));
                } catch (NumberFormatException e) {
                    continue;
                }
                String display = o.optString("display_name", "");
                out.add(place(firstSegment(display), display, lat, lng));
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bo.size() + n > MAX_BODY) throw new IOException("response too large");
                bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        }
    }

    private static String firstSegment(String s) {
        if (s == null) return "";
        int i = s.indexOf(',');
        int nl = s.indexOf('\n');
        if (nl >= 0 && (i < 0 || nl < i)) i = nl;
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    private static Favorite place(String name, String address, double lat, double lng) {
        if (name == null || name.isEmpty()) name = CoordParser.text(lat, lng);
        return new Favorite("", name, address == null ? "" : address, lat, lng, 0, 0, null);
    }

    // ------------------------------------------------------------------ escolha

    private void pick(Favorite p) {
        if (altTask != null) altTask.cancel();
        altTask = altitude(this, p.lat, p.lng, alt -> {
            Intent d = new Intent()
                    .putExtra(Intents.EXTRA_NAME, p.name).putExtra(Intents.EXTRA_ADDRESS, p.address)
                    .putExtra(Intents.EXTRA_LAT, p.lat).putExtra(Intents.EXTRA_LNG, p.lng)
                    .putExtra(Intents.EXTRA_ALT, alt);
            setResult(RESULT_OK, d);
            finish();
        });
    }

    /**
     * Diálogo "Retrieving altitude…" cancelável + Elevation.fetch no Io (também usado pela FavoritesActivity).
     * done recebe a altitude (0 se cancelado, desligado ou falha). Cancele a Task devolvida no onDestroy (null se a elevação está desligada).
     */
    public static Io.Task altitude(Activity a, double lat, double lng, DoubleConsumer done) {
        if (!Prefs.getBool(a, Prefs.ELEVATION_LOOKUP_ENABLED)) {   // elevação desligada: sem diálogo
            done.accept(0);
            return null;
        }
        View v = a.getLayoutInflater().inflate(R.layout.fav_progress, null);
        AlertDialog d = new MaterialAlertDialogBuilder(a).setView(v).setCancelable(true).create();
        Context app = a.getApplicationContext();
        Io.Task t = Io.run(() -> Elevation.fetch(app, lat, lng), new Io.Callback<Double>() {
            @Override public void onResult(Double e) { finish(e == null || e.isNaN() ? 0 : e); }

            @Override public void onError(Throwable err) { finish(0); }

            private void finish(double alt) {
                if (a.isFinishing() || a.isDestroyed() || !d.isShowing()) return;
                d.dismiss();
                done.accept(alt);
            }
        });
        d.setOnCancelListener(x -> {
            t.cancel();
            done.accept(0);
        });
        d.show();
        return t;
    }

    // ------------------------------------------------------------------ lista

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final LayoutInflater inflater;
        private final ArrayList<Favorite> items = new ArrayList<>(MAX);

        Adapter(LayoutInflater inflater) { this.inflater = inflater; }

        @SuppressWarnings("NotifyDataSetChanged")
        void set(List<Favorite> r) {
            items.clear();
            items.addAll(r);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new VH(inflater.inflate(R.layout.search_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Favorite p = items.get(pos);
            h.name.setText(p.name);
            h.address.setText(p.address);
            h.address.setVisibility(p.address.isEmpty() ? View.GONE : View.VISIBLE);
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name, address;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.search_name);
                address = v.findViewById(R.id.search_address);
                v.setOnClickListener(x -> {
                    int p = getBindingAdapterPosition();
                    if (p >= 0 && p < items.size()) pick(items.get(p));
                });
            }
        }
    }
}
