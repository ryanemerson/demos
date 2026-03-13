package com.example.demo.frontend;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/")
@RegisterRestClient(configKey = "microservice1")
public interface Microservice1Client {

    @GET
    Response call(@HeaderParam("Authorization") String authorization);
}
