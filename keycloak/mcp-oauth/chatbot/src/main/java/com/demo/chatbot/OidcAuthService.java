package com.demo.chatbot;

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

@ApplicationScoped
public class OidcAuthService {

    @ConfigProperty(name = "app.oidc.auth-server-url")
    String authServerUrl;

    @ConfigProperty(name = "app.oidc.client-id")
    String clientId;

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
                    + "?id_token_hint=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(logoutUrl))
                    .GET()
                    .build();
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
            System.out.println("Logged out from Keycloak.");
        } catch (Exception e) {
            System.err.println("Logout failed: " + e.getMessage());
        } finally {
            accessToken = null;
            idToken = null;
        }
    }

    public void login() throws Exception {
        JsonObject deviceAuth = requestDeviceAuthorization();

        String userCode = deviceAuth.getString("user_code");
        String verificationUri = deviceAuth.getString("verification_uri");
        String deviceCode = deviceAuth.getString("device_code");
        int interval = deviceAuth.getInteger("interval", 5);

        System.out.println("\nTo sign in, open your browser and visit:");
        System.out.println("  " + verificationUri);
        System.out.println("\nEnter the code: " + userCode);

        openBrowser(verificationUri);

        JsonObject tokens = pollForTokens(deviceCode, interval);
        this.accessToken = tokens.getString("access_token");
        this.idToken = tokens.getString("id_token");
        System.out.println("Login successful! Access token obtained.");
    }

    private JsonObject requestDeviceAuthorization() throws Exception {
        String deviceAuthUrl = authServerUrl + "/protocol/openid-connect/auth/device";

        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(scopes, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceAuthUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Device authorization request failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        return new JsonObject(response.body());
    }

    private JsonObject pollForTokens(String deviceCode, int intervalSeconds) throws Exception {
        String tokenUrl = authServerUrl + "/protocol/openid-connect/token";

        String body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();

        while (true) {
            Thread.sleep(intervalSeconds * 1000L);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject json = new JsonObject(response.body());

            if (response.statusCode() == 200) {
                return json;
            }

            String error = json.getString("error");
            switch (error) {
                case "authorization_pending":
                    break;
                case "slow_down":
                    intervalSeconds += 5;
                    break;
                case "expired_token":
                    throw new RuntimeException("Device code expired. Please try again.");
                case "access_denied":
                    throw new RuntimeException("Authorization denied by user.");
                default:
                    throw new RuntimeException("Token request failed: " + error + " - " + json.getString("error_description"));
            }
        }
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
}
