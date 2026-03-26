package com.example.demo.authzen.mapper;

import com.example.demo.authzen.spi.AuthZenPropertyMapper;
import com.example.demo.authzen.spi.AuthZenPropertyMapperFactory;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class UserProfilePropertyMapperFactory implements AuthZenPropertyMapperFactory {

    @Override
    public AuthZenPropertyMapper create(KeycloakSession session) {
        return new UserProfilePropertyMapper();
    }

    @Override
    public String getId() {
        return "user-profile";
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
