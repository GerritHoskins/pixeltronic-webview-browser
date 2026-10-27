import type { PluginListenerHandle } from '@capacitor/core';

export interface InAppBrowserInterface {
  /**
   * Open a URL inside the webview or show a hidden webview
   * Should only be called after createWebView has finished successfully
   * optional headers can be set here as well for example User-Agent
   */
  openWebView(options: OpenOptions): Promise<void>;
  /**
   * Close and destroy an open webview.
   */
  closeWebView(): Promise<void>;
  /**
   * Hides the current webview.
   */
  hideWebView(): Promise<void>;
  /**
   * Shows the current webview.
   */
  showWebView(): Promise<void>;
  /**
   * Handle back navigation inside the webview
   */
  navigateBack(): Promise<void>;
  /**
   * Handle forward navigation inside the webview
   */
  navigateForward(): Promise<void>;
  /**
   * Load a specified URL in an existing webview.
   */
  loadUrl(options: { url: string }): Promise<void>;
  /**
   * Refresh the current page in the webview.
   */
  reload(): Promise<void>;
  /**
   * Observe a document-navigation decision already applied by the native policy.
   * Listeners cannot pause, replay, allow or cancel the request.
   * Configure navigationPolicy on openWebView to control allowed destinations.
   */
  onNavigation(listenerFunc: ListenerFunc<NavigationEvent>): Promise<void>;
  /**
   * Event triggered by the webview when the target URL has finished loading
   */
  onPageLoaded(listenerFunc: ListenerFunc<PageLoadStatus>): Promise<void>;
  /**
   * Event triggered by the webview when URL loading has failed
   * Returns the HTTP request error code.
   */
  onPageLoadError(listenerFunc: ListenerFunc<ErrorCode>): Promise<void>;
  /**
   * Event triggered by the webview when the webview is resized
   */
  onUpdateDimensions(listenerFunc: ListenerFunc<void>): Promise<void>;
  /**
   *  Create a screenshot of the current view
   *  and set it as a background of the web view container element.
   *  This is needed if there are HTML/Javascript UI elements to be overlaid.
   */
  captureScreen(showScreenCapture: boolean): Promise<void>;
  /**
   * Update the dimensions of the webview
   */
  updateDimensions(options?: Dimensions): Promise<void>;
}

export interface NativeInterface {
  openWebView(options: OpenOptions): Promise<void>;
  closeWebView(): Promise<void>;
  showWebView(): Promise<void>;
  hideWebView(): Promise<void>;
  openSystemBrowser(options: { url: string }): Promise<void>;
  openBrowser(options: OpenOptions): Promise<void>;
  navigateBack(): Promise<void>;
  navigateForward(): Promise<void>;
  refresh(): Promise<void>;
  loadUrl(options?: { url: string }): Promise<void>;
  onNavigation(options?: { allow: boolean }): Promise<void>;
  updateDimensions(options?: Dimensions): Promise<void>;
  captureScreen(options?: Dimensions): Promise<ScreenShot>;
  setLayoutVisibility(options: { visible: boolean }): Promise<void>;
  setSnapshotMode(options: { enabled: boolean }): Promise<void>;
  addListener(
    eventName: EventListeners | 'browserClosed',
    listenerFunc: (...args: any[]) => void,
  ): Promise<PluginListenerHandle>;
}
export interface OpenOptions extends Dimensions {
  /**
   * The URL to open the webview to
   */
  url: string;
  /**
   * element id of DOM node to which the webview should be attached
   * web view only
   */
  element?: HTMLElement;
  /**
   * Headers for programmatic open/loadUrl requests to the initial origin.
   * User-Agent applies to the WebView session. Native redirects and subresources
   * are managed by the platform; this is not a guarantee of per-request headers.
   */
  headers?: Headers;
  /**
   * Set the color scheme for safari system browser and custom tabs
   */
  colorScheme?: ColorScheme;
  /** Native document-navigation policy. Defaults to HTTPS at the initial origin. */
  navigationPolicy?: NavigationPolicy;
}

/**
 * Document navigation rules, not network isolation. Subresources and renderer-managed
 * redirects can issue requests before native document callbacks. In particular,
 * Android 307 POST redirects may send their body before the final document is blocked.
 */
export interface NavigationPolicy {
  /** Exact HTTP(S) document origins. Defaults to the initial origin. No wildcards. Popups are blocked. */
  allowedOrigins?: string[];
  /** Opt in to HTTP document navigation. Does not enable mixed content or override host transport restrictions. */
  allowHttp?: boolean;
}

export type NavigationDecisionReason =
  | 'ALLOWED'
  | 'INVALID_URL'
  | 'SCHEME_BLOCKED'
  | 'CREDENTIALS_BLOCKED'
  | 'HTTPS_REQUIRED'
  | 'ORIGIN_BLOCKED'
  | 'POPUP_BLOCKED';
export interface ColorScheme {
  /**
   * Set the toolbar color
   * Android & iOS
   */
  toolBarColor?: string;
  /**
   * Set the navigation bar color
   * Android only
   */
  navigationBarColor?: string;
  /**
   * Set the navigation bar divider color
   * Android only
   */
  navigationBarDividerColor?: string;
  /**
   * Set the secondar toolbar color
   * Android only
   */
  secondaryToolbarColor?: string;
}
export interface Dimensions {
  width?: number;
  height?: number;
  x?: number;
  y?: number;
  ratio?: number;
  /** Visual viewport metadata supplied by the wrapper for CSS-to-native conversion. */
  viewportWidth?: number;
  viewportHeight?: number;
  offsetLeft?: number;
  offsetTop?: number;
}
export interface Headers {
  [key: string]: string;
}
export interface ListenerFunc<T> {
  (event: T): void;
}
export type NavigationEvent = {
  /**
   *  current url being loaded
   */
  url: string;
  /**
   *  current url target (new window, i.e. _blank / _self, etc)
   */
  newWindowRequest: boolean;
  /**
   *  current url is from same host
   */
  isSameHost: boolean;
  /**
   * Legacy decision callback retained only to report the unsupported operation.
   */
  /** Native decision already applied; listeners cannot stall, replay or override the request. */
  allowed: boolean;
  reason: NavigationDecisionReason;
  isMainFrame: boolean;
  /** @deprecated Configure navigationPolicy before opening. Calling this method rejects with UNIMPLEMENTED. */
  complete: (allow: boolean) => Promise<void>;
};
export type EventListeners =
  | 'pageLoaded'
  | 'updateDimensions'
  | 'updateScreenCapture'
  | 'navigationHandler'
  | 'pageLoadError';
export type ErrorCode = { errorCode: number };
export type PageLoadStatus = { isLoading: boolean };
export type ScreenShot = { src: string };
