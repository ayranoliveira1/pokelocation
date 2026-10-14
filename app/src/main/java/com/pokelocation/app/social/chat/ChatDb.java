package com.pokelocation.app.social.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.Envelope;
import com.pokelocation.app.social.store.SocialDb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversas e mensagens no social.db. Tudo dentro do SocialDb.atomic, sem rede. Título, preview e corpo selados pelo
 * Envelope com aad = a linha (um blob copiado para outra conversa/mensagem não abre).
 */
final class ChatDb {
    static final int PREVIEW_CHARS = 80, CONVERSATIONS_LIMIT = 200, MEMBERS_LIMIT = 60;
    private static final String MINE = "";
    private static final String CONV = "SELECT id, grp, title, preview, preview_type, last_ts, unread, my_live_until,"
            + " live_until FROM conversations";
    private static final String MSG = "SELECT id, client_msg_id, sender, type, state, ts, live_until, body, conv_id"
            + " FROM messages";

    private ChatDb() {}

    static String directId(String userId) { return "d:" + userId; }

    /** Cria se não existe; o título (label do amigo ou nome do grupo) é atualizado quando vem um novo. */
    static void ensureConversation(Context c, String id, boolean group, String peer, String title) throws GeneralSecurityException {
        ContentValues v = new ContentValues(4);
        v.put("id", id);
        v.put("grp", group ? 1 : 0);
        v.put("peer", peer);
        if (title != null) v.put("title", seal(c, title, "ct|" + id));
        if (db(c).insertWithOnConflict("conversations", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1 && title != null) {
            ContentValues t = new ContentValues(1);
            t.put("title", seal(c, title, "ct|" + id));
            db(c).update("conversations", t, "id = ?", new String[]{id});
        }
    }

    /** A lista toda de uma vez (o servidor manda os membros completos); o rótulo já vai pronto para exibir: "@nome". */
    static void setMembers(Context c, String convId, List<Api.Friend> members) {
        SQLiteDatabase db = db(c);
        db.delete("group_members", "conv_id = ?", new String[]{convId});
        for (Api.Friend m : members) {
            ContentValues v = new ContentValues(3);
            v.put("conv_id", convId);
            v.put("user_id", m.userId);
            v.put("label", m.username != null ? "@" + m.username : m.friendCode);
            db.insert("group_members", null, v);
        }
    }

    /** Os outros (sem mim); num 1:1, o amigo. */
    static List<Api.Friend> members(Context c, String convId) {
        List<Api.Friend> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery("SELECT user_id, label FROM group_members WHERE conv_id = ? LIMIT " + MEMBERS_LIMIT,
                new String[]{convId})) {
            while (q.moveToNext()) out.add(friend(q.getString(0), q.getString(1)));
        }
        if (!out.isEmpty()) return out;
        try (Cursor q = db(c).rawQuery("SELECT peer, title FROM conversations WHERE id = ? AND grp = 0", new String[]{convId})) {
            if (q.moveToFirst() && q.getString(0) != null) out.add(friend(q.getString(0), open(c, q.getBlob(1), "ct|" + convId)));
        }
        return out;
    }

    private static Api.Friend friend(String userId, String label) {
        Api.Friend f = new Api.Friend();
        f.userId = userId;
        f.label = label;
        f.username = label != null && label.startsWith("@") ? label.substring(1) : null;
        return f;
    }

