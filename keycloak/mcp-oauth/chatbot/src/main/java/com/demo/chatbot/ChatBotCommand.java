package com.demo.chatbot;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(name = "chatbot", mixinStandardHelpOptions = true,
        description = "MCP-enabled AI chatbot with Keycloak OIDC authentication")
public class ChatBotCommand implements Callable<Integer> {

    @Inject
    OidcAuthService oidcAuthService;

    @Inject
    AiAssistant assistant;

    @Override
    public Integer call() {
        try {
            System.out.println("=== MCP ChatBot ===");
            System.out.println("Initiating OIDC login...\n");
            oidcAuthService.login();

            System.out.println("\nYou can now chat with the AI assistant.");
            System.out.println("The assistant has access to MCP tools on the secured server.");
            System.out.println("Type 'quit' or 'exit' to end the session.\n");

            Scanner scanner = new Scanner(System.in);
            try {
                while (true) {
                    System.out.print("You: ");
                    if (!scanner.hasNextLine()) {
                        break;
                    }
                    String input = scanner.nextLine().trim();

                    if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                        System.out.println("Goodbye!");
                        break;
                    }
                    if (input.isEmpty()) {
                        continue;
                    }

                    try {
                        String response = assistant.chat(input);
                        System.out.println("Assistant: " + response);
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                    System.out.println();
                }
            } finally {
                oidcAuthService.logout();
            }
            return 0;

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
