package com.example.spiffe.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.net.ssl.SSLContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.spiffeid.SpiffeIdUtils;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Calls the hello-server every second with a random name using the JDK
 * java.net.http.HttpClient and a SSLContext initialised/updated by the java-spiffe libraries.
 */
@ApplicationScoped
public class HelloScheduler {

    private static final Logger log = Logger.getLogger(HelloScheduler.class);

    private static final String[] NAMES = {
          "Alice", "Bob", "Charlie", "Diana", "Eve",
          "Frank", "Grace", "Hank", "Iris", "Jack"
    };

    private final Random random = new Random();

    @ConfigProperty(name = "hello.server.url")
    String serverUrl;

    @ConfigProperty(name = "spiffe.ids", defaultValue = "*")
    String spiffeIds;

    private volatile X509Source x509Source;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        // Initialize the X509Source. This opens the gRPC stream to the SPIRE Agent specified by SPIFFE_ENDPOINT_SOCKET
        // and automatically rotates the certs stored in-memory.
        try {
            x509Source = DefaultX509Source.newSource();

            var optionsBuilder = SpiffeSslContextFactory.SslContextOptions.builder()
                  // Quarkus only enables 1.3 by default
                  .sslProtocol("TLSv1.3")
                  .x509Source(x509Source);

            if ("*".equals(spiffeIds)) {
                optionsBuilder.acceptAnySpiffeId();
            } else {
                optionsBuilder.acceptedSpiffeIdsSupplier(() -> SpiffeIdUtils.toSetOfSpiffeIds(spiffeIds));
            }

            SSLContext sslContext = SpiffeSslContextFactory.getSslContext(optionsBuilder.build());

            httpClient = HttpClient.newBuilder()
                  .sslContext(sslContext)
                  .build();
        } catch (X509SourceException | SocketEndpointAddressException e) {
            System.err.println("Failed to initialize SPIFFE X509Source: " + e.getMessage());
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            System.err.println("Failed to generate SSL Context: " + e.getMessage());
        }
    }

    @Scheduled(every = "60s", delayed = "5s")
    void callServer() {
        String name = NAMES[random.nextInt(NAMES.length)];
        try {
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(serverUrl + "/hello/" + name))
                  .GET()
                  .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.infof("Response: %s", response.body());
        } catch (Exception e) {
            log.errorf("Failed to call hello-server: %s", e.getMessage());
        }
    }

    @PreDestroy
    void cleanup() {
        if (httpClient != null) {
            httpClient.close();
        }

        if (x509Source != null) {
            try {
                x509Source.close();
            } catch (IOException e) {
                log.warnf("Error closing SPIFFE X509Source: %s", e.getMessage());
            }
        }
    }
}
