package com.example.spiffe.client;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Random;

/**
 * Calls the hello-server every second with a random name using the JDK
 * java.net.http.HttpClient, which accepts a custom SSLContext directly.
 * <p>
 * Two-layer hostname bypass for SPIFFE URI SANs:
 * 1. SSLParameters.setEndpointIdentificationAlgorithm("") - the JDK HTTP client
 * only overrides this when it is null; setting "" prevents "HTTPS" being
 * injected, so X509TrustManagerImpl.checkIdentity is never reached.
 * 2. SpiffeNoHostnameTrustManager - intercepts checkServerTrusted and clears
 * the algorithm on the SSLEngine as a belt-and-suspenders safety net.
 * <p>
 * CA chain validation is still enforced via the SPIFFE trust bundle.
 */
@ApplicationScoped
public class HelloScheduler {

    private static final Logger log = Logger.getLogger(HelloScheduler.class);

    private static final String[] NAMES = {
          "Alice", "Bob", "Charlie", "Diana", "Eve",
          "Frank", "Grace", "Hank", "Iris", "Jack"
    };

    private final Random random = new Random();

    @ConfigProperty(name = "hello.server.url")
    String serverUrl;

    @ConfigProperty(name = "spiffe.cert.dir", defaultValue = "/opt/spiffe-certs")
    String certDir;

    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        tryBuildClient();
    }

    @Scheduled(every = "1s", delayed = "5s")
    void callServer() {
        if (httpClient == null) {
            tryBuildClient();
            return;
        }
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

    private void tryBuildClient() {
        try {
            SSLContext sslContext = buildSpiffeSslContext();

            // Setting "" (not null) prevents the JDK HTTP client from injecting
            // "HTTPS" as the default endpointIdentificationAlgorithm for HTTPS URIs.
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm("");

            httpClient = HttpClient.newBuilder()
                  .sslContext(sslContext)
                  .sslParameters(sslParams)
                  .build();
            log.info("SPIFFE HTTP client initialised");
        } catch (Exception e) {
            log.debugf("SPIFFE certs not yet available (%s), will retry", e.getMessage());
        }
    }

    /**
     * Builds an SSLContext that:
     * - presents the SPIFFE SVID as the client certificate (mutual TLS)
     * - trusts only the SPIFFE CA bundle
     * - uses SpiffeNoHostnameTrustManager to skip DNS hostname verification
     */
    private SSLContext buildSpiffeSslContext() throws Exception {
        Path dir = Path.of(certDir);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // --- Trust store: SPIFFE CA bundle ---
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        try (FileInputStream fis = new FileInputStream(dir.resolve("bundle.0.pem").toFile())) {
            int i = 0;
            for (Certificate cert : cf.generateCertificates(fis)) {
                trustStore.setCertificateEntry("spiffe-ca-" + i++, cert);
            }
        }
        TrustManagerFactory tmf =
              TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        TrustManager[] wrappedTMs = Arrays.stream(tmf.getTrustManagers())
              .map(tm -> tm instanceof X509ExtendedTrustManager x509
                    ? new SpiffeNoHostnameTrustManager(x509)
                    : tm)
              .toArray(TrustManager[]::new);

        // --- Key store: SVID private key + certificate chain ---
        String keyPem = Files.readString(dir.resolve("svid.0.key"));
        byte[] keyBytes = Base64.getDecoder().decode(
              keyPem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("EC").generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        }

        Collection<? extends Certificate> certChain;
        try (FileInputStream fis = new FileInputStream(dir.resolve("svid.0.pem").toFile())) {
            certChain = cf.generateCertificates(fis);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("spiffe-svid", privateKey, new char[0],
              certChain.stream().map(c -> (X509Certificate) c).toArray(X509Certificate[]::new));

        KeyManagerFactory kmf =
              KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), wrappedTMs, null);
        return sslContext;
    }
}
