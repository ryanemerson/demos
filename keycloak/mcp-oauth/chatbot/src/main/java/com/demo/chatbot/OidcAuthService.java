package com.demo.chatbot;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class OidcAuthService {

    @ConfigProperty(name = "app.oidc.auth-server-url")
    String authServerUrl;

    @ConfigProperty(name = "app.oidc.client-id")
    String clientId;

    @ConfigProperty(name = "app.oidc.redirect-uri")
    String redirectUri;

    @ConfigProperty(name = "app.oidc.scopes")
    String scopes;

    private volatile String accessToken;
    private volatile String idToken;

    public String getAccessToken() {
        return accessToken;
    }

    public void logout() {
        if (idToken == null) {
            return;
        }
        try {
            String logoutUrl = authServerUrl + "/protocol/openid-connect/logout"
                    + "?id_token_hint=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8)
                    + "&post_logout_redirect_uri=" + URLEncoder.encode("http://localhost:8080/logged-out", StandardCharsets.UTF_8);
            openBrowser(logoutUrl);
            System.out.println("Logged out from Keycloak.");
        } catch (Exception e) {
            System.err.println("Logout failed: " + e.getMessage());
        } finally {
            accessToken = null;
            idToken = null;
        }
    }

    public void login() throws Exception {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateRandomString(32);

        CompletableFuture<String> authCodeFuture = new CompletableFuture<>();

        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer();

        server.requestHandler(request -> {
            if ("/callback".equals(request.path())) {
                String code = request.getParam("code");
                String returnedState = request.getParam("state");
                if (code != null && state.equals(returnedState)) {
                    request.response()
                            .putHeader("Content-Type", "text/html")
                            .end("<html><body><h2>Login successful!</h2><p>You can close this window and return to the terminal.</p></body></html>");
                    authCodeFuture.complete(code);
                } else {
                    String error = request.getParam("error");
                    request.response()
                            .setStatusCode(400)
                            .putHeader("Content-Type", "text/html")
                            .end("<html><body><h2>Login failed</h2><p>" + error + "</p></body></html>");
                    authCodeFuture.completeExceptionally(new RuntimeException("Authentication failed: " + error));
                }
            } else {
                request.response().setStatusCode(404).end();
            }
        });

        server.listen(8080).toCompletionStage().toCompletableFuture().get();

        String authUrl = authServerUrl + "/protocol/openid-connect/auth"
                + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(scopes, StandardCharsets.UTF_8)
                + "&state=" + state
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        System.out.println("Opening browser for login...");
        System.out.println("If the browser does not open automatically, visit:");
        System.out.println(authUrl);

        openBrowser(authUrl);

        System.out.println("Waiting for login...");
        String authCode = authCodeFuture.get();

        JsonObject tokens = exchangeCodeForTokens(authCode, codeVerifier);
        this.accessToken = tokens.getString("access_token");
        this.idToken = tokens.getString("id_token");
        System.out.println("Login successful! Access token obtained.");

        server.close().toCompletionStage().toCompletableFuture().get();
        vertx.close().toCompletionStage().toCompletableFuture().get();
    }

    private JsonObject exchangeCodeForTokens(String code, String codeVerifier) throws Exception {
        String tokenUrl = authServerUrl + "/protocol/openid-connect/token";

        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&code_verifier=" + codeVerifier;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Token exchange failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        return new JsonObject(response.body());
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                new ProcessBuilder("xdg-open", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            }
        } catch (Exception ignored) {
        }
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateRandomString(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }
}
