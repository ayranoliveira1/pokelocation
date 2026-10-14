package com.pokelocation.app.social.chat;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * [kind u8][…]: MSG = [tipo u8][ts i64][liveUntil i64][lat f64][lng f64][texto]; GROUP_INFO = [u16][group_id][nome].
 * RESET vazio só força uma sessão nova pelo bundle; quem recebe confirma, como todo kind desconhecido.
 */
final class Content {
    static final int SKDM = 1, MSG = 2, GROUP_INFO = 3, RESET = 4, RESEND = 5;

    private Content() {}

    static byte[] msg(Chat.Msg m) {
        byte[] body = ChatDb.body(m.lat, m.lng, m.text);
        return ByteBuffer.allocate(18 + body.length).put((byte) MSG).put((byte) m.type).putLong(m.ts).putLong(m.liveUntil)
                .put(body).array();
    }

    static Chat.Msg msg(byte[] plain) {
        if (plain.length < 18 + 16 || plain[0] != MSG) return null;
        ByteBuffer b = ByteBuffer.wrap(plain, 1, plain.length - 1);
        Chat.Msg m = new Chat.Msg();
        m.type = b.get() & 0xff;
        m.ts = b.getLong();
        m.liveUntil = b.getLong();
        ChatDb.readBody(Arrays.copyOfRange(plain, 18, plain.length), m);
        return m;
    }

    /** + 10 min de folga de relógio entre aparelhos. */
    static final long MAX_LIVE_MS = 8 * 3600_000L + 600_000L;

    /** Aviso de sistema vindo de outra pessoa é falso: descartado. */
    static boolean acceptable(Chat.Msg m) {
        if (m.type != Chat.TEXT && m.type != Chat.LOC && m.type != Chat.LIVE_START && m.type != Chat.LIVE_STOP) return false;
        if (m.liveUntil - m.ts > MAX_LIVE_MS || m.liveUntil < 0) m.liveUntil = m.ts + MAX_LIVE_MS;
        return true;
    }

    static boolean isGroupId(String id) {
        if (id == null || id.length() != 36) return false;
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** RESET = [n u8]{[len u8][id]}: as que quem pede não conseguiu ler (vazio = só a sessão nova). */
    static byte[] reset(List<String> ids) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        List<byte[]> ok = new ArrayList<>();
        for (String id : ids) {
            byte[] x = id.getBytes(StandardCharsets.UTF_8);
            if (x.length <= 64 && ok.size() < Rules.RESEND_MAX) ok.add(x);
        }
        b.write(RESET);
        b.write(ok.size());
        for (byte[] x : ok) {
            b.write(x.length);
            b.write(x, 0, x.length);
        }
        return b.toByteArray();
    }

    static List<String> resetIds(byte[] plain) {
        List<String> out = new ArrayList<>();
        if (plain.length < 2 || plain[0] != RESET) return out;
        int n = Math.min(plain[1] & 0xff, Rules.RESEND_MAX), at = 2;
        for (int i = 0; i < n; i++) {
            if (at >= plain.length) return new ArrayList<>();
            int len = plain[at++] & 0xff;
            if (len == 0 || at + len > plain.length) return new ArrayList<>();
            out.add(new String(plain, at, len, StandardCharsets.UTF_8));
            at += len;
        }
        return out;
    }

    /** RESEND = [len u8][id original][MSG]: o id original só existe dentro do cifrado; o envelope leva um id novo. */
    static byte[] resend(String original, Chat.Msg m) {
        byte[] id = original.getBytes(StandardCharsets.UTF_8), msg = msg(m);
        return ByteBuffer.allocate(2 + id.length + msg.length).put((byte) RESEND).put((byte) id.length).put(id).put(msg).array();
    }

    static Chat.Msg resend(byte[] plain) {
        if (plain.length < 2 || plain[0] != RESEND) return null;
        int len = plain[1] & 0xff;
        if (len == 0 || 2 + len > plain.length) return null;
        Chat.Msg m = msg(Arrays.copyOfRange(plain, 2 + len, plain.length));
        if (m != null) m.clientMsgId = new String(plain, 2, len, StandardCharsets.UTF_8);
        return m;
    }

    static byte[] groupInfo(String groupId, String name) {
        byte[] g = groupId.getBytes(StandardCharsets.UTF_8), n = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(3 + g.length + n.length).put((byte) GROUP_INFO).putShort((short) g.length).put(g).put(n).array();
    }

    static String[] groupInfo(byte[] plain) {
        if (plain.length < 3 || plain[0] != GROUP_INFO) return null;
        try {
            int n = ByteBuffer.wrap(plain, 1, 2).getShort() & 0xffff;
            if (3 + n > plain.length) return null;
            return new String[]{new String(plain, 3, n, StandardCharsets.UTF_8),
                    new String(plain, 3 + n, plain.length - 3 - n, StandardCharsets.UTF_8)};
        } catch (BufferUnderflowException e) {
            return null;
        }
    }
}
