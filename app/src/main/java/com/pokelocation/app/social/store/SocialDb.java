package com.pokelocation.app.social.store;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.pokelocation.app.social.crypto.SignalLoader;

/**
 * Banco do social (chaves do Signal, A3), separado do pokelocation.db: fica FORA do DbBackup (restaurar ratchet/prekeys
 * velhos corrompe sessões; os blobs dependem da chave do Keystore, que não viaja). Só aberto com login; logout apaga o arquivo.
 * Acesso SÓ no Io (single-thread): Session.clear fecha o helper, e outra thread com o db aberto quebraria.
 * v1 (A3): identity/prekeys/kyber_prekeys, blob = Vault.seal(registro); pub/sig = parte pública em claro (vai ao
 * servidor sem abrir o Vault); up = já enviado no PUT /v1/keys.
 * v2 (A7): sessions/identities/sender_keys, blob = Envelope.seal(chave-envelope, registro, endereço da linha);
 * envelope = a chave-envelope selada pelo Vault (Keystore só ao abrir); kyber_base_keys = base keys já vistas na last resort.
 */
public final class SocialDb extends SQLiteOpenHelper {
    public static final String NAME = "social.db";
    private static SocialDb sHelper;

    private SocialDb(Context c) {
        super(c.getApplicationContext(), NAME, null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    public static synchronized SQLiteDatabase get(Context c) {
        if (sHelper == null) sHelper = new SocialDb(c);
        return sHelper.getWritableDatabase();
    }

    /** Logout: fecha e apaga o arquivo (com -wal/-shm) e esquece as chaves em memória. */
    public static synchronized void delete(Context c) {
        if (sHelper != null) sHelper.close();
        sHelper = null;
        c.deleteDatabase(NAME);
        // sem a lib, os caches estão vazios e nada de org.signal é carregado à toa
        if (SignalLoader.loaded()) SignalStore.forget();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE identity(id INTEGER PRIMARY KEY CHECK(id = 0), reg_id INTEGER NOT NULL, pub BLOB NOT NULL,"
                + " up INTEGER NOT NULL DEFAULT 0, blob BLOB NOT NULL)");
        db.execSQL("CREATE TABLE prekeys(id INTEGER NOT NULL, signed INTEGER NOT NULL, pub BLOB NOT NULL, sig BLOB,"
                + " up INTEGER NOT NULL DEFAULT 0, blob BLOB NOT NULL, PRIMARY KEY(signed, id)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE kyber_prekeys(id INTEGER PRIMARY KEY, last_resort INTEGER NOT NULL, pub BLOB NOT NULL,"
                + " sig BLOB NOT NULL, up INTEGER NOT NULL DEFAULT 0, blob BLOB NOT NULL)");
        createV2(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createV2(db);
    }

    private static void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE envelope(id INTEGER PRIMARY KEY CHECK(id = 0), blob BLOB NOT NULL)");
        db.execSQL("CREATE TABLE sessions(name TEXT NOT NULL, device INTEGER NOT NULL, blob BLOB NOT NULL,"
                + " PRIMARY KEY(name, device)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE identities(name TEXT PRIMARY KEY, blob BLOB NOT NULL) WITHOUT ROWID");
        db.execSQL("CREATE TABLE sender_keys(name TEXT NOT NULL, device INTEGER NOT NULL, dist TEXT NOT NULL,"
                + " blob BLOB NOT NULL, PRIMARY KEY(name, device, dist)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE kyber_base_keys(kyber_id INTEGER NOT NULL, signed_id INTEGER NOT NULL, base_key BLOB NOT NULL,"
                + " PRIMARY KEY(kyber_id, signed_id, base_key)) WITHOUT ROWID");
    }
}
