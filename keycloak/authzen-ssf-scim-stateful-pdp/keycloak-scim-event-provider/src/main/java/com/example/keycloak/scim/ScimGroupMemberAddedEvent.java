package com.example.keycloak.scim;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.keycloak.ssf.event.SsfEvent;

public class ScimGroupMemberAddedEvent extends SsfEvent {

    public static final String TYPE = "urn:ietf:params:scim:event:feed:addMember";

    @JsonProperty("userName")
    private String userName;

    @JsonProperty("scopes")
    private List<String> scopes;

    public ScimGroupMemberAddedEvent() {
        super(TYPE);
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }
}
