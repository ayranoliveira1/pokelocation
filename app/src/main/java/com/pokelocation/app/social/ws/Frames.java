package com.pokelocation.app.social.ws;

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * Payload dos frames binários do WebSocket, espelho do wire do servidor (api.md §6): [tipo u8][campos]; inteiros
 * big-endian; str = u16 + UTF-8; bin = u32 + bytes. Puro, sem I/O.
 */
public final class Frames {
    public static final int AUTH = 1, ENVELOPE = 2, ACK = 3, LOCATION = 4, LOCATION_UPDATE = 5, LOCATION_OFF = 6, ERROR = 7,
            GROUP_ENVELOPE = 8, GROUP_CHANGED = 9;

    /**
     * peer: destinatário/remetente (Envelope, Ack), from (LocationUpdate, LocationOff e GroupEnvelope do servidor),
     * token (Auth); msgId: Envelope/Ack/GroupEnvelope; code: Error; groupId: GroupEnvelope/GroupChanged;
     * payload: Envelope/Location/LocationUpdate/GroupEnvelope. O que não se aplica fica null.
     */
    public static final class Frame {
        public final int type;
        public final String peer, msgId, code, groupId;
        public final byte[] payload;

        Frame(int type, String peer, String msgId, String code, byte[] payload) {
            this(type, peer, msgId, code, payload, null);
        }

        Frame(int type, String peer, String msgId, String code, byte[] payload, String groupId) {
            this.groupId = groupId;
            this.type = type;
            this.peer = peer;
            this.msgId = msgId;
            this.code = code;
            this.payload = payload;
        }
    }

    private Frames() {}

    public static byte[] auth(String token) { return new W(AUTH).str(token).out(); }

    public static byte[] envelope(String peer, String msgId, byte[] payload) {
        return new W(ENVELOPE).str(peer).str(msgId).bin(payload).out();
    }

    public static byte[] ack(String peer, String msgId) { return new W(ACK).str(peer).str(msgId).out(); }

    public static byte[] location(byte[] payload) { return new W(LOCATION).bin(payload).out(); }

    public static byte[] locationOff() { return new byte[]{LOCATION_OFF}; }

    public static byte[] groupEnvelope(String groupId, String msgId, byte[] payload) {
        return new W(GROUP_ENVELOPE).str(groupId).str(msgId).bin(payload).out();
    }

    /** ProtocolException: vazio, tipo desconhecido, tamanho que passa do frame ou bytes sobrando. */
    public static final int ERR_IGNORE = 0, ERR_RENEW = 1, ERR_STOP = 2, ERR_LOGOUT = 3, ERR_PEER = 4;

    public static int onError(String code) {
        if ("unauthorized".equals(code)) return ERR_RENEW;
        if ("username_required".equals(code)) return ERR_STOP;
        if ("account_deleted".equals(code)) return ERR_LOGOUT;
        if ("forbidden".equals(code)) return ERR_PEER;
        return ERR_IGNORE;
    }

    public static Frame decode(byte[] f) throws ProtocolException {
        R r = new R(f);
        int type = r.u8();
        Frame out;
        switch (type) {
            case AUTH: out = new Frame(type, r.str(), null, null, null); break;
            case ENVELOPE: out = new Frame(type, r.str(), r.str(), null, r.bin()); break;
            case ACK: out = new Frame(type, r.str(), r.str(), null, null); break;
            case LOCATION: out = new Frame(type, null, null, null, r.bin()); break;
            case LOCATION_UPDATE: out = new Frame(type, r.str(), null, null, r.bin()); break;
            case LOCATION_OFF: out = new Frame(type, r.left() == 0 ? null : r.str(), null, null, null); break; // C→S vazio
            case ERROR: out = new Frame(type, null, null, r.str(), null); break;
            case GROUP_ENVELOPE: { // do servidor vem com o from no fim: o C→S é prefixo dele
                String g = r.str(), m = r.str();
                byte[] p = r.bin();
                out = new Frame(type, r.left() == 0 ? null : r.str(), m, null, p, g);
                break;
            }
            case GROUP_CHANGED: out = new Frame(type, null, null, null, null, r.str()); break;
            default: throw new ProtocolException("type " + type);
        }
        if (r.left() != 0) throw new ProtocolException("trailing");
        return out;
    }

    private static final class W {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream(64);

        W(int type) { b.write(type); }

        W str(String s) {
            byte[] v = s.getBytes(StandardCharsets.UTF_8);
            if (v.length > 0xffff) throw new IllegalArgumentException("str > 65535 B");
            b.write(v.length >> 8);
            b.write(v.length);
            b.write(v, 0, v.length);
            return this;
        }

        W bin(byte[] v) {
            for (int s = 24; s >= 0; s -= 8) b.write(v.length >> s);
            b.write(v, 0, v.length);
            return this;
        }

        byte[] out() { return b.toByteArray(); }
    }

    private static final class R {
        private final byte[] f;
        private int i;

        R(byte[] f) { this.f = f; }

        int left() { return f.length - i; }

        private void need(long n) throws ProtocolException {
            if (n > left()) throw new ProtocolException("length past end");
        }

        int u8() throws ProtocolException {
            need(1);
            return f[i++] & 0xff;
        }

        String str() throws ProtocolException {
            need(2);
            int n = (f[i] & 0xff) << 8 | (f[i + 1] & 0xff);
            i += 2;
            need(n);
            String s = new String(f, i, n, StandardCharsets.UTF_8);
            i += n;
            return s;
        }

        byte[] bin() throws ProtocolException {
            need(4);
            long n = (f[i] & 0xffL) << 24 | (f[i + 1] & 0xff) << 16 | (f[i + 2] & 0xff) << 8 | (f[i + 3] & 0xff);
            i += 4;
            need(n);
            byte[] v = new byte[(int) n];
            System.arraycopy(f, i, v, 0, v.length);
            i += v.length;
            return v;
        }
    }
}
