package com.gorundebug.servicearchitect;

import com.intellij.util.io.HttpRequests;
import com.intellij.util.proxy.CommonProxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Short-lived bridge from child-process proxy support to the IDE's per-URL routing. */
final class CliProxyEnvironment implements AutoCloseable {
    private static final Pattern BASIC_CHALLENGE = Pattern.compile("(?i)(?:^|,)\\s*Basic(?:\\s|$)");
    private static final Pattern REALM = Pattern.compile("(?i)\\brealm=\"([^\"]*)\"");
    private static final Set<String> HOP_HEADERS = Set.of(
        "connection", "proxy-connection", "proxy-authenticate", "proxy-authorization",
        "keep-alive", "transfer-encoding", "te", "trailer", "upgrade");
    private final ServerSocket listener;
    private final ProxySelector selector;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Map<InetSocketAddress, String> proxyCredentials = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(32);
    private final String secret = UUID.randomUUID().toString();
    private final String authorization = "Basic " + Base64.getEncoder().encodeToString(
        ("service-architect:" + secret).getBytes(StandardCharsets.UTF_8));
    private volatile boolean closed;
    private volatile String diagnostic = "";

    private CliProxyEnvironment() throws IOException {
        this(ideSelector());
    }

    private static ProxySelector ideSelector() {
        CommonProxy proxy = CommonProxy.getInstance();
        proxy.ensureAuthenticator();
        return proxy;
    }

