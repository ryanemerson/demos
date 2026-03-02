package com.example.spiffe.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Random;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.spiffe.exception.JwtSourceException;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeTrustManagerFactory;
import io.spiffe.spiffeid.SpiffeIdUtils;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.workloadapi.CachedJwtSource;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.JwtSource;
import io.spiffe.workloadapi.X509Source;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ZeroTrustContext {

    private static final Logger log = Logger.getLogger(ZeroTrustContext.class);

    // Quarkus servers only enable v1.3 by default
    private static final String TLS_PROTOCOL = "TLSv1.3";

    @ConfigProperty(name = "server.url")
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
    private volatile JwtSource jwtSource;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        // Initialize SPIFFE sources. These open gRPC streams to the SPIRE Agent specified by
        // SPIFFE_ENDPOINT_SOCKET and automatically rotate the certs/tokens stored in-memory.
        try {
            x509Source = DefaultX509Source.newSource();
            jwtSource = CachedJwtSource.newSource();

            log.info("Configuring SSLContext TLS=" + tlsMode);
            httpClient = HttpClient.newBuilder()
                  .sslContext(getContext())
                  .build();
        } catch (X509SourceException | JwtSourceException | SocketEndpointAddressException e) {
            log.error("Failed to initialize SPIFFE source: " + e.getMessage());
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

    HttpClient getHttpClient() {
        return httpClient;
    }

    String getServerUrl() {
        return serverUrl;
    }

    String fetchAccessToken() throws Exception {
        String keycloakHost = "https://keycloak.keycloak.svc.cluster.local:8443";
        // Fetch a JWT-SVID from the SPIRE agent for the Keycloak audience.
        String jwtToken = jwtSource.fetchJwtSvid(keycloakHost + "/realms/spiffe").getToken();
        logJwt("JWT-SVID", jwtToken);

        // Exchange the JWT-SVID for a Keycloak access token using the client_credentials grant.
        String formBody = "grant_type=client_credentials"
              + "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-spiffe", StandardCharsets.UTF_8)
              + "&client_assertion=" + URLEncoder.encode(jwtToken, StandardCharsets.UTF_8);
        HttpRequest tokenRequest = HttpRequest.newBuilder()
              .uri(URI.create(keycloakHost + "/realms/spiffe/protocol/openid-connect/token"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(formBody))
              .build();
        log.info("Requesting access token from Keycloak");
        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        if (tokenResponse.statusCode() == 200) {
            String accessToken = new ObjectMapper().readTree(tokenResponse.body()).get("access_token").asText();
            logJwt("Access Token", accessToken);
            return accessToken;
        }
        throw new IllegalStateException("Unable to retrieve token response [%d]: %s".formatted(tokenResponse.statusCode(), tokenResponse.body()));
    }

    @PreDestroy
    void cleanup() {
        if (httpClient != null) {
            httpClient.close();
        }

        if (jwtSource != null) {
            try {
                jwtSource.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIFFE JwtSource: %s", e.getMessage());
            }
        }

        if (x509Source != null) {
            try {
                x509Source.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIFFE X509Source: %s", e.getMessage());
            }
        }
    }

    private void logJwt(String label, String token) {
        try {
            String[] parts = token.split("\\.");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            ObjectMapper mapper = new ObjectMapper();

            String header = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
            String payload = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);

            String prettyHeader = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(header));
            String prettyPayload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(payload));

            log.infof("%s Header:\n%s", label, prettyHeader);
            log.infof("%s Payload:\n%s", label, prettyPayload);
        } catch (Exception e) {
            log.infof("%s (raw): %s", label, token);
        }
    }
}
