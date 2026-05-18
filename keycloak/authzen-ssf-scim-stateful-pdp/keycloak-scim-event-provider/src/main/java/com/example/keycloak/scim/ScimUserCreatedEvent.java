package com.example.keycloak.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.keycloak.ssf.event.SsfEvent;

public class ScimUserCreatedEvent extends SsfEvent {

    public static final String TYPE = "urn:ietf:params:scim:event:feed:add";

    @JsonProperty("userName")
    private String userName;

    @JsonProperty("active")
    private Boolean active;

    public ScimUserCreatedEvent() {
        super(TYPE);
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
