package com.example.demo.authzen;

import org.keycloak.Config;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.policy.provider.PolicyProvider;
import org.keycloak.authorization.policy.provider.PolicyProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;

public class AuthZenPolicyProviderFactory implements PolicyProviderFactory<PolicyRepresentation> {

    static final String PROVIDER_ID = "authzen";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "AuthZen";
    }

    @Override
    public String getGroup() {
        return "AuthZen Policy Provider";
    }

    @Override
    public PolicyProvider create(KeycloakSession session) {
        return new AuthZenPolicyProvider(session);
    }

    @Override
    public PolicyProvider create(AuthorizationProvider authorization) {
        return new AuthZenPolicyProvider(authorization.getKeycloakSession());
    }

    @Override
    public void onCreate(Policy policy, PolicyRepresentation representation, AuthorizationProvider authorization) {
        policy.setConfig(representation.getConfig());
    }

    @Override
    public void onUpdate(Policy policy, PolicyRepresentation representation, AuthorizationProvider authorization) {
        policy.setConfig(representation.getConfig());
    }

    @Override
    public void onImport(Policy policy, PolicyRepresentation representation, AuthorizationProvider authorization) {
        policy.setConfig(representation.getConfig());
    }

    @Override
    public PolicyRepresentation toRepresentation(Policy policy, AuthorizationProvider authorization) {
        PolicyRepresentation representation = new PolicyRepresentation();
        representation.setType(PROVIDER_ID);
        representation.setConfig(policy.getConfig());
        return representation;
    }

    @Override
    public Class<PolicyRepresentation> getRepresentationType() {
        return PolicyRepresentation.class;
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
}
