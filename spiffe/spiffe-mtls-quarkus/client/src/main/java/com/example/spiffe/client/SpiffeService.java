package com.example.spiffe.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Connects to the SPIRE Workload API to obtain X.509 SVIDs and writes them
 * to disk so the Quarkus TLS registry can load and hot-reload them.
 *
 * The SPIRE Agent socket path is configurable via the 'spire.socket.path'
 * property, allowing the SPIRE endpoint to be changed without rebuilding.
 */
@ApplicationScoped
public class SpiffeService {

    private static final Logger log = Logger.getLogger(SpiffeService.class);

    /**
     * Path to the SPIRE Agent Unix socket.
     * Format: unix:/path/to/agent.sock
     * Configured per-site by the deployment (env var SPIRE_SOCKET_PATH).
     */
    @ConfigProperty(name = "spire.socket.path", defaultValue = "unix:/run/spire/sockets/agent.sock")
    String spireSocketPath;

    /**
     * Directory where PEM files are written. Must match quarkus.tls.spiffe.* paths.
     * Populated initially by the spiffe-init container, then kept fresh by this service.
     */
    @ConfigProperty(name = "spiffe.cert.dir", defaultValue = "/opt/spiffe-certs")
    String certDir;

    private X509Source x509Source;

    void onStart(@Observes StartupEvent ev) {
        log.infof("Connecting to SPIRE Workload API at %s", spireSocketPath);
        try {
            DefaultX509Source.X509SourceOptions options = DefaultX509Source.X509SourceOptions.builder()
                    .spiffeSocketPath(spireSocketPath)
                    .build();
            x509Source = DefaultX509Source.newSource(options);
            X509Svid svid = x509Source.getX509Svid();
            log.infof("SPIFFE identity obtained: %s", svid.getSpiffeId());
            writeCerts();
        } catch (Exception e) {
            // If SPIRE is unavailable at startup the init-container certs are still valid.
            // The scheduler will retry on the next interval.
            log.warnf("Could not connect to SPIRE at startup (%s). Using init-container certs.", e.getMessage());
        }
    }

    /**
     * Periodically refreshes X.509 SVIDs from SPIRE and writes updated PEM files.
     * Quarkus TLS reload-period picks up file changes automatically.
     */
    @Scheduled(every = "60s", delayed = "60s")
    void refreshCerts() {
        if (x509Source == null) {
            try {
                DefaultX509Source.X509SourceOptions options = DefaultX509Source.X509SourceOptions.builder()
                        .spiffeSocketPath(spireSocketPath)
                        .build();
                x509Source = DefaultX509Source.newSource(options);
                log.info("Reconnected to SPIRE Workload API");
            } catch (Exception e) {
                log.warnf("SPIRE reconnect failed: %s", e.getMessage());
                return;
            }
        }
        try {
            writeCerts();
            log.debug("SPIFFE certs refreshed from SPIRE");
        } catch (Exception e) {
            log.errorf(e, "Failed to refresh SPIFFE certs from SPIRE");
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (x509Source != null) {
            try {
                x509Source.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIRE X509Source: %s", e.getMessage());
            }
        }
    }

    private void writeCerts() throws Exception {
        X509Svid svid = x509Source.getX509Svid();
        Path dir = Paths.get(certDir);
        Files.createDirectories(dir);

        // Write SVID certificate chain (may include intermediates).
        // Filename matches what `spire-agent api fetch x509 -write` produces.
        StringBuilder certPem = new StringBuilder();
        for (X509Certificate cert : svid.getChain()) {
            certPem.append(toPem("CERTIFICATE", cert.getEncoded()));
        }
        writeAtomic(dir.resolve("svid.0.pem"), certPem.toString());

        // Write SVID private key (PKCS#8 DER -> PEM)
        PrivateKey key = svid.getPrivateKey();
        writeAtomic(dir.resolve("svid.0.key"), toPem("PRIVATE KEY", key.getEncoded()));
    }

    private String toPem(String type, byte[] derEncoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(derEncoded)
                + "\n-----END " + type + "-----\n";
    }

    private void writeAtomic(Path target, String content) throws IOException {
        // Write to a temp file then rename to avoid partial reads during TLS reload
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
