package org.example;

import io.quarkus.oidc.Tenant;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api")
@Tenant("backend")
public class MyApi {

   @GET
   @Path("/public")
   @PermitAll
   public String publicEndpoint() {
      return "Anyone can access this.";
   }

   @GET
   @Path("/secure")
   @RolesAllowed("secure-endpoints")
   public String secureEndpoint() {
      return "You are authenticated!";
   }
}