    /** -1 se já existia (dedup por remetente + client_msg_id). Atualiza a conversa: preview, last_ts e, se não é minha, unread. */
    static long insert(Context c, Chat.Msg m) throws GeneralSecurityException {
        String sender = m.from == null ? MINE : m.from;
        ContentValues v = new ContentValues(8);
        v.put("conv_id", m.convId);
        v.put("client_msg_id", m.clientMsgId);
        v.put("sender", sender);
        v.put("type", m.type);
        v.put("state", m.state);
        v.put("ts", m.ts);
        v.put("live_until", m.liveUntil);
        v.put("body", Envelope.seal(c, body(m.lat, m.lng, m.text), aad(m.convId, sender, m.clientMsgId)));
        long id = db(c).insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) return -1;
        m.id = id;
        // fila offline entrega atrasada: uma mais antiga não pode virar a prévia (o UPDATE lê os valores antigos da linha)
        db(c).execSQL("UPDATE conversations SET preview = CASE WHEN ? >= last_ts THEN ? ELSE preview END,"
                        + " preview_type = CASE WHEN ? >= last_ts THEN ? ELSE preview_type END,"
                        + " last_ts = MAX(last_ts, ?), unread = unread + ? WHERE id = ?",
                new Object[]{m.ts, m.type == Chat.TEXT ? seal(c, preview(m.text), "cp|" + m.convId) : null, m.ts, m.type,
                        m.ts, m.from == null ? 0 : 1, m.convId});
        return id;
    }

    static void setState(Context c, long id, int state) {
        ContentValues v = new ContentValues(1);
        v.put("state", state);
        db(c).update("messages", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Posição nova de uma live (a própria mensagem do LIVE_START) ou o fim dela (liveUntil = hora do stop). */
    static Chat.Msg updateLive(Context c, String convId, String from, String clientMsgId, double lat, double lng, long liveUntil)
            throws GeneralSecurityException {
        String sender = from == null ? MINE : from;
        ContentValues v = new ContentValues(2);
        v.put("live_until", liveUntil);
        v.put("body", Envelope.seal(c, body(lat, lng, null), aad(convId, sender, clientMsgId)));
        db(c).update("messages", v, "sender = ? AND client_msg_id = ?", new String[]{sender, clientMsgId});
        try (Cursor q = db(c).rawQuery(MSG + " WHERE sender = ? AND client_msg_id = ?", new String[]{sender, clientMsgId})) {
            return q.moveToFirst() ? msg(c, q, null) : null;
        }
    }

    /** Ativa = until maior que o relógio (sem job); 0 = nenhuma. */
    static void setLive(Context c, String convId, boolean mine, long until) {
        ContentValues v = new ContentValues(1);
        v.put(mine ? "my_live_until" : "live_until", until);
        db(c).update("conversations", v, "id = ?", new String[]{convId});
    }

    static List<Chat.Msg> page(Context c, String convId, long beforeTs, long beforeId, int limit) throws GeneralSecurityException {
        List<Chat.Msg> out = new ArrayList<>(limit);
        String[] args = {convId, String.valueOf(beforeTs), String.valueOf(beforeTs), String.valueOf(beforeId), String.valueOf(limit)};
        try (Cursor q = db(c).rawQuery(SocialDb.PAGE, args)) {
            while (q.moveToNext()) out.add(msg(c, q, convId));
        }
        return out;
    }

    static Chat.Msg byId(Context c, long id) throws GeneralSecurityException {
        try (Cursor q = db(c).rawQuery(MSG + " WHERE id = ?", new String[]{String.valueOf(id)})) {
            return q.moveToFirst() ? msg(c, q, null) : null;
        }
    }

    /** Colunas na ordem do SocialDb.PAGE; convId null = ler da 9ª coluna (só o MSG traz). */
    private static Chat.Msg msg(Context c, Cursor q, String convId) throws GeneralSecurityException {
        Chat.Msg m = new Chat.Msg();
        m.id = q.getLong(0);
        m.clientMsgId = q.getString(1);
        String sender = q.getString(2);
        m.from = MINE.equals(sender) ? null : sender;
        m.type = q.getInt(3);
        m.state = q.getInt(4);
        m.ts = q.getLong(5);
        m.liveUntil = q.getLong(6);
        m.convId = convId != null ? convId : q.getString(8);
        try {
            readBody(Envelope.open(c, q.getBlob(7), aad(m.convId, sender, m.clientMsgId)), m);
        } catch (GeneralSecurityException e) {
            m.type = Chat.UNREADABLE; // uma que não abre não derruba a página nem a conversa
        }
        return m;
    }

    static List<Chat.Conv> conversations(Context c) {
        List<Chat.Conv> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery(CONV + " ORDER BY last_ts DESC LIMIT " + CONVERSATIONS_LIMIT, null)) {
            while (q.moveToNext()) out.add(conv(c, q));
        }
        return out;
    }

    static Chat.Conv conversation(Context c, String id) {
        try (Cursor q = db(c).rawQuery(CONV + " WHERE id = ?", new String[]{id})) {
            return q.moveToFirst() ? conv(c, q) : null;
        }
    }

    private static Chat.Conv conv(Context c, Cursor q) {
        Chat.Conv v = new Chat.Conv();
        v.id = q.getString(0);
        v.group = q.getInt(1) == 1;
        v.title = open(c, q.getBlob(2), "ct|" + v.id);
        v.preview = open(c, q.getBlob(3), "cp|" + v.id);
        v.previewType = q.getInt(4);
        v.lastTs = q.getLong(5);
        v.unread = q.getInt(6);
        v.myLiveUntil = q.getLong(7);
        v.liveUntil = q.getLong(8);
        return v;
    }

    static void markRead(Context c, String convId) {
        db(c).execSQL("UPDATE conversations SET unread = 0 WHERE id = ?", new Object[]{convId});
    }

    private static SQLiteDatabase db(Context c) { return SocialDb.get(c); }

    private static String aad(String convId, String sender, String clientMsgId) { return "m|" + convId + "|" + sender + "|" + clientMsgId; }

    private static byte[] seal(Context c, String text, String aad) throws GeneralSecurityException {
        return Envelope.seal(c, text.getBytes(StandardCharsets.UTF_8), aad);
    }

    /** null se não houver ou não abrir: título e preview são só exibição, nunca derrubam a lista. */
    private static String open(Context c, byte[] blob, String aad) {
        if (blob == null) return null;
        try {
            return new String(Envelope.open(c, blob, aad), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /** [f64 lat][f64 lng][UTF-8 texto]. */
    static byte[] body(double lat, double lng, String text) {
        byte[] t = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(16 + t.length).putDouble(lat).putDouble(lng).put(t).array();
    }

    static void readBody(byte[] body, Chat.Msg m) {
        ByteBuffer b = ByteBuffer.wrap(body);
        m.lat = b.getDouble();
        m.lng = b.getDouble();
        m.text = new String(body, 16, body.length - 16, StandardCharsets.UTF_8);
    }

    /** Corta em code points: nunca parte um emoji no meio. */
    static String preview(String text) {
        if (text == null) return "";
        if (text.codePointCount(0, text.length()) <= PREVIEW_CHARS) return text;
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_CHARS - 1)) + "…";
    }
}
