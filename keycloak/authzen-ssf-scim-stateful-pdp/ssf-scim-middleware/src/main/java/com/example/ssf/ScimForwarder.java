package com.example.ssf;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ScimForwarder {

    private static final Logger LOG = Logger.getLogger(ScimForwarder.class.getName());

    @ConfigProperty(name = "scim.endpoint", defaultValue = "http://scim:8085")
    String scimEndpoint;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void createUser(Map<String, Object> scimData) throws Exception {
        String userName = (String) scimData.get("userName");

        Map<String, Object> scimRequest = new LinkedHashMap<>();
        scimRequest.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        scimRequest.put("userName", userName);
        scimRequest.put("active", scimData.getOrDefault("active", true));
        scimRequest.put("displayName", userName);
        scimRequest.put("emails", List.of(Map.of(
                "value", userName,
                "type", "work",
                "primary", true
        )));

        String body = objectMapper.writeValueAsString(scimRequest);
        String url = scimEndpoint + "/Users";

        LOG.info("Forwarding SCIM user creation to " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/scim+json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOG.info("SCIM user created successfully: " + response.body());
        } else {
            LOG.log(Level.SEVERE, "SCIM user creation failed with status " + response.statusCode() + ": " + response.body());
            throw new RuntimeException("SCIM user creation failed: " + response.statusCode());
        }
    }

    public void addUserToGroup(String userName, String groupId) throws Exception {
        Map<String, Object> patchRequest = new LinkedHashMap<>();
        patchRequest.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"));
        patchRequest.put("Operations", List.of(Map.of(
                "op", "add",
                "path", "members",
                "value", List.of(Map.of("value", userName))
        )));

        String body = objectMapper.writeValueAsString(patchRequest);
        String url = scimEndpoint + "/Groups/" + groupId;

        LOG.info("Adding user '" + userName + "' to group '" + groupId + "' via SCIM PATCH " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/scim+json")
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOG.info("User '" + userName + "' added to group '" + groupId + "': " + response.body());
        } else {
            LOG.log(Level.SEVERE, "Failed to add user to group. Status " + response.statusCode() + ": " + response.body());
            throw new RuntimeException("SCIM group PATCH failed: " + response.statusCode());
        }
    }
}
