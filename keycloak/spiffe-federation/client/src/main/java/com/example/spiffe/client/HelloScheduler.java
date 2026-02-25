package com.example.spiffe.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Random;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeTrustManagerFactory;
import io.spiffe.spiffeid.SpiffeIdUtils;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Calls the hello-server every second with a random name using the JDK
 * java.net.http.HttpClient and a SSLContext initialised/updated by the java-spiffe libraries.
 */
@ApplicationScoped
public class HelloScheduler {

    private static final Logger log = Logger.getLogger(HelloScheduler.class);

    // Quarkus servers only enable v1.3 by default
    private static final String TLS_PROTOCOL = "TLSv1.3";

    private static final String[] NAMES = {
          "Alice", "Bob", "Charlie", "Diana", "Eve",
          "Frank", "Grace", "Hank", "Iris", "Jack"
    };

    private final Random random = new Random();

    @ConfigProperty(name = "hello.server.url")
    String serverUrl;

    @ConfigProperty(name = "spiffe.ids", defaultValue = "*")
    String spiffeIds;

    @ConfigProperty(name = "spiffe.tls", defaultValue = "MTLS")
    TLSConfig tlsMode;

    enum TLSConfig {
        MTLS,
        TLS,
        LEGACY
    }

    private volatile X509Source x509Source;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        // Initialize the X509Source. This opens the gRPC stream to the SPIRE Agent specified by SPIFFE_ENDPOINT_SOCKET
        // and automatically rotates the certs stored in-memory.
        try {
            x509Source = DefaultX509Source.newSource();

            log.info("Configuring SSLContext TLS=" + tlsMode);
            httpClient = HttpClient.newBuilder()
                  .sslContext(getContext())
                  .build();
        } catch (X509SourceException | SocketEndpointAddressException e) {
            log.error("Failed to initialize SPIFFE X509Source: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to generate SSL Context: " + e.getMessage());
        }
    }

    SSLContext getContext() throws Exception {
        boolean allowAllIds = "*".equals(spiffeIds);
        return switch (tlsMode) {
            case MTLS -> {
                var optionsBuilder = SpiffeSslContextFactory.SslContextOptions.builder()
                      .sslProtocol(TLS_PROTOCOL)
                      .x509Source(x509Source);

                if (allowAllIds) {
                    optionsBuilder.acceptAnySpiffeId();
                } else {
                    optionsBuilder.acceptedSpiffeIdsSupplier(() -> SpiffeIdUtils.toSetOfSpiffeIds(spiffeIds));
                }
                yield SpiffeSslContextFactory.getSslContext(optionsBuilder.build());
            }
            case TLS -> {
                SpiffeTrustManagerFactory trustManagerFactory = new SpiffeTrustManagerFactory();
                TrustManager[] trustManagers = allowAllIds ?
                      trustManagerFactory.engineGetTrustManagers(x509Source) :
                      trustManagerFactory.engineGetTrustManagers(x509Source, () -> SpiffeIdUtils.toSetOfSpiffeIds(spiffeIds));
                SSLContext ctx = SSLContext.getInstance(TLS_PROTOCOL);
                ctx.init(null, trustManagers, null);
                yield ctx;
            }
            case LEGACY -> {
                String trustDomainName = "demo.example.com";
                X509Certificate caCertificate = x509Source.getBundleForTrustDomain(TrustDomain.parse(trustDomainName)).getX509Authorities().iterator().next();
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setCertificateEntry(trustDomainName, caCertificate);

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keyStore);

                SSLContext ctx = SSLContext.getInstance(TLS_PROTOCOL);
                ctx.init(null, tmf.getTrustManagers(), null);
                yield ctx;
            }
        };
    }

    @Scheduled(every = "60s", delayed = "5s")
    void callServer() {
        String name = NAMES[random.nextInt(NAMES.length)];
        try {
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(serverUrl + "/hello/" + name))
                  .GET()
                  .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.infof("Response: %s", response.body());
        } catch (Exception e) {
            log.errorf("Failed to call hello-server: %s", e.getMessage());
        }
    }

    @PreDestroy
    void cleanup() {
        if (httpClient != null) {
            httpClient.close();
        }

        if (x509Source != null) {
            try {
                x509Source.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIFFE X509Source: %s", e.getMessage());
            }
        }
    }
}
