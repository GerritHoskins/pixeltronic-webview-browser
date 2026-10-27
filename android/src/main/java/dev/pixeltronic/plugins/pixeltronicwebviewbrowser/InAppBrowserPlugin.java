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
import java.net.URI;
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
    private final Map<CallResult, Runnable> captures = new HashMap<>();
    private ExecutorService encoder;
    private Handler handler;

    @PluginMethod
    public void openWebView(PluginCall call) {
        execute(call, result -> {
            String url = InAppBrowserOptions.url(call.getString("url"));
            InAppBrowserOptions.Bounds bounds = InAppBrowserOptions.Bounds.from(call);
            Map<String, String> requestHeaders = InAppBrowserOptions.headers(call);
            if (webView != null) { result.reject("BUSY", "Close the existing WebView before opening another."); return; }
            WebView view = createWebView();
            webView = view;
            headers = requestHeaders;
            hidden = false;
            opening = true;
            openingOwner = result;
            configureWebSettings(view);
            view.setWebViewClient(createWebViewClient());
            view.setWebChromeClient(createWebChromeClient());
            attachWebView(view, bounds);
            beginLoad(result);
            // Header behavior is unchanged in Stage 1; policy/parity is handled separately.
            view.loadUrl(url);
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

    @PluginMethod public void showWebView(PluginCall call) { withView(call, false, (view, result) -> { hidden = false; updateVisibility(); result.resolve(); }); }
    @PluginMethod public void hideWebView(PluginCall call) { withView(call, false, (view, result) -> { hidden = true; updateVisibility(); result.resolve(); }); }
    @PluginMethod public void navigateBack(PluginCall call) { withView(call, true, (view, result) -> { if (view.canGoBack()) view.goBack(); result.resolve(); }); }
    @PluginMethod public void navigateForward(PluginCall call) { withView(call, true, (view, result) -> { if (view.canGoForward()) view.goForward(); result.resolve(); }); }
    @PluginMethod public void refresh(PluginCall call) { withView(call, true, (view, result) -> { view.reload(); result.resolve(); }); }

    @PluginMethod
    public void loadUrl(PluginCall call) {
        withView(call, true, (view, result) -> {
            String url = InAppBrowserOptions.url(call.getString("url"));
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
            result.reject("UNIMPLEMENTED", "Android navigation decisions are not implemented.");
        });
    }

    @PluginMethod
    public void captureScreen(PluginCall call) {
        withView(call, true, (view, result) -> {
            if (!captures.isEmpty()) { result.reject("BUSY", "A screenshot is already in progress."); return; }
            if (view.getWidth() <= 0 || view.getHeight() <= 0) throw new IllegalArgumentException("The WebView has no drawable bounds.");
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                view.draw(new Canvas(bitmap));
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

    protected void attachWebView(WebView view, InAppBrowserOptions.Bounds bounds) {
        ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
        parent.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyBounds(view, bounds);
    }

    private void applyBounds(WebView view, InAppBrowserOptions.Bounds bounds) {
        float density = view.getResources().getDisplayMetrics().density;
        double scaledWidth = bounds.width * density, scaledHeight = bounds.height * density;
        if (!Double.isFinite(scaledWidth) || !Double.isFinite(scaledHeight) || scaledWidth > Integer.MAX_VALUE || scaledHeight > Integer.MAX_VALUE ||
            Math.abs(bounds.x * density) > Integer.MAX_VALUE || Math.abs(bounds.y * density) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Bounds exceed the native coordinate range.");
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = Math.max(1, (int) Math.round(scaledWidth));
        params.height = Math.max(1, (int) Math.round(scaledHeight));
        view.setLayoutParams(params);
        view.setX((float) (bounds.x * density));
        view.setY((float) (bounds.y * density));
    }

    private void beginLoad(CallResult result) {
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
        headers = new HashMap<>();
        hidden = false;
        loading = false;
        if (view != null) {
            view.stopLoading();
            view.setWebViewClient(null);
            view.setWebChromeClient(null);
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view.destroy();
        }
    }

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
        Map<String, String> requestHeaders = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("User-Agent")) view.getSettings().setUserAgentString(entry.getValue());
            else requestHeaders.put(entry.getKey(), entry.getValue());
        }
        view.loadUrl(url, requestHeaders);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportMultipleWindows(true);
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message message) {
                if (view != webView) return false;
                WebView popup = createWebView();
                popup.setWebViewClient(new WebViewClient() {
                    @Override public void onLoadResource(WebView child, String url) {
                        if (webView == view) {
                            if (hasListeners("navigationHandler")) navigationEvent(url, true);
                            else view.loadUrl(url);
                        }
                        child.stopLoading();
                        child.destroy();
                    }
                });
                ((WebView.WebViewTransport) message.obj).setWebView(popup);
                message.sendToTarget();
                return true;
            }
        };
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (view != webView) return;
                loading = true;
                loadingEvent();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != webView || (view.getUrl() != null && !url.equals(view.getUrl()))) return;
                loading = false;
                finishLoad(null, null);
                loadingEvent();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == webView && request.isForMainFrame()) pageFailed(error.getErrorCode());
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == webView && request.isForMainFrame()) pageFailed(response.getStatusCode());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (view != webView) return true;
                if (!hasListeners("navigationHandler")) return false;
                navigationEvent(url, false);
                return true;
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

    private void updateVisibility() { if (webView != null) webView.setVisibility(hidden || loading ? View.INVISIBLE : View.VISIBLE); }
    protected void loadingEvent() { updateVisibility(); notifyListeners("pageLoaded", new JSObject().put("isLoading", loading), true); }

    private void navigationEvent(String url, boolean newWindow) {
        if (webView == null) return;
        try {
            URI current = new URI(webView.getUrl());
            URI target = new URI(url);
            boolean sameHost = current.getHost() != null && current.getHost().equalsIgnoreCase(target.getHost());
            notifyListeners("navigationHandler", new JSObject().put("url", url).put("newWindowRequest", newWindow).put("isSameHost", sameHost));
        } catch (Exception ignored) {
            // Invalid/non-hierarchical targets have no comparable host.
        }
    }
}
