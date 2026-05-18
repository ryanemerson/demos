package com.example.ssf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/ssf")
public class SsfEventResource {

    private static final Logger LOG = Logger.getLogger(SsfEventResource.class.getName());
    private static final String SCIM_ADD_EVENT = "urn:ietf:params:scim:event:feed:add";
    private static final String SCIM_ADD_MEMBER_EVENT = "urn:ietf:params:scim:event:feed:addMember";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Inject
    ScimForwarder scimForwarder;

    @Inject
    ObjectMapper objectMapper;

    @POST
    @Path("/events")
    @Consumes("application/secevent+jwt")
    public Response receiveEvent(String setJwt) {
        LOG.info("Received SSF event");

        try {
            Map<String, Object> payload = decodeSetPayload(setJwt);
            processEvents(payload);
            return Response.accepted().build();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to process SSF event", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to process event: " + e.getMessage())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeSetPayload(String setJwt) throws Exception {
        String[] parts = setJwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid SET JWT format");
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readValue(payloadBytes, MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private void processEvents(Map<String, Object> payload) throws Exception {
        Object eventsObj = payload.get("events");
        if (!(eventsObj instanceof Map)) {
            LOG.warning("No events found in SET payload");
            return;
        }

        Map<String, Object> events = (Map<String, Object>) eventsObj;

        if (events.containsKey(SCIM_ADD_EVENT)) {
            Map<String, Object> scimData = (Map<String, Object>) events.get(SCIM_ADD_EVENT);
            LOG.info("Processing SCIM add event for user: " + scimData.get("userName"));
            scimForwarder.createUser(scimData);
        } else if (events.containsKey(SCIM_ADD_MEMBER_EVENT)) {
            Map<String, Object> memberData = (Map<String, Object>) events.get(SCIM_ADD_MEMBER_EVENT);
            String userName = (String) memberData.get("userName");
            Object scopesObj = memberData.get("scopes");
            if (scopesObj instanceof java.util.List<?> scopes) {
                LOG.info("Processing group membership event for user: " + userName + " scopes: " + scopes);
                for (Object scope : scopes) {
                    scimForwarder.addUserToGroup(userName, scope + "s");
                }
            }
        } else {
            LOG.info("Ignoring unhandled event types: " + events.keySet());
        }
    }
}