    CliProxyEnvironment(ProxySelector selector) throws IOException {
        this.selector = selector;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32);
        workers.execute(this::accept);
    }

    static CliProxyEnvironment configure(Map<String, String> environment) throws IOException {
        CliProxyEnvironment bridge = new CliProxyEnvironment();
        bridge.configureEnvironment(environment);
        return bridge;
    }

    void configureEnvironment(Map<String, String> environment) {
        String address = "http://service-architect:" + secret + "@127.0.0.1:" + listener.getLocalPort();
        for (String key : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
            environment.put(key, address);
        }
        // Exclusions and PAC decisions belong to the IDE, not the subprocess.
        environment.put("NO_PROXY", "");
        environment.put("no_proxy", "");
        environment.putIfAbsent("UV_NATIVE_TLS", "true");
    }

    String redact(String output) {
        return output.replace(secret, "[redacted]").replace(authorization, "[redacted]");
    }

    String diagnostic() {
        return diagnostic;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                sockets.add(socket);
                if (closed || !capacity.tryAcquire()) {
                    discard(socket);
                    continue;
                }
                workers.execute(() -> {
                    try (socket) {
                        serve(socket);
                    } catch (IOException | RuntimeException ignored) {
                        // The downloader receives an HTTP failure or a closed connection and reports it.
                    } finally {
                        sockets.remove(socket);
                        capacity.release();
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                if (!closed) close();
            }
        }
    }

    private void serve(Socket client) throws IOException {
        client.setSoTimeout(20_000);
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        String[] lines = readHeaders(input).split("\r\n");
        String[] request = lines[0].split(" ", 3);
        String supplied = "";
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase("Proxy-Authorization")) {
                supplied = lines[i].substring(colon + 1).trim();
            }
        }
        if (!MessageDigest.isEqual(authorization.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
            reply(output, "407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"Service Architect setup\"\r\n");
            return;
        }
        if (request.length != 3) {
            reply(output, "400 Bad Request", "");
            return;
        }
        if (request[0].equals("CONNECT")) {
            URI destination;
            try {
                destination = URI.create("https://" + request[1]);
                if (destination.getHost() == null || destination.getRawUserInfo() != null
                    || destination.getPort() < 1 || destination.getPort() > 65535
                    || !destination.getRawPath().isEmpty() || destination.getRawQuery() != null
                    || destination.getRawFragment() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                reply(output, "400 Bad Request", "");
                return;
            }
            Socket upstream;
            try {
                upstream = connect(destination);
            } catch (IOException error) {
                reply(output, "502 Bad Gateway", "");
                return;
            }
            try (upstream) {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                client.setSoTimeout(0);
                var outgoing = workers.submit(() -> pump(client, upstream));
                try {
                    pump(upstream, client);
                } finally {
                    discard(client);
                    outgoing.cancel(true);
                }
            } finally {
                sockets.remove(upstream);
            }
        } else if (request[0].equals("GET") || request[0].equals("HEAD")) {
            forwardHttp(request, lines, output);
        } else {
            reply(output, "405 Method Not Allowed", "");
        }
    }

    private Socket connect(URI destination) throws IOException {
        IOException failure = new IOException("No usable route in IDE proxy settings");
        for (Proxy proxy : selector.select(destination)) {
            if (closed) throw new IOException("CLI setup cancelled");
            try {
                return proxy.type() == Proxy.Type.HTTP
                    ? connectHttpProxy(proxy, destination) : connectJvmProxy(proxy, destination);
            } catch (IOException error) {
                failure = error;
                if (proxy.address() != null) selector.connectFailed(destination, proxy.address(), error);
            }
        }
        throw failure;
    }

    private Socket connectJvmProxy(Proxy proxy, URI destination) throws IOException {
        Socket socket = new Socket(proxy);
        sockets.add(socket);
        try {
            if (closed) throw new IOException("CLI setup cancelled");
            InetSocketAddress target = proxy.type() == Proxy.Type.DIRECT
                ? new InetSocketAddress(destination.getHost(), destination.getPort())
                : InetSocketAddress.createUnresolved(destination.getHost(), destination.getPort());
            socket.connect(target, 20_000);
            return socket;
        } catch (IOException | RuntimeException error) {
            discard(socket);
            throw error;
        }
    }

    private Socket connectHttpProxy(Proxy proxy, URI destination) throws IOException {
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        String credentials = proxyCredentials.get(address);
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            sockets.add(socket);
            boolean connected = false;
            try {
                if (closed) throw new IOException("CLI setup cancelled");
                socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), 20_000);
                socket.setSoTimeout(20_000);
                String authority = destination.getRawAuthority();
                String request = "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority
                    + "\r\nProxy-Connection: keep-alive\r\n"
                    + (credentials == null ? "" : "Proxy-Authorization: " + credentials + "\r\n") + "\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                String[] response = readHeaders(socket.getInputStream()).split("\r\n");
                String[] status = response[0].split(" ", 3);
                if (status.length < 2 || !status[0].startsWith("HTTP/1.") || !status[1].matches("[0-9]{3}")) {
                    throw new IOException("Invalid HTTP proxy response");
                }
                if (status[1].equals("200")) {
                    socket.setSoTimeout(0);
                    connected = true;
                    return socket;
                }
                diagnostic = "The upstream proxy returned HTTP " + status[1] + ".";
                if (!status[1].equals("407")) throw new IOException(diagnostic);
                proxyCredentials.remove(address);
                if (attempt > 0) throw new IOException(diagnostic);
                String challenge = null;
                for (int i = 1; i < response.length; i++) {
                    int colon = response[i].indexOf(':');
                    if (colon > 0 && response[i].substring(0, colon).equalsIgnoreCase("Proxy-Authenticate")) {
                        String value = response[i].substring(colon + 1).trim();
                        if (BASIC_CHALLENGE.matcher(value).find()) { challenge = value; break; }
                    }
                }
                // Do not hold the failed tunnel while the IDE may show an authentication prompt.
                discard(socket);
                if (challenge == null) {
                    // Retain the JVM's supported non-Basic authentication mechanisms.
                    return connectJvmProxy(proxy, destination);
                }
                var realm = REALM.matcher(challenge);
                PasswordAuthentication authentication = Authenticator.requestPasswordAuthentication(
                    address.getHostString(), address.getAddress(), address.getPort(), "http",
                    realm.find() ? realm.group(1) : "Proxy authentication", "Basic",
                    destination.toURL(), Authenticator.RequestorType.PROXY);
                if (authentication == null) {
                    diagnostic = "Proxy authentication was cancelled or no credentials were provided.";
                    throw new IOException(diagnostic);
                }
                var charset = challenge.toLowerCase(Locale.ROOT).contains("charset=\"utf-8\"")
                    ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                credentials = "Basic " + Base64.getEncoder().encodeToString(
                    (authentication.getUserName() + ":" + new String(authentication.getPassword())).getBytes(charset));
                proxyCredentials.put(address, credentials);
            } finally {
                if (!connected) discard(socket);
            }
        }
        throw new IOException("Proxy authentication failed");
    }

    private void forwardHttp(String[] request, String[] headers, OutputStream output) throws IOException {
        URI uri;
        try {
            uri = URI.create(request[1]);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            reply(output, "400 Bad Request", "");
            return;
        }
        HttpRequests.request(uri.toASCIIString()).useProxy(true).connectTimeout(20_000).readTimeout(45_000)
            .followRedirects(false).gzip(false).throwStatusCodeException(false).tuner(connection -> {
                if (connection instanceof java.net.HttpURLConnection http) http.setRequestMethod(request[0]);
                for (int i = 1; i < headers.length; i++) {
                    int colon = headers[i].indexOf(':');
                    if (colon <= 0) continue;
                    String name = headers[i].substring(0, colon);
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!HOP_HEADERS.contains(lower) && !lower.equals("host") && !lower.equals("content-length")) {
                        connection.setRequestProperty(name, headers[i].substring(colon + 1).trim());
                    }
                }
            }).connect(response -> {
                var connection = (java.net.HttpURLConnection) response.getConnection();
                try {
                    int status = connection.getResponseCode();
                    output.write(("HTTP/1.1 " + status + " Response\r\nConnection: close\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (var entry : connection.getHeaderFields().entrySet()) {
                        if (entry.getKey() == null || HOP_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) continue;
                        for (String value : entry.getValue()) {
                            output.write((entry.getKey() + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                    }
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    if (!request[0].equals("HEAD")) {
                        try (InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                            if (body != null) body.transferTo(output);
                        }
                    }
                    output.flush();
                } finally {
                    connection.disconnect();
                }
                return null;
            });
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int suffix = 0;
        while (bytes.size() < 32_768) {
            int next = input.read();
            if (next < 0) throw new IOException("Incomplete proxy request");
            bytes.write(next);
            suffix = (suffix << 8) | next;
            if (suffix == 0x0d0a0d0a) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new IOException("Proxy request headers too large");
    }

    private static void pump(Socket source, Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.shutdownOutput();
        } catch (IOException ignored) {
            try { destination.close(); } catch (IOException suppressed) { }
        }
    }

    private static void reply(OutputStream output, String status, String headers) throws IOException {
        output.write(("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private void discard(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        closed = true;
        proxyCredentials.clear();
        try { listener.close(); } catch (IOException ignored) { }
        sockets.forEach(this::discard);
        workers.shutdownNow();
    }
}
