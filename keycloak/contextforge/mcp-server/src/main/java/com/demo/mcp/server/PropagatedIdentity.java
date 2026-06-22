package com.demo.mcp.server;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import io.quarkus.security.ForbiddenException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RequestScoped
public class PropagatedIdentity {

    @Inject
    RoutingContext routingContext;

    public String getUsername() {
        String email = routingContext.request().getHeader("X-Forwarded-User-Email");
        if (email != null) {
            return email;
        }
        String id = routingContext.request().getHeader("X-Forwarded-User-Id");
        if (id != null) {
            return id;
        }
        throw new ForbiddenException("No propagated user identity found");
    }

    public boolean isAdmin() {
        return "true".equalsIgnoreCase(routingContext.request().getHeader("X-Forwarded-User-Admin"));
    }

    public List<String> getRoles() {
        List<String> roles = new ArrayList<>();
        String rolesHeader = routingContext.request().getHeader("X-Forwarded-User-Roles");
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            Arrays.stream(rolesHeader.split(",")).map(String::trim).forEach(roles::add);
        }
        String groupsHeader = routingContext.request().getHeader("X-Forwarded-User-Groups");
        if (groupsHeader != null && !groupsHeader.isBlank()) {
            Arrays.stream(groupsHeader.split(",")).map(String::trim).forEach(roles::add);
        }
        if (isAdmin()) {
            roles.add("admin");
        }
        return roles;
    }

    public void requireRole(String role) {
        if (getRoles().stream().noneMatch(r -> r.equalsIgnoreCase(role))) {
            throw new ForbiddenException("Role '" + role + "' required");
        }
    }
}
