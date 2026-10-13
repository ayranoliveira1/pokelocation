package com.pokelocation.app.social.store;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Banco do social (chaves do Signal, A3), separado do pokelocation.db: fica FORA do DbBackup (restaurar ratchet/prekeys
 * velhos corrompe sessões; os blobs dependem da chave do Keystore, que não viaja). Só aberto com login; logout apaga o arquivo.
 * blob = Vault.seal(registro serializado).
 */
public final class SocialDb extends SQLiteOpenHelper {
    public static final String NAME = "social.db";
    private static SocialDb sHelper;

    private SocialDb(Context c) {
        super(c.getApplicationContext(), NAME, null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    public static synchronized SQLiteDatabase get(Context c) {
        if (sHelper == null) sHelper = new SocialDb(c);
        return sHelper.getWritableDatabase();
    }

    /** Logout: fecha e apaga o arquivo (com -wal/-shm). */
    public static synchronized void delete(Context c) {
        if (sHelper != null) sHelper.close();
        sHelper = null;
        c.deleteDatabase(NAME);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE identity(id INTEGER PRIMARY KEY, blob BLOB NOT NULL)");
        db.execSQL("CREATE TABLE prekeys(id INTEGER NOT NULL, signed INTEGER NOT NULL, blob BLOB NOT NULL,"
                + " PRIMARY KEY(signed, id)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE kyber_prekeys(id INTEGER PRIMARY KEY, last_resort INTEGER NOT NULL,"
                + " used INTEGER NOT NULL DEFAULT 0, blob BLOB NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
}
