package com.pokelocation.app.social.loc;

import android.content.Context;
import android.util.Log;

import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.chat.Inbox;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SignalStore;
import com.pokelocation.app.social.store.SocialDb;

import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
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
 * Posições dos amigos: cada um cifra a própria com a sender key "loc:{id}" e entrega a SKDM aos amigos por envelope
 * 1:1. Só com SignalLoader.loaded(). Rede sempre fora do SocialDb.atomic (HTTP primeiro, depois um atomic curto de
 * cripto + banco) e numa thread do social, nunca no Io.
 * Envelope 1:1 = [tipo da mensagem Signal u8][ciphertext]; conteúdo decifrado = [kind u8][dados].
 */
public final class Locations {
    public static final class FriendLoc { public String userId, friendCode, username; public double lat, lng; public long at; }

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
     * Bloqueante. Processa a fila de envelopes (SKDMs) e decifra as posições. Amigo sem sender key ainda ou payload
     * ilegível fica de fora: nunca lança por um amigo só.
     */
    public static List<FriendLoc> fetch(Context c) throws IOException {
        requireUsername(c);
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
                        f.username = l.username;
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

    private static volatile boolean sMeChecked;

    /** Sem @nome o servidor responde 403 a tudo: nada de rede a cada 5 s; um GET /v1/me por processo para conferir. */
    private static void requireUsername(Context c) throws IOException {
        if (Session.username(c) != null) return;
        if (!sMeChecked) {
            sMeChecked = true;
            if (Api.me(c).username != null) return;
        }
        throw new Api.Error(403, "username_required");
    }

    /** friend_code/username faltando: completa pela lista de amigos, só quando precisa. */
    private static void fillCodes(Context c, List<FriendLoc> out) throws IOException {
        Map<String, Api.Friend> byId = null;
        for (FriendLoc f : out) {
            if (f.friendCode != null && f.username != null) continue;
            if (byId == null) {
                byId = new HashMap<>();
                for (Api.Friend x : Api.friends(c).accepted) byId.put(x.userId, x);
            }
            Api.Friend x = byId.get(f.userId);
            if (x == null) continue;
            if (f.friendCode == null) f.friendCode = x.friendCode;
            if (f.username == null) f.username = x.username;
        }
    }

    /**
     * Uma página. false = fila vazia ou nada confirmado (transitório: buscar de novo já traria a mesma página). Cada envelope no seu atomic: transação aninhada no Android não volta parcial, então uma
     * falha desfaria todos. Apaga do servidor só o que ack() aceita.
     */
    public static boolean drain(Context c) throws IOException {
        List<Api.Envelope> es = Api.envelopes(c);
        if (es.isEmpty()) return false;
        atomic(c, () -> SocialDb.get(c).delete("seen_envelopes", "at < ?",
                new String[]{String.valueOf(System.currentTimeMillis() - SEEN_TTL_MS)}));
        List<String> ids = new ArrayList<>(es.size());
        List<Inbox.Result> got = new ArrayList<>(es.size());
        for (Api.Envelope e : es) {
            Throwable fail = null;
            try {
                got.add(atomic(c, () -> Inbox.receive(c, e.from, e.groupId, e.clientMsgId, e.payload)));
            } catch (Api.Error x) {
                throw x; // 401: sessão morta
            } catch (IOException x) {
                fail = x;
            }
            if (ack(fail)) ids.add(e.id);
            else Log.w(TAG, "envelope sem ack: " + rootName(fail));
        }
        Api.ack(c, ids);
        for (Inbox.Result r : got) Inbox.deliver(c, r, null);
        Inbox.deliver(c, atomic(c, () -> Inbox.prune(c)), null);
        return !ids.isEmpty();
    }

    /**
     * Ack ao servidor? Sim se processado (null, inclui o duplicado já visto) ou ilegível para sempre (erro de mensagem
     * da libsignal ou payload malformado na cadeia de causas). Não em falha transitória (lock, Keystore, banco, timeout)
     * o servidor entrega de novo.
     */
    /** Falha transitória explícita: sem Ack, mesmo que a causa seja uma exceção da libsignal. */
    public static final class Transient extends IOException {
        public Transient(String msg, Throwable cause) { super(msg, cause); }
    }

    public static boolean ack(Throwable failure) {
        if (failure == null) return true;
        for (Throwable t = failure; t != null; t = t.getCause()) if (t instanceof Transient) return false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException // payload/registro malformado (cliente alterado): não melhora
                    || t instanceof InvalidMessageException || t instanceof InvalidKeyException || t instanceof InvalidKeyIdException
                    || t instanceof InvalidVersionException || t instanceof LegacyMessageException
                    || t instanceof DuplicateMessageException || t instanceof NoSessionException) {
                return true;
            }
        }
        return false;
    }

    public static String rootName(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName();
    }

    /**
     * Dentro de SocialDb.atomic: envelope 1:1 com a SKDM da minha sender key loc:{meuId}. bundle != null abre a sessão
     * (buscado fora do lock); bundle == null e sem sessão → null (busque o bundle).
     */
    static byte[] skdmFor(Context c, String myId, String friendId, Api.Bundle bundle) throws Exception {
        SenderKeyDistributionMessage skdm = new GroupSessionBuilder(new SignalStore(c)).create(address(myId), distribution(myId));
        return encryptTo(c, friendId, frame(KIND_SKDM, skdm.serialize()), bundle);
    }

    /**
     * Dentro de SocialDb.atomic: envelope 1:1 ([tipo][ciphertext]) para o amigo. bundle != null abre a sessão (buscado
     * fora do lock); bundle == null e sem sessão → null (busque o bundle).
     */
    public static byte[] encryptTo(Context c, String friendId, byte[] plain, Api.Bundle bundle) throws Exception {
        return encryptTo(new SignalStore(c), friendId, plain, bundle);
    }

    /** Com o store de quem chama: st.replaced(friendId) diz se o bundle trouxe identity nova. */
    public static byte[] encryptTo(SignalStore st, String friendId, byte[] plain, Api.Bundle bundle) throws Exception {
        SignalProtocolAddress to = address(friendId);
        if (bundle != null) new SessionBuilder(st, to).process(preKeyBundle(bundle));
        else if (!st.containsSession(to)) return null;
        CiphertextMessage m = new SessionCipher(st, to).encrypt(plain);
        return frame(m.getType(), m.serialize());
    }

    /** Dentro de SocialDb.atomic. A sender key é criada na 1ª vez. */
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

    /** Só cripto + banco aqui dentro, nunca rede. Logout no outro processo = sessão morta. */
    public static <T> T atomic(Context c, SocialDb.Op<T> op) throws IOException {
        try {
            return SocialDb.atomic(c, op);
        } catch (IOException e) {
            throw e;
        } catch (IllegalStateException e) {
            // só o "logged out" do SocialDb (geração nova sem arquivo) é sessão morta; o resto é falha do store
            if ("logged out".equals(e.getMessage())) throw new Api.Error(401, "unauthorized");
            throw new IOException("signal", e);
        } catch (Exception e) {
            throw new IOException("signal", e);
        }
    }

    /** f64 lat, f64 lng, i64 epochMs big-endian = 24 B. */
    static byte[] position(double lat, double lng, long at) {
        return ByteBuffer.allocate(24).putDouble(lat).putDouble(lng).putLong(at).array();
    }

    /** {lat, lng, at}; null se não tiver 24 B. */
    static double[] position(byte[] p) {
        if (p == null || p.length != 24) return null;
        ByteBuffer b = ByteBuffer.wrap(p);
        return new double[]{b.getDouble(), b.getDouble(), b.getLong()};
    }

    public static byte[] frame(int kind, byte[] body) {
        byte[] f = new byte[1 + body.length];
        f[0] = (byte) kind;
        System.arraycopy(body, 0, f, 1, body.length);
        return f;
    }

    /** -1 se vazio. */
    static int kind(byte[] f) { return f.length == 0 ? -1 : f[0] & 0xff; }

    static byte[] body(byte[] f) { return Arrays.copyOfRange(f, 1, f.length); }

    /** "loc:{id}" → UUID v3: a mesma distribuição em todos os aparelhos. */
    static UUID distribution(String userId) { return UUID.nameUUIDFromBytes(("loc:" + userId).getBytes(StandardCharsets.UTF_8)); }
}
