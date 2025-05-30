package org.achme;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/users")
public class ProtectedResource {

   @Inject
   SecurityIdentity securityIdentity;

   @Inject
   Template secured;

   @GET
   @Path("/me")
   @Authenticated
   @Produces(MediaType.TEXT_HTML)
   public String me() {
      return secured.data("username", securityIdentity.getPrincipal().getName())
            .data("securityIdentity", securityIdentity).render();
   }
}
