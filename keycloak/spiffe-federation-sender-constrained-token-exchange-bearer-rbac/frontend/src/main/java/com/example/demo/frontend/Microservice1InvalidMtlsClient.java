package com.example.demo.frontend;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/")
@RegisterRestClient(configKey = "microservice1-invalid-mtls")
public interface Microservice1InvalidMtlsClient {

    @GET
    Response call(@HeaderParam("Authorization") String authorization);
}
