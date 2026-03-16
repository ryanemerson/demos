package com.example.demo.frontend;

import java.util.Base64;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.security.Authenticated;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    @RestClient
    Microservice1Client microservice1Client;

    @Inject
    JsonWebToken accessToken;

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
