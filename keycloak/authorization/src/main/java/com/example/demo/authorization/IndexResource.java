package com.example.demo.authorization;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/")
@PermitAll
public class IndexResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance index();
    }

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Object index() {
        if (!securityIdentity.isAnonymous()) {
            String username = securityIdentity.getPrincipal().getName();
            return Response.seeOther(URI.create("/users/" + username)).build();
        }
        return Templates.index();
    }
}
