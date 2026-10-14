package com.pokelocation.app.social.chat;

import android.content.Context;
import android.util.Log;

import com.pokelocation.app.App;
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

/** Alguém saiu → sender key nova: quem saiu não lê o que vem depois. */
final class Groups {
    private static final Map<String, Set<String>> SENT = new ConcurrentHashMap<>();

    private Groups() {}

    static String myId(Context c) throws IOException {
        String id = Api.jwtSubject(Session.access(c));
        if (id == null) throw new Api.Error(401, "unauthorized");
        return id;
    }

    static List<Api.Friend> others(List<Api.Friend> members, String me) {
        List<Api.Friend> out = new ArrayList<>(members.size());
        for (Api.Friend f : members) if (!f.userId.equals(me)) out.add(f);
        return out;
    }

    static void sync(Context c, String groupId) throws IOException {
        String me = myId(c);
        Api.Group g;
        try {
            g = Api.group(c, groupId);
        } catch (Api.Error e) {
            if (e.http != 404) throw e;
            leave(c, groupId);
            return;
        }
        List<Api.Friend> now = others(g.members, me);
        boolean someoneLeft = Locations.atomic(c, () -> {
            ChatDb.ensureConversation(c, groupId, true, null, null);
            ChatDb.rejoin(c, groupId);
            Set<String> before = new HashSet<>(), after = new HashSet<>();
            for (Api.Friend f : ChatDb.members(c, groupId)) before.add(f.userId);
            for (Api.Friend f : now) after.add(f.userId);
            ChatDb.setMembers(c, groupId, now);
            if (!before.equals(after)) ChatDb.setKeyOk(c, groupId, false);
            before.removeAll(after);
            if (before.isEmpty()) return false;
            new SignalStore(c).deleteSenderKey(new SignalProtocolAddress(me, 1), UUID.fromString(groupId));
            return true;
        });
        if (someoneLeft) SENT.remove(groupId);
    }

    static void leave(Context c, String groupId) throws IOException {
        Locations.atomic(c, () -> {
            ChatDb.leave(c, groupId);
            new SignalStore(c).deleteSenderKeys(UUID.fromString(groupId));
            return null;
        });
        SENT.remove(groupId);
        if (App.process() == App.PROC_MAIN) Live.hint(c); // o social_share só tem um escritor
    }

    /** Sem WS na hora, o GroupChanged não fica guardado: grupo local que sumiu da lista do servidor = saí. */
    static void leaveMissing(Context c) {
        try {
            Set<String> now = new HashSet<>();
            for (Api.Group g : Api.groups(c)) now.add(g.id);
            for (String gid : Locations.atomic(c, () -> ChatDb.activeGroups(c))) if (!now.contains(gid)) leave(c, gid);
        } catch (IOException e) {
            Log.w("Groups", "groups: " + e.getClass().getSimpleName());
        }
    }

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
            // entregue só no Ack S→C (o servidor gravou): enfileirado não basta
            ChatSocket.send(Frames.envelope(member, id, seal1to1(c, member, skdm, null)), id, () -> {
                done.add(member);
                markKeyOk(c, groupId, done);
            }, null);
            sent = true;
        }
        if (!sent) markKeyOk(c, groupId, done);
        // o nome só no GroupEnvelope do próprio grupo: o servidor confere a membresia
        if (sent && name != null) {
            byte[] ct = Locations.atomic(c, () -> encrypt(c, me, groupId, Content.groupInfo(groupId, name), true));
            ChatSocket.send(Frames.groupEnvelope(groupId, UUID.randomUUID().toString(), ct), null, null, null);
        }
    }

    private static void markKeyOk(Context c, String groupId, Set<String> done) {
        if (SENT.get(groupId) != done) return; // Ack de uma distribuição anterior à rotação
        try {
            Locations.atomic(c, () -> {
                for (Api.Friend m : ChatDb.members(c, groupId)) if (!done.contains(m.userId)) return null;
                ChatDb.setKeyOk(c, groupId, true);
                return null;
            });
        } catch (IOException e) {
            Log.w("Groups", "key ok: " + e.getClass().getSimpleName());
        }
    }

    static void distributeLives(Context c) {
        try {
            for (Object[] l : Locations.atomic(c, () -> ChatDb.myLives(c, System.currentTimeMillis()))) {
                String gid = (String) l[0];
                if ((boolean) l[1] && !Locations.atomic(c, () -> ChatDb.keyOk(c, gid))) distribute(c, gid);
            }
        } catch (IOException e) {
            Log.w("Groups", "lives: " + e.getClass().getSimpleName());
        }
    }

    /** Os mesmos parâmetros do Signal (5200 iterações, versão 2): o código bate com o de outro cliente Signal. */
    static String safetyNumber(Context c, String friendUserId) throws IOException {
        String me = myId(c);
        SignalProtocolAddress a = new SignalProtocolAddress(friendUserId, 1);
        IdentityKey theirs = Locations.atomic(c, () -> new SignalStore(c).getIdentity(a));
        if (theirs == null) {
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

    /** create = false no :overlay, que não distribui: uma chave criada ali sairia sem ninguém a ter recebido. */
    static byte[] encrypt(Context c, String me, String groupId, byte[] plain, boolean create) throws Exception {
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress a = new SignalProtocolAddress(me, 1);
        UUID dist = UUID.fromString(groupId);
        try {
            return new GroupCipher(st, a).encrypt(dist, plain).serialize();
        } catch (NoSessionException e) {
            if (!create) return null;
            new GroupSessionBuilder(st).create(a, dist);
            return new GroupCipher(st, a).encrypt(dist, plain).serialize();
        }
    }
}
