package com.example.demo.frontend;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.oidc.common.OidcEndpoint;
import io.quarkus.oidc.common.OidcRequestFilter;
import io.smallrye.mutiny.Uni;
import io.spiffe.workloadapi.CachedJwtSource;
import io.spiffe.workloadapi.JwtSource;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@Unremovable
@ApplicationScoped
@OidcEndpoint(value = OidcEndpoint.Type.TOKEN)
public class SpiffeJwtClientAssertionFilter implements OidcRequestFilter {

    private static final Logger log = Logger.getLogger(SpiffeJwtClientAssertionFilter.class);

    @ConfigProperty(name = "keycloak.issuer-url")
    String keycloakIssuerUrl;

    private volatile JwtSource jwtSource;

    @PostConstruct
    void init() {
        try {
            jwtSource = CachedJwtSource.newSource();
            log.info("SPIFFE JwtSource initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize SPIFFE JwtSource: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void cleanup() {
        if (jwtSource != null) {
            try {
                jwtSource.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIFFE JwtSource: %s", e.getMessage());
            }
        }
    }

    @Override
    public Uni<Void> filter(OidcRequestFilterContext rc) {
        try {
            log.info("Adding SPIFFE JWT-SVID client assertion to token endpoint request");
            String jwtSvid = jwtSource.fetchJwtSvid(keycloakIssuerUrl).getToken();

            // Remove client_id from the body — Keycloak's federated-jwt authenticator
            // derives the client from the JWT-SVID sub claim, and rejects requests where
            // client_id doesn't match the sub (e.g. "frontend" != "spiffe://...//frontend")
            String body = rc.requestBody().toString()
                    .replaceAll("&?client_id=[^&]*", "");
            if (body.startsWith("&")) {
                body = body.substring(1);
            }

            body += "&client_assertion_type="
                    + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-spiffe", StandardCharsets.UTF_8)
                    + "&client_assertion="
                    + URLEncoder.encode(jwtSvid, StandardCharsets.UTF_8);

            rc.requestBody(Buffer.buffer(body));
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch SPIFFE JWT-SVID: " + e.getMessage(), e);
        }
        return Uni.createFrom().voidItem();
    }
}
