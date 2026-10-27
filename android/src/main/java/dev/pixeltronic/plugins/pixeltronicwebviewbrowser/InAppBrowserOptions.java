package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

/** Validation produces complete values before any native state is mutated. */
final class InAppBrowserOptions {
    static final class Bounds {
        final double width, height, x, y;
        final Double viewportWidth, viewportHeight;
        final double offsetLeft, offsetTop;

        Bounds(Double width, Double height, Double x, Double y) {
            this(width, height, x, y, null, null, 0.0, 0.0);
        }

        Bounds(Double width, Double height, Double x, Double y, Double viewportWidth, Double viewportHeight, Double offsetLeft, Double offsetTop) {
            if (!finite(width) || !finite(height) || !finite(x) || !finite(y) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Finite x/y and positive width/height are required.");
            }
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            if ((viewportWidth != null && (!finite(viewportWidth) || viewportWidth <= 0)) ||
                (viewportHeight != null && (!finite(viewportHeight) || viewportHeight <= 0)) || !finite(offsetLeft) || !finite(offsetTop)) {
                throw new IllegalArgumentException("Invalid viewport metrics.");
            }
            this.viewportWidth = viewportWidth; this.viewportHeight = viewportHeight;
            this.offsetLeft = offsetLeft; this.offsetTop = offsetTop;
        }

        private static boolean finite(Double value) { return value != null && Double.isFinite(value); }

        static Bounds from(PluginCall call) {
            return new Bounds(call.getDouble("width"), call.getDouble("height"), call.getDouble("x"), call.getDouble("y"),
                optional(call, "viewportWidth", null), optional(call, "viewportHeight", null),
                optional(call, "offsetLeft", 0.0), optional(call, "offsetTop", 0.0));
        }

        private static Double optional(PluginCall call, String key, Double fallback) {
            Double value = call.getDouble(key);
            if (value == null && call.getData() != null && call.getData().has(key)) throw new IllegalArgumentException("Invalid viewport metric: " + key);
            return value == null ? fallback : value;
        }
    }

    static String url(String value) {
        try {
            if (value == null || value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
                throw new URISyntaxException("", "Missing or invalid URL");
            }
            URI uri = new URI(value);
            if (!uri.isAbsolute() || (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) &&
                (uri.getHost() == null || uri.getHost().isEmpty()))) {
                throw new URISyntaxException(value, "An absolute URL is required");
            }
            return value;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("An absolute URL without whitespace is required.");
        }
    }

    static Map<String, String> headers(PluginCall call) {
        JSObject data = call.getData();
        JSObject headers = call.getObject("headers");
        if (data != null && data.has("headers") && headers == null) {
            throw new IllegalArgumentException("Headers must be an object.");
        }
        Map<String, String> result = new HashMap<>();
        if (headers == null) return result;
        Iterator<String> keys = headers.keys();
        Set<String> names = new HashSet<>();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = headers.opt(key);
            if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !(value instanceof String) ||
                !names.add(key.toLowerCase(Locale.ROOT)) || ((String) value).chars().anyMatch(ch -> (ch < 32 && ch != 9) || ch == 127)) {
                throw new IllegalArgumentException("Invalid header name or value.");
            }
            result.put(key, (String) value);
        }
        return result;
    }

    static Map<String, String> requestHeaders(Map<String, String> headers, String initialUrl, String url) {
        Map<String, String> result = new HashMap<>();
        String origin = NavigationPolicy.origin(initialUrl);
        if (origin == null || !origin.equals(NavigationPolicy.origin(url))) return result;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("User-Agent")) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
