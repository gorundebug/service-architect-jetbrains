package com.gorundebug.servicearchitect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CliProxyEnvironmentTest {
    private static String headers(Socket socket) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        int suffix = 0;
        while (bytes.size() < 32768) {
            int next = socket.getInputStream().read();
            if (next < 0) break;
            bytes.write(next);
            suffix = (suffix << 8) | next;
            if (suffix == 0x0d0a0d0a) break;
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static ProxySelector routes(java.util.function.Function<URI, List<Proxy>> select) {
        return new ProxySelector() {
            public List<Proxy> select(URI uri) { return select.apply(uri); }
            public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        };
    }

    private static Socket client(CliProxyEnvironment bridge, String target, boolean authenticate) throws IOException {
        Map<String, String> env = new HashMap<>();
        bridge.configureEnvironment(env);
        URI address = URI.create(env.get("HTTPS_PROXY"));
        Socket socket = new Socket(address.getHost(), address.getPort());
        socket.setSoTimeout(5000);
        String auth = authenticate ? "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
            address.getUserInfo().getBytes(StandardCharsets.UTF_8)) + "\r\n" : "";
        socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n" + auth + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    @Test void directTunnelTransfersBytesAndClosesWithBridge() throws Exception {
        try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             var bridge = new CliProxyEnvironment(routes(uri -> List.of(Proxy.NO_PROXY)))) {
            Future<?> echo = workers.submit(() -> {
                try (Socket socket = target.accept()) { socket.getInputStream().transferTo(socket.getOutputStream()); }
                catch (IOException ignored) { }
            });
            try (Socket client = client(bridge, "127.0.0.1:" + target.getLocalPort(), true)) {
                assertTrue(headers(client).startsWith("HTTP/1.1 200"));
                client.getOutputStream().write(new byte[]{1, 2, 3});
                assertArrayEquals(new byte[]{1, 2, 3}, client.getInputStream().readNBytes(3));
                bridge.close();
                assertEquals(-1, client.getInputStream().read());
            }
            echo.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsUnauthenticatedClientsBeforeRouting() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var bridge = new CliProxyEnvironment(routes(uri -> {
            count.incrementAndGet(); return List.of(Proxy.NO_PROXY);
        })); Socket client = client(bridge, "example.invalid:443", false)) {
            assertTrue(headers(client).startsWith("HTTP/1.1 407"));
            assertEquals(0, count.get());
        }
    }

    @Test void resolvesEveryDestinationAndUsesProxyWithoutLocalDns() throws Exception {
        List<String> selected = new CopyOnWriteArrayList<>();
        try (ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             var bridge = new CliProxyEnvironment(routes(uri -> {
                 selected.add(uri.toString());
                 return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", upstream.getLocalPort())));
             }))) {
            Future<?> server = workers.submit(() -> {
                try {
                    for (int i = 0; i < 2; i++) try (Socket socket = upstream.accept()) {
                        socket.setSoTimeout(5000);
                        assertTrue(headers(socket).startsWith("CONNECT destination" + i + ".invalid:443 "));
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            for (int i = 0; i < 2; i++) try (Socket socket = client(bridge, "destination" + i + ".invalid:443", true)) {
                assertTrue(headers(socket).startsWith("HTTP/1.1 200"));
            }
            server.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("https://destination0.invalid:443", "https://destination1.invalid:443"), selected);
        }
    }

    @Test void doesNotInventDirectFallback() throws Exception {
        try (var bridge = new CliProxyEnvironment(routes(uri -> List.of()));
             Socket client = client(bridge, "127.0.0.1:80", true)) {
            assertTrue(headers(client).startsWith("HTTP/1.1 502"));
        }
    }

    @Test void environmentContainsOnlyLoopbackCredentials() throws Exception {
        try (var bridge = new CliProxyEnvironment(routes(uri -> List.of(Proxy.NO_PROXY)))) {
            Map<String, String> env = new HashMap<>(Map.of("HTTPS_PROXY", "http://secret:password@corporate:8080", "NO_PROXY", "*"));
            bridge.configureEnvironment(env);
            String url = env.get("HTTPS_PROXY");
            assertEquals("127.0.0.1", URI.create(url).getHost());
            assertEquals("", env.get("NO_PROXY"));
            assertFalse(env.toString().contains("password"));
            assertFalse(bridge.redact(url).contains(URI.create(url).getUserInfo().split(":")[1]));
        }
    }
}
