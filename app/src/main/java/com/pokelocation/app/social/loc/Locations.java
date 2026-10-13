package com.pokelocation.app.social.loc;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SignalStore;
import com.pokelocation.app.social.store.SocialDb;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Posições dos amigos (A8 ↔ A9). Cada um cifra a própria posição com a sender key "loc:{id}" e entrega a SKDM (a
 * mensagem que dá a sender key) aos amigos por envelope 1:1. Só com SignalLoader.loaded().
 * Regras do maestro: rede SEMPRE fora do SocialDb.atomic (HTTP primeiro, depois um atomic curto só de cripto + banco);
 * rede periódica numa thread do social (a do mapa no principal, "pl-loc" no :overlay), nunca no Io.
 * Envelope 1:1 = [tipo da mensagem Signal u8][ciphertext]; conteúdo decifrado = [kind u8][dados].
 */
public final class Locations {
    public static final class FriendLoc { public String userId, friendCode; public double lat, lng; public long at; }

    static final int KIND_SKDM = 1;
    private static final int DEVICE = 1;
    private static final String TAG = "Locations";
    private static final long SEEN_TTL_MS = 31L * 24 * 3600 * 1000;
    /**
     * Último payload decifrado por amigo: o GET /v1/locations devolve a MESMA mensagem por até 60 s, e decifrar de novo
     * dá DuplicateMessageException. Texto claro só no processo principal, podado a cada fetch.
     */
    private static final Map<String, Object[]> SEEN = new HashMap<>();

    private Locations() {}

    /**
     * Bloqueante, numa thread do social (nunca o Io), só com SignalLoader.loaded(). Busca a fila de envelopes pendentes
     * (processa SKDMs), GET /v1/locations e decifra. Amigo sem sender key ainda ou payload ilegível fica de fora (nunca
     * lança por um amigo só). Rede fora do lock; cripto e banco sob o lock+transação da regra multiprocesso.
     */
    public static List<FriendLoc> fetch(Context c) throws IOException {
        drain(c);
        List<Api.Location> ls = Api.locations(c);
        List<FriendLoc> out = new ArrayList<>(ls.size());
        List<Api.Location> fresh = new ArrayList<>();
        Map<String, Object[]> keep = new HashMap<>();
        synchronized (SEEN) {
            for (Api.Location l : ls) {
                Object[] s = SEEN.get(l.userId);
                if (s != null && Arrays.equals((byte[]) s[0], l.payload)) {
                    out.add((FriendLoc) s[1]);
                    keep.put(l.userId, s);
                } else {
                    fresh.add(l);
                }
            }
        }
        if (!fresh.isEmpty()) {
            out.addAll(atomic(c, () -> {
                SignalStore st = new SignalStore(c);
                List<FriendLoc> r = new ArrayList<>(fresh.size());
                for (Api.Location l : fresh) {
                    try {
                        double[] p = position(new GroupCipher(st, address(l.userId)).decrypt(l.payload));
                        if (p == null) continue;
                        FriendLoc f = new FriendLoc();
                        f.userId = l.userId;
                        f.friendCode = l.friendCode;
                        f.lat = p[0];
                        f.lng = p[1];
                        f.at = (long) p[2];
                        r.add(f);
                        keep.put(l.userId, new Object[]{l.payload, f});
                    } catch (Exception e) {
                        // sem a sender key dele ainda, repetida (SEEN perdido) ou ilegível: fica de fora
                    }
                }
                return r;
            }));
        }
        synchronized (SEEN) {
            SEEN.clear();
            SEEN.putAll(keep);
        }
        fillCodes(c, out);
        return out;
    }

    /** O servidor pode não mandar friend_code (JSON provisório): completa pela lista de amigos, só quando falta. */
    private static void fillCodes(Context c, List<FriendLoc> out) throws IOException {
        Map<String, String> codes = null;
        for (FriendLoc f : out) {
            if (f.friendCode != null) continue;
            if (codes == null) {
                codes = new HashMap<>();
                for (Api.Friend x : Api.friends(c).accepted) codes.put(x.userId, x.friendCode);
            }
            f.friendCode = codes.get(f.userId);
        }
    }

    /** Fila 1:1 pendente (SKDMs dos amigos; 1 página por fetch): baixa, processa sob o lock e só depois apaga do servidor. */
    static void drain(Context c) throws IOException {
        List<Api.Envelope> es = Api.envelopes(c);
        if (es.isEmpty()) return;
        List<String> done = atomic(c, () -> {
            SocialDb.get(c).delete("seen_envelopes", "at < ?",
                    new String[]{String.valueOf(System.currentTimeMillis() - SEEN_TTL_MS)});
            List<String> ids = new ArrayList<>(es.size());
            for (Api.Envelope e : es) {
                try {
                    receive(c, e.from, e.clientMsgId, e.payload);
                } catch (Exception x) {
                    // ponytail: apaga mesmo assim (ilegível não melhora); o remetente reenvia a SKDM a cada sessão do joystick
                    Log.w(TAG, "envelope: " + x.getClass().getSimpleName());
                }
                ids.add(e.id);
            }
            return ids;
        });
        Api.ack(c, done);
    }

