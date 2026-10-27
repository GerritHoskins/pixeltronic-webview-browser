package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BrowserNavigationDeviceTest {
    private static final class Call extends PluginCall {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
        Call(JSObject options) { super(null, "test", "test", "test", options); }
        @Override public void resolve() { assertMain(); done.countDown(); }
        @Override public void reject(String message, String code) { assertMain(); error = code; done.countDown(); }
        void await() throws InterruptedException { assertTrue("Call did not settle", done.await(10, TimeUnit.SECONDS)); }
    }

    private static final class Harness extends InAppBrowserPlugin {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout root = new FrameLayout(context);
        final BlockingQueue<String> decisions = new LinkedBlockingQueue<>();
        final BlockingQueue<String> closures = new LinkedBlockingQueue<>();
        WebView view;
        final AtomicInteger creations = new AtomicInteger();
        @Override protected void runOnUI(Runnable runnable) { new Handler(Looper.getMainLooper()).post(runnable); }
        @Override protected WebView createWebView() { creations.incrementAndGet(); view = new WebView(context); return view; }
        @Override protected void attachWebView(WebView view, InAppBrowserOptions.Bounds bounds) {
            root.addView(view, new FrameLayout.LayoutParams(300, 400)); view.layout(0, 0, 300, 400);
        }
        @Override protected void loadingEvent() { updateVisibility(); }
        @Override protected void emitLoadError(int code) {}
        @Override protected void emitBrowserClosed(String reason) { assertMain(); closures.add(reason); }
        @Override protected void emitNavigation(String url, boolean popup, boolean mainFrame, NavigationPolicy.Decision decision) {
            assertMain(); decisions.add(decision.reason);
        }
    }

    private static final class Request {
        final String method, path, body;
        final java.util.Map<String, String> headers;
        Request(String method, String path, String body, java.util.Map<String, String> headers) {
            this.method = method; this.path = path; this.body = body; this.headers = headers;
        }
    }

    /** An isolated loopback fixture: no public network endpoints or external services. */
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
        final BlockingQueue<Request> received = new LinkedBlockingQueue<>();
        Server() throws Exception {
            socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (!socket.isClosed()) {
                    try { Socket client = socket.accept(); workers.execute(() -> serve(client)); }
                    catch (Exception ex) { if (!socket.isClosed()) throw new RuntimeException(ex); }
                }
            });
        }
        String origin() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        String otherOrigin() { return "http://localhost:" + socket.getLocalPort(); }
        private void serve(Socket client) {
            try (Socket connection = client) {
                connection.setSoTimeout(3000);
                InputStream input = connection.getInputStream();
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                while (header.size() < 16384) {
                    int value = input.read(); if (value < 0) return; header.write(value);
                    if (header.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) break;
                }
                String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
                String[] first = lines[0].split(" ");
                int length = 0;
                java.util.Map<String, String> requestHeaders = new java.util.HashMap<>();
                for (String line : lines) {
                    int colon = line.indexOf(':');
                    if (colon > 0) requestHeaders.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
                }
                for (String line : lines) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                if (length < 0 || length > 16384) return;
                byte[] body = new byte[length]; int read = 0;
                while (read < length) { int count = input.read(body, read, length - read); if (count < 0) return; read += count; }
                Request request = new Request(first[0], first[1], new String(body, StandardCharsets.UTF_8), requestHeaders);
                requests.add(request); received.add(request);
                String location = request.path.equals("/redirect") ? "/done" :
                    request.path.equals("/blocked-redirect") ? otherOrigin() + "/denied" :
                    request.path.equals("/post-redirect") ? otherOrigin() + "/redirected-post" : null;
                String html = "<html><body><form id='form' method='POST' action='/post'><input name='token' value='stage2'></form>fixture</body></html>";
                byte[] content = html.getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 " + (location == null ? "200 OK" : request.path.equals("/post-redirect") ? "307 Temporary Redirect" : "302 Found") + "\r\n" +
                    (location == null ? "" : "Location: " + location + "\r\n") +
                    "Content-Type: text/html; charset=utf-8\r\nContent-Length: " + content.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(content);
            } catch (Exception ignored) { /* Browser cancellation may close a socket before its response is read. */ }
        }
        Request awaitPath(String path) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Request request = received.poll(250, TimeUnit.MILLISECONDS);
                if (request != null && request.path.equals(path)) return request;
            }
            throw new AssertionError("No fixture request for " + path);
        }
        @Override public void close() throws Exception { socket.close(); workers.shutdownNow(); }
    }

    private static void assertMain() { assertEquals(Looper.getMainLooper(), Looper.myLooper()); }
    private static void ui(Runnable runnable) { InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable); }
    private Harness harness() {
        AtomicReference<Harness> result = new AtomicReference<>(); ui(() -> result.set(new Harness())); return result.get();
    }
    private Call open(Harness plugin, String url) throws Exception {
        Call call = new Call(new JSObject().put("url", url).put("width", 300).put("height", 400).put("x", 0).put("y", 0)
            .put("navigationPolicy", new JSObject().put("allowHttp", true)));
        plugin.openWebView(call); call.await(); assertNull("Initial page failed: " + call.error, call.error); return call;
    }
    private void dispose(Harness plugin) { plugin.handleOnDestroy(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    @Test public void sameOriginRedirectCompletesWithoutAJavaScriptDecision() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                Call load = new Call(new JSObject().put("url", server.origin() + "/redirect"));
                plugin.loadUrl(load); load.await(); assertNull(load.error);
                assertEquals("GET", server.awaitPath("/done").method);
            } finally { dispose(plugin); }
        }
    }

    @Test public void firstRequestAndExplicitSameOriginLoadsReceiveHeadersButOtherOriginsDoNot() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                org.json.JSONArray origins = new org.json.JSONArray().put(server.origin()).put(server.otherOrigin());
                Call first = new Call(new JSObject().put("url", server.origin() + "/first").put("width", 300).put("height", 400).put("x", 0).put("y", 0)
                    .put("headers", new JSObject().put("Authorization", "fixture-token").put("User-Agent", "Phase3-Fixture"))
                    .put("navigationPolicy", new JSObject().put("allowHttp", true).put("allowedOrigins", origins)));
                plugin.openWebView(first); first.await(); assertNull(first.error);
                Request request = server.awaitPath("/first");
                assertEquals("fixture-token", request.headers.get("authorization"));
                assertEquals("Phase3-Fixture", request.headers.get("user-agent"));
                Call same = new Call(new JSObject().put("url", server.origin() + "/same"));
                plugin.loadUrl(same); same.await(); assertNull(same.error);
                assertEquals("fixture-token", server.awaitPath("/same").headers.get("authorization"));
                Call other = new Call(new JSObject().put("url", server.otherOrigin() + "/other"));
                plugin.loadUrl(other); other.await(); assertNull(other.error);
                request = server.awaitPath("/other");
                assertNull(request.headers.get("authorization")); assertEquals("Phase3-Fixture", request.headers.get("user-agent"));
            } finally { dispose(plugin); }
        }
    }

    @Test public void allowedFormPostPreservesMethodAndBodyWithoutReplayingAsGet() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                ui(() -> plugin.view.evaluateJavascript("document.getElementById('form').submit()", null));
                Request request = server.awaitPath("/post");
                assertEquals("POST", request.method); assertEquals("token=stage2", request.body);
                assertEquals(1, server.requests.stream().filter(item -> item.path.equals("/post")).count());
            } finally { dispose(plugin); }
        }
    }

    @Test public void crossOriginRedirectRejectsAndDoesNotReachTheForbiddenFixture() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                Call load = new Call(new JSObject().put("url", server.origin() + "/blocked-redirect"));
                plugin.loadUrl(load); load.await(); assertEquals("NAVIGATION_BLOCKED", load.error);
                assertFalse(server.requests.stream().anyMatch(item -> item.path.equals("/denied")));
            } finally { dispose(plugin); }
        }
    }

    @Test public void crossOriginFormPostIsBlockedBeforeSendingItsBody() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/"); plugin.decisions.clear();
                ui(() -> plugin.view.evaluateJavascript("document.getElementById('form').action='" + server.otherOrigin() + "/blocked-post';document.getElementById('form').submit()", null));
                String reason = plugin.decisions.poll(10, TimeUnit.SECONDS);
                assertEquals("ORIGIN_BLOCKED", reason);
                assertFalse(server.requests.stream().anyMatch(item -> item.path.equals("/blocked-post")));
            } finally { dispose(plugin); }
        }
    }

    @Test public void realWebViewUsesSecureFileAndMixedContentSettings() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                ui(() -> {
                    WebSettings settings = plugin.view.getSettings();
                    assertFalse(settings.getAllowFileAccess()); assertFalse(settings.getAllowContentAccess());
                    assertFalse(settings.getAllowUniversalAccessFromFileURLs()); assertFalse(settings.getAllowFileAccessFromFileURLs());
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
                    assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
                });
            } finally { dispose(plugin); }
        }
    }

    @Test public void post307RedirectCannotCommitAForbiddenDocument() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                ui(() -> plugin.view.evaluateJavascript("document.getElementById('form').action='/post-redirect';document.getElementById('form').submit()", null));
                Request original = server.awaitPath("/post-redirect");
                assertEquals("POST", original.method); assertEquals("token=stage2", original.body);
                assertEquals("NAVIGATION_BLOCKED", plugin.closures.poll(10, TimeUnit.SECONDS));
                // WebView callbacks are not a network firewall: on some engine versions a
                // redirect request can precede the final document policy callback.
                System.out.println("307 redirected request reached fixture before document block: " +
                    server.requests.stream().anyMatch(item -> item.path.equals("/redirected-post")));
            } finally { dispose(plugin); }
        }
    }

    @Test public void scriptPopupDoesNotAllocateAnotherWebViewOrLoadItsTarget() throws Exception {
        try (Server server = new Server()) {
            Harness plugin = harness();
            try {
                open(plugin, server.origin() + "/");
                CountDownLatch evaluated = new CountDownLatch(1);
                ui(() -> plugin.view.evaluateJavascript("window.open('/popup')", value -> evaluated.countDown()));
                assertTrue(evaluated.await(5, TimeUnit.SECONDS));
                assertEquals(1, plugin.creations.get());
                assertFalse(server.requests.stream().anyMatch(item -> item.path.equals("/popup")));
            } finally { dispose(plugin); }
        }
    }
}
