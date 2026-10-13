package com.pokelocation.app.social.crypto;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.store.Vault;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chaves do Signal deste aparelho (A3): identity, signed prekey, kyber last resort, 100 one-time EC e 100 Kyber,
 * guardadas cifradas no social.db e enviadas por PUT /v1/keys; repõe um lote quando o servidor tem < 20.
 * SÓ no Io, com login e SignalLoader.loaded(): classe separada do SignalLoader para nada de org.signal ser resolvido
 * antes do System.load. Entrada: SignalLoader.kick.
 */
final class Keys {
    static final int BATCH = 100, LOW = 20;

    private Keys() {}

    /** Banco sob SocialDb.atomic (regra multiprocesso); rede fora do lock (um PUT lento não trava o :overlay). */
    static void ensure(Context c) throws IOException {
        if (!SignalLoader.loaded() || !Session.loggedIn(c)) return;
        try {
            boolean pending = SocialDb.atomic(c, () -> {
                SQLiteDatabase db = SocialDb.get(c);
                if (DatabaseUtils.queryNumEntries(db, "identity") == 0) create(db);
                return pending(db);
            });
            if (!pending) {
                int[] n = Api.keyCount(c);
                if (n[0] >= LOW && n[1] >= LOW) return;
                SocialDb.atomic(c, () -> {
                    refill(SocialDb.get(c), n[0] < LOW, n[1] < LOW);
                    return null;
                });
            }
            // pendente = gerado e ainda não aceito pelo servidor (falha de rede antes): reenvia sem gerar de novo
            Api.putKeys(c, SocialDb.atomic(c, () -> bundle(SocialDb.get(c))));
            // ponytail: marca todas as up=0; certo porque só este método (Io do principal) gera chaves
            SocialDb.atomic(c, () -> {
                SQLiteDatabase db = SocialDb.get(c);
                for (String t : new String[]{"identity", "prekeys", "kyber_prekeys"}) db.execSQL("UPDATE " + t + " SET up = 1 WHERE up = 0");
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        } catch (InvalidKeyException e) {
            throw new IOException("keys", e);
        } catch (Exception e) {
            throw new IOException("keys", e);
        }
    }

    // ponytail: signed prekey e kyber last resort nunca rotacionam (o Signal rotaciona ~30 dias); a spec não pede.
    // Rotacionar = gerar nova com id+1, PUT, e manter a anterior alguns dias para mensagens em trânsito.
    private static void create(SQLiteDatabase db) throws GeneralSecurityException {
        IdentityKeyPair id = IdentityKeyPair.generate();
        ECKeyPair spk = ECKeyPair.generate();
        byte[] spkPub = spk.getPublicKey().serialize(), spkSig = id.getPrivateKey().calculateSignature(spkPub);
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues(4);
            v.put("id", 0);
            v.put("reg_id", KeyHelper.generateRegistrationId(false));
            v.put("pub", id.getPublicKey().serialize());
            v.put("blob", Vault.seal(id.serialize()));
            db.insertOrThrow("identity", null, v);
            put(db, "prekeys", 1, "signed", 1, spkPub, spkSig, new SignedPreKeyRecord(1, now, spk, spkSig).serialize());
            kyber(db, id, 1, true, now);
            ec(db, BATCH);
            kyber(db, id, BATCH, false, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void refill(SQLiteDatabase db, boolean ec, boolean kyber) throws GeneralSecurityException, InvalidKeyException {
        IdentityKeyPair id = null;
        if (kyber) {
            try (Cursor q = db.rawQuery("SELECT blob FROM identity", null)) {
                q.moveToFirst();
                id = new IdentityKeyPair(Vault.open(q.getBlob(0)));
            }
        }
        db.beginTransaction();
        try {
            if (ec) ec(db, BATCH);
            if (kyber) kyber(db, id, BATCH, false, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ponytail: ids = MAX+1; o Signal usa 24 bits (0xFFFFFF) → ~167 mil lotes antes de dar a volta, sem tratamento.
    private static int next(SQLiteDatabase db, String table, String where) {
        return (int) DatabaseUtils.longForQuery(db, "SELECT IFNULL(MAX(id), 0) + 1 FROM " + table + " WHERE " + where, null);
    }

    private static void ec(SQLiteDatabase db, int n) throws GeneralSecurityException {
        int first = next(db, "prekeys", "signed = 0");
        for (int i = first; i < first + n; i++) {
            ECKeyPair kp = ECKeyPair.generate();
            put(db, "prekeys", i, "signed", 0, kp.getPublicKey().serialize(), null, new PreKeyRecord(i, kp).serialize());
        }
    }

    private static void kyber(SQLiteDatabase db, IdentityKeyPair id, int n, boolean lastResort, long now)
            throws GeneralSecurityException {
        int first = next(db, "kyber_prekeys", "1");
        for (int i = first; i < first + n; i++) {
            KEMKeyPair kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] pub = kp.getPublicKey().serialize(), sig = id.getPrivateKey().calculateSignature(pub);
            put(db, "kyber_prekeys", i, "last_resort", lastResort ? 1 : 0, pub, sig, new KyberPreKeyRecord(i, now, kp, sig).serialize());
        }
    }

    private static void put(SQLiteDatabase db, String table, int id, String flag, int flagValue, byte[] pub, byte[] sig,
                            byte[] record) throws GeneralSecurityException {
        ContentValues v = new ContentValues(6);
        v.put("id", id);
        v.put(flag, flagValue);
        v.put("pub", pub);
        if (sig != null) v.put("sig", sig);
        v.put("blob", Vault.seal(record));
        db.insertOrThrow(table, null, v);
    }

    private static boolean pending(SQLiteDatabase db) {
        return DatabaseUtils.longForQuery(db, "SELECT (SELECT COUNT(*) FROM identity WHERE up = 0)"
                + " + (SELECT COUNT(*) FROM prekeys WHERE up = 0) + (SELECT COUNT(*) FROM kyber_prekeys WHERE up = 0)", null) > 0;
    }

    /** Só a parte pública em claro: não abre o Vault nem toca a libsignal. */
    private static Api.KeyBundle bundle(SQLiteDatabase db) {
        Api.KeyBundle b = new Api.KeyBundle();
        try (Cursor q = db.rawQuery("SELECT reg_id, pub FROM identity", null)) {
            q.moveToFirst();
            b.registrationId = q.getInt(0);
            b.identity = q.getBlob(1);
        }
        b.signed = keys(db, "SELECT id, pub, sig FROM prekeys WHERE signed = 1 ORDER BY id DESC LIMIT 1").get(0);
        b.lastResort = keys(db, "SELECT id, pub, sig FROM kyber_prekeys WHERE last_resort = 1 ORDER BY id DESC LIMIT 1").get(0);
        b.ec = keys(db, "SELECT id, pub, sig FROM prekeys WHERE signed = 0 AND up = 0");
        b.kyber = keys(db, "SELECT id, pub, sig FROM kyber_prekeys WHERE last_resort = 0 AND up = 0");
        return b;
    }

    private static List<Api.PreKey> keys(SQLiteDatabase db, String sql) {
        try (Cursor q = db.rawQuery(sql, null)) {
            List<Api.PreKey> out = new ArrayList<>(q.getCount());
            while (q.moveToNext()) {
                Api.PreKey k = new Api.PreKey();
                k.id = q.getInt(0);
                k.pub = q.getBlob(1);
                k.sig = q.isNull(2) ? null : q.getBlob(2);
                out.add(k);
            }
            return out;
        }
    }
}