    /**
     * Envelope 1:1 de um amigo, DENTRO de SocialDb.atomic: decifra e, se for SKDM, guarda a sender key dele. O mesmo
     * envelope pode vir pelo WS (:overlay) e pelo REST (principal) antes do ack: o 2º é ignorado (dedup por
     * (from, client_msg_id), decisão do Tear) — decifrar de novo falharia e avançaria nada.
     * ponytail: só existe o kind SKDM; o chat (subprojeto 3) transforma isto num inbox que despacha por kind.
     */
    static void receive(Context c, String from, String msgId, byte[] payload) throws Exception {
        ContentValues seen = new ContentValues(3);
        seen.put("sender", from);
        seen.put("msg_id", msgId);
        seen.put("at", System.currentTimeMillis());
        if (SocialDb.get(c).insertWithOnConflict("seen_envelopes", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1) return;
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress a = address(from);
        SessionCipher sc = new SessionCipher(st, a);
        byte[] ct = body(payload);
        byte[] plain = kind(payload) == CiphertextMessage.PREKEY_TYPE
                ? sc.decrypt(new PreKeySignalMessage(ct)) : sc.decrypt(new SignalMessage(ct));
        if (kind(plain) == KIND_SKDM) new GroupSessionBuilder(st).process(a, new SenderKeyDistributionMessage(body(plain)));
    }

    /**
     * DENTRO de SocialDb.atomic: envelope 1:1 com a SKDM da minha sender key loc:{meuId} para o amigo. bundle != null
     * abre a sessão (veio de Api.keyBundle, fora do lock); bundle == null e sem sessão → null (busque o bundle).
     */
    static byte[] skdmFor(Context c, String myId, String friendId, Api.Bundle bundle) throws Exception {
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress to = address(friendId);
        if (bundle != null) new SessionBuilder(st, to).process(preKeyBundle(bundle));
        else if (!st.containsSession(to)) return null;
        SenderKeyDistributionMessage skdm = new GroupSessionBuilder(st).create(address(myId), distribution(myId));
        CiphertextMessage m = new SessionCipher(st, to).encrypt(frame(KIND_SKDM, skdm.serialize()));
        return frame(m.getType(), m.serialize());
    }

    /** DENTRO de SocialDb.atomic: minha posição cifrada com a sender key loc:{meuId} (criada na 1ª vez). */
    static byte[] encrypt(Context c, String myId, double lat, double lng, long at) throws Exception {
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress me = address(myId);
        GroupCipher g = new GroupCipher(st, me);
        try {
            return g.encrypt(distribution(myId), position(lat, lng, at)).serialize();
        } catch (NoSessionException e) {
            new GroupSessionBuilder(st).create(me, distribution(myId));
            return g.encrypt(distribution(myId), position(lat, lng, at)).serialize();
        }
    }

    private static PreKeyBundle preKeyBundle(Api.Bundle b) throws Exception {
        return new PreKeyBundle(b.registrationId, DEVICE,
                b.preKey == null ? PreKeyBundle.NULL_PRE_KEY_ID : b.preKey.id,
                b.preKey == null ? null : new ECPublicKey(b.preKey.pub),
                b.signed.id, new ECPublicKey(b.signed.pub), b.signed.sig, new IdentityKey(b.identity),
                b.kyber.id, new KEMPublicKey(b.kyber.pub), b.kyber.sig);
    }

    private static SignalProtocolAddress address(String userId) { return new SignalProtocolAddress(userId, DEVICE); }

    /** SocialDb.atomic (só cripto + banco: nada de rede aqui dentro). Logout no outro processo = sessão morta. */
    static <T> T atomic(Context c, SocialDb.Op<T> op) throws IOException {
        try {
            return SocialDb.atomic(c, op);
        } catch (IOException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new Api.Error(401, "unauthorized"); // geração nova sem arquivo; quem limpa é só o principal
        } catch (Exception e) {
            throw new IOException("signal", e);
        }
    }

    // ---- puros (LocationsTest) ----

    /** Contrato A8↔A9: f64 lat, f64 lng, i64 epochMs big-endian = 24 B. */
    static byte[] position(double lat, double lng, long at) {
        return ByteBuffer.allocate(24).putDouble(lat).putDouble(lng).putLong(at).array();
    }

    /** {lat, lng, at}; null se não tiver 24 B. */
    static double[] position(byte[] p) {
        if (p == null || p.length != 24) return null;
        ByteBuffer b = ByteBuffer.wrap(p);
        return new double[]{b.getDouble(), b.getDouble(), b.getLong()};
    }

    static byte[] frame(int kind, byte[] body) {
        byte[] f = new byte[1 + body.length];
        f[0] = (byte) kind;
        System.arraycopy(body, 0, f, 1, body.length);
        return f;
    }

    /** -1 se vazio. */
    static int kind(byte[] f) { return f.length == 0 ? -1 : f[0] & 0xff; }

    static byte[] body(byte[] f) { return Arrays.copyOfRange(f, 1, f.length); }

    /** Distribuição da sender key de localização de um usuário: "loc:{id}" → UUID v3 (igual em todos os aparelhos). */
    static UUID distribution(String userId) { return UUID.nameUUIDFromBytes(("loc:" + userId).getBytes(StandardCharsets.UTF_8)); }
}
