package ee.doniss.claudeweb.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Enumeration;

/**
 * The server's own TLS certificate, extracted from the configured keystore. PUBLIC material only
 * (the same bytes every TLS handshake already sends) — served unauthenticated so fresh LAN clients
 * can add it to their trust store BEFORE anything else works without warnings (and the service
 * worker / web push needs a trusted origin, not just an accepted warning).
 */
@Component
public class ServerCertificate {

    private static final Logger log = LoggerFactory.getLogger(ServerCertificate.class);

    private final ResourceLoader resources;
    private final String keyStore;
    private final String keyStorePassword;
    private final String keyStoreType;
    private final String keyAlias;

    private volatile byte[] der;
    private volatile boolean loadFailed;

    public ServerCertificate(ResourceLoader resources,
                             @Value("${server.ssl.key-store:}") String keyStore,
                             @Value("${server.ssl.key-store-password:}") String keyStorePassword,
                             @Value("${server.ssl.key-store-type:PKCS12}") String keyStoreType,
                             @Value("${server.ssl.key-alias:}") String keyAlias) {
        this.resources = resources;
        this.keyStore = keyStore;
        this.keyStorePassword = keyStorePassword;
        this.keyStoreType = keyStoreType;
        this.keyAlias = keyAlias;
    }

    /** False when no keystore is configured/readable (e.g. the plain-http dev profile). */
    public boolean available() {
        return load() != null;
    }

    /** DER bytes of the certificate (the {@code .cer} download). */
    public byte[] der() {
        byte[] bytes = load();
        if (bytes == null) {
            throw new IllegalStateException("server certificate unavailable");
        }
        return bytes.clone();
    }

    /** PEM rendering (64-char base64 lines) for embedding into install scripts. */
    public String pem() {
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        String b64 = Base64.getEncoder().encodeToString(der());
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        }
        return sb.append("-----END CERTIFICATE-----\n").toString();
    }

    private byte[] load() {
        byte[] cached = der;
        if (cached != null) {
            return cached;
        }
        if (loadFailed || keyStore.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (der != null || loadFailed) {
                return der;
            }
            try {
                Resource resource = resources.getResource(keyStore);
                if (!resource.exists()) {
                    loadFailed = true;
                    return null;
                }
                KeyStore ks = KeyStore.getInstance(keyStoreType.isBlank() ? "PKCS12" : keyStoreType);
                try (InputStream in = resource.getInputStream()) {
                    ks.load(in, keyStorePassword.toCharArray());
                }
                Certificate cert = findCertificate(ks);
                if (cert == null) {
                    loadFailed = true;
                    return null;
                }
                der = cert.getEncoded();
                return der;
            } catch (Exception e) {
                log.warn("could not read the TLS certificate from {}: {}", keyStore, e.toString());
                loadFailed = true;
                return null;
            }
        }
    }

    private Certificate findCertificate(KeyStore ks) throws Exception {
        if (!keyAlias.isBlank() && ks.containsAlias(keyAlias)) {
            return ks.getCertificate(keyAlias);
        }
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            Certificate cert = ks.getCertificate(aliases.nextElement());
            if (cert != null) {
                return cert;
            }
        }
        return null;
    }
}
