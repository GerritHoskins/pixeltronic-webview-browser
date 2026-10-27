package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Validation produces complete values before any native state is mutated. */
final class InAppBrowserOptions {
    static final class Bounds {
        final double width, height, x, y;

        Bounds(Double width, Double height, Double x, Double y) {
            if (!finite(width) || !finite(height) || !finite(x) || !finite(y) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Finite x/y and positive width/height are required.");
            }
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }

        private static boolean finite(Double value) { return value != null && Double.isFinite(value); }

        static Bounds from(PluginCall call) {
            return new Bounds(call.getDouble("width"), call.getDouble("height"), call.getDouble("x"), call.getDouble("y"));
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
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = headers.opt(key);
            if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !(value instanceof String) ||
                ((String) value).contains("\r") || ((String) value).contains("\n")) {
                throw new IllegalArgumentException("Invalid header name or value.");
            }
            result.put(key, (String) value);
        }
        return result;
    }
}
