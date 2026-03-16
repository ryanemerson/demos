package com.example.demo.service1;

import java.util.Base64;
import java.util.Map;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OidcClient oidcClient;

    @Inject
    JsonWebToken jwt;

    @Inject
    @RestClient
    Microservice2Client microservice2Client;

    @GET
    @RolesAllowed("service1")
    @Produces(MediaType.TEXT_PLAIN)
    public String call() {
        String currentToken = jwt.getRawToken();

        Map<String, String> additionalParams = Map.of(
                "subject_token", currentToken,
                "subject_token_type", "urn:ietf:params:oauth:token-type:access_token"
        );
        Tokens tokens = oidcClient.getTokens(additionalParams).await().indefinitely();
        String exchangedToken = tokens.getAccessToken();

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
