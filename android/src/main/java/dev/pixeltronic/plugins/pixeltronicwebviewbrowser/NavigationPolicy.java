package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;

/** Immutable document-navigation rules. These rules are not a network/subresource firewall. */
final class NavigationPolicy {
    static final class Decision {
        final boolean allowed;
        final String reason;
        Decision(boolean allowed, String reason) { this.allowed = allowed; this.reason = reason; }
    }

    private final Set<String> origins;
    private final boolean allowHttp;

    private NavigationPolicy(Set<String> origins, boolean allowHttp) {
        this.origins = Collections.unmodifiableSet(new HashSet<>(origins));
        this.allowHttp = allowHttp;
    }

    static NavigationPolicy create(String initialUrl, List<String> allowedOrigins, boolean allowHttp) {
        Set<String> origins = new HashSet<>();
        if (allowedOrigins == null) {
            String origin = origin(initialUrl);
            if (origin != null) origins.add(origin);
        } else {
            if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("allowedOrigins must not be empty.");
            for (String value : allowedOrigins) {
                URI uri = parse(value);
                String normalized = origin(value);
                if (normalized == null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
                    throw new IllegalArgumentException("allowedOrigins must contain exact HTTP(S) origins without paths, credentials, queries or fragments.");
                }
                origins.add(normalized);
            }
        }
        return new NavigationPolicy(origins, allowHttp);
    }

    static NavigationPolicy from(PluginCall call, String initialUrl) {
        JSObject raw = call.getObject("navigationPolicy");
        if (raw == null) {
            if (call.getData() != null && call.getData().has("navigationPolicy")) {
                throw new IllegalArgumentException("navigationPolicy must be an object.");
            }
            return create(initialUrl, null, false);
        }
        Object http = raw.opt("allowHttp");
        if (raw.has("allowHttp") && !(http instanceof Boolean)) throw new IllegalArgumentException("allowHttp must be a boolean.");
        List<String> origins = null;
        if (raw.has("allowedOrigins")) {
            Object value = raw.opt("allowedOrigins");
            if (!(value instanceof JSONArray)) throw new IllegalArgumentException("allowedOrigins must be an array.");
            JSONArray array = (JSONArray) value;
            origins = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                if (!(array.opt(index) instanceof String)) throw new IllegalArgumentException("Each allowed origin must be a string.");
                origins.add((String) array.opt(index));
            }
        }
        return create(initialUrl, origins, Boolean.TRUE.equals(http));
    }

    Decision evaluate(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getScheme() == null) return new Decision(false, "INVALID_URL");
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return new Decision(false, "SCHEME_BLOCKED");
        if (uri.getRawUserInfo() != null) return new Decision(false, "CREDENTIALS_BLOCKED");
        String origin = origin(value);
        if (origin == null) return new Decision(false, "INVALID_URL");
        if (scheme.equals("http") && !allowHttp) return new Decision(false, "HTTPS_REQUIRED");
        return origins.contains(origin) ? new Decision(true, "ALLOWED") : new Decision(false, "ORIGIN_BLOCKED");
    }

    static Decision popupBlocked() { return new Decision(false, "POPUP_BLOCKED"); }

    static boolean sameHost(String first, String second) {
        URI a = parse(first), b = parse(second);
        return a != null && b != null && a.getHost() != null && b.getHost() != null &&
            a.getHost().equalsIgnoreCase(b.getHost());
    }

    static String origin(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getScheme() == null || uri.getRawUserInfo() != null || uri.getHost() == null) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int port = uri.getPort();
        if (port == 0 || port > 65535 || port < -1) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        // URI.getHost includes the brackets for IPv6 literals.
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static URI parse(String value) {
        try {
            if (value == null || value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) return null;
            return new URI(value);
        } catch (URISyntaxException ex) { return null; }
    }
}
