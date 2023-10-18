package net.bitburst.plugin.inappbrowser;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.webkit.WebSettings;
import com.getcapacitor.Bridge;
import com.getcapacitor.PluginCall;
import com.getcapacitor.plugin.WebView;
import net.bitburst.plugins.inappbrowser.InAppBrowserPlugin;
import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class InAppBrowserPluginTest {

    @Mock
    PluginCall mockPluginCall;

    @Mock
    Bridge mockBridge;

    @Mock
    WebView mockWebView;

    InAppBrowserPlugin inAppBrowserPlugin;

    @Before
    public void setup() {
        openMocks(this);
        inAppBrowserPlugin = spy(InAppBrowserPlugin.class);
        inAppBrowserPlugin.setBridge(mockBridge);
    }

    @Test
    public void openWebView_destroysExistingWebView() throws JSONException {
        WebView oldWebView = mockWebView;
        doReturn(oldWebView).when(inAppBrowserPlugin.webView);
        inAppBrowserPlugin.openWebView(mockPluginCall);
        verify(inAppBrowserPlugin).destroyWebView();
    }

    @Test
    public void openWebView_enableJavaScriptCanOpenWindowsAutomatically() throws JSONException {
        WebSettings mockWebSettings = mock(WebSettings.class);
        WebView mockWebView = mock(WebView.class);
        doReturn(inAppBrowserPlugin).when(mockWebView).getContext();
        // doReturn(mockWebSettings).when(mockWebView).getSettings();
        doReturn(mockWebSettings).when(mockWebView).getBridge().getWebView().getSettings();
        doReturn(mockWebView).when(inAppBrowserPlugin).getBridge().getWebView();

        PluginCall mockPluginCall = mock(PluginCall.class);
        inAppBrowserPlugin.openWebView(mockPluginCall);
        verify(mockWebSettings, times(2)).setJavaScriptCanOpenWindowsAutomatically(true);
    }

    @Test
    public void openWebView_setWebSettingsCorrectly() throws JSONException {
        WebSettings mockWebSettings = mock(WebSettings.class);
        WebView mockWebView = mock(WebView.class);
        doReturn(inAppBrowserPlugin).when(mockWebView).getContext();
        doReturn(mockWebSettings).when(mockWebView).getBridge().getWebView().getSettings();
        doReturn(mockWebView).when(inAppBrowserPlugin.webView);

        PluginCall mockPluginCall = mock(PluginCall.class);

        inAppBrowserPlugin.openWebView(mockPluginCall);

        verify(mockWebSettings).setJavaScriptCanOpenWindowsAutomatically(true);
        verify(mockWebSettings).setJavaScriptEnabled(true);
        verify(mockWebSettings).setDatabaseEnabled(true);
        verify(mockWebSettings).setDomStorageEnabled(true);
        verify(mockWebSettings).setAllowContentAccess(true);
        verify(mockWebSettings).setAllowFileAccess(true);
        verify(mockWebSettings).setAllowFileAccessFromFileURLs(true);
        verify(mockWebSettings).setAllowUniversalAccessFromFileURLs(true);
        verify(mockWebSettings).setLoadWithOverviewMode(true);
        verify(mockWebSettings).setUseWideViewPort(true);
        verify(mockWebSettings).setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        verify(mockWebSettings).setSupportMultipleWindows(true);
    }
}
