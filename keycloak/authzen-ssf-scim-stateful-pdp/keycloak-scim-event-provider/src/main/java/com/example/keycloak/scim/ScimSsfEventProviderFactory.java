package com.example.keycloak.scim;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.ssf.event.SsfEvent;
import org.keycloak.ssf.event.SsfEventProvider;
import org.keycloak.ssf.event.SsfEventProviderFactory;

public class ScimSsfEventProviderFactory implements SsfEventProviderFactory {

    @Override
    public Map<String, Supplier<? extends SsfEvent>> getContributedEventFactories() {
        return Map.of(
                ScimUserCreatedEvent.TYPE, ScimUserCreatedEvent::new,
                ScimGroupMemberAddedEvent.TYPE, ScimGroupMemberAddedEvent::new
        );
    }

    @Override
    public Set<String> getEmittableEventTypes() {
        return Set.of(ScimUserCreatedEvent.TYPE, ScimGroupMemberAddedEvent.TYPE);
    }

    @Override
    public Set<String> getNativelyEmittedEventTypes() {
        return Set.of(ScimUserCreatedEvent.TYPE, ScimGroupMemberAddedEvent.TYPE);
    }

    @Override
    public SsfEventProvider create(KeycloakSession session) {
        return null;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "scim-ssf-events";
    }
}
