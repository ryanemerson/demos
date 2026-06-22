package com.demo.mcp.server;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.net.InetAddress;

public class McpServerTools {

    @Inject
    PropagatedIdentity propagatedIdentity;

    @Tool(name = "who-am-i", description = "Returns the username of the currently authenticated user")
    @Authenticated
    TextContent whoAmI() {
        String username = propagatedIdentity.getUsername();
        return new TextContent("Current user: " + username);
    }

    @Tool(name = "server-secret", description = "Returns the server hostname. Only accessible to users with the admin role.")
    @Authenticated
    TextContent serverSecret() throws Exception {
        propagatedIdentity.requireRole("admin");
        String hostname = InetAddress.getLocalHost().getHostName();
        return new TextContent("Server hostname: " + hostname);
    }
}
