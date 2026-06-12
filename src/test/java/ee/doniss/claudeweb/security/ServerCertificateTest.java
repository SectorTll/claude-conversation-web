package ee.doniss.claudeweb.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCertificateTest {

    private static final String FIXTURE = "classpath:certs/test-keystore.p12";

    private ServerCertificate cert(String keyStore, String password, String alias) {
        return new ServerCertificate(new DefaultResourceLoader(), keyStore, password, "PKCS12", alias);
    }

    @Test
    void extractsDerAndPemFromTheKeystore() {
        ServerCertificate c = cert(FIXTURE, "changeit", "claudeweb");
        assertTrue(c.available());
        byte[] der = c.der();
        assertTrue(der.length > 100);
        assertEquals(0x30, der[0] & 0xFF, "DER SEQUENCE tag");

        String pem = c.pem();
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"));
        assertTrue(pem.lines().filter(l -> !l.startsWith("-----")).allMatch(l -> l.length() <= 64));
    }

    @Test
    void blankAliasFallsBackToTheFirstEntry() {
        assertTrue(cert(FIXTURE, "changeit", "").available());
    }

    @Test
    void unavailableWithoutKeystoreOrOnBadPassword() {
        assertFalse(cert("", "", "").available(), "no keystore configured (dev http)");
        assertFalse(cert("file:./does-not-exist.p12", "changeit", "").available());
        assertFalse(cert(FIXTURE, "wrong-password", "").available());
        assertThrows(IllegalStateException.class, () -> cert("", "", "").der());
    }
}
