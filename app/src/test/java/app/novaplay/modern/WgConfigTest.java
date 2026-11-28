package app.novaplay.modern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * WarpAccount.wgQuick() read by the real WireGuard library's parser (the one the tunnel uses on the phone), and the
 * library's keys checked against what WarpAccount accepts. A configuration the library rejects would only show up on a
 * phone, after registering and the VPN question, so it is checked here before an APK is built.
 */
public class WgConfigTest {
    private static final String APP = "app.novaplay.modern";

    private static WarpAccount account(String v6, String endpoint) {
        KeyPair mine = new KeyPair();
        KeyPair theirs = new KeyPair();
        return new WarpAccount(mine.getPrivateKey().toBase64(), theirs.getPublicKey().toBase64(), "172.16.0.2", v6, endpoint,
                new int[]{2408, 500, 4500, 1701}, "id", "token");
    }

    private static Config parse(String text) throws Exception {
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void appOnlyConfigIsAccepted() throws Exception {
        WarpAccount a = account("2606:4700:110:8a36:df92:102a:9602:fa7b", "162.159.192.1");
        Config c = parse(a.wgQuick(APP, 2408));
        assertEquals(2, c.getInterface().getAddresses().size());
        assertEquals(4, c.getInterface().getDnsServers().size());
        assertTrue(c.getInterface().getMtu().isPresent());
        assertEquals(1280, c.getInterface().getMtu().get().intValue());
        assertEquals(1, c.getInterface().getIncludedApplications().size());
        assertTrue(c.getInterface().getIncludedApplications().contains(APP));
        assertTrue(c.getInterface().getExcludedApplications().isEmpty());
        assertEquals(1, c.getPeers().size());
        assertEquals(2, c.getPeers().get(0).getAllowedIps().size());
        assertEquals(25, c.getPeers().get(0).getPersistentKeepalive().get().intValue());
        assertEquals("162.159.192.1", c.getPeers().get(0).getEndpoint().get().getHost());
        assertEquals(2408, c.getPeers().get(0).getEndpoint().get().getPort());
        String back = c.toWgQuickString();
        assertTrue(back, back.contains("IncludedApplications = " + APP));
        assertTrue(back, back.contains("172.16.0.2/32"));
    }

    @Test
    public void wholePhoneConfigHasNoApplicationList() throws Exception {
        Config c = parse(account("2606:4700:110:8a36:df92:102a:9602:fa7b", "162.159.192.1").wgQuick("", 2408));
        assertTrue(c.getInterface().getIncludedApplications().isEmpty());
        assertTrue(c.getInterface().getExcludedApplications().isEmpty());
        assertEquals(1, c.getPeers().size());
    }

    @Test
    public void ipv4OnlyAccountIsAccepted() throws Exception {
        Config c = parse(account("", "162.159.192.1").wgQuick(APP, 500));
        assertEquals(1, c.getInterface().getAddresses().size());
        assertEquals(2, c.getInterface().getDnsServers().size());
        assertEquals(500, c.getPeers().get(0).getEndpoint().get().getPort());
    }

    @Test
    public void everyPortIsAccepted() throws Exception {
        WarpAccount a = account("2606:4700:110:8a36:df92:102a:9602:fa7b", "162.159.192.1");
        for (int p : a.ports) {
            Config c = parse(a.wgQuick(APP, p));
            assertEquals(p, c.getPeers().get(0).getEndpoint().get().getPort());
        }
    }

    @Test
    public void ipv6EndpointIsAccepted() throws Exception {
        Config c = parse(account("2606:4700:110:8a36:df92:102a:9602:fa7b", "2606:4700:d0::a29f:c001").wgQuick(APP, 2408));
        assertEquals(2408, c.getPeers().get(0).getEndpoint().get().getPort());
    }

    @Test
    public void keysFromTheLibraryAreAcceptedAndKeepTheirPublicKey() throws Exception {
        KeyPair kp = new KeyPair();
        String priv = kp.getPrivateKey().toBase64();
        String pub = kp.getPublicKey().toBase64();
        assertTrue(WarpAccount.validKey(priv));
        assertTrue(WarpAccount.validKey(pub));
        assertFalse(priv.equals(pub));
        assertEquals(pub, new KeyPair(Key.fromBase64(priv)).getPublicKey().toBase64());
    }

    @Test
    public void everyConfigCarriesItsOwnPrivateKey() throws Exception {
        WarpAccount a = account("", "162.159.192.1");
        Config c = parse(a.wgQuick(APP, 2408));
        assertEquals(a.privateKey, c.getInterface().getKeyPair().getPrivateKey().toBase64());
        assertEquals(a.peerKey, c.getPeers().get(0).getPublicKey().toBase64());
    }
}
