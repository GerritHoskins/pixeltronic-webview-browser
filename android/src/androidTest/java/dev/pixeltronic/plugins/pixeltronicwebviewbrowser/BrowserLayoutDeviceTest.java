package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises production attachment and coordinates; this is not visual/pixel acceptance. */
@RunWith(AndroidJUnit4.class)
public class BrowserLayoutDeviceTest {
    private static final String URL = "https://example.com";
    private static final class Call extends PluginCall {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
        Call(JSObject options) { super(null, "test", "test", "test", options); }
        @Override public void resolve() { done.countDown(); }
        @Override public void reject(String message, String code) { error = code; done.countDown(); }
        void await() throws Exception { assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(error, error); }
    }
    private static final class Harness extends InAppBrowserPlugin {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout root = new FrameLayout(context);
        final WebView host = new WebView(context);
        WebView view;
        int changes;
        Harness() {
            root.addView(host, new FrameLayout.LayoutParams(1200, 2400));
            root.layout(0, 0, 1400, 2600); host.layout(30, 60, 1230, 2460);
        }
        @Override protected void runOnUI(Runnable action) { new Handler(Looper.getMainLooper()).post(action); }
        @Override protected WebView hostWebView() { return host; }
        @Override protected WebView createWebView() {
            view = new WebView(context) { @Override public void loadUrl(String url, Map<String, String> headers) {} };
            return view;
        }
        @Override protected void loadingEvent() { updateVisibility(); }
        @Override protected void emitDimensions(InAppBrowserOptions.Bounds bounds) { changes++; }
        @Override protected void emitNavigation(String url, boolean popup, boolean mainFrame, NavigationPolicy.Decision decision) {}
        @Override protected void emitLoadError(int code) {}
        @Override protected void emitBrowserClosed(String reason) {}
    }
    private static void ui(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }

    @Test public void productionOverlayTracksHostInsetsRotationAndViewportClipThenDetaches() throws Exception {
        AtomicReference<Harness> ref = new AtomicReference<>(); ui(() -> ref.set(new Harness()));
        Harness plugin = ref.get();
        try {
            Call open = new Call(new JSObject().put("url", URL).put("width", 300.25).put("height", 400.5)
                .put("x", 15.5).put("y", 30.25).put("viewportWidth", 400).put("viewportHeight", 250)
                .put("offsetLeft", 5).put("offsetTop", 20));
            plugin.openWebView(open); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            ui(() -> {
                plugin.view.getWebViewClient().onPageFinished(plugin.view, URL);
                ViewGroup overlay = (ViewGroup) plugin.view.getParent();
                assertEquals(2, plugin.root.getChildCount()); assertEquals(30f, overlay.getX(), 0); assertEquals(60f, overlay.getY(), 0);
                assertEquals(1200, overlay.getLayoutParams().width); assertEquals(750, overlay.getLayoutParams().height);
                assertEquals(901, plugin.view.getLayoutParams().width); assertEquals(1202, plugin.view.getLayoutParams().height);
                assertEquals(31.5f, plugin.view.getX(), 0.001f); assertEquals(30.75f, plugin.view.getY(), 0.001f);
                assertTrue(overlay.getClipChildren());
                plugin.host.layout(50, 80, 1650, 880);
                plugin.host.getViewTreeObserver().dispatchOnGlobalLayout();
                assertEquals(50f, overlay.getX(), 0); assertEquals(80f, overlay.getY(), 0);
                assertEquals(1600, overlay.getLayoutParams().width); assertEquals(800, overlay.getLayoutParams().height);
                assertEquals(1201, plugin.view.getLayoutParams().width);
                assertEquals(42f, plugin.view.getX(), 0.001f);
            });
            open.await();
            Call close = new Call(new JSObject()); plugin.closeWebView(close); close.await();
            ui(() -> {
                assertEquals(1, plugin.root.getChildCount()); int changes = plugin.changes;
                plugin.host.getViewTreeObserver().dispatchOnGlobalLayout(); assertEquals(changes, plugin.changes);
            });
        } finally {
            plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            ui(() -> { plugin.root.removeView(plugin.host); plugin.host.destroy(); });
        }
    }
}
