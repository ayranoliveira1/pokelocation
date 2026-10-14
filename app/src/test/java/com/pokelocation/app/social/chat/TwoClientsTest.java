package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;

import org.junit.Test;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.util.Arrays;
import java.util.UUID;

/** Store em memória da libsignal (o do app é SQLite do Android) com a mesma política de identity do app. */
public class TwoClientsTest {
    private static final String G = "11111111-2222-3333-4444-555555555555";

    private static final class Client {
        final String id;
        final InMemorySignalProtocolStore st;
        final Api.Bundle bundle = new Api.Bundle();

        Client(String id) throws Exception {
            this.id = id;
            IdentityKeyPair ik = IdentityKeyPair.generate();
            st = new InMemorySignalProtocolStore(ik, KeyHelper.generateRegistrationId(false)) {
                @Override public boolean isTrustedIdentity(SignalProtocolAddress a, IdentityKey k, IdentityKeyStore.Direction d) {
                    return true;
                }
            };
            long now = System.currentTimeMillis();
            ECKeyPair spk = ECKeyPair.generate(), pk = ECKeyPair.generate();
            byte[] spkSig = ik.getPrivateKey().calculateSignature(spk.getPublicKey().serialize());
            st.storeSignedPreKey(1, new SignedPreKeyRecord(1, now, spk, spkSig));
            st.storePreKey(7, new PreKeyRecord(7, pk));
            KEMKeyPair kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] kemSig = ik.getPrivateKey().calculateSignature(kem.getPublicKey().serialize());
            st.storeKyberPreKey(1, new KyberPreKeyRecord(1, now, kem, kemSig));
            bundle.registrationId = st.getLocalRegistrationId();
            bundle.identity = ik.getPublicKey().serialize();
            bundle.signed = preKey(1, spk.getPublicKey().serialize(), spkSig);
            bundle.preKey = preKey(7, pk.getPublicKey().serialize(), null);
            bundle.kyber = preKey(1, kem.getPublicKey().serialize(), kemSig);
        }

        SignalProtocolAddress addr() { return new SignalProtocolAddress(id, 1); }

        byte[] send(Client to, String text) throws Exception {
            byte[] env = Locations.encryptTo(st, to.id, Content.msg(text(text)), null);
            return env != null ? env : Locations.encryptTo(st, to.id, Content.msg(text(text)), to.bundle);
        }

        String read(Client from, byte[] env) throws Exception {
            return Content.msg(Inbox.decrypt1to1(st, from.addr(), env)).text;
        }
    }

    private static Api.PreKey preKey(int id, byte[] pub, byte[] sig) {
        Api.PreKey p = new Api.PreKey();
        p.id = id;
        p.pub = pub;
        p.sig = sig;
        return p;
    }

    private static Chat.Msg text(String s) {
        Chat.Msg m = new Chat.Msg();
        m.type = Chat.TEXT;
        m.ts = 1000;
        m.text = s;
        return m;
    }

    @Test public void oneToOneBothWays() throws Exception {
        Client a = new Client("a"), b = new Client("b");
        byte[] first = a.send(b, "oi");
        assertEquals(CiphertextMessage.PREKEY_TYPE, first[0]);
        assertEquals("oi", b.read(a, first));
        byte[] reply = b.send(a, "olá");
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply[0]);
        assertEquals("olá", a.read(b, reply));
    }

    @Test public void outOfOrderDecryptsAndReplayIsAcked() throws Exception {
        Client a = new Client("a"), b = new Client("b");
        b.read(a, a.send(b, "0"));
        a.read(b, b.send(a, "ok"));
        byte[] m1 = a.send(b, "1"), m2 = a.send(b, "2"), m3 = a.send(b, "3");
        assertEquals("3", b.read(a, m3));
        assertEquals("1", b.read(a, m1));
        assertEquals("2", b.read(a, m2));
        try {
            b.read(a, m2);
            fail("repetida decifrou");
        } catch (DuplicateMessageException e) {
            assertTrue(Locations.ack(e));
        }
    }

    @Test public void reinstalledPeerGetsNewIdentityAndOldInFlightIsAcked() throws Exception {
        Client a = new Client("a"), b = new Client("b");
        b.read(a, a.send(b, "antes"));
        a.read(b, b.send(a, "ok"));
        byte[] inFlight = a.send(b, "em trânsito");
        IdentityKey old = a.st.getIdentity(b.addr());

        Client b2 = new Client("b");
        a.st.deleteSession(b.addr());
        byte[] fresh = a.send(b2, "depois");
        IdentityKey now = a.st.getIdentity(b.addr());
        assertFalse(Arrays.equals(old.serialize(), now.serialize()));
        assertEquals("depois", b2.read(a, fresh));
        try {
            b2.read(a, inFlight);
            fail("sessão velha decifrou no aparelho novo");
        } catch (Exception e) {
            assertTrue(e.getClass().getSimpleName(), Locations.ack(e));
        }
    }

    @Test public void groupMessageBeforeSkdmIsDecryptedLater() throws Exception {
        Client a = new Client("a"), b = new Client("b");
        UUID dist = UUID.fromString(G);
        SenderKeyDistributionMessage skdm = new GroupSessionBuilder(a.st).create(a.addr(), dist);
        GroupCipher out = new GroupCipher(a.st, a.addr()), in = new GroupCipher(b.st, a.addr());
        byte[] g1 = out.encrypt(dist, Content.msg(text("g1"))).serialize();
        byte[] g2 = out.encrypt(dist, Content.msg(text("g2"))).serialize();

        try {
            in.decrypt(g1);
            fail("decifrou sem a SKDM");
        } catch (NoSessionException e) {
            assertFalse(Rules.ephemeral(UUID.randomUUID().toString()));
            assertTrue(Rules.ephemeral(Rules.liveId()));
        }

        byte[] env = a.send(b, "x");
        b.read(a, env);
        byte[] skdmEnv = Locations.encryptTo(a.st, b.id, Locations.frame(Content.SKDM, skdm.serialize()), null);
        byte[] plain = Inbox.decrypt1to1(b.st, a.addr(), skdmEnv);
        assertEquals(Content.SKDM, plain[0]);
        new GroupSessionBuilder(b.st).process(a.addr(), new SenderKeyDistributionMessage(Arrays.copyOfRange(plain, 1, plain.length)));

        assertEquals("g2", Content.msg(in.decrypt(g2)).text);
        assertEquals("g1", Content.msg(in.decrypt(g1)).text);
    }

    @Test public void liveUpdateKeepsStartIdThroughGroup() throws Exception {
        Client a = new Client("a"), b = new Client("b");
        UUID dist = UUID.fromString(G);
        SenderKeyDistributionMessage skdm = new GroupSessionBuilder(a.st).create(a.addr(), dist);
        new GroupSessionBuilder(b.st).process(a.addr(), new SenderKeyDistributionMessage(skdm.serialize()));
        Chat.Msg m = new Chat.Msg();
        m.type = Chat.LIVE_START;
        m.text = "start-id";
        m.lat = -23.5;
        m.lng = -46.6;
        m.ts = 1000;
        m.liveUntil = 1000 + 3600_000L;
        Chat.Msg got = Content.msg(new GroupCipher(b.st, a.addr()).decrypt(
                new GroupCipher(a.st, a.addr()).encrypt(dist, Content.msg(m)).serialize()));
        assertNotNull(got);
        assertEquals(Chat.LIVE_START, got.type);
        assertEquals("start-id", got.text);
        assertArrayEquals(new double[]{-23.5, -46.6}, new double[]{got.lat, got.lng}, 1e-9);
        assertTrue(Content.acceptable(got));
    }
}
