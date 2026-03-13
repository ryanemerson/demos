package com.example.demo.service2;

import java.util.Base64;

import org.eclipse.microprofile.jwt.JsonWebToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class Service2Resource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    JsonWebToken jwt;

    @GET
    @RolesAllowed("service2")
    @Produces(MediaType.TEXT_PLAIN)
    public String getTokenDetails() {
        var sb = new StringBuilder();
        sb.append("=== Microservice 2 ===\n\n");
        sb.append(prettyPrintJwt(jwt.getRawToken())).append("\n\n");
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
