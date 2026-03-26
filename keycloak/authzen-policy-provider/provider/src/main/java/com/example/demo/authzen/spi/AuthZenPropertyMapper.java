package com.example.demo.authzen.spi;

import java.util.Map;

import org.keycloak.authorization.policy.evaluation.Evaluation;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.Provider;

/**
 * SPI for enriching AuthZen evaluation requests with additional subject and resource properties.
 * <p>
 * Implementations act as a Policy Information Point (PIP), resolving context that is not
 * available in the token or the Keycloak resource model — for example, looking up attributes
 * of the target resource owner from the user store.
 * <p>
 * Multiple mappers can be registered; the AuthZen policy provider discovers all of them and
 * merges their output into the AuthZen request.
 */
public interface AuthZenPropertyMapper extends Provider {

    /**
     * Returns additional properties to include in the AuthZen request's {@code subject} object.
     *
     * @return a map of property names to values (strings or lists), or an empty map
     */
    Map<String, Object> mapSubjectProperties(Evaluation evaluation, KeycloakSession session);

    /**
     * Returns additional properties to include in the AuthZen request's {@code resource} object.
     *
     * @return a map of property names to values (strings or lists), or an empty map
     */
    Map<String, Object> mapResourceProperties(Evaluation evaluation, KeycloakSession session);
}
