package com.example.spiffe.client;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Random;

/**
 * Calls the hello-server in site-east every second with a random name,
 * printing the plain-text response to demonstrate SPIFFE mTLS over Skupper.
 */
@ApplicationScoped
public class HelloScheduler {

    private static final Logger log = Logger.getLogger(HelloScheduler.class);

    private static final String[] NAMES = {
            "Alice", "Bob", "Charlie", "Diana", "Eve",
            "Frank", "Grace", "Hank", "Iris", "Jack"
    };

    private final Random random = new Random();

    @Inject
    @RestClient
    HelloClient helloClient;

    @Scheduled(every = "1s", delayed = "5s")
    void callServer() {
        String name = NAMES[random.nextInt(NAMES.length)];
        try {
            String response = helloClient.hello(name);
            log.infof("Response: %s", response);
        } catch (Exception e) {
            log.errorf("Failed to call hello-server: %s", e.getMessage());
        }
    }
}
