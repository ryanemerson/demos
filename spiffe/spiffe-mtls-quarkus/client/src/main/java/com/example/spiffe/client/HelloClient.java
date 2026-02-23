package com.example.spiffe.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
/**
 * REST client interface for the hello-server.
 */
@Path("/hello")
public interface HelloClient {

    @GET
    @Path("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String hello(@PathParam("name") String name);
}
