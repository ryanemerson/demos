package com.demo.chatbot;

import com.google.auth.oauth2.GoogleCredentials;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.anthropic.VertexAiAnthropicChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

@ApplicationScoped
public class ChatModelProducer implements Supplier<ChatModel> {

    private static final Path ADC_PATH = Path.of(
            System.getProperty("user.home"), ".config", "gcloud", "application_default_credentials.json");

    @Override
    public ChatModel get() {
        try {
            var config = ConfigProvider.getConfig();
            String projectId = config.getValue("vertex-ai.project-id", String.class);
            String location = config.getValue("vertex-ai.location", String.class);
            String modelName = config.getValue("vertex-ai.model-name", String.class);
            int maxTokens = config.getValue("vertex-ai.max-tokens", Integer.class);

            if (!Files.exists(ADC_PATH)) {
                throw new IllegalStateException(
                        "Google Application Default Credentials not found at " + ADC_PATH
                                + ". Run 'gcloud auth application-default login' first.");
            }

            GoogleCredentials credentials;
            try (FileInputStream fis = new FileInputStream(ADC_PATH.toFile())) {
                credentials = GoogleCredentials.fromStream(fis)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }

            return VertexAiAnthropicChatModel.builder()
                    .project(projectId)
                    .location(location)
                    .modelName(modelName)
                    .maxTokens(maxTokens)
                    .credentials(credentials)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Google credentials from " + ADC_PATH, e);
        }
    }
}
