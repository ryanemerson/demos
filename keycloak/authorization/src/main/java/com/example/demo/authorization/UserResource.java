package com.example.demo.authorization;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashSet;

@Path("/users")
public class UserResource {

    private static final String REALM = "demo";

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance user(String username, String currentUser, String currentUserTeam,
                                                   Set<String> profileRoles, boolean isOwnProfile, boolean isManager, List<String> reports);
    }

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    JsonWebToken accessToken;

    @Inject
    Keycloak keycloak;

    @GET
    public Response redirectToOwnProfile() {
        String currentUser = idToken.getClaim("preferred_username");
        return Response.seeOther(URI.create("/users/" + currentUser)).build();
    }

    @GET
    @Path("/{username}")
    @Produces(MediaType.TEXT_HTML)
    public Object getUser(@PathParam("username") String username) {
        String currentUser = idToken.getClaim("preferred_username");
        boolean isManager = securityIdentity.hasRole("manager");
        boolean isOwnProfile = currentUser.equals(username);

        Object teamClaim = accessToken.getClaim("team");
        String currentUserTeam = teamClaim != null ? teamClaim.toString() : "";

        Set<String> profileRoles;
        List<String> reports = List.of();

        if (isOwnProfile) {
            // Own profile - extract roles from the access token
            profileRoles = new LinkedHashSet<>(securityIdentity.getRoles());
            profileRoles.remove("default-roles-demo");

            if (isManager) {
                reports = keycloak.realm(REALM).users().searchByAttributes("team:" + currentUserTeam).stream()
                        .map(UserRepresentation::getUsername)
                        .filter(u -> !u.equals(currentUser) && !u.startsWith("service-account-"))
                        .sorted()
                        .toList();
            }
        } else {
            // Other user's profile - look up in Keycloak
            UserRepresentation profileUser = findUser(username);
            profileRoles = getUserRealmRoles(profileUser.getId());
        }

        return Templates.user(username, currentUser, currentUserTeam,
                profileRoles, isOwnProfile, isManager, reports);
    }

    private UserRepresentation findUser(String username) {
        List<UserRepresentation> users = keycloak.realm(REALM).users().search(username);
        if (users.isEmpty()) {
            throw new NotFoundException("User not found: " + username);
        }
        return users.getFirst();
    }

    private Set<String> getUserRealmRoles(String userId) {
        return keycloak.realm(REALM).users().get(userId).roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName)
                .filter(r -> !r.startsWith("default-roles-"))
                .collect(Collectors.toSet());
    }
}
