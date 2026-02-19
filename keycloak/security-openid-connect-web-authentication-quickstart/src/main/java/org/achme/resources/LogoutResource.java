package org.achme.resources;

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/logout")
public class LogoutResource {

   @GET
   public Response logout() throws URISyntaxException {
      return Response.seeOther(new URI("http://localhost:8081/realms/demo/protocol/openid-connect/logout")).build();
   }
}