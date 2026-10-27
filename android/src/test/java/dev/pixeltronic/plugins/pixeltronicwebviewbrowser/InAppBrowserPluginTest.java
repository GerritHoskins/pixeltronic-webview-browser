package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import com.getcapacitor.PluginCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class InAppBrowserPluginTest {
    private static final String URL = "https://example.com";

    private static final class Harness extends InAppBrowserPlugin {
        final Queue<Runnable> ui = new ArrayDeque<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final WebView view = mock(WebView.class);
        int creations;

        Harness() {
            when(view.getSettings()).thenReturn(mock(WebSettings.class));
            when(view.getUrl()).thenReturn(URL);
        }
        @Override protected void runOnUI(Runnable action) { ui.add(action); }
        @Override protected void postTimeout(Runnable action) { timeouts.add(action); }
        @Override protected void cancelTimeout(Runnable action) { timeouts.remove(action); }
        @Override protected WebView createWebView() { creations++; return view; }
        @Override protected void attachWebView(WebView view, InAppBrowserOptions.Bounds bounds) {}
        @Override protected void loadingEvent() {}
        @Override protected void emitLoadError(int code) {}
        void drain() { while (!ui.isEmpty()) ui.remove().run(); }
        WebViewClient client() {
            ArgumentCaptor<WebViewClient> captor = ArgumentCaptor.forClass(WebViewClient.class);
            verify(view, atLeastOnce()).setWebViewClient(captor.capture());
            return captor.getAllValues().stream().filter(value -> value != null).reduce((a, b) -> b).orElseThrow();
        }
        PluginCall open() { PluginCall call = validCall(); openWebView(call); drain(); return call; }
        void finish() { client().onPageFinished(view, URL); }
    }

    private static PluginCall validCall() {
        PluginCall call = mock(PluginCall.class);
        when(call.getString("url")).thenReturn(URL);
        when(call.getDouble("width")).thenReturn(300.0);
        when(call.getDouble("height")).thenReturn(400.0);
        when(call.getDouble("x")).thenReturn(-5.0);
        when(call.getDouble("y")).thenReturn(10.0);
        return call;
    }

    private static void rejected(PluginCall call, String code) {
        verify(call, times(1)).reject(anyString(), eq(code));
        verify(call, never()).resolve();
    }

    @Test public void commandsBeforeOpenRejectOnTheUiDispatcher() {
        Harness plugin = new Harness();
        List<Consumer<PluginCall>> commands = List.of(plugin::navigateBack, plugin::navigateForward, plugin::refresh,
            plugin::showWebView, plugin::hideWebView, plugin::loadUrl, plugin::updateDimensions,
            plugin::captureScreen, plugin::onNavigation);
        for (Consumer<PluginCall> command : commands) {
            PluginCall call = validCall();
            command.accept(call);
            verifyNoInteractions(call);
            plugin.drain();
            rejected(call, "NOT_OPEN");
        }
    }

    @Test public void validationDoesNotCreateNativeViews() {
        for (String url : List.of("", "relative", "https://", "https://example.com/a b")) {
            Harness plugin = new Harness();
            PluginCall call = validCall();
            when(call.getString("url")).thenReturn(url);
            plugin.openWebView(call); plugin.drain();
            rejected(call, "INVALID_ARGUMENT");
            assertEquals(0, plugin.creations);
        }
        for (Double value : new Double[] { null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY }) {
            Harness plugin = new Harness();
            PluginCall call = validCall();
            when(call.getDouble("width")).thenReturn(value);
            plugin.openWebView(call); plugin.drain();
            rejected(call, "INVALID_ARGUMENT");
            assertEquals(0, plugin.creations);
        }
    }

    @Test public void successfulOpenSettlesOnceDespiteDuplicatePageCallbacks() {
        Harness plugin = new Harness();
        PluginCall call = plugin.open();
        verify(call, never()).resolve();
        plugin.finish(); plugin.finish();
        verify(call, times(1)).resolve();
        assertTrue(plugin.timeouts.isEmpty());
    }

    @Test public void concurrentCallsCannotOverwriteThePendingOpen() {
        Harness plugin = new Harness();
        PluginCall first = plugin.open();
        PluginCall second = validCall(), load = validCall();
        plugin.openWebView(second); plugin.loadUrl(load); plugin.drain();
        rejected(second, "BUSY"); rejected(load, "BUSY");
        assertEquals(1, plugin.creations);
        plugin.finish(); verify(first).resolve();
    }

    @Test public void invalidConcurrentOpenDoesNotDisposeTheFirstOpen() {
        Harness plugin = new Harness();
        PluginCall first = plugin.open();
        PluginCall invalid = validCall(); when(invalid.getString("url")).thenReturn("bad");
        plugin.openWebView(invalid); plugin.drain();
        rejected(invalid, "INVALID_ARGUMENT");
        verify(plugin.view, never()).destroy();
        plugin.finish(); verify(first).resolve();
    }

    @Test public void closeDuringOpenCancelsAndIgnoresLateCompletion() {
        Harness plugin = new Harness();
        PluginCall opening = plugin.open();
        WebViewClient stale = plugin.client();
        PluginCall close = mock(PluginCall.class), again = mock(PluginCall.class);
        plugin.closeWebView(close); plugin.closeWebView(again); plugin.drain();
        rejected(opening, "CANCELLED");
        verify(close).resolve(); verify(again).resolve();
        verify(plugin.view, times(1)).destroy();
        stale.onPageFinished(plugin.view, URL);
        verify(opening, never()).resolve();
        assertTrue(plugin.timeouts.isEmpty());
    }

    @Test public void mainFrameFailureRejectsButSubresourceFailureDoesNot() {
        Harness plugin = new Harness();
        PluginCall opening = plugin.open();
        WebResourceRequest request = mock(WebResourceRequest.class);
        WebResourceError error = mock(WebResourceError.class);
        plugin.client().onReceivedError(plugin.view, request, error);
        verify(opening, never()).reject(anyString(), anyString());
        when(request.isForMainFrame()).thenReturn(true);
        WebViewClient client = plugin.client();
        client.onReceivedError(plugin.view, request, error);
        client.onPageFinished(plugin.view, URL);
        rejected(opening, "LOAD_FAILED");
    }

    @Test public void httpMainFrameFailureRejectsThePendingLoad() {
        Harness plugin = new Harness(); PluginCall call = plugin.open();
        WebResourceRequest request = mock(WebResourceRequest.class);
        when(request.isForMainFrame()).thenReturn(true);
        WebResourceResponse response = mock(WebResourceResponse.class);
        when(response.getStatusCode()).thenReturn(500);
        plugin.client().onReceivedHttpError(plugin.view, request, response);
        rejected(call, "LOAD_FAILED");
    }

    @Test public void timeoutAndTeardownCancelPendingWorkOnlyOnce() {
        Harness plugin = new Harness(); PluginCall call = plugin.open();
        plugin.timeouts.get(0).run();
        rejected(call, "LOAD_TIMEOUT");
        plugin.handleOnDestroy(); plugin.drain();
        verify(call, times(1)).reject(anyString(), anyString());
        PluginCall after = validCall(); plugin.openWebView(after); plugin.drain();
        rejected(after, "DESTROYED");
        PluginCall close = mock(PluginCall.class); plugin.closeWebView(close); plugin.drain(); verify(close).resolve();
    }

    @Test public void destructionDuringLoadRejectsAndDisposesTheView() {
        Harness plugin = new Harness(); PluginCall call = plugin.open();
        plugin.handleOnDestroy(); plugin.drain();
        rejected(call, "CANCELLED"); verify(plugin.view).stopLoading(); verify(plugin.view).destroy();
    }

    @Test public void synchronousNativeFailureRejectsAndRollsBack() {
        Harness plugin = new Harness();
        doThrow(new IllegalStateException()).when(plugin.view).loadUrl(URL);
        PluginCall call = plugin.open();
        rejected(call, "NATIVE_ERROR"); verify(plugin.view).destroy();
        assertTrue(plugin.timeouts.isEmpty());
    }

    @Test public void partialResizeDoesNotMutateExistingView() {
        Harness plugin = new Harness(); plugin.open(); plugin.finish();
        PluginCall resize = validCall(); when(resize.getDouble("height")).thenReturn(null);
        plugin.updateDimensions(resize); plugin.drain();
        rejected(resize, "INVALID_ARGUMENT"); verify(plugin.view, never()).setLayoutParams(any());
        verify(plugin.view, never()).destroy();
    }

    @Test public void unimplementedNavigationDecisionRejectsInsteadOfHanging() {
        Harness plugin = new Harness(); plugin.open(); plugin.finish();
        PluginCall decision = validCall(); when(decision.getBoolean("allow")).thenReturn(true);
        plugin.onNavigation(decision); plugin.drain(); rejected(decision, "UNIMPLEMENTED");
    }
}
