package android.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Só para os testes JVM: a mesma API de leitura em fluxo do android.util.JsonReader, sobre o org.json (dependência de
 * teste). Achata o documento em tokens; não mede alocação de produção.
 */
public final class JsonReader implements Closeable {
    private final List<Object[]> tokens = new ArrayList<>();
    private int pos;

    public JsonReader(Reader in) {
        try {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[4096];
            int r;
            while ((r = in.read(buf)) > 0) b.append(buf, 0, r);
            flatten(new JSONTokener(b.toString()).nextValue());
        } catch (IOException | JSONException e) {
            tokens.clear();
            tokens.add(new Object[]{e, e}); // peek() vira IOException, como o MalformedJsonException
        }
        tokens.add(new Object[]{JsonToken.END_DOCUMENT, null});
    }

    private void flatten(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            tokens.add(new Object[]{JsonToken.BEGIN_OBJECT, null});
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                tokens.add(new Object[]{JsonToken.NAME, k});
                flatten(o.opt(k));
            }
            tokens.add(new Object[]{JsonToken.END_OBJECT, null});
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            tokens.add(new Object[]{JsonToken.BEGIN_ARRAY, null});
            for (int i = 0; i < a.length(); i++) flatten(a.opt(i));
            tokens.add(new Object[]{JsonToken.END_ARRAY, null});
        } else if (v == null || JSONObject.NULL.equals(v)) {
            tokens.add(new Object[]{JsonToken.NULL, null});
        } else if (v instanceof Boolean) {
            tokens.add(new Object[]{JsonToken.BOOLEAN, v});
        } else if (v instanceof Number) {
            tokens.add(new Object[]{JsonToken.NUMBER, v.toString()});
        } else {
            tokens.add(new Object[]{JsonToken.STRING, v.toString()});
        }
    }

    public JsonToken peek() throws IOException {
        Object t = tokens.get(pos)[0];
        if (!(t instanceof JsonToken)) throw new IOException("malformed");
        return (JsonToken) t;
    }

    private Object take(JsonToken want) throws IOException {
        if (peek() != want) throw new IllegalStateException("expected " + want + " but was " + peek());
        return tokens.get(pos++)[1];
    }

    public void beginObject() throws IOException { take(JsonToken.BEGIN_OBJECT); }
    public void endObject() throws IOException { take(JsonToken.END_OBJECT); }
    public void beginArray() throws IOException { take(JsonToken.BEGIN_ARRAY); }
    public void endArray() throws IOException { take(JsonToken.END_ARRAY); }

    public boolean hasNext() throws IOException {
        JsonToken t = peek();
        return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != JsonToken.END_DOCUMENT;
    }

    public String nextName() throws IOException { return (String) take(JsonToken.NAME); }

    public String nextString() throws IOException {
        if (peek() == JsonToken.NUMBER) return (String) take(JsonToken.NUMBER);
        return (String) take(JsonToken.STRING);
    }

    public double nextDouble() throws IOException {
        return Double.parseDouble(peek() == JsonToken.STRING ? (String) take(JsonToken.STRING) : (String) take(JsonToken.NUMBER));
    }

    public long nextLong() throws IOException { return (long) nextDouble(); }
    public int nextInt() throws IOException { return (int) nextDouble(); }
    public boolean nextBoolean() throws IOException { return (Boolean) take(JsonToken.BOOLEAN); }
    public void nextNull() throws IOException { take(JsonToken.NULL); }

    public void skipValue() throws IOException {
        int depth = 0;
        do {
            JsonToken t = peek();
            if (t == JsonToken.BEGIN_OBJECT || t == JsonToken.BEGIN_ARRAY) depth++;
            else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) depth--;
            pos++;
        } while (depth > 0);
    }

    @Override public void close() {}
}
