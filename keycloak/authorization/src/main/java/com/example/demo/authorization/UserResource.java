package com.example.demo.authorization;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.Set;

@Path("/users")
public class UserResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance user(String username, String currentUser, Set<String> roles, String team, boolean isOwnProfile);
    }

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    JsonWebToken accessToken;

    @GET
    @Path("/{username}")
    @Produces(MediaType.TEXT_HTML)
    public Object getUser(@PathParam("username") String username) {
        String currentUser = idToken.getClaim("preferred_username");

        // Handle /users/me by redirecting to the actual username
        if ("me".equals(username)) {
            return Response.seeOther(URI.create("/users/" + currentUser)).build();
        }

        boolean isManager = securityIdentity.hasRole("manager");
        boolean isOwnProfile = currentUser.equals(username);

        if (!isOwnProfile && !isManager) {
            throw new ForbiddenException("You can only view your own profile unless you have the manager role.");
        }

        Set<String> roles = securityIdentity.getRoles();
        Object teamClaim = accessToken.getClaim("team");
        String team = teamClaim != null ? teamClaim.toString() : "";
        return Templates.user(username, currentUser, roles, team, isOwnProfile);
    }
}
