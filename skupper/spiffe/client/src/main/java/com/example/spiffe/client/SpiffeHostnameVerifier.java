package com.example.spiffe.client;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/**
 * SPIFFE-aware hostname verifier.
 *
 * Standard TLS hostname verification checks the server certificate's Common Name
 * or DNS SAN entries against the hostname being connected to. SPIFFE certificates
 * do not use DNS hostnames for identity — instead, the workload identity is carried
 * as a URI SAN in the form: spiffe://trust-domain/workload-path
 *
 * The trust is established through the certificate chain (SPIRE CA) and the SPIFFE
 * ID in the URI SAN. Standard hostname verification would fail because the server
 * certificate's SAN contains "spiffe://east.demo.example.com/hello-server", not
 * "hello-server" (the Skupper listener hostname).
 *
 * This verifier bypasses hostname checking. Trust is still fully enforced via the
 * mutual TLS certificate chain — both sides must present a certificate signed by
 * a trusted SPIRE CA (east or west, linked via SPIFFE federation).
 *
 * Configured via: quarkus.rest-client.hello-server.hostname-verifier
 */
public class SpiffeHostnameVerifier implements HostnameVerifier {

    @Override
    public boolean verify(String hostname, SSLSession session) {
        // Identity is verified via SPIFFE certificate chain, not hostname matching.
        return true;
    }
}
