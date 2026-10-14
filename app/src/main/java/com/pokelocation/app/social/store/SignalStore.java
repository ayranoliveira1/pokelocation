package com.pokelocation.app.social.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Stores do Signal (A7) sobre o social.db. Sessões, identidades dos amigos e sender keys: Envelope (software, chave em
 * memória); prekeys e a identity própria: Vault (raros). Com login e SignalLoader.loaded() (o nativo chama estes
 * métodos por JNI; a regra *Store do proguard os mantém). Toda operação que usa este store (encrypt, decrypt,
 * bundle/SKDM) roda inteira dentro de SocialDb.atomic (regra multiprocesso): fora dele, db() lança.
 * Em memória só o imutável na vida do login (chave-envelope aberta, identity própria); registros sempre do arquivo.
 * Falha de cripto/registro dentro de um callback do nativo vira IllegalStateException (as interfaces não declaram).
 */
public final class SignalStore implements SignalProtocolStore {
    private static volatile byte[] sEnvelope;
    private static volatile IdentityKeyPair sIdentity;
    private static volatile int sRegId;

    private final Context app;

    public SignalStore(Context c) { app = c.getApplicationContext(); }

    private SQLiteDatabase db() {
        if (!SocialDb.inAtomic()) throw new IllegalStateException("SignalStore fora de SocialDb.atomic");
        return SocialDb.get(app);
    }

    /** Logout (SocialDb.delete): esquece o que está em memória. */
    static void forget() {
        sEnvelope = null;
        sIdentity = null;
        sRegId = 0;
    }

    /** Chave-envelope: gerada uma vez, guardada selada pelo Vault; o Keystore só é tocado aqui, uma vez por processo. */
    private byte[] envelope() {
        byte[] k = sEnvelope;
        if (k != null) return k;
        synchronized (SignalStore.class) {
            if (sEnvelope != null) return sEnvelope;
            try {
                byte[] sealed = blob("SELECT blob FROM envelope", (String[]) null);
                if (sealed != null) {
                    k = Vault.open(sealed);
                } else {
                    k = Envelope.newKey();
                    ContentValues v = new ContentValues(2);
                    v.put("id", 0);
                    v.put("blob", Vault.seal(k));
                    db().insertOrThrow("envelope", null, v);
                }
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("envelope", e);
            }
            return sEnvelope = k;
        }
    }

    private byte[] blob(String sql, String... args) {
        try (Cursor q = db().rawQuery(sql, args)) {
            return q.moveToFirst() ? q.getBlob(0) : null;
        }
    }

    /** null se não houver a linha. */
    private byte[] openEnv(String sql, String aad, String... args) {
        byte[] b = blob(sql, args);
        if (b == null) return null;
        try {
            return Envelope.open(envelope(), b, aad);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(aad, e);
        }
    }

    private void putEnv(String table, ContentValues v, byte[] plain, String aad) {
        try {
            v.put("blob", Envelope.seal(envelope(), plain, aad));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(aad, e);
        }
        db().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static byte[] openVault(byte[] b) {
        try {
            return Vault.open(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("vault", e);
        }
    }

    private static byte[] sealVault(byte[] b) {
        try {
            return Vault.seal(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("vault", e);
        }
    }

    private static String sa(SignalProtocolAddress a) { return "s|" + a.getName() + "|" + a.getDeviceId(); }

    private static String[] key(SignalProtocolAddress a) { return new String[]{a.getName(), String.valueOf(a.getDeviceId())}; }

    // ---- IdentityKeyStore ----

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        IdentityKeyPair id = sIdentity;
        if (id != null) return id;
        try (Cursor q = db().rawQuery("SELECT reg_id, blob FROM identity", null)) {
            if (!q.moveToFirst()) throw new IllegalStateException("sem identity (Keys.ensure não rodou)");
            sRegId = q.getInt(0);
            return sIdentity = new IdentityKeyPair(openVault(q.getBlob(1)));
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("identity", e);
        }
    }

    @Override
    public int getLocalRegistrationId() {
        if (sRegId == 0) getIdentityKeyPair();
        return sRegId;
    }

    /** Troca de chave do amigo = guardada já na nova; REPLACED_EXISTING deixa o chat avisar "o código de segurança mudou". */
    @Override
    public IdentityChange saveIdentity(SignalProtocolAddress a, IdentityKey key) {
        IdentityKey old = getIdentity(a);
        byte[] k = key.serialize();
        if (old != null && !replaced(old.serialize(), k)) return IdentityChange.NEW_OR_UNCHANGED;
        ContentValues v = new ContentValues(2);
        v.put("name", a.getName());
        putEnv("identities", v, k, "i|" + a.getName());
        return old == null ? IdentityChange.NEW_OR_UNCHANGED : IdentityChange.REPLACED_EXISTING;
    }

    /** Spec §7: confiança no primeiro uso; troca (reinstalação) é aceita e avisada, nunca bloqueia a conversa. */
    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress a, IdentityKey key, Direction direction) { return true; }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress a) {
        byte[] k = openEnv("SELECT blob FROM identities WHERE name = ?", "i|" + a.getName(), a.getName());
        try {
            return k == null ? null : new IdentityKey(k);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("identities", e);
        }
    }

