package com.example.demo.authzen.mapper;

import java.util.Map;

import com.example.demo.authzen.spi.AuthZenPropertyMapper;
import org.keycloak.authorization.attribute.Attributes;
import org.keycloak.authorization.policy.evaluation.Evaluation;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * An {@link AuthZenPropertyMapper} that enriches the AuthZen resource properties with
 * attributes from the target user's Keycloak profile.
 * <p>
 * The target user is identified by extracting the last path segment from the
 * {@code request-uri} evaluation context attribute (set via the client's claim
 * information point configuration). The user's {@code team} attribute is then
 * looked up from the Keycloak user store and added to the resource properties.
 */
public class UserProfilePropertyMapper implements AuthZenPropertyMapper {

    @Override
    public Map<String, Object> mapSubjectProperties(Evaluation evaluation, KeycloakSession session) {
        return Map.of();
    }

    @Override
    public Map<String, Object> mapResourceProperties(Evaluation evaluation, KeycloakSession session) {
        Attributes.Entry requestUriEntry = evaluation.getContext().getAttributes().getValue("request-uri");
        if (requestUriEntry == null || requestUriEntry.isEmpty()) {
            return Map.of();
        }

        String requestUri = requestUriEntry.asString(0);
        String targetUsername = requestUri.contains("/")
                ? requestUri.substring(requestUri.lastIndexOf('/') + 1)
                : requestUri;

        RealmModel realm = session.getContext().getRealm();
        UserModel targetUser = session.users().getUserByUsername(realm, targetUsername);
        if (targetUser != null) {
            String team = targetUser.getFirstAttribute("team");
            if (team != null)
                return Map.of("team", team);
        }
        return Map.of();
    }

    @Override
    public void close() {
    }
}
