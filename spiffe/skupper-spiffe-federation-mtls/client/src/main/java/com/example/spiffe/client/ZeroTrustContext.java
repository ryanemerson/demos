package com.example.spiffe.client;

import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
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

    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        try {
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

    @PreDestroy
    void cleanup() {
        if (httpClient != null) {
            httpClient.close();
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
}
