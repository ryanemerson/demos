package com.example.spiffe.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the hello-server in site-east.
 * Connects via Skupper Listener (hello-server:8443) using SPIFFE mTLS.
 * The hostname verifier is configured via application.properties
 * (quarkus.rest-client.hello-server.hostname-verifier).
 */
@RegisterRestClient(configKey = "hello-server")
@Path("/hello")
public interface HelloClient {

    @GET
    @Path("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String hello(@PathParam("name") String name);
}
