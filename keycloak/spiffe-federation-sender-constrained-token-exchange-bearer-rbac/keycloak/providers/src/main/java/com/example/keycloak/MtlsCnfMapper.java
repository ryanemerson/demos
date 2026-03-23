package com.example.keycloak;

import static org.keycloak.services.util.MtlsHoKTokenUtil.bindTokenWithClientCertificate;

import java.util.Collections;
import java.util.List;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;

/**
 * Protocol mapper that binds access tokens to the client's mTLS certificate
 * by adding the "cnf" (confirmation) claim with an "x5t#S256" certificate
 * thumbprint. This ensures sender-constrained tokens for all grant types,
 * including token exchange.
 */
public class MtlsCnfMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper {

    public static final String PROVIDER_ID = "oidc-mtls-cnf-token-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "X.509 Certificate Bound Access Token";
    }

    @Override
    public String getHelpText() {
        return "Binds access tokens to the client's mTLS certificate by adding the cnf claim with x5t#S256 thumbprint.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }

    @Override
    public AccessToken transformAccessToken(AccessToken token, ProtocolMapperModel mappingModel,
            KeycloakSession session, UserSessionModel userSession, ClientSessionContext clientSessionCtx) {
        AccessToken.Confirmation confirmation = bindTokenWithClientCertificate(session.getContext().getHttpRequest(), session);
        token.setConfirmation(confirmation);
        return token;
    }
}
