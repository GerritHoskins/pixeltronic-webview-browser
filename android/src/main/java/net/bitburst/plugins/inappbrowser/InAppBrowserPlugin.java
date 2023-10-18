package net.bitburst.plugins.inappbrowser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "InAppBrowserPlugin", permissions = { @Permission(strings = { Manifest.permission.INTERNET }, alias = "internet") })
public class InAppBrowserPlugin extends Plugin {

    public WebView webView;

    public InAppBrowserOptions options;
    public static final String LOG_TAG = "bitburst.inAppBrowser ";
    public static final String NO_WEBVIEW_ERROR = "No valid InAppBrowser instance found";
    public static final String MISSING_DIMENSIONS_ERROR = "Height or width is missing";
    public static final String INVALID_MISSING_URL_ERROR = "must provide a valid URL to open";
    private InAppBrowserCustomTabs customTabs = null;

    private boolean isLoading = false;

    @Override
    public void handleOnResume() {
        if (customTabs != null) {
            customTabs.bindService();
        }
    }

    @Override
    public void handleOnPause() {
        if (customTabs != null) {
            customTabs.unbindService();
        }
    }

    @Override
    public void load() {
        super.load();
        options = new InAppBrowserOptions(this.getContext());
        if (
            InAppBrowserHelper.isPackageInstalled(this.getContext(), "com.android.chrome") &&
            InAppBrowserHelper.isChromeEnabled(this.getContext())
        ) {
            customTabs = new InAppBrowserCustomTabs(this);
        }
    }

    @PluginMethod
    public void openWebView(final PluginCall call) {
        if (!InAppBrowserHelper.isPackageInstalled(this.getContext(), "com.google.android.webview")) {
            call.reject(LOG_TAG, "Android web view is not installed");
        }
        options.setSavedCall(call);
        getActivity().runOnUiThread(this::configureWebView);
    }

