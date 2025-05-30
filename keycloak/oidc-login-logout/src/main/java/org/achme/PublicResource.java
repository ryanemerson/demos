package org.achme;

import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PublicResource {

   @Inject
   Template index;

   @Inject
   SecurityIdentity securityIdentity;

   @GET
   @Produces(MediaType.TEXT_HTML)
   public String getIndex() {
      return index.data("securityIdentity", securityIdentity).render();
   }
}
