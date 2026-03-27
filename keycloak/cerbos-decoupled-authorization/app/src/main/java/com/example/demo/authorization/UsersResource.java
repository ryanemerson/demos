package com.example.demo.authorization;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/users")
@Authenticated
public class UsersResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance user(String username, String currentUser, String currentUserTeam,
                                                   String profileUserTeam, Set<String> profileRoles, boolean isOwnProfile, boolean isManager, List<String> reports);
    }

    @ConfigProperty(name = "quarkus.keycloak.admin-client.realm")
    String realm;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    JsonWebToken accessToken;

    @Inject
    Keycloak keycloak;

    @Inject
    AuthZenClient authZenClient;

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

        // For other profiles, fetch the target user once and reuse for team, roles, etc.
        String profileUserTeam;
        UserResource profileUser = null;
        if (isOwnProfile) {
            profileUserTeam = currentUserTeam;
        } else {
            profileUser = findUser(username);
            profileUserTeam = getUserTeam(profileUser);
        }

        // AuthZen evaluation call - post-fetch model with subject properties from the OIDC token
        boolean permitted = authZenClient.evaluate(
                AuthZenClient.EvaluationRequest.builder()
                        .subject("user", currentUser)
                        .subjectProperty("team", currentUserTeam)
                        .subjectProperty("kc.realm.roles", securityIdentity.getRoles())
                        .resource("user_profile", username)
                        .resourceProperty("team", profileUserTeam)
                        .action("view")
                        .build()
        );

        if (!permitted) {
            throw new ForbiddenException("Access denied by AuthZen policy");
        }

        Set<String> profileUserRoles;
        List<String> reports = List.of();
        if (isOwnProfile) {
            profileUserRoles = securityIdentity.getRoles();
            if (isManager) {
                reports = keycloak.realm(realm).users().searchByAttributes("team:" + currentUserTeam).stream()
                        .map(UserRepresentation::getUsername)
                        .filter(u -> !u.equals(currentUser) && !u.startsWith("service-account-"))
                        .sorted()
                        .toList();
            }
        } else {
            profileUserRoles = getUserRealmRoles(profileUser);
        }

        return Templates.user(username, currentUser, currentUserTeam,
                profileUserTeam, profileUserRoles, isOwnProfile, isManager, reports);
    }

    private UserResource findUser(String username) {
        List<UserRepresentation> users = keycloak.realm(realm).users().searchByUsername(username, true);
        if (users.isEmpty()) {
            throw new NotFoundException("User not found: " + username);
        }
        return keycloak.realm(realm).users().get(users.getFirst().getId());
    }

    private String getUserTeam(UserResource user) {
        UserRepresentation rep = user.toRepresentation();
        List<String> teamAttr = rep.getAttributes() != null ? rep.getAttributes().get("team") : null;
        return (teamAttr != null && !teamAttr.isEmpty()) ? teamAttr.getFirst() : "";
    }

    private Set<String> getUserRealmRoles(UserResource user) {
        return user.roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName)
                .filter(r -> !r.startsWith("default-roles-"))
                .collect(Collectors.toSet());
    }
}
