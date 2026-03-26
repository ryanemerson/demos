package com.example.demo.authzen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jboss.logging.Logger;
import org.keycloak.authorization.attribute.Attributes;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.policy.evaluation.Evaluation;
import org.keycloak.authorization.policy.provider.PolicyProvider;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakSession;

import com.example.demo.authzen.spi.AuthZenPropertyMapper;
import com.example.demo.authzen.spi.AuthZenPropertyMapperFactory;

/**
 * A generic PolicyProvider that delegates authorization decisions to an external AuthZen PDP.
 * <p>
 * The provider discovers the PDP's access evaluation endpoint via the
 * {@code /.well-known/authzen-configuration} endpoint and caches it for subsequent evaluations.
 * <p>
 * Subject and resource identifiers can be derived from evaluation context attributes via
 * the {@code authzen.subject-id-attribute} and {@code authzen.resource-id-attribute} policy
 * configuration properties. All identity attributes are forwarded as subject properties, and
 * all resource attributes are forwarded as resource properties, keeping the provider decoupled
 * from any specific application domain.
 */
public class AuthZenPolicyProvider implements PolicyProvider {

    private static final Logger LOG = Logger.getLogger(AuthZenPolicyProvider.class);
    // TODO utilise Keycloak built in caching
    private static final Map<String, String> ENDPOINT_CACHE = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper PRETTY_MAPPER = MAPPER.copy()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, true);

    private final KeycloakSession session;

    public AuthZenPolicyProvider(KeycloakSession session) {
        this.session = session;
    }

    record EvaluationRequest(Subject subject, AuthZenResource resource, Action action) {}

    record Subject(String type, String id, Map<String, Object> properties) {}

    record AuthZenResource(String type, String id, Map<String, Object> properties) {}

    record Action(String name) {}

    record EvaluationResponse(boolean decision) {}

    record WellKnownConfiguration(String access_evaluation_endpoint) {}

    @Override
    public void evaluate(Evaluation evaluation) {
        try {
            Map<String, String> config = evaluation.getPolicy().getConfig();
            String baseUrl = buildBaseUrl(config);

            String endpoint = ENDPOINT_CACHE.get(baseUrl);
            if (endpoint == null) {
                endpoint = discoverEndpoint(baseUrl);
                ENDPOINT_CACHE.put(baseUrl, endpoint);
            }

            EvaluationRequest request = buildAuthZenRequest(evaluation, config);
            String requestJson = MAPPER.writeValueAsString(request);
            if (LOG.isTraceEnabled()) {
                LOG.tracef("AuthZen evaluation request:\n%s", PRETTY_MAPPER.writeValueAsString(request));
            }

            boolean decision = executeEvaluation(endpoint, requestJson);
            LOG.tracef("AuthZen evaluation decision: %s", decision);

            if (decision) {
                evaluation.grant();
            }
        } catch (Exception e) {
            LOG.errorf(e, "AuthZen policy evaluation failed");
        }
    }

    private String buildBaseUrl(Map<String, String> config) {
        String host = config.get("authzen.host");
        if (host == null || host.isEmpty()) {
            throw new IllegalStateException("authzen.host is not configured");
        }

        String scheme = config.getOrDefault("authzen.scheme", "http");
        String port = config.get("authzen.port");

        if (port == null || port.isEmpty()) {
            port = "https".equals(scheme) ? "443" : "80";
        }

        boolean isDefaultPort = ("http".equals(scheme) && "80".equals(port))
                || ("https".equals(scheme) && "443".equals(port));

        return isDefaultPort
                ? scheme + "://" + host
                : scheme + "://" + host + ":" + port;
    }

    private String discoverEndpoint(String baseUrl) {
        String wellKnownUrl = baseUrl + "/.well-known/authzen-configuration";
        LOG.debugf("Discovering AuthZen configuration from %s", wellKnownUrl);

        try {
            var httpClient = session.getProvider(HttpClientProvider.class).getHttpClient();
            HttpGet get = new HttpGet(wellKnownUrl);

            try (CloseableHttpResponse response = httpClient.execute(get)) {
                String body = EntityUtils.toString(response.getEntity());
                WellKnownConfiguration wellKnown = MAPPER.readValue(body, WellKnownConfiguration.class);

                if (wellKnown.access_evaluation_endpoint() == null) {
                    throw new IllegalStateException(
                            "access_evaluation_endpoint not found in AuthZen well-known configuration");
                }
                LOG.debugf("Discovered AuthZen access evaluation endpoint: %s",
                        wellKnown.access_evaluation_endpoint());
                return wellKnown.access_evaluation_endpoint();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to discover AuthZen endpoint from " + wellKnownUrl, e);
        }
    }

    private EvaluationRequest buildAuthZenRequest(Evaluation evaluation, Map<String, String> config) {
        // Subject — identity from the evaluation context
        String subjectId = resolveSubjectId(evaluation, config);
        Map<String, Object> subjectProperties = flattenAttributes(
                evaluation.getContext().getIdentity().getAttributes().toMap());

        // Resource — from the UMA resource permission
        Resource keycloakResource = evaluation.getPermission().getResource();
        String resourceId = resolveResourceId(evaluation, config, keycloakResource);

        Map<String, Object> resourceProperties = new LinkedHashMap<>();
        Map<String, List<String>> resourceAttrs = keycloakResource.getAttributes();
        if (resourceAttrs != null) {
            resourceProperties.putAll(flattenAttributes(resourceAttrs));
        }

        // Enrich via explicitly configured AuthZenPropertyMapper implementations (PIP layer)
        Set<AuthZenPropertyMapper> mappers = resolvePropertyMappers(config);
        for (AuthZenPropertyMapper mapper : mappers) {
            subjectProperties.putAll(mapper.mapSubjectProperties(evaluation, session));
            resourceProperties.putAll(mapper.mapResourceProperties(evaluation, session));
        }

        Subject subject = new Subject("user", subjectId, subjectProperties);
        AuthZenResource resource = new AuthZenResource(keycloakResource.getType(), resourceId, resourceProperties);

        // Action — from the requested scope
        String action = evaluation.getPermission().getScopes().stream()
                .findFirst()
                .map(Scope::getName)
                .orElse("access");

        return new EvaluationRequest(subject, resource, new Action(action));
    }

    /**
     * Resolves the set of {@link AuthZenPropertyMapper} instances to apply based on the
     * {@code property-mappers} policy configuration. The value is a comma-separated list of
     * fully-qualified class names of {@link AuthZenPropertyMapperFactory} implementations.
     * Only explicitly configured mappers are applied — no mappers are loaded by default.
     */
    private Set<AuthZenPropertyMapper> resolvePropertyMappers(Map<String, String> config) {
        String mappersConfig = config.get("property-mappers");
        if (mappersConfig == null || mappersConfig.isBlank()) {
            return Set.of();
        }

        var factories = session.getKeycloakSessionFactory()
                .getProviderFactoriesStream(AuthZenPropertyMapper.class);

        Set<String> configuredNames = Set.of(mappersConfig.split(","));

        var result = new java.util.LinkedHashSet<AuthZenPropertyMapper>();
        factories.forEach(factory -> {
            if (configuredNames.contains(factory.getClass().getName())) {
                result.add(session.getProvider(AuthZenPropertyMapper.class, factory.getId()));
            }
        });
        return result;
    }

    /**
     * Resolves the subject identifier. If {@code authzen.subject-id-attribute} is configured,
     * the value is read from the identity's attributes; otherwise {@link
     * org.keycloak.authorization.identity.Identity#getId()} is used (the Keycloak user UUID).
     */
    private String resolveSubjectId(Evaluation evaluation, Map<String, String> config) {
        String attrName = config.get("authzen.subject-id-attribute");
        if (attrName != null) {
            Attributes.Entry entry = evaluation.getContext().getIdentity().getAttributes().getValue(attrName);
            if (entry != null && !entry.isEmpty()) {
                return entry.asString(0);
            }
        }
        return evaluation.getContext().getIdentity().getId();
    }

    /**
     * Resolves the resource identifier. If {@code authzen.resource-id-attribute} is configured,
     * the value is read from the evaluation context attributes (e.g. claim information points).
     * For URI-valued attributes the last path segment is used as the identifier.
     * Falls back to the Keycloak resource name.
     */
    private String resolveResourceId(Evaluation evaluation, Map<String, String> config, Resource resource) {
        String attrName = config.get("authzen.resource-id-attribute");
        if (attrName != null) {
            Attributes.Entry entry = evaluation.getContext().getAttributes().getValue(attrName);
            if (entry != null && !entry.isEmpty()) {
                String value = entry.asString(0);
                // For URI values, extract the last path segment as the resource identifier
                if (value.contains("/")) {
                    value = value.substring(value.lastIndexOf('/') + 1);
                }
                return value;
            }
        }
        return resource.getName();
    }

    /**
     * Flattens an attribute map for JSON serialisation: single-valued collections become plain
     * strings, multi-valued collections become arrays.
     */
    private static Map<String, Object> flattenAttributes(Map<String, ? extends Collection<String>> attrs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : attrs.entrySet()) {
            Collection<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (values.size() == 1) {
                result.put(entry.getKey(), values.iterator().next());
            } else {
                result.put(entry.getKey(), new ArrayList<>(values));
            }
        }
        return result;
    }

    private boolean executeEvaluation(String endpoint, String requestJson) throws IOException {
        var httpClient = session.getProvider(HttpClientProvider.class).getHttpClient();
        HttpPost post = new HttpPost(endpoint);
        post.setEntity(new StringEntity(requestJson, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String body = EntityUtils.toString(response.getEntity());
            EvaluationResponse result = MAPPER.readValue(body, EvaluationResponse.class);
            return result.decision();
        }
    }

    @Override
    public void close() {
    }
}
