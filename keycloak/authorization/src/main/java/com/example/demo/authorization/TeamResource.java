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
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;

@Path("/teams")
public class TeamResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance team(String teamName, String currentUser, Set<String> roles, String userTeam);
    }

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    JsonWebToken accessToken;

    @GET
    @Path("/{teamname}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance getTeam(@PathParam("teamname") String teamname) {
        String currentUser = idToken.getClaim("preferred_username");

        // Keycloak enforces the "manager" role requirement via policy enforcer.
        // Additionally, verify the user's "team" attribute matches the requested team.
        Object teamClaim = accessToken.getClaim("team");
        String userTeam = teamClaim != null ? teamClaim.toString() : "";

        if (!userTeam.equals(teamname)) {
            throw new ForbiddenException("You can only view your own team. Your team is: " + userTeam);
        }

        Set<String> roles = securityIdentity.getRoles();
        return Templates.team(teamname, currentUser, roles, userTeam);
    }
}
