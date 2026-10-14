package com.pokelocation.app.social.store;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Banco do social, separado do pokelocation.db e fora do DbBackup: restaurar ratchet/prekeys velhos corrompe sessões,
 * e os blobs dependem da chave do Keystore, que não viaja. Só existe com login; o logout apaga o arquivo.
 * identity/prekeys/kyber_prekeys: blob = Vault.seal(registro); pub/sig em claro para o PUT /v1/keys sem abrir o Vault;
 * up = já aceito pelo servidor. sessions/identities/sender_keys: blob = Envelope.seal com aad = endereço da linha.
 *
 * Multiprocesso (principal e :overlay): o arquivo é a única fonte da verdade e toda operação Signal que lê e grava
 * estado roda inteira em atomic(): files/social.lock (FileLock, entre processos) e BEGIN IMMEDIATE, nessa ordem.
 * Nunca na main: pode esperar o outro processo.
 */
public final class SocialDb extends SQLiteOpenHelper {
    public static final String NAME = "social.db";
    private static final String LOCK_FILE = "social.lock";
    /** Monitor do processo: o FileLock é por processo, então as threads daqui se excluem neste objeto. */
    private static final Object LOCK = new Object();
    private static int sDepth;               // reentrância no mesmo thread (guardado por LOCK)
    private static boolean sAtomic;          // dentro de atomic() (guardado por LOCK)
    private static RandomAccessFile sLockFile; // aberto enquanto sDepth > 0 (guardado por LOCK)
    private static SocialDb sHelper;
    /** Geração do social.db que este processo enxerga; -1 = ainda não conferiu. Guardada no social.lock (nunca apagado). */
    private static long sGen = -1;

    public interface Op<T> { T run() throws Exception; }

    private SocialDb(Context c) {
        super(c.getApplicationContext(), NAME, null, 5);
        setWriteAheadLoggingEnabled(true);
    }

    /** Sob o lock (atomic/locked). */
    public static synchronized SQLiteDatabase get(Context c) {
        if (sHelper == null) sHelper = new SocialDb(c);
        return sHelper.getWritableDatabase();
    }

    public static boolean exists(Context c) { return c.getDatabasePath(NAME).exists(); }

    /**
     * Lock entre processos + transação IMMEDIATE, reentrante no mesmo thread. IllegalStateException("logged out") se o
     * outro processo saiu.
     */
    public static <T> T atomic(Context c, Op<T> op) throws Exception { return atomic(c, false, op); }

    /** create = pode criar o social.db (só o login); senão, sem arquivo = "logged out", nunca um banco vazio. */
    public static <T> T atomic(Context c, boolean create, Op<T> op) throws Exception {
        return locked(c, () -> {
            if (sAtomic) return op.run(); // aninhada: já está na transação de fora
            if (!create && !exists(c)) throw new IllegalStateException("logged out");
            SQLiteDatabase db = get(c);
            db.beginTransactionNonExclusive(); // BEGIN IMMEDIATE
            sAtomic = true;
            try {
                T r = op.run();
                db.setTransactionSuccessful();
                return r;
            } finally {
                sAtomic = false;
                db.endTransaction();
            }
        });
    }

    /** Para o SignalStore recusar uso fora de atomic(). */
    static boolean inAtomic() {
        synchronized (LOCK) {
            return sAtomic;
        }
    }

    /** Sem transação: leitura simples ou o delete do logout. */
    public static <T> T locked(Context c, Op<T> op) throws Exception {
        return exclusive(new File(c.getFilesDir(), LOCK_FILE), () -> {
            if (sDepth == 1) sync(c); // 1ª entrada: o outro processo pode ter feito logout (e login)
            return op.run();
        });
    }

    /** Exclusivo entre processos (FileLock) e entre threads (monitor); reentrante. */
    public static <T> T exclusive(File lock, Op<T> op) throws Exception {
        synchronized (LOCK) {
            if (sDepth > 0) { // mesmo thread (os outros esperam no monitor)
                sDepth++;
                try {
                    return op.run();
                } finally {
                    sDepth--;
                }
            }
            try (RandomAccessFile f = new RandomAccessFile(lock, "rw"); FileLock ignored = f.getChannel().lock()) {
                sDepth = 1;
                sLockFile = f;
                try {
                    return op.run();
                } finally {
                    sLockFile = null;
                    sDepth = 0;
                }
            }
        }
    }

