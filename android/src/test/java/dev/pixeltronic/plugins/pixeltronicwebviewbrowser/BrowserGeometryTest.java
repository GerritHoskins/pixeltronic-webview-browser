package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class BrowserGeometryTest {
    @Test public void visualViewportUsesUniformScaleAndClipsKeyboardHeight() {
        InAppBrowserOptions.Bounds bounds = new InAppBrowserOptions.Bounds(300.25, 400.5, 15.5, 30.25, 400.0, 250.0, 5.0, 20.0);
        BrowserGeometry.Frame frame = BrowserGeometry.project(bounds, 1200, 2400, 2);
        assertEquals(901, frame.width); assertEquals(1202, frame.height);
        assertEquals(31.5f, frame.x, 0.001f); assertEquals(30.75f, frame.y, 0.001f);
        assertEquals(1200, frame.clipWidth); assertEquals(750, frame.clipHeight);
    }

    @Test public void rotationReprojectsWithTheCurrentHostWidth() {
        InAppBrowserOptions.Bounds bounds = new InAppBrowserOptions.Bounds(200.0, 100.0, -10.0, 20.0, 800.0, 400.0, 0.0, 0.0);
        BrowserGeometry.Frame frame = BrowserGeometry.project(bounds, 1600, 800, 3);
        assertEquals(400, frame.width); assertEquals(-20f, frame.x, 0);
        assertEquals(800, frame.clipHeight);
    }

    @Test public void legacyBoundsUseDensityAndOversizedBoundsReject() {
        assertEquals(600, BrowserGeometry.project(new InAppBrowserOptions.Bounds(300.0, 400.0, 0.0, 0.0), 1000, 2000, 2).width);
        assertThrows(IllegalArgumentException.class, () -> BrowserGeometry.project(new InAppBrowserOptions.Bounds(Double.MAX_VALUE, 1.0, 0.0, 0.0), 1000, 2000, 2));
        assertThrows(IllegalArgumentException.class, () -> new InAppBrowserOptions.Bounds(1.0, 1.0, 0.0, 0.0, 0.0, null, 0.0, 0.0));
    }

    @Test public void snapshotsPreserveAspectRatioAndLimitAllocation() {
        assertArrayEquals(new int[] {1024, 2048}, BrowserGeometry.snapshotSize(4000, 8000));
        assertArrayEquals(new int[] {2048, 1024}, BrowserGeometry.snapshotSize(8000, 4000));
        assertArrayEquals(new int[] {300, 400}, BrowserGeometry.snapshotSize(300, 400));
        assertThrows(IllegalArgumentException.class, () -> BrowserGeometry.snapshotSize(0, 10));
    }

    @Test public void customHeadersOnlyAttachToTheInitialOriginAndExcludeUserAgent() {
        Map<String, String> headers = Map.of("Authorization", "fixture", "User-Agent", "fixture-agent");
        assertEquals(Map.of("Authorization", "fixture"), InAppBrowserOptions.requestHeaders(headers, "https://example.com/start", "https://EXAMPLE.com:443/next"));
        assertTrue(InAppBrowserOptions.requestHeaders(headers, "https://example.com", "https://login.example.com").isEmpty());
        assertTrue(InAppBrowserOptions.requestHeaders(headers, "https://example.com", "http://example.com").isEmpty());
        assertEquals(2, headers.size());
    }
}
