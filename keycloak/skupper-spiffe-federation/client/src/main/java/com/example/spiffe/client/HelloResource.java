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
    ZeroTrustContext context;

    @GET
    @Path("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(context.getServerUrl() + "/hello/World"))
                  .GET()
                  .build();
            HttpResponse<String> response = context.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() + ": " + response.body();
        } catch (Exception e) {
            log.errorf(e, "Request failed: %s", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
