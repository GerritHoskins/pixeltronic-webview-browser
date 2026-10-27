package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public class BrowserThreadingTest {
    private static final String URL = "https://example.com";

    private static final class RecordingCall extends PluginCall {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger settlements = new AtomicInteger();
        volatile String error;
        volatile JSObject result;
        volatile boolean onMain;

        RecordingCall(JSObject data) { super(null, "test", "test", "test", data); }
        @Override public void resolve() { record(null, null); }
        @Override public void resolve(JSObject data) { record(null, data); }
        @Override public void reject(String message, String code) { record(code, null); }
        private void record(String error, JSObject data) {
            this.error = error; result = data;
            onMain = Looper.myLooper() == Looper.getMainLooper();
            settlements.incrementAndGet(); done.countDown();
        }
        void awaitResult() throws InterruptedException {
            assertTrue("Bridge call did not settle", done.await(5, TimeUnit.SECONDS));
            assertTrue("Settlement must occur on main", onMain);
            assertEquals(1, settlements.get());
        }
    }

    private static final class CheckingWebView extends WebView {
        int draws;
        boolean disposed;
        CheckingWebView(Context context) { super(context); }
        @Override public void loadUrl(String url) { assertMain(); /* No network: completion is driven by the test. */ }
        @Override public void draw(Canvas canvas) { assertMain(); draws++; super.draw(canvas); }
        @Override public void stopLoading() { assertMain(); super.stopLoading(); }
        @Override public void destroy() { assertMain(); disposed = true; super.destroy(); }
        private static void assertMain() { assertEquals(Looper.getMainLooper(), Looper.myLooper()); }
    }

    private static final class Harness extends InAppBrowserPlugin {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout root = new FrameLayout(context);
        CheckingWebView view;
        @Override protected void runOnUI(Runnable action) { new Handler(Looper.getMainLooper()).post(action); }
        @Override protected WebView createWebView() { view = new CheckingWebView(context); return view; }
        @Override protected void attachWebView(WebView view, InAppBrowserOptions.Bounds bounds) {
            root.addView(view, new FrameLayout.LayoutParams(300, 400));
            view.layout(0, 0, 300, 400);
        }
        @Override protected void loadingEvent() {}
        @Override protected void emitLoadError(int code) {}
    }

    private Harness harness() {
        AtomicReference<Harness> plugin = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> plugin.set(new Harness()));
        return plugin.get();
    }

    private static RecordingCall openCall() {
        return new RecordingCall(new JSObject().put("url", URL).put("width", 300).put("height", 400).put("x", 0).put("y", 0));
    }

    private void finishOpen(Harness plugin) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> plugin.view.getWebViewClient().onPageFinished(plugin.view, URL));
    }

    @Test public void actualWebViewCaptureAndDisposalUseMainThread() throws Exception {
        Harness plugin = harness();
        try {
            RecordingCall open = openCall(); plugin.openWebView(open); finishOpen(plugin); open.awaitResult();
            RecordingCall capture = new RecordingCall(new JSObject());
            plugin.captureScreen(capture); capture.awaitResult();
            assertNull(capture.error); assertNotNull(capture.result.getString("src"));
            assertFalse(capture.result.getString("src").isEmpty());
            assertEquals(1, plugin.view.draws);
            RecordingCall close = new RecordingCall(new JSObject()); plugin.closeWebView(close); close.awaitResult();
            assertTrue(plugin.view.disposed);
            RecordingCall again = new RecordingCall(new JSObject()); plugin.closeWebView(again); again.awaitResult();
        } finally { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    }

    @Test public void closeCancelsOpeningAndLateNativeCompletionIsIgnored() throws Exception {
        Harness plugin = harness();
        try {
            RecordingCall open = openCall(); plugin.openWebView(open);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicReference<android.webkit.WebViewClient> oldClient = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> oldClient.set(plugin.view.getWebViewClient()));
            RecordingCall close = new RecordingCall(new JSObject()); plugin.closeWebView(close);
            open.awaitResult(); close.awaitResult(); assertEquals("CANCELLED", open.error);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> oldClient.get().onPageFinished(plugin.view, URL));
            assertEquals(1, open.settlements.get());
        } finally { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    }

    @Test public void commandBeforeOpenRejectsOnMainWithoutCrashing() throws Exception {
        Harness plugin = harness();
        try {
            RecordingCall call = new RecordingCall(new JSObject()); plugin.navigateBack(call); call.awaitResult();
            assertEquals("NOT_OPEN", call.error);
        } finally { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    }

    @Test public void malformedBridgeInputsDoNotAllocateAWebView() throws Exception {
        Harness plugin = harness();
        try {
            for (JSObject data : new JSObject[] {
                new JSObject().put("url", URL).put("width", true).put("height", 400).put("x", 0).put("y", 0),
                new JSObject().put("url", URL).put("width", 300).put("height", 400).put("x", 0).put("y", 0)
                    .put("headers", new JSObject().put("X-Test", "a\r\nb"))
            }) {
                RecordingCall call = new RecordingCall(data); plugin.openWebView(call); call.awaitResult();
                assertEquals("INVALID_ARGUMENT", call.error); assertNull(plugin.view);
            }
        } finally { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    }

    @Test public void closeCancelsAnInFlightScreenshotExactlyOnce() throws Exception {
        Harness plugin = harness();
        try {
            RecordingCall open = openCall(); plugin.openWebView(open); finishOpen(plugin); open.awaitResult();
            RecordingCall capture = new RecordingCall(new JSObject()), close = new RecordingCall(new JSObject());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                plugin.captureScreen(capture); plugin.closeWebView(close);
            });
            capture.awaitResult(); close.awaitResult();
            assertEquals("CANCELLED", capture.error); assertTrue(plugin.view.disposed);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, capture.settlements.get());
        } finally { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    }
}
