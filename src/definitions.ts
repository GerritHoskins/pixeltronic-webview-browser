import type { PluginListenerHandle } from '@capacitor/core';

export interface InAppBrowserInterface {
  /** Create one native WebView and resolve when its initial page finishes loading. */
  openWebView(options: OpenOptions): Promise<void>;
  /** Destroy the view and remove this session's listeners, observers and snapshot. */
  closeWebView(): Promise<void>;
  hideWebView(): Promise<void>;
  showWebView(): Promise<void>;
  navigateBack(): Promise<void>;
  navigateForward(): Promise<void>;
  loadUrl(options: { url: string }): Promise<void>;
  reload(): Promise<void>;
  /** Observe native policy decisions; callbacks cannot override or delay navigation. */
  onNavigation(
    listener: ListenerFunc<NavigationEvent>,
  ): Promise<PluginListenerHandle>;
  /** Reports loading starts and finishes, including failures. */
  onPageLoaded(
    listener: ListenerFunc<PageLoadStatus>,
  ): Promise<PluginListenerHandle>;
  /** Error codes are platform-specific, not uniformly HTTP status codes. */
  onPageLoadError(
    listener: ListenerFunc<ErrorCode>,
  ): Promise<PluginListenerHandle>;
  /** Reports applied layout changes in CSS pixels. */
  onUpdateDimensions(
    listener: ListenerFunc<Dimensions>,
  ): Promise<PluginListenerHandle>;
  /**
   * true: display a decoded screenshot beneath the hidden native view.
   * false: restore the live view without taking another screenshot.
   * Captures require a loaded, visible view and have a maximum edge of 2048 pixels.
   */
  captureScreen(showScreenCapture: boolean): Promise<void>;
  /** Supply complete CSS bounds, or omit the argument to remeasure the container. */
  updateDimensions(options?: Dimensions): Promise<void>;
}

export interface OpenOptions {
  url: string;
  /** Mounted HTML element whose bounding rectangle positions the native view. */
  element: HTMLElement;
  /**
   * Headers for programmatic open/loadUrl requests to the initial origin.
   * User-Agent applies to the WebView session. Native redirects and subresources
   * are managed by the platform; this is not a guarantee of per-request headers.
   */
  headers?: Headers;
  /** Defaults to HTTPS document navigation at the initial origin. */
  navigationPolicy?: NavigationPolicy;
}

/**
 * Document navigation rules, not network isolation. Subresources and renderer-managed
 * redirects can issue requests before native document callbacks. In particular,
 * Android 307 POST redirects may send their body before the final document is blocked.
 */
export interface NavigationPolicy {
  /** Exact HTTP(S) origins. Defaults to the initial origin. No wildcards. Popups are blocked. */
  allowedOrigins?: string[];
  /** Does not enable mixed content or override host transport restrictions. */
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

/** @deprecated No native toolbar styling is implemented. Retained as a type alias for migration only. */
export interface ColorScheme {
  toolBarColor?: string;
  navigationBarColor?: string;
  navigationBarDividerColor?: string;
  secondaryToolbarColor?: string;
}

/** Complete bounds in CSS pixels, relative to the host page's layout viewport. */
export interface Dimensions {
  width: number;
  height: number;
  x: number;
  y: number;
}

export interface Headers {
  [key: string]: string;
}
export type ListenerFunc<T> = (event: T) => void;
export type NavigationEvent = {
  url: string;
  newWindowRequest: boolean;
  isSameHost: boolean;
  allowed: boolean;
  reason: NavigationDecisionReason;
  isMainFrame: boolean;
  /** @deprecated Configure navigationPolicy before opening. Always rejects with UNIMPLEMENTED. */
  complete: (allow: boolean) => Promise<void>;
};
export type BrowserEventMap = {
  pageLoaded: PageLoadStatus;
  updateDimensions: Dimensions;
  navigationHandler: Omit<NavigationEvent, 'complete'>;
  pageLoadError: ErrorCode;
};
export type EventListeners = keyof BrowserEventMap;
export type ErrorCode = { errorCode: number };
export type PageLoadStatus = { isLoading: boolean };
/** @deprecated Raw image data is internal to the native bridge. captureScreen returns void. */
export type ScreenShot = { src: string };
