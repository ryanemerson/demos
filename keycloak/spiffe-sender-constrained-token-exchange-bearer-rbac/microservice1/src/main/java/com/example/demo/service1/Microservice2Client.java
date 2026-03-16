package com.example.demo.service1;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/")
@RegisterRestClient(configKey = "microservice2")
public interface Microservice2Client {

    @GET
    Response call(@HeaderParam("Authorization") String authorization);
}
