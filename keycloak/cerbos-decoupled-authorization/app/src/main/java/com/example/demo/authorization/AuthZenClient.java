package com.example.demo.authorization;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AuthZenClient {

    private static final Logger LOG = Logger.getLogger(AuthZenClient.class);

    @ConfigProperty(name = "authzen.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "authzen.port", defaultValue = "3592")
    int port;

    @ConfigProperty(name = "authzen.scheme", defaultValue = "http")
    String scheme;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String evaluationEndpoint;

    @PostConstruct
    void init() {
        discoverEndpoint();
    }

    private void discoverEndpoint() {
        String wellKnownUrl = "%s://%s:%d/.well-known/authzen-configuration".formatted(scheme, host, port);
        LOG.debugf("Fetching AuthZen well-known configuration from: %s", wellKnownUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wellKnownUrl))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debugf("Well-known response [status=%d]: %s", response.statusCode(), response.body());
            if (response.statusCode() == 200) {
                WellKnownResponse config = objectMapper.readValue(response.body(), WellKnownResponse.class);
                evaluationEndpoint = config.accessEvaluationEndpoint();
                LOG.infof("Discovered AuthZen evaluation endpoint: %s", evaluationEndpoint);
            } else {
                fallbackEndpoint(response.statusCode());
            }
        } catch (Exception e) {
            LOG.warnf("AuthZen well-known discovery failed: %s. Using default endpoint.", e.getMessage());
            evaluationEndpoint = defaultEndpoint();
        }
    }

    private void fallbackEndpoint(int statusCode) {
        evaluationEndpoint = defaultEndpoint();
        LOG.warnf("Well-known discovery returned status %d, using default endpoint: %s", statusCode, evaluationEndpoint);
    }

    private String defaultEndpoint() {
        return "%s://%s:%d/access/v1/evaluation".formatted(scheme, host, port);
    }

    public boolean evaluate(EvaluationRequest evaluationRequest) {
        try {
            String json = objectMapper.writeValueAsString(evaluationRequest);
            LOG.debugf("AuthZen evaluation request to %s: %s", evaluationEndpoint, json);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(evaluationEndpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                EvaluationResponse result = objectMapper.readValue(response.body(), EvaluationResponse.class);
                LOG.debugf("AuthZen evaluation result: decision=%s (subject=%s, resource=%s/%s, action=%s)",
                        result.decision(),
                        evaluationRequest.subject().id(),
                        evaluationRequest.resource().type(),
                        evaluationRequest.resource().id(),
                        evaluationRequest.action().name());
                return result.decision();
            }
            LOG.errorf("AuthZen evaluation failed with status %d: %s", response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            LOG.errorf(e, "AuthZen evaluation error for subject=%s, resource=%s/%s, action=%s",
                    evaluationRequest.subject().id(),
                    evaluationRequest.resource().type(),
                    evaluationRequest.resource().id(),
                    evaluationRequest.action().name());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WellKnownResponse(
            String policyDecisionPoint,
            String accessEvaluationEndpoint,
            String accessEvaluationsEndpoint
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvaluationRequest(Subject subject, Resource resource, Action action) {

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String subjectType;
            private String subjectId;
            private Map<String, Object> subjectProperties;

            private String resourceType;
            private String resourceId;
            private Map<String, Object> resourceProperties;

            private String actionName;

            public Builder subject(String type, String id) {
                this.subjectType = type;
                this.subjectId = id;
                return this;
            }

            public Builder subjectProperties(Map<String, Object> properties) {
                this.subjectProperties = properties;
                return this;
            }

            public Builder subjectProperty(String key, Object value) {
                if (subjectProperties == null) {
                    subjectProperties = new HashMap<>();
                }
                subjectProperties.put(key, value);
                return this;
            }

            public Builder resource(String type, String id) {
                this.resourceType = type;
                this.resourceId = id;
                return this;
            }

            public Builder resourceProperties(Map<String, Object> properties) {
                this.resourceProperties = properties;
                return this;
            }

            public Builder resourceProperty(String key, Object value) {
                if (resourceProperties == null) {
                    resourceProperties = new HashMap<>();
                }
                resourceProperties.put(key, value);
                return this;
            }

            public Builder action(String name) {
                this.actionName = name;
                return this;
            }

            public EvaluationRequest build() {
                return new EvaluationRequest(
                        new Subject(subjectType, subjectId, subjectProperties),
                        new Resource(resourceType, resourceId, resourceProperties),
                        new Action(actionName)
                );
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvaluationResponse(boolean decision) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(String type, String id, Map<String, Object> properties) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(String type, String id, Map<String, Object> properties) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String name) {}
}
