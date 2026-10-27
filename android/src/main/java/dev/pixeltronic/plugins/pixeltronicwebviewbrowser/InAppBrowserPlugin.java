package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@CapacitorPlugin(name = "InAppBrowserPlugin", permissions = { @Permission(strings = { Manifest.permission.INTERNET }, alias = "internet") })
public class InAppBrowserPlugin extends Plugin {
    private static final long TIMEOUT_MS = 30000;
    private WebView webView;
    private boolean destroyed;
    private boolean hidden;
    private boolean loading;
    private boolean opening;
    private CallResult openingOwner;
    private CallResult pendingLoad;
    private Runnable loadTimeout;
    private Map<String, String> headers = new HashMap<>();
    private NavigationPolicy navigationPolicy;
    private String initialUrl;
    private final Map<CallResult, Runnable> captures = new HashMap<>();
    private ExecutorService encoder;
    private Handler handler;
    private FrameLayout overlay;
    private WebView layoutHost;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private InAppBrowserOptions.Bounds latestBounds;
    private boolean layoutVisible = true;
    private boolean snapshotMode;
    private boolean geometryReady = true;

    @PluginMethod
    public void openWebView(PluginCall call) {
        execute(call, result -> {
            String url = InAppBrowserOptions.url(call.getString("url"));
            InAppBrowserOptions.Bounds bounds = InAppBrowserOptions.Bounds.from(call);
            Map<String, String> requestHeaders = InAppBrowserOptions.headers(call);
            NavigationPolicy policy = NavigationPolicy.from(call, url);
            NavigationPolicy.Decision decision = policy.evaluate(url);
            if (!decision.allowed) { result.reject("POLICY_BLOCKED", decision.reason); return; }
            if (webView != null) { result.reject("BUSY", "Close the existing WebView before opening another."); return; }
            WebView view = createWebView();
            webView = view;
            headers = requestHeaders;
            navigationPolicy = policy;
            initialUrl = url;
            hidden = false;
            layoutVisible = true;
            snapshotMode = false;
            geometryReady = true;
            opening = true;
            openingOwner = result;
            configureWebSettings(view);
            view.setWebViewClient(createWebViewClient(policy));
            view.setWebChromeClient(createWebChromeClient());
            attachWebView(view, bounds);
            beginLoad(result);
            emitNavigation(url, false, true, decision);
            loadUrlWithHeaders(view, url);
        });
    }

    @PluginMethod
    public void closeWebView(PluginCall call) {
        // Close remains safe after native teardown as well as after a previous close.
        runOnUI(() -> {
            CallResult result = new CallResult(call);
            try {
                closeInternal("CANCELLED", "The WebView was closed.");
                result.resolve();
            } catch (RuntimeException ex) {
                result.reject("NATIVE_ERROR", "The WebView could not be disposed.");
            }
        });
    }

    @PluginMethod public void showWebView(PluginCall call) { withView(call, false, (view, result) -> { hidden = false; snapshotMode = false; updateVisibility(); result.resolve(); }); }
    @PluginMethod public void hideWebView(PluginCall call) { withView(call, false, (view, result) -> { hidden = true; snapshotMode = false; updateVisibility(); result.resolve(); }); }

    @PluginMethod public void setLayoutVisibility(PluginCall call) {
        withView(call, false, (view, result) -> {
            Boolean visible = call.getBoolean("visible");
            if (visible == null) throw new IllegalArgumentException("A boolean visible value is required.");
            layoutVisible = visible; updateVisibility(); result.resolve();
        });
    }

