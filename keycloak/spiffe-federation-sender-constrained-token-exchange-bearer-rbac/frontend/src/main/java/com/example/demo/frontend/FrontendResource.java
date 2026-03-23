package com.example.demo.frontend;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;

@Path("/")
@Authenticated
public class FrontendResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance index(String username, String roles);
    }

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        String username = idToken.getClaim("preferred_username");
        if (username == null) {
            username = securityIdentity.getPrincipal().getName();
        }

        Set<String> roleSet = securityIdentity.getRoles();
        String roles = roleSet != null && !roleSet.isEmpty()
                ? String.join(", ", roleSet)
                : "No roles assigned";

        return Templates.index(username, roles);
    }
}