    /** Pura (SignalStoreTest): a chave guardada existia e é outra. */
    static boolean replaced(byte[] stored, byte[] incoming) { return stored != null && !Arrays.equals(stored, incoming); }

    // ---- SessionStore ----

    @Override
    public SessionRecord loadSession(SignalProtocolAddress a) {
        byte[] r = openEnv("SELECT blob FROM sessions WHERE name = ? AND device = ?", sa(a), key(a));
        try {
            return r == null ? null : new SessionRecord(r);
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("sessions", e);
        }
    }

    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> as) throws NoSessionException {
        List<SessionRecord> out = new ArrayList<>(as.size());
        for (SignalProtocolAddress a : as) {
            SessionRecord r = loadSession(a);
            if (r == null) throw new NoSessionException("sem sessão: " + a.getName());
            out.add(r);
        }
        return out;
    }

    /** Devices além do principal (1), como no Signal. Hoje um aparelho por conta: normalmente vazio. */
    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        try (Cursor q = db().rawQuery("SELECT device FROM sessions WHERE name = ? AND device != 1", new String[]{name})) {
            List<Integer> out = new ArrayList<>(q.getCount());
            while (q.moveToNext()) out.add(q.getInt(0));
            return out;
        }
    }

    @Override
    public void storeSession(SignalProtocolAddress a, SessionRecord r) {
        ContentValues v = new ContentValues(3);
        v.put("name", a.getName());
        v.put("device", a.getDeviceId());
        putEnv("sessions", v, r.serialize(), sa(a));
    }

    @Override
    public boolean containsSession(SignalProtocolAddress a) {
        return blob("SELECT blob FROM sessions WHERE name = ? AND device = ?", key(a)) != null;
    }

    @Override
    public void deleteSession(SignalProtocolAddress a) { db().delete("sessions", "name = ? AND device = ?", key(a)); }

    @Override
    public void deleteAllSessions(String name) { db().delete("sessions", "name = ?", new String[]{name}); }

    // ---- PreKeyStore / SignedPreKeyStore (tabela prekeys do A3, Vault) ----

    private byte[] prekey(int id, int signed) throws InvalidKeyIdException {
        byte[] b = blob("SELECT blob FROM prekeys WHERE signed = ? AND id = ?", String.valueOf(signed), String.valueOf(id));
        if (b == null) throw new InvalidKeyIdException("prekey " + signed + "/" + id);
        return openVault(b);
    }

    private void putPrekey(int id, int signed, byte[] pub, byte[] sig, byte[] record) {
        ContentValues v = new ContentValues(5);
        v.put("id", id);
        v.put("signed", signed);
        v.put("pub", pub);
        if (sig != null) v.put("sig", sig);
        v.put("blob", sealVault(record));
        db().insertWithOnConflict("prekeys", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private boolean hasPrekey(int id, int signed) {
        return blob("SELECT blob FROM prekeys WHERE signed = ? AND id = ?", String.valueOf(signed), String.valueOf(id)) != null;
    }

    @Override
    public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        try {
            return new PreKeyRecord(prekey(id, 0));
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("prekeys", e);
        }
    }

    @Override
    public void storePreKey(int id, PreKeyRecord r) {
        try {
            putPrekey(id, 0, r.getKeyPair().getPublicKey().serialize(), null, r.serialize());
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("prekeys", e);
        }
    }

    @Override
    public boolean containsPreKey(int id) { return hasPrekey(id, 0); }

    /** One-time EC usada numa sessão nova: sai daqui (o servidor já a apagou ao entregar). */
    @Override
    public void removePreKey(int id) { db().delete("prekeys", "signed = 0 AND id = ?", new String[]{String.valueOf(id)}); }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        try {
            return new SignedPreKeyRecord(prekey(id, 1));
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("prekeys", e);
        }
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> out = new ArrayList<>();
        try (Cursor q = db().rawQuery("SELECT blob FROM prekeys WHERE signed = 1", null)) {
            while (q.moveToNext()) out.add(new SignedPreKeyRecord(openVault(q.getBlob(0))));
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("prekeys", e);
        }
        return out;
    }

    @Override
    public void storeSignedPreKey(int id, SignedPreKeyRecord r) {
        try {
            putPrekey(id, 1, r.getKeyPair().getPublicKey().serialize(), r.getSignature(), r.serialize());
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("prekeys", e);
        }
    }

    @Override
    public boolean containsSignedPreKey(int id) { return hasPrekey(id, 1); }

    @Override
    public void removeSignedPreKey(int id) { db().delete("prekeys", "signed = 1 AND id = ?", new String[]{String.valueOf(id)}); }

    // ---- KyberPreKeyStore ----

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        byte[] b = blob("SELECT blob FROM kyber_prekeys WHERE id = ?", String.valueOf(id));
        if (b == null) throw new InvalidKeyIdException("kyber " + id);
        try {
            return new KyberPreKeyRecord(openVault(b));
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("kyber_prekeys", e);
        }
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> out = new ArrayList<>();
        try (Cursor q = db().rawQuery("SELECT blob FROM kyber_prekeys", null)) {
            while (q.moveToNext()) out.add(new KyberPreKeyRecord(openVault(q.getBlob(0))));
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("kyber_prekeys", e);
        }
        return out;
    }

    /** Pela interface entra como one-time (last_resort = 0); a last resort só nasce no Keys. */
    @Override
    public void storeKyberPreKey(int id, KyberPreKeyRecord r) {
        try {
            ContentValues v = new ContentValues(5);
            v.put("id", id);
            v.put("last_resort", 0);
            v.put("pub", r.getKeyPair().getPublicKey().serialize());
            v.put("sig", r.getSignature());
            v.put("blob", sealVault(r.serialize()));
            db().insertWithOnConflict("kyber_prekeys", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("kyber_prekeys", e);
        }
    }

    @Override
    public boolean containsKyberPreKey(int id) {
        return blob("SELECT blob FROM kyber_prekeys WHERE id = ?", String.valueOf(id)) != null;
    }

    /**
     * One-time: apaga (uso único). Last resort: fica, mas o par (signed, base key) só vale uma vez; repetido =
     * mensagem reenviada/replay → ReusedBaseKeyException (como o InMemoryKyberPreKeyStore da libsignal).
     */
    @Override
    public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey) throws ReusedBaseKeyException {
        SQLiteDatabase db = db();
        String sid = String.valueOf(id);
        if (db.delete("kyber_prekeys", "id = ? AND last_resort = 0", new String[]{sid}) > 0) return;
        ContentValues v = new ContentValues(3);
        v.put("kyber_id", id);
        v.put("signed_id", signedId);
        v.put("base_key", baseKey.serialize());
        if (db.insertWithOnConflict("kyber_base_keys", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            throw new ReusedBaseKeyException();
        }
    }

    // ---- SenderKeyStore (localização: loc:{id}; grupos no chat) ----

    private static String ka(SignalProtocolAddress a, UUID dist) { return "k|" + a.getName() + "|" + a.getDeviceId() + "|" + dist; }

    @Override
    public void storeSenderKey(SignalProtocolAddress a, UUID dist, SenderKeyRecord r) {
        ContentValues v = new ContentValues(4);
        v.put("name", a.getName());
        v.put("device", a.getDeviceId());
        v.put("dist", dist.toString());
        putEnv("sender_keys", v, r.serialize(), ka(a, dist));
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress a, UUID dist) {
        byte[] r = openEnv("SELECT blob FROM sender_keys WHERE name = ? AND device = ? AND dist = ?", ka(a, dist),
                a.getName(), String.valueOf(a.getDeviceId()), dist.toString());
        try {
            return r == null ? null : new SenderKeyRecord(r);
        } catch (InvalidMessageException e) {
            throw new IllegalStateException("sender_keys", e);
        }
    }
}
