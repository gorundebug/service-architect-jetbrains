package com.gorundebug.servicearchitect;

import com.intellij.credentialStore.Credentials;
import com.intellij.util.net.HttpConfigurable;
import com.intellij.util.net.ProxyAuthentication;
import com.intellij.util.net.ProxyConfiguration;
import com.intellij.util.net.ProxySettings;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Process-local proxy configuration. Credentials are never written to disk or command arguments. */
final class CliProxyEnvironment {
    private static final List<String> PROXY_KEYS = List.of(
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy");

    private CliProxyEnvironment() {}

    static boolean configure(Map<String, String> environment) throws IOException {
        ProxyConfiguration configuration = ProxySettings.getInstance().getProxyConfiguration();
        if (configuration instanceof ProxyConfiguration.StaticProxyConfiguration proxy) {
            String bypass = bypassList(proxy.getExceptions());
            String address;
            try {
                address = new URI(proxy.getProtocol() == ProxyConfiguration.ProxyProtocol.SOCKS ? "socks5h" : "http",
                    null, proxy.getHost(), proxy.getPort(), null, null, null).toASCIIString();
            } catch (URISyntaxException error) {
                throw new IOException("Invalid IDE proxy address. Check Settings > HTTP Proxy.");
            }
            ProxyAuthentication authentication = ProxyAuthentication.getInstance();
            Credentials credentials = authentication.getKnownAuthentication(proxy.getHost(), proxy.getPort());
            if (credentials == null && HttpConfigurable.getInstance().PROXY_AUTHENTICATION) {
                credentials = authentication.getPromptedAuthentication(
                    "Service Architect CLI setup", proxy.getHost(), proxy.getPort());
                if (credentials == null) throw new IOException("Proxy authentication cancelled. CLI setup was not completed.");
            }
            if (credentials != null && credentials.getUserName() != null) {
                String password = credentials.getPasswordAsString();
                String userInfo = encode(credentials.getUserName()) + ":" + encode(password == null ? "" : password);
                int separator = address.indexOf("://") + 3;
                address = address.substring(0, separator) + userInfo + "@" + address.substring(separator);
            }
            for (String key : PROXY_KEYS) environment.put(key, address);
            // Use the IDE's exclusions, not possibly unrelated inherited NO_PROXY values.
            environment.put("NO_PROXY", bypass);
            environment.put("no_proxy", bypass);
        } else if (HttpConfigurable.getInstance().USE_PROXY_PAC) {
            throw new IOException("CLI setup cannot translate per-address PAC routes into a single process proxy. "
                + "Use a manual HTTP proxy in Settings > HTTP Proxy for CLI setup; automatic routes will not be bypassed silently.");
        }
        // Trust corporate roots installed in the OS without disabling TLS verification.
        environment.putIfAbsent("UV_NATIVE_TLS", "true");
        for (String key : PROXY_KEYS) {
            String value = environment.get(key);
            if (value != null && value.contains("@")) return true;
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String bypassList(String exceptions) throws IOException {
        List<String> hosts = new ArrayList<>();
        if (exceptions == null || exceptions.isBlank()) return "";
        for (String entry : exceptions.split("[,;|\\s]+")) {
            if (entry.isEmpty()) continue;
            if (entry.startsWith("*.")) entry = entry.substring(1);
            if ((!entry.equals("*") && entry.contains("*")) || entry.contains("?") || entry.contains("<")) {
                throw new IOException("An IDE proxy exception cannot be represented in NO_PROXY: " + entry
                    + ". Use host names or *.domain exceptions for CLI setup.");
            }
            hosts.add(entry);
        }
        return String.join(",", hosts);
    }
}
