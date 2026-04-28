package com.demo.mcp.server;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.net.InetAddress;

public class McpServerTools {

    @Inject
    SecurityIdentity identity;

    @Tool(name = "who-am-i", description = "Returns the username of the currently authenticated user")
    @Authenticated
    TextContent whoAmI() {
        String username = identity.getPrincipal().getName();
        return new TextContent("Current user: " + username);
    }

    @Tool(name = "server-secret", description = "Returns the server hostname. Only accessible to users with the admin role.")
    @RolesAllowed("admin")
    TextContent serverSecret() throws Exception {
        String hostname = InetAddress.getLocalHost().getHostName();
        return new TextContent("Server hostname: " + hostname);
    }
}
