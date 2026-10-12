package com.pokelocation.app.gpx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Agrupamento por pasta para as listas de import/export (06 §3.9). Lógica pura (sem android.*).
 * Lista plana = para cada pasta (DFS, ordem de aparição): [Header] + itens da pasta + subpastas; depois os itens sem pasta.
 * Nível: Header = profundidade − 1 (0-based); item = profundidade da sua pasta (0 sem pasta).
 */
public final class FolderTree {

    public static final class Header {
        public final String key, path, title;
        public final int level;
        public List<Object> hidden;   // != null = recolhido (itens tirados da lista ficam aqui)
        public int count;             // usado pelos cabeçalhos de seção do export

        public Header(String key, String path, String title, int level) {
            this.key = key;
            this.path = path;
            this.title = title;
            this.level = level;
        }
    }

    private FolderTree() {}

    /** "  A / /B " → "A/B"; null se vazio. Remove \n \r \t. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (String seg : raw.split("/")) {
            String s = seg.replace("\n", "").replace("\r", "").replace("\t", "").trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Nº de segmentos de um caminho normalizado (0 para null). */
    public static int depth(String path) {
        if (path == null) return 0;
        int d = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') d++;
        return d;
    }

    private static final class Node {
        final Header h;
        final ArrayList<Object> items = new ArrayList<>();
        final LinkedHashMap<String, Node> children = new LinkedHashMap<>();
        Node(Header h) { this.h = h; }
    }

    /**
     * Monta a lista plana. folderOf devolve o caminho normalizado do item (ou null).
     * Sem nenhuma pasta → os próprios itens, sem cabeçalhos. keyPrefix separa chaves de seções diferentes.
     */
    public static <T> ArrayList<Object> flatten(List<T> items, Function<T, String> folderOf, String keyPrefix) {
        LinkedHashMap<String, Node> roots = new LinkedHashMap<>();
        LinkedHashMap<String, Node> all = new LinkedHashMap<>();
        ArrayList<Object> noFolder = new ArrayList<>();
        for (T it : items) {
            String path = folderOf.apply(it);
            if (path == null) {
                noFolder.add(it);
                continue;
            }
            Node n = all.get(path);
            if (n == null) n = node(path, roots, all, keyPrefix);
            n.items.add(it);
        }
        if (all.isEmpty()) return noFolder;
        ArrayList<Object> out = new ArrayList<>(items.size() + all.size());
        for (Node n : roots.values()) emit(n, out);
        out.addAll(noFolder);
        return out;
    }

    private static Node node(String path, LinkedHashMap<String, Node> roots, LinkedHashMap<String, Node> all, String keyPrefix) {
        Node parent = null;
        int from = 0;
        while (true) {
            int slash = path.indexOf('/', from);
            String sub = slash < 0 ? path : path.substring(0, slash);
            Node n = all.get(sub);
            if (n == null) {
                String title = path.substring(from, slash < 0 ? path.length() : slash);
                n = new Node(new Header(keyPrefix + sub, sub, title, depth(sub) - 1));
                all.put(sub, n);
                (parent == null ? roots : parent.children).put(sub, n);
            }
            if (slash < 0) return n;
            parent = n;
            from = slash + 1;
        }
    }

    private static void emit(Node n, ArrayList<Object> out) {
        out.add(n.h);
        out.addAll(n.items);
        for (Node c : n.children.values()) emit(c, out);
    }
}
