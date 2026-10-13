package com.pokelocation.app.social.ws;

import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.social.net.Api;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * WebSocket mínimo (RFC 6455) sobre SSLSocket, sem OkHttp: só wss, só frames binários (payload = Frames), frames do
 * cliente mascarados, ping → pong, close. Thread "pl-ws" conecta, manda Auth, lê e reconecta com espera 1–60 s + jitter;
 * thread "pl-ws-w" escreve o que send()/stop() enfileiram: quem chama (inclusive a main) nunca faz I/O de rede.
 * Conexão morta: o servidor pinga a cada 30 s (S6); sem nada em 75 s, cai e reconecta.
 * Callbacks na thread "pl-ws". Quem cria (A8, :overlay) só faz isso com login.
 */
public final class WsClient {
    static final int CONT = 0, TEXT = 1, BIN = 2, CLOSE = 8, PING = 9, PONG = 10;
    // v1 só tem texto e localização (spec §10, sem mídia): 64 KB por mensagem sobra e limita o pico de RAM no :overlay
    private static final int MAX = 64 * 1024, QUEUE = 64, TIMEOUT_MS = 15_000, READ_TIMEOUT_MS = 75_000, HEAD_MAX = 8192;
    private static final long STABLE_MS = 60_000, MAX_WAIT_MS = 60_000;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] STOP = new byte[0];

    public interface Listener {
        /** Conectado e com o Auth enviado. */
        void onOpen();

        /** Frame do servidor. */
        void onFrame(Frames.Frame f);

        /** Caiu (não chamado depois de stop); reconecta sozinho. why.getMessage() "unauthorized" = token null. */
        void onClosed(IOException why);
    }

    /** Recebe a mensagem binária inteira (fragmentos já juntados). */
    interface Sink { void on(byte[] message) throws IOException; }

    static final class Raw {
        final int op;
        final boolean fin;
        final byte[] data;

        Raw(int op, boolean fin, byte[] data) {
            this.op = op;
            this.fin = fin;
            this.data = data;
        }
    }

    private final URI uri;
    private final Callable<String> token;
    private final Listener listener;
    private final Object writeLock = new Object();
    private volatile boolean stopped;
    private volatile Socket socket;
    private volatile OutputStream out;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE);
    private Thread thread;

    /** url = wss://host[:porta]/v1/ws; token = access token atual (chamado a cada conexão, na thread do WsClient). */
    public WsClient(String url, Callable<String> token, Listener listener) {
        uri = URI.create(url);
        if (!"wss".equals(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("só wss://host");
        this.token = token;
        this.listener = listener;
    }

    public synchronized void start() {
        if (thread != null || stopped) return;
        thread = new Thread(this::loop, "pl-ws");
        thread.start();
        new Thread(this::drain, "pl-ws-w").start();
    }

    /**
     * Para de reconectar e fecha: a "pl-ws-w" escreve o que já estava na fila (ex.: LocationOff), manda o close 1000 e
     * fecha. Não faz I/O aqui: pode chamar da main. Definitivo.
     */
    public void stop() {
        stopped = true;
        while (!queue.offer(STOP)) queue.poll(); // fila cheia: o STOP vale mais que o frame mais velho
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
        }
        if (t != null) t.interrupt(); // acorda o sleep do backoff
    }

    /**
     * Enfileira um frame do Frames para a "pl-ws-w". Não bloqueia nem faz I/O: pode chamar da main. false se
     * desconectado ou com a fila cheia (64): quem chama decide (posição descarta, chat fica na fila dele).
     */
    public boolean send(byte[] frame) { return !stopped && out != null && queue.offer(frame); }

    private void drain() {
        try {
            while (true) {
                byte[] f = queue.take();
                OutputStream o = out;
                if (f == STOP) {
                    try {
                        if (o != null) send(o, writeLock, CLOSE, new byte[]{0x03, (byte) 0xe8});
                    } catch (IOException | RuntimeException ignored) {
                    }
                    close(); // acorda o read da "pl-ws", que vê stopped e sai
                    return;
                }
                if (o == null) continue; // caiu/reconectando: descarta (não fecha o socket que o connect() monta)
                try {
                    send(o, writeLock, BIN, f);
                } catch (IOException | RuntimeException e) {
                    if (out == o) close(); // a "pl-ws" vê a queda e reconecta
                }
            }
        } catch (InterruptedException e) {
            close();
        }
    }

    private void close() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void loop() {
        int attempt = 0;
        while (!stopped) {
            long opened = 0;
            IOException why;
            try {
                connect();
                opened = System.currentTimeMillis();
                listener.onOpen();
                pump(new BufferedInputStream(socket.getInputStream(), 4096), out, writeLock,
                        m -> listener.onFrame(Frames.decode(m)));
                why = new IOException("closed by server");
            } catch (IOException e) {
                why = e;
            } catch (Exception e) { // token, NPE de socket fechado por stop(), etc.: nunca mata o processo
                why = new IOException(e);
            }
            close();
            // o que sobrou era da conexão velha (posições velhas não saem na nova); um stop() concorrente pode ter
            // tido o STOP limpo aqui: devolve
            queue.clear();
            if (stopped) {
                queue.offer(STOP);
                return;
            }
            try {
                listener.onClosed(why);
            } catch (RuntimeException ignored) {
                // código do A8 não derruba a "pl-ws" nem o processo
            }
            if (opened > 0 && System.currentTimeMillis() - opened >= STABLE_MS) attempt = 0;
            try {
                Thread.sleep(backoff(attempt++, RANDOM.nextDouble()));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void connect() throws Exception {
        String t = token.call(); // antes de conectar: o Auth tem de sair em até 5 s
        if (t == null) throw new IOException("unauthorized");
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        Socket raw = new Socket();
        socket = raw; // stop() durante o connect também fecha
        if (stopped) throw new IOException("stopped"); // stop() entre o close() dele e a linha acima
        raw.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        socket = s;
        if (stopped) throw new IOException("stopped");
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(p);
        s.setSoTimeout(TIMEOUT_MS);
        s.startHandshake();
        // reforço: Conscrypt antigo pode ignorar o algoritmo de identificação
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
            throw new SSLPeerUnverifiedException(host);
        }
        byte[] k = new byte[16];
        RANDOM.nextBytes(k);
        String key = Api.b64(k, false);
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        OutputStream o = s.getOutputStream();
        o.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + (port == 443 ? "" : ":" + port)
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: pokelocation/" + BuildConfig.VERSION_NAME + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        o.flush();
        checkHandshake(head(s.getInputStream()), key);
        s.setSoTimeout(READ_TIMEOUT_MS);
        send(o, writeLock, BIN, Frames.auth(t)); // Auth antes de publicar o out: nenhum send() passa na frente dele
        out = o;
    }

    /** Cabeçalho da resposta até \r\n\r\n, byte a byte (nada do 1º frame é consumido), teto de 8 KB. */
    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(256);
        int end = 0;
        while (end < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("handshake");
            if (b.size() >= HEAD_MAX) throw new ProtocolException("handshake too large");
            b.write(c);
            end = c == (end % 2 == 0 ? '\r' : '\n') ? end + 1 : c == '\r' ? 1 : 0;
        }
        return b.toString("ISO-8859-1");
    }

    // ---- puros (WsClientTest) ----

    /** Sec-WebSocket-Accept esperado: base64(SHA-1(key + GUID)). */
    static String accept(String key) {
        try {
            return Api.b64(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII)), false);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 101 + Upgrade: websocket + Connection: Upgrade + Sec-WebSocket-Accept certo; senão ProtocolException. */
    static void checkHandshake(String head, String key) throws IOException {
        String[] lines = head.split("\r\n");
        if (!lines[0].startsWith("HTTP/1.1 101")) throw new ProtocolException("status " + lines[0]);
        boolean upgrade = false, connection = false, accepted = false;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String name = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), v = lines[i].substring(c + 1).trim();
            if (name.equals("upgrade")) upgrade = v.equalsIgnoreCase("websocket");
            else if (name.equals("connection")) connection = v.toLowerCase(Locale.ROOT).contains("upgrade");
            else if (name.equals("sec-websocket-accept")) accepted = v.equals(accept(key));
        }
        if (!upgrade || !connection || !accepted) throw new ProtocolException("handshake"); // RFC 6455 §4.1
    }

    /** Frame do cliente: FIN + op, bit de máscara, tamanho (7/16/64 bits), chave, payload XOR chave. */
    static byte[] frame(int op, byte[] payload, byte[] mask) {
        int n = payload.length, h = n < 126 ? 2 : n < 65536 ? 4 : 10;
        byte[] f = new byte[h + 4 + n];
        f[0] = (byte) (0x80 | op);
        if (n < 126) {
            f[1] = (byte) (0x80 | n);
        } else if (n < 65536) {
            f[1] = (byte) (0x80 | 126);
            f[2] = (byte) (n >> 8);
            f[3] = (byte) n;
        } else {
            f[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((long) n >> (56 - 8 * i));
        }
        System.arraycopy(mask, 0, f, h, 4);
        for (int i = 0; i < n; i++) f[h + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        return f;
    }

    private static void send(OutputStream out, Object lock, int op, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        byte[] f = frame(op, payload, mask);
        synchronized (lock) {
            out.write(f);
            out.flush();
        }
    }

    /** Frame do servidor: sem máscara (RFC 6455 §5.1), sem RSV, tamanho ≤ max. */
    static Raw read(InputStream in, int max) throws IOException {
        int b0 = in.read(), b1 = in.read();
        if (b0 < 0 || b1 < 0) throw new EOFException();
        if ((b0 & 0x70) != 0) throw new ProtocolException("rsv");
        if ((b1 & 0x80) != 0) throw new ProtocolException("masked server frame");
        long n = b1 & 0x7f;
        if (n == 126) n = number(in, 2);
        else if (n == 127) n = number(in, 8);
        if (n < 0 || n > max) throw new ProtocolException("size " + n);
        byte[] d = new byte[(int) n];
        for (int i = 0, r; i < d.length; i += r) {
            r = in.read(d, i, d.length - i);
            if (r < 0) throw new EOFException();
        }
        return new Raw(b0 & 0x0f, (b0 & 0x80) != 0, d);
    }

    private static long number(InputStream in, int bytes) throws IOException {
        long n = 0;
        for (int i = 0; i < bytes; i++) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            n = n << 8 | c;
        }
        return n;
    }

    /** Lê até o close do servidor (responde e retorna): binários (com fragmentos) ao sink, ping → pong; texto = erro. */
    static void pump(InputStream in, OutputStream out, Object lock, Sink sink) throws IOException {
        ByteArrayOutputStream msg = null;
        while (true) {
            Raw r = read(in, MAX);
            if (r.op >= CLOSE && (!r.fin || r.data.length > 125)) throw new ProtocolException("control frame");
            switch (r.op) {
                case PING:
                    send(out, lock, PONG, r.data);
                    break;
                case PONG:
                    break;
                case CLOSE:
                    send(out, lock, CLOSE, r.data.length >= 2 ? Arrays.copyOf(r.data, 2) : new byte[0]);
                    return;
                case BIN:
                    if (msg != null) throw new ProtocolException("interleaved");
                    if (r.fin) {
                        sink.on(r.data);
                    } else {
                        msg = new ByteArrayOutputStream(r.data.length * 2);
                        msg.write(r.data, 0, r.data.length);
                    }
                    break;
                case CONT:
                    if (msg == null) throw new ProtocolException("continuation");
                    if (msg.size() + r.data.length > MAX) throw new ProtocolException("message too large");
                    msg.write(r.data, 0, r.data.length);
                    if (r.fin) {
                        sink.on(msg.toByteArray());
                        msg = null;
                    }
                    break;
                default: // texto e opcodes reservados
                    throw new ProtocolException("opcode " + r.op);
            }
        }
    }

    /** Espera antes da tentativa attempt (0 = 1ª falha): 1 s dobrando até 60 s, + até 50% de jitter, teto 60 s. */
    static long backoff(int attempt, double rnd) {
        long base = Math.min(MAX_WAIT_MS, 1000L << Math.min(attempt, 16));
        return Math.min(MAX_WAIT_MS, base + (long) (rnd * base / 2));
    }
}
