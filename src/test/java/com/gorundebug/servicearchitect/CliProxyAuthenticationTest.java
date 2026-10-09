package com.gorundebug.servicearchitect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CliProxyAuthenticationTest {
    private static String header(Socket socket) throws IOException {
        var result = new ByteArrayOutputStream();
        int suffix = 0;
        while (result.size() < 32768) {
            int b = socket.getInputStream().read();
            if (b < 0) break;
            result.write(b); suffix = (suffix << 8) | b;
            if (suffix == 0x0d0a0d0a) break;
        }
        return result.toString(StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usesJvmProxyAuthenticatorForUpstreamBasicChallenge(boolean correctPassword) throws Exception {
        Authenticator previous = Authenticator.getDefault();
        Authenticator.setDefault(new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                assertEquals(RequestorType.PROXY, getRequestorType());
                assertEquals("basic", getRequestingScheme().toLowerCase(Locale.ROOT));
                return new PasswordAuthentication("corporate-user", (correctPassword ? "corporate-password" : "wrong-password").toCharArray());
            }
        });
        try (ServerSocket proxy = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setSoTimeout(5000);
            var selector = new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress("127.0.0.1", proxy.getLocalPort()))); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            };
            Future<?> upstream = workers.submit(() -> {
                try {
                    for (int i = 0; i < 2; i++) try (Socket connection = proxy.accept()) {
                        connection.setSoTimeout(5000);
                        String request = header(connection);
                        String basic = Base64.getEncoder().encodeToString("corporate-user:corporate-password".getBytes(StandardCharsets.UTF_8));
                        if (request.contains("Proxy-Authorization: Basic " + basic)) {
                            connection.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            return;
                        }
                        connection.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                            + "Proxy-Authenticate: Basic realm=\"corporate\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    }
                    if (correctPassword) fail("Proxy did not receive authentication");
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            try (var bridge = new CliProxyEnvironment(selector)) {
                Map<String, String> env = new HashMap<>(); bridge.configureEnvironment(env);
                URI local = URI.create(env.get("HTTPS_PROXY"));
                try (Socket client = new Socket(local.getHost(), local.getPort())) {
                    client.setSoTimeout(5000);
                    String auth = Base64.getEncoder().encodeToString(local.getUserInfo().getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().write(("CONNECT packages.invalid:443 HTTP/1.1\r\nHost: packages.invalid:443\r\n"
                        + "Proxy-Authorization: Basic " + auth + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertTrue(header(client).startsWith(correctPassword ? "HTTP/1.1 200" : "HTTP/1.1 502"),
                        "Correct credentials must open the tunnel; wrong credentials must fail without endless retries");
                    if (!correctPassword) assertTrue(bridge.diagnostic().contains("407"));
                }
            }
            upstream.get(5, TimeUnit.SECONDS);
        } finally { Authenticator.setDefault(previous); }
    }
}
