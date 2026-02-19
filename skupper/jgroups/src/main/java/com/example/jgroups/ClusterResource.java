package com.example.jgroups;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jgroups.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/cluster")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    @Inject
    JGroupsService jgroupsService;

    @GET
    @Path("/info")
    public Map<String, Object> getClusterInfo() {
        Map<String, Object> info = new HashMap<>();
        View view = jgroupsService.getView();

        info.put("localAddress", jgroupsService.getLocalAddress());
        if (view != null) {
            info.put("viewId", view.getViewId().toString());
            info.put("members", view.getMembers().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList()));
            info.put("memberCount", view.size());
        }

        return info;
    }

    @POST
    @Path("/send")
    public Response sendMessage(MessageRequest request) {
        try {
            jgroupsService.sendMessage(request.message);
            return Response.ok()
                    .entity(Map.of("status", "Message sent successfully"))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/messages")
    public List<String> getReceivedMessages() {
        return jgroupsService.getReceivedMessages();
    }

    @DELETE
    @Path("/messages")
    public Response clearMessages() {
        jgroupsService.clearMessages();
        return Response.ok()
                .entity(Map.of("status", "Messages cleared"))
                .build();
    }

    public static class MessageRequest {
        public String message;
    }
}
