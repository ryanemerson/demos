package com.example.demo.service1;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.net.ssl.SSLContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.spiffe.workloadapi.CachedJwtSource;
import io.spiffe.workloadapi.JwtSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
public class Service1Resource {

    private static final Logger log = Logger.getLogger(Service1Resource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "keycloak.token-url")
    String keycloakTokenUrl;

    @ConfigProperty(name = "keycloak.issuer-url")
    String keycloakIssuerUrl;

    @Inject
    JsonWebToken jwt;

    @Inject
    @RestClient
    Microservice2Client microservice2Client;

    @Inject
    TlsConfigurationRegistry tlsRegistry;

    private volatile JwtSource jwtSource;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        try {
            jwtSource = CachedJwtSource.newSource();
            log.info("SPIFFE JwtSource initialized successfully");

            TlsConfiguration tlsConfig = tlsRegistry.get("spiffe").orElseThrow(
                    () -> new IllegalStateException("TLS configuration 'spiffe' not found"));
            SSLContext sslContext = tlsConfig.createSSLContext();
            httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            log.error("Failed to initialize SPIFFE JwtSource: " + e.getMessage(), e);
        }
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

    @GET
    @RolesAllowed("service1")
    @Produces(MediaType.TEXT_PLAIN)
    public String call() {
        String currentToken = jwt.getRawToken();

        String exchangedToken = exchangeToken(currentToken);

        var sb = new StringBuilder();
        sb.append("=== Microservice 1 ===\n\n");
        sb.append("--- Exchanged JWT (Bearer Token) ---\n");
        sb.append(prettyPrintJwt(exchangedToken)).append("\n\n");

        try {
            String authHeader = "Bearer " + exchangedToken;
            Response response = microservice2Client.call(authHeader);
            String responseBody = response.readEntity(String.class);
            sb.append(responseBody).append("\n");
        } catch (WebApplicationException e) {
            Response errorResponse = e.getResponse();
            String errorBody = errorResponse.readEntity(String.class);
            sb.append("--- Error from Microservice 2 (HTTP ").append(errorResponse.getStatus()).append(") ---\n");
            sb.append(errorBody).append("\n");
        }

        return sb.toString();
    }

    private String exchangeToken(String subjectToken) {
        try {
            // Fetch a JWT-SVID from the SPIRE agent for the Keycloak audience
            log.infof("Requesting JWT-SVID from SPIRE Workload API for audience=%s", keycloakIssuerUrl);
            String jwtSvid = jwtSource.fetchJwtSvid(keycloakIssuerUrl).getToken();

            // Exchange the user's token using the JWT-SVID as client authentication
            String formBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange", StandardCharsets.UTF_8)
                    + "&subject_token=" + URLEncoder.encode(subjectToken, StandardCharsets.UTF_8)
                    + "&subject_token_type=" + URLEncoder.encode("urn:ietf:params:oauth:token-type:access_token", StandardCharsets.UTF_8)
                    + "&audience=microservice2"
                    + "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-spiffe", StandardCharsets.UTF_8)
                    + "&client_assertion=" + URLEncoder.encode(jwtSvid, StandardCharsets.UTF_8);

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakTokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            log.info("Requesting token exchange from Keycloak using JWT-SVID client assertion");
            HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());

            if (tokenResponse.statusCode() == 200) {
                String accessToken = MAPPER.readTree(tokenResponse.body()).get("access_token").asText();
                return accessToken;
            }
            throw new IllegalStateException("Token exchange failed [%d]: %s".formatted(tokenResponse.statusCode(), tokenResponse.body()));
        } catch (Exception e) {
            throw new RuntimeException("Token exchange failed: " + e.getMessage(), e);
        }
    }

    private static String prettyPrintJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            String header = prettyPrintJson(base64Decode(parts[0]));
            String payload = prettyPrintJson(base64Decode(parts[1]));
            return "Header:\n" + header + "\nPayload:\n" + payload;
        } catch (Exception e) {
            return token;
        }
    }

    private static String base64Decode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded));
    }

    private static String prettyPrintJson(String json) throws Exception {
        Object obj = MAPPER.readValue(json, Object.class);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }
}
