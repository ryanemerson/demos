package com.demo.chatbot;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class McpIdentityHeadersSupplier implements McpHeadersSupplier {

    @Inject
    OidcAuthService oidcAuthService;

    @Override
    public Map<String, String> apply(McpCallContext context) {
        String token = oidcAuthService.getAccessToken();
        if (token == null) {
            return Map.of();
        }

        Map<String, String> headers = new HashMap<>();
        try {
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonObject claims = new JsonObject(payload);

            String email = claims.getString("email");
            if (email != null) {
                headers.put("X-Forwarded-User-Email", email);
                headers.put("X-Forwarded-User-Id", email);
            }

            JsonObject realmAccess = claims.getJsonObject("realm_access");
            if (realmAccess != null) {
                JsonArray roles = realmAccess.getJsonArray("roles");
                if (roles != null) {
                    boolean isAdmin = roles.contains("admin");
                    headers.put("X-Forwarded-User-Admin", String.valueOf(isAdmin));
                    headers.put("X-Forwarded-User-Roles", String.join(",", roles.stream()
                            .map(Object::toString)
                            .toList()));
                }
            }
        } catch (Exception e) {
            // Fall back to email-only if token parsing fails
            try {
                headers.put("X-Forwarded-User-Email", oidcAuthService.getUserEmail());
            } catch (Exception ignored) {
            }
        }
        return headers;
    }
}
