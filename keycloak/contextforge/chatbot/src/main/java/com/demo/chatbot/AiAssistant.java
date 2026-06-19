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
            You are a helpful assistant with access to secured MCP tools through a ContextForge gateway.
            The available tools let you check the authenticated user's identity and retrieve the server hostname (admin only).
            Use the available tools when the user asks about their identity or the server secret.
            """)
    @McpToolBox("mcp-server")
    String chat(@UserMessage String message);
}
