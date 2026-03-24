package com.example.demo.authorization;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;

@Path("/")
public class IndexResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance index(boolean authenticated, String username);
    }

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Object index() {
        if (!securityIdentity.isAnonymous()) {
            String username = idToken.getClaim("preferred_username");
            return Response.seeOther(URI.create("/users/" + username)).build();
        }
        return Templates.index(false, null);
    }
}