    @PluginMethod
    public void closeWebView(final PluginCall call) {
        getActivity()
            .runOnUiThread(
                () -> {
                    if (webView != null) {
                        ViewGroup rootGroup = ((ViewGroup) getBridge().getWebView().getParent());
                        int count = rootGroup.getChildCount();
                        if (count > 1) {
                            rootGroup.removeView(webView);
                            webView.destroyDrawingCache();
                            webView.destroy();
                            webView = null;
                        }
                    }
                    options.setHidden(false);
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void showWebView(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        getActivity()
            .runOnUiThread(
                () -> {
                    options.setHidden(false);
                    webView.setVisibility(View.VISIBLE);
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void hideWebView(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        getActivity()
            .runOnUiThread(
                () -> {
                    options.setHidden(true);
                    webView.setVisibility(View.INVISIBLE);
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void navigateBack(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        getActivity()
            .runOnUiThread(
                () -> {
                    webView.goBack();
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void navigateForward(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        getActivity()
            .runOnUiThread(
                () -> {
                    webView.goForward();
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void refresh(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        getActivity()
            .runOnUiThread(
                () -> {
                    webView.reload();
                    call.resolve();
                }
            );
    }

    @PluginMethod
    public void loadUrl(final PluginCall call) {
        String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR);
            return;
        }

        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }

        options.setSavedCall(call);
        getActivity().runOnUiThread(() -> loadUrlWithHeaders(urlString));
    }

    @PluginMethod
    public void onNavigation(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }

        getActivity()
            .runOnUiThread(
                () -> {
                    try {
                        if (options.getTargetUrl() != null) {
                            boolean allow = Boolean.TRUE.equals(call.getBoolean("allow", true));
                            if (allow) {
                                webView.loadUrl(options.getTargetUrl());
                            } else {
                                isLoading = false;
                                sendLoadingEvent();
                            }
                            options.setTargetUrl(null);
                        }
                        call.resolve();
                    } catch (Exception e) {
                        call.reject(LOG_TAG, "Failed to navigate.", e);
                    }
                }
            );
    }

    @PluginMethod
    public void updateDimensions(final PluginCall call) {
        if (webView != null) {
            getActivity()
                    .runOnUiThread(
                            () -> {
                                setWebViewOptions(call);

                                ViewGroup.LayoutParams params = webView.getLayoutParams();

                                params.width = options.getWidthInPixels();
                                params.height = options.getHeightInPixels();
                                webView.setX(options.getXInPixels());
                                webView.setY(options.getYInPixels());
                                webView.requestLayout();

                                if (options.isHidden()) {
                                    // notifyListeners("captureScreen", new JSObject());
                                }

                                call.resolve();
                            }
                    );
        }
    }

    @PluginMethod
    public void captureScreen(final PluginCall call) {
        if (webView == null) {
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
            return;
        }
        if(webView.getWidth() > 0 &&  webView.getHeight() > 0) {
            Executor executor = Executors.newSingleThreadExecutor();
            executor.execute(new InAppBrowserScreenTask(call, webView));
        } else {
            call.reject(LOG_TAG, MISSING_DIMENSIONS_ERROR);
        }
    }

    @PluginMethod
    public void openBrowser(final PluginCall call) {
        if (customTabs != null) {
            String urlString = call.getString("url");
            if (urlString == null || urlString.isEmpty()) {
                call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR);
                return;
            }
            Uri url;
            try {
                url = Uri.parse(urlString);
            } catch (Exception ex) {
                call.reject(ex.getLocalizedMessage());
                return;
            }

            if (options != null) {
                JSObject colorScheme = call.getObject("colorScheme");
                if (colorScheme != null) {
                    options.setColorScheme(colorScheme);
                }
                JSObject headers = call.getObject("headers");
                if (headers != null) {
                    options.setHeaders(headers);
                }
            }
            customTabs.openBrowser(this.getContext(), url);
            call.resolve();
        }
    }

    @PluginMethod
    public void openSystemBrowser(final PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR);
            return;
        }

        final PackageManager manager = this.getContext().getPackageManager();
        Intent launchIntent = new Intent(Intent.ACTION_VIEW);
        launchIntent.setData(Uri.parse(url));
        try {
            getActivity().startActivity(launchIntent);
        } catch (Exception ex) {
            launchIntent = manager.getLaunchIntentForPackage(url);
            try {
                getActivity().startActivity(launchIntent);
            } catch (Exception expgk) {
                call.reject(LOG_TAG, expgk.getLocalizedMessage());
            }
        }
        call.resolve();
    }

    private void loadUrlWithHeaders(String urlString) {
        if (webView == null) {
            return;
        }
        JSObject headers = options.getHeaders();
        if (headers == null) {
            webView.loadUrl(urlString);
            return;
        }

        Map<String, String> requestHeaders = new HashMap<>();
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = headers.getString(key);
            if (value == null) {
                continue;
            }

            if (TextUtils.equals(key, "User-Agent")) {
                webView.getSettings().setUserAgentString(value);
            } else {
                requestHeaders.put(key, value);
            }
        }
        webView.loadUrl(urlString, requestHeaders);
    }

    private void configureWebView() {
        PluginCall call = options.getSavedCall();
        try {
            options.setHidden(false);

            webView = new WebView(this.getContext());
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            webView.setDrawingCacheEnabled(true);

            configureWebSettings(call);

            bridge.getWebView().getSettings().setJavaScriptCanOpenWindowsAutomatically(true);

            webView.setWebChromeClient(createWebChromeClient());
            webView.setWebViewClient(createWebViewClient());
            webView.setVisibility(View.INVISIBLE);

            String urlString = call.getString("url");
            if (urlString == null || urlString.isEmpty()) {
                call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR);
            }

            setWebViewOptions(call);

            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );

            params.width = options.getWidthInPixels();
            params.height = options.getHeightInPixels();
            webView.setX(options.getXInPixels());
            webView.setY(options.getYInPixels());
            webView.requestLayout();

            ((ViewGroup) getBridge().getWebView().getParent()).addView(webView);

            webView.loadUrl(urlString);
        } catch (Exception e) {
            isLoading = false;
            sendLoadingEvent();
            call.reject(LOG_TAG, NO_WEBVIEW_ERROR);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(final PluginCall call) {
        WebSettings settings = webView.getSettings();
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

        JSObject headers = call.getObject("headers", null);
        if (headers != null) {
            options.setHeaders(headers);
        }
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView createdWebView = new WebView(getActivity());
                createdWebView.setWebViewClient(createNewWebViewClient());
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(createdWebView);
                resultMsg.sendToTarget();
                return true;
            }
        };
    }

    private WebViewClient createNewWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    handlePageLoadError(error.getErrorCode());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                handlePageLoadError(errorResponse.getStatusCode());
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (hasListeners("navigationHandler")) {
                    handleNavigationEvent(url, true);
                } else {
                    webView.loadUrl(url);
                }
                view.removeAllViews();
                view.destroy();
            }
        };
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    handlePageLoadError(error.getErrorCode());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                handlePageLoadError(errorResponse.getStatusCode());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isLoading = true;
                sendLoadingEvent();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isLoading = false;
                sendLoadingEvent();
                if (options.getSavedCall() != null) {
                    options.getSavedCall().resolve();
                    options.setSavedCall(null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (hasListeners("navigationHandler")) {
                    handleNavigationEvent(url, false);
                    return true;
                } else {
                    options.setTargetUrl(null);
                    return false;
                }
            }
        };
    }

    private void handlePageLoadError(int errorCode) {
        JSObject ret = new JSObject();
        ret.put("errorCode", errorCode);
        notifyListeners("pageLoadError", ret);
    }

    private void sendLoadingEvent() {
        webView.setVisibility(isLoading ? View.INVISIBLE : View.VISIBLE);
        JSObject result = new JSObject();
        result.put("isLoading", isLoading);
        notifyListeners("pageLoaded", result, true);
    }

    private void setWebViewOptions(final PluginCall call) {
        Integer height = call.getInt("height");
        if (height == null) {
            call.reject(LOG_TAG, "height is required");
            return;
        }
        options.setHeight(height);

        Integer width = call.getInt("width");
        if (width == null) {
            call.reject(LOG_TAG, "width is required");
            return;
        }
        options.setWidth(width);

        Integer x = call.getInt("x");
        if (x == null) {
            call.reject(LOG_TAG, "x coordinate is required");
            return;
        }
        options.setX(x);

        Integer y = call.getInt("y");
        if (y == null) {
            call.reject(LOG_TAG, "y coordinate is required");
            return;
        }
        options.setY(y);
        options.setRatio(2.5f);
    }

    private void handleNavigationEvent(String url, boolean newWindow) {
        if (webView != null) {
            options.setTargetUrl(url);
            try {
                URL currentUrl = new URL(webView.getUrl());
                URL targetUrl = new URL(url);
                boolean sameHost = currentUrl.getHost().equals(targetUrl.getHost());
                JSObject ret = new JSObject();
                ret.put("url", url);
                ret.put("newWindowRequest", newWindow);
                ret.put("isSameHost", sameHost);

                notifyListeners("navigationHandler", ret);
            } catch (MalformedURLException e) {
                Log.d(LOG_TAG, e.getLocalizedMessage());
            }
        }
    }
}
