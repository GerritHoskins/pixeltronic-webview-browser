package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class NavigationPolicyTest {
    @Test public void defaultsAllowOnlyHttpsAtTheInitialOrigin() {
        NavigationPolicy policy = NavigationPolicy.create("https://example.com/start", null, false);
        assertTrue(policy.evaluate("https://example.com/next?q=1#section").allowed);
        assertTrue(policy.evaluate("HTTPS://EXAMPLE.COM:443/next").allowed);
        assertEquals("ORIGIN_BLOCKED", policy.evaluate("https://example.com.evil.test/").reason);
        assertEquals("ORIGIN_BLOCKED", policy.evaluate("https://example.com:8443/").reason);
        assertEquals("HTTPS_REQUIRED", policy.evaluate("http://example.com/").reason);
    }

    @Test public void rejectsActiveLocalAndCustomSchemesAndCredentialUrls() {
        NavigationPolicy policy = NavigationPolicy.create("https://example.com", null, false);
        for (String url : List.of("file:///tmp/private", "content://provider/file", "javascript:alert(1)",
            "data:text/html,hello", "intent://example", "about:blank")) {
            assertEquals(url, "SCHEME_BLOCKED", policy.evaluate(url).reason);
        }
        assertEquals("CREDENTIALS_BLOCKED", policy.evaluate("https://user:secret@example.com/").reason);
        assertEquals("INVALID_URL", policy.evaluate("relative").reason);
    }

    @Test public void explicitlyConfiguredOriginsSupportRedirectsWithoutWildcards() {
        NavigationPolicy policy = NavigationPolicy.create("https://example.com", List.of("https://example.com/", "https://login.example.com"), false);
        assertTrue(policy.evaluate("https://login.example.com/login").allowed);
        assertFalse(policy.evaluate("https://other.example.com").allowed);
        assertFalse(policy.evaluate("https://login.example.com.evil.test").allowed);
    }

    @Test public void httpRequiresBothOptInAndAnAllowedOrigin() {
        NavigationPolicy policy = NavigationPolicy.create("https://example.com", List.of("https://example.com", "http://localhost:8080"), true);
        assertTrue(policy.evaluate("http://localhost:8080/form").allowed);
        assertFalse(policy.evaluate("http://localhost:8081/form").allowed);
        assertFalse(policy.evaluate("http://example.com/form").allowed);
    }

    @Test public void invalidOriginConfigurationIsRejected() {
        for (String origin : List.of("*", "https://*.example.com", "https://example.com/path", "https://example.com?q=1",
            "https://example.com#fragment", "https://user@example.com", "file:///tmp", "https://example.com:0")) {
            assertThrows(origin, IllegalArgumentException.class, () -> NavigationPolicy.create("https://example.com", List.of(origin), false));
        }
        assertThrows(IllegalArgumentException.class, () -> NavigationPolicy.create("https://example.com", List.of(), false));
    }

    @Test public void originPortsAndIpv6HaveDeterministicMatching() {
        NavigationPolicy policy = NavigationPolicy.create("https://[::1]:443", null, false);
        assertTrue(policy.evaluate("https://[::1]/next").allowed);
        assertFalse(policy.evaluate("https://[::1]:8443").allowed);
    }
}
