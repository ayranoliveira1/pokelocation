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

/** Dentro do SocialDb.atomic, sem rede. Selado com aad = a linha: um blob copiado para outra conversa/mensagem não abre. */
final class ChatDb {
    static final int PREVIEW_CHARS = 80, CONVERSATIONS_LIMIT = 200, MEMBERS_LIMIT = 60;
    private static final String MINE = "";
    private static final String CONV = "SELECT id, grp, title, preview, preview_type, last_ts, unread, my_live_until,"
            + " live_until, gone FROM conversations";
    private static final String MSG = "SELECT id, client_msg_id, sender, type, state, ts, live_until, body, conv_id"
            + " FROM messages";

    private ChatDb() {}

    static String directId(String userId) { return "d:" + userId; }

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
        // hora de chegada, não o relógio do remetente; o CASE segura um relógio local que voltou (o UPDATE lê a linha antiga)
        long at = System.currentTimeMillis();
        db(c).execSQL("UPDATE conversations SET preview = CASE WHEN ? >= last_ts THEN ? ELSE preview END,"
                        + " preview_type = CASE WHEN ? >= last_ts THEN ? ELSE preview_type END,"
                        + " last_ts = MAX(last_ts, ?), unread = unread + ? WHERE id = ?",
                new Object[]{at, m.type == Chat.TEXT ? seal(c, preview(m.text), "cp|" + m.convId) : null, at, m.type,
                        at, m.from == null ? 0 : 1, m.convId});
        return id;
    }

    static void setState(Context c, long id, int state) {
        ContentValues v = new ContentValues(1);
        v.put("state", state);
        db(c).update("messages", v, "id = ?", new String[]{String.valueOf(id)});
    }

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

    static Chat.Msg byClientId(Context c, String from, String clientMsgId) throws GeneralSecurityException {
        try (Cursor q = db(c).rawQuery(MSG + " WHERE sender = ? AND client_msg_id = ?",
                new String[]{from == null ? MINE : from, clientMsgId})) {
            return q.moveToFirst() ? msg(c, q, null) : null;
        }
    }

    static void recomputeLive(Context c, String convId) {
        db(c).execSQL("UPDATE conversations SET live_until = IFNULL((SELECT MAX(live_until) FROM messages WHERE conv_id = ?"
                + " AND type = " + Chat.LIVE_START + " AND sender != ''), 0) WHERE id = ?", new Object[]{convId, convId});
    }

    static String myLiveStart(Context c, String convId) {
        try (Cursor q = db(c).rawQuery("SELECT client_msg_id FROM messages WHERE conv_id = ? AND sender = '' AND type = "
                + Chat.LIVE_START + " ORDER BY ts DESC, id DESC LIMIT 1", new String[]{convId})) {
            return q.moveToFirst() ? q.getString(0) : null;
        }
    }

    /** {convId, grp, peer, client_msg_id do LIVE_START, até} */
    static List<Object[]> myLives(Context c, long now) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery("SELECT id, grp, peer, my_live_until FROM conversations WHERE my_live_until > ?",
                new String[]{String.valueOf(now)})) {
            while (q.moveToNext()) {
                String start = myLiveStart(c, q.getString(0));
                if (start != null) out.add(new Object[]{q.getString(0), q.getInt(1) == 1, q.getString(2), start, q.getLong(3)});
            }
        }
        return out;
    }

    static boolean keyOk(Context c, String convId) {
        try (Cursor q = db(c).rawQuery("SELECT key_ok FROM conversations WHERE id = ?", new String[]{convId})) {
            return q.moveToFirst() && q.getInt(0) == 1;
        }
    }

    static void setKeyOk(Context c, String convId, boolean ok) {
        db(c).execSQL("UPDATE conversations SET key_ok = ? WHERE id = ?", new Object[]{ok ? 1 : 0, convId});
    }

    static long maxMyLive(Context c) {
        try (Cursor q = db(c).rawQuery("SELECT IFNULL(MAX(my_live_until), 0) FROM conversations", null)) {
            return q.moveToFirst() ? q.getLong(0) : 0;
        }
    }

    static String peer(Context c, String convId) {
        try (Cursor q = db(c).rawQuery("SELECT peer FROM conversations WHERE id = ?", new String[]{convId})) {
            return q.moveToFirst() ? q.getString(0) : null;
        }
    }

    static boolean isMember(Context c, String convId, String userId) {
        try (Cursor q = db(c).rawQuery("SELECT 1 FROM group_members WHERE conv_id = ? AND user_id = ?", new String[]{convId, userId})) {
            return q.moveToFirst();
        }
    }

    static boolean isGroup(Context c, String convId) {
        try (Cursor q = db(c).rawQuery("SELECT grp FROM conversations WHERE id = ?", new String[]{convId})) {
            return q.moveToFirst() && q.getInt(0) == 1;
        }
    }

    static void leave(Context c, String groupId) {
        String[] a = {groupId};
        db(c).execSQL("UPDATE conversations SET gone = 1, key_ok = 0, my_live_until = 0 WHERE id = ?", a);
        db(c).execSQL("UPDATE messages SET state = " + Chat.FAILED + " WHERE conv_id = ? AND sender = '' AND state = "
                + Chat.SENDING, a);
        db(c).delete("group_members", "conv_id = ?", a);
        db(c).delete("pending_group", "group_id = ?", a);
    }

    static void rejoin(Context c, String groupId) {
        db(c).execSQL("UPDATE conversations SET gone = 0 WHERE id = ?", new Object[]{groupId});
    }

    static boolean gone(Context c, String convId) {
        try (Cursor q = db(c).rawQuery("SELECT gone FROM conversations WHERE id = ?", new String[]{convId})) {
            return q.moveToFirst() && q.getInt(0) == 1;
        }
    }

    static List<String> activeGroups(Context c) {
        List<String> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery("SELECT id FROM conversations WHERE grp = 1 AND gone = 0", null)) {
            while (q.moveToNext()) out.add(q.getString(0));
        }
        return out;
    }

    static List<Long> sendingIds(Context c) {
        List<Long> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery("SELECT id FROM messages WHERE state = " + Chat.SENDING + " AND sender = '' LIMIT "
                + CONVERSATIONS_LIMIT, null)) {
            while (q.moveToNext()) out.add(q.getLong(0));
        }
        return out;
    }

    static int incTries(Context c, long id) {
        db(c).execSQL("UPDATE messages SET tries = tries + 1 WHERE id = ?", new Object[]{id});
        return tries(c, id);
    }

    static int tries(Context c, long id) {
        try (Cursor q = db(c).rawQuery("SELECT tries FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return q.moveToFirst() ? q.getInt(0) : Integer.MAX_VALUE;
        }
    }

    static void addPending(Context c, String sender, String msgId, String groupId, byte[] payload) {
        ContentValues v = new ContentValues(5);
        v.put("sender", sender);
        v.put("msg_id", msgId);
        v.put("group_id", groupId);
        v.put("payload", payload);
        v.put("at", System.currentTimeMillis());
        db(c).insertWithOnConflict("pending_group", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** {msg_id, group_id, payload} */
    static List<Object[]> pendingFrom(Context c, String sender) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor q = db(c).rawQuery("SELECT msg_id, group_id, payload FROM pending_group WHERE sender = ? LIMIT 50",
                new String[]{sender})) {
            while (q.moveToNext()) out.add(new Object[]{q.getString(0), q.getString(1), q.getBlob(2)});
        }
        return out;
    }

    static int pendingCount(Context c, String sender) {
        return (int) android.database.DatabaseUtils.longForQuery(db(c), "SELECT COUNT(*) FROM pending_group WHERE sender = ?",
                new String[]{sender});
    }

    static String[] oldestPending(Context c, String sender) {
        try (Cursor q = db(c).rawQuery("SELECT msg_id, group_id FROM pending_group WHERE sender = ? ORDER BY at LIMIT 1",
                new String[]{sender})) {
            return q.moveToFirst() ? new String[]{q.getString(0), q.getString(1)} : null;
        }
    }

    static void dropPending(Context c, String sender, String msgId) {
        db(c).delete("pending_group", "sender = ? AND msg_id = ?", new String[]{sender, msgId});
    }

    /** {sender, msg_id, group_id}, já apagadas da fila */
    static List<String[]> takeExpiredPending(Context c, long before) {
        List<String[]> out = new ArrayList<>();
        String[] a = {String.valueOf(before)};
        try (Cursor q = db(c).rawQuery("SELECT sender, msg_id, group_id FROM pending_group WHERE at < ? LIMIT 50", a)) {
            while (q.moveToNext()) out.add(new String[]{q.getString(0), q.getString(1), q.getString(2)});
        }
        for (String[] p : out) dropPending(c, p[0], p[1]);
        return out;
    }

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
            m.type = Chat.UNREADABLE;
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
        v.left = q.getInt(9) == 1;
        return v;
    }

    static int unreadTotal(Context c) {
        try (Cursor q = db(c).rawQuery("SELECT IFNULL(SUM(unread), 0) FROM conversations", null)) {
            return q.moveToFirst() ? q.getInt(0) : 0;
        }
    }

    static void markRead(Context c, String convId) {
        db(c).execSQL("UPDATE conversations SET unread = 0 WHERE id = ?", new Object[]{convId});
    }

    private static SQLiteDatabase db(Context c) { return SocialDb.get(c); }

    private static String aad(String convId, String sender, String clientMsgId) { return "m|" + convId + "|" + sender + "|" + clientMsgId; }

    private static byte[] seal(Context c, String text, String aad) throws GeneralSecurityException {
        return Envelope.seal(c, text.getBytes(StandardCharsets.UTF_8), aad);
    }

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

    static String preview(String text) {
        if (text == null) return "";
        if (text.codePointCount(0, text.length()) <= PREVIEW_CHARS) return text;
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_CHARS - 1)) + "…";
    }
}
