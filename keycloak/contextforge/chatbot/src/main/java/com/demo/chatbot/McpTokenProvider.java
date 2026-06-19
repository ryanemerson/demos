package com.demo.chatbot;

import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpTokenProvider implements McpClientAuthProvider {

    @Inject
    OidcAuthService oidcAuthService;

    @Override
    public String getAuthorization(Input input) {
        String token = oidcAuthService.getAccessToken();
        if (token == null) {
            throw new IllegalStateException("No access token available. OIDC login is required first.");
        }
        return "Bearer " + token;
    }
}
