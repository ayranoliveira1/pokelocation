package com.pokelocation.app.social.chat;

import android.content.Context;

import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SignalStore;
import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.ws.Frames;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grupos: fan-out no servidor (api.md §7). Minha sender key do grupo (distribuição = o id do grupo) vai a cada membro
 * por 1:1, junto do nome (GROUP_INFO). Alguém saiu → chave nova (quem saiu não lê o que vem depois).
 */
final class Groups {
    /** Por grupo, quem já recebeu minha sender key nesta vida do processo; quem recebe processa repetida sem problema. */
    private static final Map<String, Set<String>> SENT = new ConcurrentHashMap<>();

    private Groups() {}

    static String myId(Context c) throws IOException {
        String id = Api.jwtSubject(Session.access(c));
        if (id == null) throw new Api.Error(401, "unauthorized");
        return id;
    }

    /** Membros sem mim, como o ChatDb guarda (rótulo = @nome). */
    static List<Api.Friend> others(List<Api.Friend> members, String me) {
        List<Api.Friend> out = new ArrayList<>(members.size());
        for (Api.Friend f : members) if (!f.userId.equals(me)) out.add(f);
        return out;
    }

    /** Rede fora do lock: GET do grupo; depois banco sob o atomic. 404 = saí ou fui removido → a conversa some. */
    static void sync(Context c, String groupId) throws IOException {
        String me = myId(c);
        Api.Group g;
        try {
            g = Api.group(c, groupId);
        } catch (Api.Error e) {
            if (e.http != 404) throw e;
            forget(c, groupId);
            return;
        }
        List<Api.Friend> now = others(g.members, me);
        boolean someoneLeft = Locations.atomic(c, () -> {
            ChatDb.ensureConversation(c, groupId, true, null, null);
            Set<String> before = new HashSet<>();
            for (Api.Friend f : ChatDb.members(c, groupId)) before.add(f.userId);
            ChatDb.setMembers(c, groupId, now);
            for (Api.Friend f : now) before.remove(f.userId);
            if (before.isEmpty()) return false;
            new SignalStore(c).deleteSenderKey(new SignalProtocolAddress(me, 1), UUID.fromString(groupId));
            return true;
        });
        if (someoneLeft) SENT.remove(groupId); // a chave nova vai a todos no próximo envio
    }

    /** Saí ou fui removido: a conversa e as sender keys do grupo (minha e dos outros) somem. */
    static void forget(Context c, String groupId) throws IOException {
        Locations.atomic(c, () -> {
            ChatDb.deleteConversation(c, groupId);
            new SignalStore(c).deleteSenderKeys(UUID.fromString(groupId));
            return null;
        });
        SENT.remove(groupId);
    }

    /**
     * Envelope 1:1 para o amigo: com sessão, dentro do atomic; sem, o bundle vem fora do lock (bundle != null já
     * buscado). Se a identity dele mudou, avisa no 1:1 também ao enviar.
     */
    static byte[] seal1to1(Context c, String peer, byte[] plain, Api.Bundle bundle) throws IOException {
        Chat.Msg[] notice = {null};
        SocialDb.Op<byte[]> op = () -> {
            SignalStore st = new SignalStore(c);
            byte[] env = Locations.encryptTo(st, peer, plain, bundle);
            if (env != null && st.replaced(peer)) notice[0] = Inbox.notice(c, peer, "identity:" + UUID.randomUUID());
            return env;
        };
        byte[] env = Locations.atomic(c, op);
        if (env == null) {
            Api.Bundle b = Api.keyBundle(c, peer);
            env = Locations.atomic(c, () -> {
                SignalStore st = new SignalStore(c);
                byte[] e = Locations.encryptTo(st, peer, plain, b);
                if (st.replaced(peer)) notice[0] = Inbox.notice(c, peer, "identity:" + UUID.randomUUID());
                return e;
            });
        }
        if (notice[0] != null) ChatSocket.dispatch(notice[0]);
        return env;
    }

    /** SKDM + nome a cada membro que ainda não tem minha sender key. Precisa do WS aberto (vai por Envelope 1:1). */
    static void distribute(Context c, String groupId) throws IOException {
        String me = myId(c);
        Set<String> done = SENT.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet());
        Chat.Conv conv = Chat.conversation(c, groupId);
        String name = conv != null ? conv.title : null;
        boolean sent = false;
        for (Api.Friend m : Chat.members(c, groupId)) {
            if (done.contains(m.userId)) continue;
            byte[] skdm = Locations.atomic(c, () -> {
                SenderKeyDistributionMessage d = new GroupSessionBuilder(new SignalStore(c))
                        .create(new SignalProtocolAddress(me, 1), UUID.fromString(groupId));
                return Locations.frame(Content.SKDM, d.serialize());
            });
            String id = UUID.randomUUID().toString();
            String member = m.userId;
            // só no Ack S→C (o servidor gravou): enfileirado não basta, e sem a minha chave as cópias dele seriam ilegíveis
            ChatSocket.send(Frames.envelope(member, id, seal1to1(c, member, skdm, null)), id, () -> done.add(member), null);
            sent = true;
        }
        // o nome só dentro do GroupEnvelope do próprio grupo (o servidor confere a membresia); quem ainda não tem a
        // minha SKDM guarda na fila até ela chegar
        if (sent && name != null) {
            byte[] ct = Locations.atomic(c, () -> encrypt(c, me, groupId, Content.groupInfo(groupId, name)));
            ChatSocket.send(Frames.groupEnvelope(groupId, UUID.randomUUID().toString(), ct), null, null, null);
        }
    }

    /** Código de segurança (60 dígitos): os mesmos parâmetros do Signal (5200 iterações, versão 2, id estável = o id). */
    static String safetyNumber(Context c, String friendUserId) throws IOException {
        String me = myId(c);
        SignalProtocolAddress a = new SignalProtocolAddress(friendUserId, 1);
        IdentityKey theirs = Locations.atomic(c, () -> new SignalStore(c).getIdentity(a));
        if (theirs == null) { // ainda sem sessão com ele: a do servidor (rede fora do lock)
            try {
                theirs = new IdentityKey(Api.keyBundle(c, friendUserId).identity);
            } catch (InvalidKeyException e) {
                throw new IOException("bad identity");
            }
        }
        IdentityKey mine = Locations.atomic(c, () -> new SignalStore(c).getIdentityKeyPair().getPublicKey());
        return new NumericFingerprintGenerator(5200).createFor(2, me.getBytes(StandardCharsets.UTF_8), mine,
                friendUserId.getBytes(StandardCharsets.UTF_8), theirs).getDisplayableFingerprint().getDisplayText();
    }

    /** Dentro do atomic: ciphertext do GroupCipher com a minha sender key (criada na 1ª vez). */
    static byte[] encrypt(Context c, String me, String groupId, byte[] plain) throws Exception {
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress a = new SignalProtocolAddress(me, 1);
        UUID dist = UUID.fromString(groupId);
        try {
            return new GroupCipher(st, a).encrypt(dist, plain).serialize();
        } catch (NoSessionException e) {
            new GroupSessionBuilder(st).create(a, dist);
            return new GroupCipher(st, a).encrypt(dist, plain).serialize();
        }
    }
}
