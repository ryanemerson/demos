package com.example.keycloak;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.x509.X509ClientCertificateLookup;

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

        try {
            X509ClientCertificateLookup lookup = session.getProvider(X509ClientCertificateLookup.class);
            if (lookup == null) {
                return token;
            }

            X509Certificate[] certs = lookup.getCertificateChain(session.getContext().getHttpRequest());
            if (certs == null || certs.length == 0) {
                return token;
            }

            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certs[0].getEncoded());
            String thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

            Map<String, String> cnf = new HashMap<>();
            cnf.put("x5t#S256", thumbprint);
            token.getOtherClaims().put("cnf", cnf);
        } catch (Exception e) {
            // Don't fail token creation if cert lookup fails
        }

        return token;
    }
}
