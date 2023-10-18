package net.bitburst.plugins.inappbrowser;

import static androidx.browser.customtabs.CustomTabsCallback.NAVIGATION_FAILED;
import static androidx.browser.customtabs.CustomTabsCallback.NAVIGATION_FINISHED;
import static androidx.browser.customtabs.CustomTabsCallback.NAVIGATION_STARTED;
import static androidx.browser.customtabs.CustomTabsCallback.TAB_HIDDEN;
import static androidx.browser.customtabs.CustomTabsCallback.TAB_SHOWN;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class InAppBrowserCustomTabs extends Plugin {

    public static final String CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome";
    private static final Map<Integer, String> NAVIGATION_EVENT_NAMES = new HashMap<>();

    static {
        NAVIGATION_EVENT_NAMES.put(NAVIGATION_STARTED, "browserPageLoadStarted");
        NAVIGATION_EVENT_NAMES.put(NAVIGATION_FINISHED, "browserPageLoaded");
        NAVIGATION_EVENT_NAMES.put(NAVIGATION_FAILED, "browserPageNavigationFailed");
        NAVIGATION_EVENT_NAMES.put(TAB_SHOWN, "browserVisible");
        NAVIGATION_EVENT_NAMES.put(TAB_HIDDEN, "browserHidden");
    }

    private final InAppBrowserPlugin mPlugin;
    private volatile CustomTabsSession currentSession;

    private CustomTabsClient customTabsClient;

    private final CustomTabsServiceConnection connection = new CustomTabsServiceConnection() {
        @Override
        public void onCustomTabsServiceConnected(@NonNull ComponentName name, CustomTabsClient client) {
            customTabsClient = client;
            client.warmup(0);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {}
    };

    public InAppBrowserCustomTabs(InAppBrowserPlugin plugin) {
        mPlugin = plugin;
    }

    public InAppBrowserOptions options() {
        return mPlugin.options;
    }

    public void openBrowser(final Context context, Uri url) {
        String packageName = InAppBrowserHelper.getPackageNameToUse(context);
        if (packageName == null) {
            mPlugin.getActivity().startActivity(new Intent(Intent.ACTION_VIEW, url));
            return;
        }

        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder(getSession());
        JSObject colors = options().getColorScheme();
        if (colors != null) {
            builder.setDefaultColorSchemeParams(
                new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(Color.parseColor(colors.getString("toolBarColor")))
                    .setNavigationBarColor(Color.parseColor(colors.getString("navigationBarColor")))
                    .setNavigationBarDividerColor(Color.parseColor(colors.getString("navigationBarDividerColor")))
                    .setSecondaryToolbarColor(Color.parseColor(colors.getString("secondaryToolbarColor")))
                    .build()
            );
        }
        builder.setShareState(CustomTabsIntent.SHARE_STATE_OFF).setShowTitle(true).setUrlBarHidingEnabled(true);

        CustomTabsIntent tabsIntent = builder.build();
        tabsIntent.intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse(Intent.URI_ANDROID_APP_SCHEME + "//" + context.getPackageName()));
        JSObject headers = options().getHeaders();
        if (headers != null) {
            tabsIntent.intent.putExtra(android.provider.Browser.EXTRA_HEADERS, getHeaders(headers));
        }
        tabsIntent.launchUrl(context, url);
    }

    private CustomTabsSession getSession() {
        if (currentSession == null) {
            currentSession =
                customTabsClient.newSession(
                    new CustomTabsCallback() {
                        @Override
                        public void onNavigationEvent(int navigationEvent, Bundle extras) {
                            super.onNavigationEvent(navigationEvent, extras);
                            String eventName = NAVIGATION_EVENT_NAMES.get(navigationEvent);
                            if (eventName != null) {
                                notifyListeners(eventName, new JSObject().put("visible", navigationEvent == TAB_SHOWN));
                            }
                        }
                    }
                );
        }
        return currentSession;
    }

    public Bundle getHeaders(JSObject headersProvided) {
        Bundle headers = new Bundle();
        if (headersProvided != null) {
            Iterator<String> keys = headersProvided.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.putString(key, headersProvided.getString(key));
            }
        }
        return headers;
    }

    public void bindService() {
        if (mPlugin != null) {
            CustomTabsClient.bindCustomTabsService(mPlugin.getContext(), CUSTOM_TAB_PACKAGE_NAME, connection);
        }
    }

    public void unbindService() {
        if (mPlugin != null) {
            mPlugin.getContext().unbindService(connection);
        }
    }
}