    @PluginMethod public void setSnapshotMode(PluginCall call) {
        withView(call, false, (view, result) -> {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) throw new IllegalArgumentException("A boolean enabled value is required.");
            snapshotMode = enabled; updateVisibility(); result.resolve();
        });
    }
    @PluginMethod public void navigateBack(PluginCall call) { withView(call, true, (view, result) -> { if (view.canGoBack()) view.goBack(); result.resolve(); }); }
    @PluginMethod public void navigateForward(PluginCall call) { withView(call, true, (view, result) -> { if (view.canGoForward()) view.goForward(); result.resolve(); }); }
    @PluginMethod public void refresh(PluginCall call) { withView(call, true, (view, result) -> { view.reload(); result.resolve(); }); }

    @PluginMethod
    public void loadUrl(PluginCall call) {
        withView(call, true, (view, result) -> {
            String url = InAppBrowserOptions.url(call.getString("url"));
            NavigationPolicy.Decision decision = navigationPolicy.evaluate(url);
            emitNavigation(url, false, true, decision);
            if (!decision.allowed) { result.reject("POLICY_BLOCKED", decision.reason); return; }
            beginLoad(result);
            loadUrlWithHeaders(view, url);
        });
    }

    @PluginMethod
    public void updateDimensions(PluginCall call) {
        withView(call, false, (view, result) -> {
            InAppBrowserOptions.Bounds bounds = InAppBrowserOptions.Bounds.from(call);
            applyBounds(view, bounds);
            result.resolve();
        });
    }

    @PluginMethod
    public void onNavigation(PluginCall call) {
        withView(call, false, (view, result) -> {
            if (call.getBoolean("allow") == null) throw new IllegalArgumentException("A boolean allow value is required.");
            result.reject("UNIMPLEMENTED", "Navigation decisions are applied natively. Configure navigationPolicy before opening the WebView.");
        });
    }

    @PluginMethod
    public void captureScreen(PluginCall call) {
        withView(call, true, (view, result) -> {
            if (!captures.isEmpty()) { result.reject("BUSY", "A screenshot is already in progress."); return; }
            if (loading) { result.reject("BUSY", "The page is loading."); return; }
            if (hidden || snapshotMode || !layoutVisible || !geometryReady) { result.reject("NOT_VISIBLE", "The WebView is hidden."); return; }
            if (view.getWidth() <= 0 || view.getHeight() <= 0) throw new IllegalArgumentException("The WebView has no drawable bounds.");
            int[] size = BrowserGeometry.snapshotSize(view.getWidth(), view.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(size[0] / (float) view.getWidth(), size[1] / (float) view.getHeight());
                view.draw(canvas);
            } catch (RuntimeException ex) {
                bitmap.recycle();
                throw ex;
            }
            Runnable timeout = () -> finishCapture(result, null, "Screenshot encoding timed out.");
            captures.put(result, timeout);
            postTimeout(timeout);
            if (encoder == null) encoder = Executors.newSingleThreadExecutor();
            try {
                encoder.execute(new InAppBrowserScreenTask(bitmap,
                    bytes -> runOnUI(() -> finishCapture(result, bytes, null)),
                    error -> runOnUI(() -> finishCapture(result, null, "Screenshot encoding failed."))));
            } catch (RuntimeException ex) {
                bitmap.recycle();
                finishCapture(result, null, "Screenshot encoding could not start.");
            }
        });
    }

    private void finishCapture(CallResult result, byte[] bytes, String error) {
        Runnable timeout = captures.remove(result);
        if (timeout == null) return; // Close, teardown, or timeout already settled it.
        cancelTimeout(timeout);
        if (error != null) result.reject("SNAPSHOT_FAILED", error);
        else result.resolve(new JSObject().put("src", Base64.encodeToString(bytes, Base64.NO_WRAP)));
    }

    private interface ViewAction { void run(WebView view, CallResult result); }

    private void withView(PluginCall call, boolean requireIdle, ViewAction action) {
        execute(call, result -> {
            if (webView == null) { result.reject("NOT_OPEN", "No WebView is open."); return; }
            if (requireIdle && (pendingLoad != null || opening || !captures.isEmpty())) { result.reject("BUSY", "A load or screenshot is already in progress."); return; }
            action.run(webView, result);
        });
    }

    private void execute(PluginCall call, Consumer<CallResult> action) {
        CallResult result = new CallResult(call);
        runOnUI(() -> {
            if (destroyed) { result.reject("DESTROYED", "The plugin has been destroyed."); return; }
            try {
                action.accept(result);
            } catch (IllegalArgumentException ex) {
                result.reject("INVALID_ARGUMENT", ex.getMessage());
                if (openingOwner == result || pendingLoad == result) closeInternal("INVALID_ARGUMENT", ex.getMessage());
            } catch (RuntimeException ex) {
                result.reject("NATIVE_ERROR", "The native operation failed.");
                if (openingOwner == result || pendingLoad == result) closeInternal("NATIVE_ERROR", "The native operation failed.");
            }
        });
    }

    protected void runOnUI(Runnable runnable) { getBridge().executeOnMainThread(runnable); }
    protected void postTimeout(Runnable runnable) { if (handler == null) handler = new Handler(Looper.getMainLooper()); handler.postDelayed(runnable, TIMEOUT_MS); }
    protected void cancelTimeout(Runnable runnable) { if (handler != null && runnable != null) handler.removeCallbacks(runnable); }
    protected WebView createWebView() { return new WebView(getContext()); }
    protected WebView hostWebView() { return getBridge().getWebView(); }

    protected void attachWebView(WebView view, InAppBrowserOptions.Bounds bounds) {
        layoutHost = hostWebView();
        ViewGroup parent = (ViewGroup) layoutHost.getParent();
        overlay = new FrameLayout(view.getContext());
        overlay.setClipChildren(true); overlay.setClipToPadding(true);
        parent.addView(overlay, new ViewGroup.LayoutParams(1, 1));
        overlay.addView(view, new FrameLayout.LayoutParams(1, 1));
        applyBounds(view, bounds);
        layoutListener = () -> {
            if (webView != view || latestBounds == null) return;
            try { applyBounds(view, latestBounds); }
            catch (IllegalArgumentException ex) { geometryReady = false; updateVisibility(); }
        };
        layoutHost.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void applyBounds(WebView view, InAppBrowserOptions.Bounds bounds) {
        WebView host = layoutHost == null ? view : layoutHost;
        if (host.getWidth() <= 0 || host.getHeight() <= 0) { latestBounds = bounds; geometryReady = false; updateVisibility(); return; }
        BrowserGeometry.Frame frame = BrowserGeometry.project(bounds, host.getWidth(), host.getHeight(), view.getResources().getDisplayMetrics().density);
        latestBounds = bounds;
        boolean changed = false;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != frame.width || params.height != frame.height) {
            params.width = frame.width; params.height = frame.height;
            view.setLayoutParams(params); changed = true;
        }
        if (view.getX() != frame.x) { view.setX(frame.x); changed = true; }
        if (view.getY() != frame.y) { view.setY(frame.y); changed = true; }
        if (overlay != null) {
            ViewGroup.LayoutParams clip = overlay.getLayoutParams();
            if (clip.width != frame.clipWidth || clip.height != frame.clipHeight) {
                clip.width = frame.clipWidth; clip.height = frame.clipHeight;
                overlay.setLayoutParams(clip); changed = true;
            }
            if (overlay.getX() != host.getX()) { overlay.setX(host.getX()); changed = true; }
            if (overlay.getY() != host.getY()) { overlay.setY(host.getY()); changed = true; }
        }
        geometryReady = true;
        updateVisibility();
        if (changed) emitDimensions(bounds);
    }

    protected void emitDimensions(InAppBrowserOptions.Bounds bounds) {
        notifyListeners("updateDimensions", new JSObject().put("width", bounds.width).put("height", bounds.height).put("x", bounds.x).put("y", bounds.y));
    }

    private void beginLoad(CallResult result) {
        loading = true;
        snapshotMode = false;
        updateVisibility();
        pendingLoad = result;
        loadTimeout = () -> {
            if (pendingLoad != result) return;
            closeInternal("LOAD_TIMEOUT", "Page loading timed out.");
        };
        postTimeout(loadTimeout);
    }

    private void finishLoad(String code, String message) {
        CallResult result = pendingLoad;
        pendingLoad = null;
        cancelTimeout(loadTimeout);
        loadTimeout = null;
        opening = false;
        openingOwner = null;
        if (result != null) { if (code == null) result.resolve(); else result.reject(code, message); }
    }

    private void closeInternal(String code, String message) {
        WebView view = webView;
        webView = null; // Invalidate callbacks before stopping/destroying the view.
        finishLoad(code, message);
        for (Map.Entry<CallResult, Runnable> entry : captures.entrySet()) {
            cancelTimeout(entry.getValue());
            entry.getKey().reject(code, message);
        }
        captures.clear();
        if (layoutHost != null && layoutListener != null && layoutHost.getViewTreeObserver().isAlive()) {
            layoutHost.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null; layoutHost = null; latestBounds = null;
        if (overlay != null) {
            overlay.removeAllViews();
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            overlay = null;
        }
        headers = new HashMap<>();
        navigationPolicy = null;
        initialUrl = null;
        hidden = false;
        loading = false;
        snapshotMode = false; layoutVisible = true; geometryReady = true;
        if (view != null) {
            view.stopLoading();
            view.setWebViewClient(null);
            view.setWebChromeClient(null);
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view.destroy();
        }
        if (view != null && "NAVIGATION_BLOCKED".equals(code)) emitBrowserClosed(code);
    }

    protected void emitBrowserClosed(String reason) { notifyListeners("browserClosed", new JSObject().put("reason", reason)); }

    @Override
    protected void handleOnDestroy() {
        runOnUI(() -> {
            destroyed = true;
            closeInternal("CANCELLED", "The plugin was destroyed.");
            // Queued bitmap encodes still recycle their bitmaps; their callbacks are ignored.
            if (encoder != null) { encoder.shutdown(); encoder = null; }
        });
    }

    private void loadUrlWithHeaders(WebView view, String url) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("User-Agent")) view.getSettings().setUserAgentString(entry.getValue());
        }
        Map<String, String> requestHeaders = InAppBrowserOptions.requestHeaders(headers, initialUrl, url);
        view.loadUrl(url, requestHeaders);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message message) {
                if (view != webView) return false;
                // Android does not expose the target URL here. Never allocate an orphan popup.
                emitNavigation("", true, true, NavigationPolicy.popupBlocked());
                return false;
            }
        };
    }

    private WebViewClient createWebViewClient(NavigationPolicy policy) {
        return new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (view != webView) return;
                NavigationPolicy.Decision decision = policy.evaluate(url);
                if (!decision.allowed) { blockNavigation(view, url, true, decision, true); return; }
                loading = true;
                snapshotMode = false;
                loadingEvent();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != webView || (view.getUrl() != null && !url.equals(view.getUrl()))) return;
                NavigationPolicy.Decision decision = policy.evaluate(url);
                if (!decision.allowed) { blockNavigation(view, url, true, decision, true); return; }
                loading = false;
                finishLoad(null, null);
                loadingEvent();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == webView && request.isForMainFrame()) pageFailed(error.getErrorCode());
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (view != webView) return;
                NavigationPolicy.Decision decision = policy.evaluate(url);
                if (!decision.allowed) blockNavigation(view, url, true, decision, true);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == webView && request.isForMainFrame()) pageFailed(response.getStatusCode());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockNavigation(view, request.getUrl().toString(), request.isForMainFrame(), policy.evaluate(request.getUrl().toString()));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockNavigation(view, url, true, policy.evaluate(url));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // This callback runs on a worker and also covers main-frame POST requests.
                // Only the immutable policy is read here; all view/state access is posted to main.
                if (!request.isForMainFrame()) return null;
                String url = request.getUrl().toString();
                NavigationPolicy.Decision decision = policy.evaluate(url);
                if (decision.allowed) return null;
                runOnUI(() -> blockNavigation(view, url, true, decision));
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        };
    }

    private void pageFailed(int errorCode) {
        // Destroy failed loads so late callbacks cannot settle a subsequent request.
        if (pendingLoad != null) closeInternal("LOAD_FAILED", "The page failed to load.");
        else { loading = false; updateVisibility(); }
        emitLoadError(errorCode);
    }

    protected void emitLoadError(int errorCode) {
        notifyListeners("pageLoadError", new JSObject().put("errorCode", errorCode));
        notifyListeners("pageLoaded", new JSObject().put("isLoading", false), true);
    }

    protected final void updateVisibility() {
        int visibility = hidden || loading || snapshotMode || !layoutVisible || !geometryReady ? View.INVISIBLE : View.VISIBLE;
        if (webView != null) webView.setVisibility(visibility);
        if (overlay != null) overlay.setVisibility(visibility);
    }
    protected void loadingEvent() { updateVisibility(); notifyListeners("pageLoaded", new JSObject().put("isLoading", loading), true); }

    private boolean blockNavigation(WebView view, String url, boolean mainFrame, NavigationPolicy.Decision decision) {
        return blockNavigation(view, url, mainFrame, decision, false);
    }

    private boolean blockNavigation(WebView view, String url, boolean mainFrame, NavigationPolicy.Decision decision, boolean late) {
        if (view != webView) return true;
        emitNavigation(url, false, mainFrame, decision);
        if (!decision.allowed && mainFrame && (pendingLoad != null || late)) {
            closeInternal("NAVIGATION_BLOCKED", "Navigation was blocked by policy: " + decision.reason);
        }
        return !decision.allowed;
    }

    protected void emitNavigation(String url, boolean newWindow, boolean mainFrame, NavigationPolicy.Decision decision) {
        String source = webView == null || webView.getUrl() == null ? initialUrl : webView.getUrl();
        notifyListeners("navigationHandler", new JSObject().put("url", url).put("newWindowRequest", newWindow)
            .put("isSameHost", NavigationPolicy.sameHost(source, url)).put("isMainFrame", mainFrame)
            .put("allowed", decision.allowed).put("reason", decision.reason));
    }
}
