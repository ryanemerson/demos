package com.demo.chatbot;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
public interface AiAssistant {

    @SystemMessage("""
            You are a helpful assistant with access to a secured MCP server.
            You have two tools available:
            - 'who-am-i': Returns the username of the currently authenticated user.
            - 'server-secret': Returns the server hostname (requires admin role).
            Use these tools when the user asks about their identity or the server secret.
            """)
    @McpToolBox("mcp-server")
    String chat(@UserMessage String message);
}
