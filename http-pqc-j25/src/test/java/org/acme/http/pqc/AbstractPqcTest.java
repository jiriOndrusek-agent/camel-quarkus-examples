/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acme.http.pqc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.specification.RequestSpecification;
import org.apache.http.conn.ssl.SSLSocketFactory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Calls the server with the JDK TLS stack (SunJSSE, no Bouncy Castle), restricting the TLS named groups the client
 * offers.
 */
abstract class AbstractPqcTest {

    /** Hybrid post-quantum key exchange only. */
    static final String[] HYBRID_GROUPS = { "X25519MLKEM768" };
    /** Classical key exchange only, as offered by a client without post-quantum support. */
    static final String[] CLASSICAL_GROUPS = { "x25519", "secp256r1" };

    private static final char[] PASSWORD = "changeit".toCharArray();

    void assertAccepted(String[] namedGroups) throws Exception {
        request(namedGroups)
                .get("/pqc/secure")
                .then()
                .statusCode(200)
                .body(containsString("Post-Quantum Cryptography"));
    }

    void assertRejected(String[] namedGroups) {
        assertThrows(SSLException.class, () -> request(namedGroups).get("/pqc/secure"));
    }

    /**
     * @param namedGroups the TLS named groups offered by the client, or {@code null} for the JDK defaults
     */
    private static RequestSpecification request(String[] namedGroups) throws Exception {
        SSLSocketFactory socketFactory = new SSLSocketFactory(createSslContext(), SSLSocketFactory.STRICT_HOSTNAME_VERIFIER) {
            @Override
            protected void prepareSocket(SSLSocket socket) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setProtocols(new String[] { "TLSv1.3" });
                if (namedGroups != null) {
                    parameters.setNamedGroups(namedGroups);
                }
                socket.setSSLParameters(parameters);
            }
        };
        return given()
                .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().sslSocketFactory(socketFactory)))
                .baseUri("https://localhost:" + RestAssured.port);
    }

    private static SSLContext createSslContext() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore("target/certs/client-keystore.p12"), PASSWORD);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(loadKeyStore("target/certs/client-truststore.p12"));

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    private static KeyStore loadKeyStore(String path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }
}
