package com.pokelocation.app.social.chat;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Texto claro dentro do Signal: [kind u8][…]. SKDM = 1 (a mesma do Locations). MSG = [tipo u8][ts i64][liveUntil i64]
 * + o corpo do ChatDb ([lat][lng][texto]). GROUP_INFO = [u16 group_id][nome UTF-8], por 1:1 junto da SKDM: quem entra
 * no grupo recebe o nome (o servidor não o conhece). RESET = vazio: só faz sair um PreKeySignalMessage pelo bundle para o
 * outro lado refazer a sessão depois de uma ilegível (quem recebe ignora e confirma, como todo kind desconhecido).
 */
final class Content {
    static final int SKDM = 1, MSG = 2, GROUP_INFO = 3, RESET = 4;

    private Content() {}

    static byte[] msg(Chat.Msg m) {
        byte[] body = ChatDb.body(m.lat, m.lng, m.text);
        return ByteBuffer.allocate(18 + body.length).put((byte) MSG).put((byte) m.type).putLong(m.ts).putLong(m.liveUntil)
                .put(body).array();
    }

    /** null se não for MSG ou estiver malformada. */
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

    /** 8 h (a maior opção) + 10 min de folga de relógio entre aparelhos. */
    static final long MAX_LIVE_MS = 8 * 3600_000L + 600_000L;

    /**
     * Mensagem vinda de outra pessoa: só os tipos de usuário (aviso de sistema falso é descartado) e live de no máximo
     * MAX_LIVE_MS a partir do ts dela (ajusta no lugar).
     */
    static boolean acceptable(Chat.Msg m) {
        if (m.type != Chat.TEXT && m.type != Chat.LOC && m.type != Chat.LIVE_START && m.type != Chat.LIVE_STOP) return false;
        if (m.liveUntil - m.ts > MAX_LIVE_MS || m.liveUntil < 0) m.liveUntil = m.ts + MAX_LIVE_MS;
        return true;
    }

    /** O servidor gera UUID para grupo; "d:<id>" é 1:1 e nunca pode ser renomeado por um GROUP_INFO. */
    static boolean isGroupId(String id) {
        if (id == null || id.length() != 36) return false;
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static byte[] groupInfo(String groupId, String name) {
        byte[] g = groupId.getBytes(StandardCharsets.UTF_8), n = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(3 + g.length + n.length).put((byte) GROUP_INFO).putShort((short) g.length).put(g).put(n).array();
    }

    /** {group_id, nome}; null se malformado. */
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
