package com.example.spiffe.client;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Wraps an X509ExtendedTrustManager to skip JDK endpoint-identification
 * (hostname) verification while still validating the certificate chain.
 *
 * SPIFFE certificates carry a URI SAN (spiffe://…), not a DNS SAN.
 * The JDK "HTTPS" endpoint-identification algorithm rejects certs without
 * a matching DNS SAN, even when verify-host=false is set in Quarkus config
 * (a known ordering issue with the TLS registry overriding the REST client
 * verify-host flag on the underlying Vert.x SSLEngine).
 *
 * This wrapper temporarily clears endpointIdentificationAlgorithm on the
 * SSLEngine before delegating so:
 *   - the SPIFFE CA chain IS still verified (trust enforced)
 *   - the hostname IS NOT checked (correct for SPIFFE URI SANs)
 */
class SpiffeNoHostnameTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;

    SpiffeNoHostnameTrustManager(X509ExtendedTrustManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        SSLParameters params = engine.getSSLParameters();
        String saved = params.getEndpointIdentificationAlgorithm();
        params.setEndpointIdentificationAlgorithm("");
        engine.setSSLParameters(params);
        try {
            delegate.checkServerTrusted(chain, authType, engine);
        } finally {
            params.setEndpointIdentificationAlgorithm(saved);
            engine.setSSLParameters(params);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
