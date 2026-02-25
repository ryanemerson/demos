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
import java.util.Collection;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.spiffe.workloadapi.CachedJwtSource;
import io.spiffe.workloadapi.JwtSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ZeroTrustContext {

    private static final Logger log = Logger.getLogger(ZeroTrustContext.class);

    @ConfigProperty(name = "server.url")
    String serverUrl;

    @Inject
    TlsConfigurationRegistry tlsRegistry;

    private volatile JwtSource jwtSource;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        try {
            jwtSource = CachedJwtSource.newSource();

            TlsConfiguration tlsConfig = tlsRegistry.get("spiffe").orElseThrow(
                    () -> new IllegalStateException("TLS configuration 'spiffe' not found"));
            SSLContext sslContext = tlsConfig.createSSLContext();

            httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();

            String spiffeID = extractSpiffedId(tlsConfig);
            log.infof("SPIFFE identity: %s", spiffeID);
        } catch (Exception e) {
            log.error("Failed to initialize ZeroTrustContext: " + e.getMessage());
        }
    }

    HttpClient getHttpClient() {
        return httpClient;
    }

    String getServerUrl() {
        return serverUrl;
    }

    String fetchAccessToken() throws Exception {
        // Fetch a JWT-SVID from the SPIRE agent for the Keycloak audience.
        String keycloakHost = "https://keycloak.keycloak.svc.cluster.local:8443";
        log.info("Requesting a JWT-SVID from the Workload API for audience=" + keycloakHost);
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
        log.info("Requesting access token from Keycloak using JWT-SVID");
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
    }

    private String extractSpiffedId(TlsConfiguration tlsConfig) {
        try {
            KeyStore ks = tlsConfig.getKeyStore();
            String alias = ks.aliases().nextElement();
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if ((Integer) san.get(0) == 6) {
                        return (String) san.get(1);
                    }
                }
            }
            throw new IllegalStateException("Unable to extract SPIFFE ID from certificate");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to extract SPIFFE ID from certificate", e);
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