    /** Geração atual (8 bytes no início do social.lock; vazio = 0). Só sob o lock. */
    static long generation() throws IOException {
        RandomAccessFile f = sLockFile;
        if (f.length() < 8) return 0;
        f.seek(0);
        return f.readLong();
    }

    /** Logout: nova geração (o outro processo vê na próxima operação). Só sob o lock. */
    static long bumpGeneration() throws IOException {
        long g = generation() + 1;
        sLockFile.seek(0);
        sLockFile.writeLong(g);
        return g;
    }

    /** Login (só o principal, sob o lock): nova geração, já vista por este processo; o :overlay descarta os caches. */
    public static void advance() throws IOException {
        synchronized (SocialDb.class) {
            sGen = bumpGeneration();
        }
    }

    /**
     * Geração mudou (logout, talvez seguido de login, no outro processo): fecha o arquivo velho e esquece TODO cache
     * (chave-envelope, identity, SecretKey do Vault, access). Se agora não há arquivo → IllegalStateException("logged out").
     */
    private static void sync(Context c) throws IOException {
        long g = generation();
        synchronized (SocialDb.class) {
            if (g == sGen) return;
            boolean seen = sGen >= 0;
            closeAndForget();
            sGen = g;
            if (seen && !exists(c)) throw new IllegalStateException("logged out");
        }
    }

    private static void closeAndForget() {
        if (sHelper != null) sHelper.close();
        sHelper = null;
        Session.forget();
        Vault.forget();
        // sem a lib, os caches estão vazios e nada de org.signal é carregado à toa
        if (SignalLoader.loaded()) SignalStore.forget();
    }

    /** Logout (só o processo principal): sob o lock, apaga o arquivo e abre uma nova geração. */
    public static void delete(Context c) {
        try {
            locked(c, () -> {
                synchronized (SocialDb.class) {
                    closeAndForget();
                    c.deleteDatabase(NAME);
                    sGen = bumpGeneration();
                }
                return null;
            });
        } catch (IllegalStateException e) {
            // já apagado (geração nova vista no sync); nada a fazer
        } catch (Exception e) {
            // lock indisponível (I/O): apaga mesmo assim; logout nunca falha.
            // ponytail: sem o incremento o :overlay só percebe no próximo login; raro.
            synchronized (SocialDb.class) {
                closeAndForget();
            }
            c.deleteDatabase(NAME);
        }
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
        createV3(db);
        createV4(db);
        db.execSQL(SEEN_ENVELOPES_AT);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createV2(db);
        if (oldVersion < 3) createV3(db);
        if (oldVersion < 4) createV4(db);
        if (oldVersion < 5) db.execSQL(SEEN_ENVELOPES_AT);
    }

    /** O WS do :overlay e o REST do principal podem trazer o mesmo envelope antes do ack. Podado após 31 dias (a fila
     *  do servidor expira em 30). */
    static final String SEEN_ENVELOPES = "CREATE TABLE seen_envelopes(sender TEXT NOT NULL, msg_id TEXT NOT NULL,"
            + " at INTEGER NOT NULL, PRIMARY KEY(sender, msg_id)) WITHOUT ROWID";
    /** A poda por data roda a cada envelope recebido; sem o índice percorre a tabela inteira. */
    static final String SEEN_ENVELOPES_AT = "CREATE INDEX IF NOT EXISTS seen_envelopes_at ON seen_envelopes(at)";

    private static void createV4(SQLiteDatabase db) { db.execSQL(SEEN_ENVELOPES); }

    /** Fora do prefs para os dois processos verem os mesmos tokens. */
    private static void createV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session(id INTEGER PRIMARY KEY CHECK(id = 0), access BLOB NOT NULL, refresh BLOB NOT NULL)");
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
