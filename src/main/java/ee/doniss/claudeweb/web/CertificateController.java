package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.security.ServerCertificate;
import ee.doniss.claudeweb.support.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Distributes the server's PUBLIC TLS certificate so LAN clients can trust it in one click
 * (required for web push: a service worker only registers on a TRUSTED origin). Deliberately
 * outside {@code /api} — reachable pre-login (a fresh client can't log in comfortably before
 * trusting the cert), but still behind the IP allowlist. No private material is exposed: these
 * are the same bytes every TLS handshake already sends.
 */
@RestController
@RequestMapping("/cert")
public class CertificateController {

    private final ServerCertificate certificate;

    public CertificateController(ServerCertificate certificate) {
        this.certificate = certificate;
    }

    /** The certificate as a {@code .cer} download (DER — double-clickable on Windows/macOS). */
    @GetMapping("/claudeweb.cer")
    public ResponseEntity<byte[]> certificate() {
        requireAvailable();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"claudeweb.cer\"")
                .contentType(MediaType.parseMediaType("application/x-x509-ca-cert"))
                .body(certificate.der());
    }

    /**
     * A self-contained Windows installer: writes the embedded PEM to a temp file and imports it
     * into the current user's Trusted Root store via {@code certutil} (one Windows confirmation
     * dialog). Restart the browser afterwards.
     */
    @GetMapping("/install-cert.bat")
    public ResponseEntity<byte[]> installScript() {
        requireAvailable();
        String script = buildWindowsInstaller(certificate.pem());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"install-cert.bat\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(script.getBytes(StandardCharsets.US_ASCII));
    }

    private void requireAvailable() {
        if (!certificate.available()) {
            throw new NotFoundException(
                    "no TLS keystore configured (plain-http dev mode needs no certificate trust)");
        }
    }

    private static String buildWindowsInstaller(String pem) {
        StringBuilder sb = new StringBuilder();
        line(sb, "@echo off");
        line(sb, "REM Trusts the claude-conversation-web self-signed certificate for the CURRENT user.");
        line(sb, "REM Windows will show one security prompt - confirm it, then RESTART your browser.");
        line(sb, "setlocal");
        line(sb, "set \"CER=%TEMP%\\claudeweb.cer\"");
        line(sb, "(");
        for (String pemLine : pem.strip().split("\n")) {
            line(sb, "echo " + pemLine);
        }
        line(sb, ") > \"%CER%\"");
        line(sb, "certutil -addstore -user Root \"%CER%\"");
        line(sb, "if errorlevel 1 (");
        line(sb, "  echo.");
        line(sb, "  echo Import FAILED or was cancelled. Manual route: double-click \"%CER%\" and");
        line(sb, "  echo install it into \"Trusted Root Certification Authorities\".");
        line(sb, ") else (");
        line(sb, "  del \"%CER%\" >nul 2>nul");
        line(sb, "  echo.");
        line(sb, "  echo Done. Restart your browser, then enable push in the app again.");
        line(sb, "  echo Firefox keeps its own store: enable security.enterprise_roots.enabled in about:config.");
        line(sb, ")");
        line(sb, "pause");
        return sb.toString();
    }

    /** batch files want CRLF line endings. */
    private static void line(StringBuilder sb, String s) {
        sb.append(s).append("\r\n");
    }
}
