package com.example.keycloak.scim;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.ssf.event.token.SsfSecurityEventToken;
import org.keycloak.ssf.subject.OpaqueSubjectId;
import org.keycloak.ssf.transmitter.SsfTransmitterProvider;
import org.keycloak.ssf.transmitter.stream.StreamConfig;
import org.keycloak.util.JsonSerialization;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScimSsfEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(ScimSsfEventListenerProvider.class.getName());

    private final KeycloakSession session;

    public ScimSsfEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        if (adminEvent.getOperationType() != OperationType.CREATE) {
            return;
        }

        SsfTransmitterProvider transmitter = session.getProvider(SsfTransmitterProvider.class);
        if (transmitter == null) {
            LOG.fine("SSF Transmitter not available — falling back silently");
            return;
        }

        if (adminEvent.getResourceType() == ResourceType.USER) {
            handleUserCreation(adminEvent, transmitter);
        } else if (adminEvent.getResourceType() == ResourceType.REALM_ROLE_MAPPING) {
            handleRealmRoleMapping(adminEvent, transmitter);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleUserCreation(AdminEvent adminEvent, SsfTransmitterProvider transmitter) {
        String representation = adminEvent.getRepresentation();
        if (representation == null || representation.isBlank()) {
            LOG.warning("No representation in admin event for user creation");
            return;
        }

        try {
            Map<String, Object> userRep = JsonSerialization.readValue(representation, Map.class);
            String username = (String) userRep.get("username");

            if (username == null || username.isBlank()) {
                LOG.warning("No username found in user representation");
                return;
            }

            ScimUserCreatedEvent event = new ScimUserCreatedEvent();
            event.setUserName(username);
            event.setActive(true);

            dispatchToAllStreams(transmitter, ScimUserCreatedEvent.TYPE, event, username);

            LOG.info("SSF SCIM user creation event dispatched for user: " + username);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to process user creation event", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRealmRoleMapping(AdminEvent adminEvent, SsfTransmitterProvider transmitter) {
        try {
            String resourcePath = adminEvent.getResourcePath();
            String[] pathParts = resourcePath.split("/");
            String userId = pathParts[1];

            RealmModel realm = session.realms().getRealm(adminEvent.getRealmId());
            UserModel user = session.users().getUserById(realm, userId);
            if (user == null) {
                LOG.warning("User not found for ID: " + userId);
                return;
            }

            List<Map<String, Object>> roles = JsonSerialization.readValue(
                    adminEvent.getRepresentation(), List.class);
            List<String> roleNames = roles.stream()
                    .map(r -> (String) r.get("name"))
                    .toList();

            if (roleNames.isEmpty()) {
                return;
            }

            ScimGroupMemberAddedEvent event = new ScimGroupMemberAddedEvent();
            event.setUserName(user.getUsername());
            event.setScopes(roleNames);

            dispatchToAllStreams(transmitter, ScimGroupMemberAddedEvent.TYPE, event, user.getUsername());

            LOG.info("SSF role mapping event dispatched for user: " + user.getUsername() + " roles: " + roleNames);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to process realm role mapping event", e);
        }
    }

    private void dispatchToAllStreams(SsfTransmitterProvider transmitter, String eventType, Object eventPayload, String username) {
        List<StreamConfig> streams = transmitter.streamService().findStreamsForSsfReceiverClients();
        if (streams.isEmpty()) {
            LOG.warning("No SSF streams found — event will not be delivered");
            return;
        }

        OpaqueSubjectId subjectId = new OpaqueSubjectId();
        subjectId.setId(username);

        for (StreamConfig stream : streams) {
            SsfSecurityEventToken token = transmitter.securityEventTokenMapper()
                    .generateSyntheticEvent(stream, eventType, eventPayload, subjectId);
            if (token == null) {
                LOG.warning("Failed to generate SET for stream: " + stream.getStreamId());
                continue;
            }
            transmitter.securityEventTokenDispatcher().dispatchEvent(token, stream);
        }
    }

    @Override
    public void close() {
    }
}
