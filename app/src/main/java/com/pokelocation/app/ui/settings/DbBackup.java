package com.pokelocation.app.ui.settings;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.pokelocation.app.data.Db;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Backup/restore do banco (F93). Todos os métodos são BLOQUEANTES: chamar no Io. */
final class DbBackup {
    private static final byte[] HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final long CHUNK = 8L << 20;

    private DbBackup() {}

    static String fileName() {
        return "pokelocation_" + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date()) + ".db";
    }

    /** Checkpoint do WAL e cópia do arquivo do banco para o documento escolhido. */
    static void save(Context c, Uri dest) throws IOException {
        Db.checkpoint(c);
        try (FileInputStream in = new FileInputStream(Db.file(c));
             ParcelFileDescriptor pfd = openForWrite(c, dest);
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            FileChannel src = in.getChannel();
            // transferTo escreve na posição atual do destino: vale também se o documento for um pipe.
            FileChannel dst = out.getChannel();
            long size = src.size(), pos = 0;
            // transferTo pode copiar menos que o pedido: repete até o fim.
            while (pos < size) {
                long n = src.transferTo(pos, Math.min(CHUNK, size - pos), dst);
                if (n <= 0) throw new IOException("short copy");
                pos += n;
            }
        }
    }

    /**
     * Copia o documento para um arquivo temporário ao lado do banco, validando antes o header SQLite.
     * O banco atual não é tocado. false = não é um arquivo SQLite ("Wrong file type").
     */
    static boolean stage(Context c, Uri src) throws IOException {
        File tmp = staged(c);
        try (ParcelFileDescriptor pfd = open(c, src, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            ByteBuffer head = ByteBuffer.allocate(HEADER.length);
            while (head.hasRemaining() && ch.read(head) > 0) { /* lê os 16 bytes */ }
            if (head.hasRemaining() || !Arrays.equals(head.array(), HEADER)) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                FileChannel dst = out.getChannel();
                head.flip();
                dst.write(head);
                // Laço read/write em vez de transferFrom: com origem FileChannel ele usa size(), que é 0 num pipe.
                ByteBuffer buf = ByteBuffer.allocate(64 << 10);
                while (ch.read(buf) != -1) {
                    buf.flip();
                    while (buf.hasRemaining()) dst.write(buf);
                    buf.clear();
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    /** Fecha o banco, apaga -wal/-shm e põe o arquivo temporário no lugar (rename atômico). */
    static boolean swap(Context c) {
        File db = Db.file(c), tmp = staged(c);
        if (!tmp.exists()) return false;
        Db.close();
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(db.getPath() + suffix).delete();
        }
        return tmp.renameTo(db);
    }

    private static File staged(Context c) {
        return new File(Db.file(c).getPath() + ".restore");
    }

    /** "wt" trunca se o documento já existir; provedores sem suporte a "wt" caem para "w". */
    private static ParcelFileDescriptor openForWrite(Context c, Uri uri) throws FileNotFoundException {
        try {
            return open(c, uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return open(c, uri, "w");
        }
    }

    private static ParcelFileDescriptor open(Context c, Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, mode);
        if (pfd == null) throw new FileNotFoundException(String.valueOf(uri));
        return pfd;
    }
}
