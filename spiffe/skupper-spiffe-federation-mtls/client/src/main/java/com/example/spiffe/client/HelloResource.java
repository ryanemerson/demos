package com.example.spiffe.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class HelloResource {

    private static final Logger log = Logger.getLogger(HelloResource.class);

    @Inject
    ZeroTrustContext scheduler;

    @GET
    @Path("/authorized-hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String authorizedHello() {
        return authorized("/hello/World");
    }

    @GET
    @Path("/authorized-admin")
    @Produces(MediaType.TEXT_PLAIN)
    public String authorizedAdmin() {
        return authorized("/admin");
    }

    @GET
    @Path("/unauthorized-hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String unauthorized() {
        return unauthorized("/hello/World");
    }

    @GET
    @Path("/unauthorized-admin")
    @Produces(MediaType.TEXT_PLAIN)
    public String unauthorizedAdmin() {
        return unauthorized("/admin");
    }

    private String unauthorized(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(scheduler.getServerUrl() + path))
                  .GET()
                  .build();
            HttpResponse<String> response = scheduler.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() + ": " + response.body();
        } catch (Exception e) {
            log.errorf(e, "Unauthorized request failed: %s", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String authorized(String path) {
        try {
            String accessToken = scheduler.fetchAccessToken();
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(scheduler.getServerUrl() + path))
                  .header("Authorization", "Bearer " + accessToken)
                  .GET()
                  .build();
            HttpResponse<String> response = scheduler.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() + ": " + response.body();
        } catch (Exception e) {
            log.errorf(e, "Authorized request failed: %s", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
