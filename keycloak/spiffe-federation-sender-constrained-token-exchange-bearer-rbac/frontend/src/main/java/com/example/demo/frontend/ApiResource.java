package com.example.demo.frontend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Instant;
import java.util.Base64;

import javax.net.ssl.SSLContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.security.Authenticated;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Authenticated
public class ApiResource {

    private static final Logger log = Logger.getLogger(ApiResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "keycloak.token-url")
    String keycloakTokenUrl;

    @ConfigProperty(name = "keycloak.issuer-url")
    String keycloakIssuerUrl;

    @Inject
    @RestClient
    Microservice1Client microservice1Client;

    @Inject
    JsonWebToken accessToken;

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
        } catch (Exception e) {
            log.error("Failed to initialize HTTP client: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/call-service")
    @Produces(MediaType.TEXT_PLAIN)
    public String callService() {
        String currentToken = accessToken.getRawToken();
        String authHeader = "Bearer " + currentToken;

        var sb = new StringBuilder();
        sb.append("=== Frontend ===\n\n");
        sb.append("--- Outgoing JWT (Bearer Token) ---\n");
        sb.append(prettyPrintJwt(currentToken)).append("\n\n");

        callMicroservice1(sb, authHeader);
        return sb.toString();
    }

    @GET
    @Path("/call-service-mocked")
    @Produces(MediaType.TEXT_PLAIN)
    public String callServiceMocked() {
        String fakeToken = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYWtlLXVzZXIiLCJhdWQiOiJtaWNyb3NlcnZpY2UxIiwidHlwIjoiQmVhcmVyIiwiaXNzIjoiZmFrZS1pc3N1ZXIiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhdHRhY2tlciJ9.";
        String authHeader = "Bearer " + fakeToken;

        var sb = new StringBuilder();
        sb.append("=== Frontend (Mocked Bearer Token) ===\n\n");
        sb.append("--- Outgoing Token ---\n");
        sb.append("Using a fabricated token not issued by Keycloak.\n");
        sb.append("This should be rejected by Microservice 1.\n\n");

        callMicroservice1(sb, authHeader);
        return sb.toString();
    }

    @GET
    @Path("/call-service-invalid-spiffe-jwt")
    @Produces(MediaType.TEXT_PLAIN)
    public String callServiceInvalidSpiffeJwt() {
        var sb = new StringBuilder();
        sb.append("=== Frontend (Invalid SPIFFE JWT) ===\n\n");

        try {
            String fakeJwt = generateFakeSpiffeJwt();
            sb.append("--- Fabricated SPIFFE JWT-SVID ---\n");
            sb.append(prettyPrintJwt(fakeJwt)).append("\n\n");
            sb.append("This JWT has the same structure as a real SPIFFE JWT-SVID but is signed\n");
            sb.append("with a randomly generated key NOT from the SPIRE trust domain.\n");
            sb.append("Keycloak will reject it because the signature cannot be verified.\n\n");

            String formBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange", StandardCharsets.UTF_8)
                    + "&subject_token=" + URLEncoder.encode(accessToken.getRawToken(), StandardCharsets.UTF_8)
                    + "&subject_token_type=" + URLEncoder.encode("urn:ietf:params:oauth:token-type:access_token", StandardCharsets.UTF_8)
                    + "&audience=microservice1"
                    + "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-spiffe", StandardCharsets.UTF_8)
                    + "&client_assertion=" + URLEncoder.encode(fakeJwt, StandardCharsets.UTF_8);

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakTokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());

            sb.append("--- Keycloak Response (HTTP ").append(response.statusCode()).append(") ---\n");
            sb.append(prettyPrintJson(response.body())).append("\n");
        } catch (Exception e) {
            sb.append("--- Error ---\n");
            sb.append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private String generateFakeSpiffeJwt() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        KeyPair keyPair = keyGen.generateKeyPair();

        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"fake-key-not-in-spire\"}";
        String payload = "{\"sub\":\"spiffe://demo.example.com/frontend\""
                + ",\"aud\":\"" + keycloakIssuerUrl + "\""
                + ",\"iss\":\"spire-server\""
                + ",\"iat\":" + now
                + ",\"exp\":" + (now + 300) + "}";

        String signingInput = base64UrlEncode(header.getBytes(StandardCharsets.UTF_8))
                + "." + base64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign((ECPrivateKey) keyPair.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] derSignature = sig.sign();
        byte[] jwtSignature = derToJose(derSignature);

        return signingInput + "." + base64UrlEncode(jwtSignature);
    }

    /**
     * Convert DER-encoded ECDSA signature to the fixed-length R||S format used by JWS (RFC 7515).
     */
    private static byte[] derToJose(byte[] der) {
        int offset = 3;
        int rLen = der[offset++] & 0xff;
        byte[] r = new byte[rLen];
        System.arraycopy(der, offset, r, 0, rLen);
        offset += rLen + 1;
        int sLen = der[offset++] & 0xff;
        byte[] s = new byte[sLen];
        System.arraycopy(der, offset, s, 0, sLen);

        int componentLen = 32;
        byte[] jose = new byte[componentLen * 2];
        copyPadded(r, jose, 0, componentLen);
        copyPadded(s, jose, componentLen, componentLen);
        return jose;
    }

    private static void copyPadded(byte[] src, byte[] dest, int destOffset, int len) {
        int srcOffset = src.length > len ? src.length - len : 0;
        int copyLen = Math.min(src.length, len);
        System.arraycopy(src, srcOffset, dest, destOffset + len - copyLen, copyLen);
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private void callMicroservice1(StringBuilder sb, String authHeader) {
        try {
            Response response = microservice1Client.call(authHeader);
            sb.append("--- Response Body from Microservice 1 ---\n");
            sb.append(response.readEntity(String.class)).append("\n");
        } catch (WebApplicationException e) {
            Response errorResponse = e.getResponse();
            String errorBody = errorResponse.readEntity(String.class);
            sb.append("--- Error from Microservice 1 (HTTP ").append(errorResponse.getStatus()).append(") ---\n");
            sb.append(errorBody).append("\n");
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
