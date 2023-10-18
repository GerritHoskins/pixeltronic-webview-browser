import type { PluginListenerHandle } from '@capacitor/core';
import { Capacitor, registerPlugin } from '@capacitor/core';
import ResizeObserver from 'resize-observer-polyfill';

import type {
  NativeInterface,
  InAppBrowserInterface,
  Dimensions,
  OpenOptions,
  NavigationEvent,
  ErrorCode,
  BrowserVisibility,
  ScreenShot,
  EventListeners,
  PageLoadStatus,
} from './definitions';

const InAppBrowserPlugin =
  registerPlugin<NativeInterface>('InAppBrowserPlugin');

export class InAppBrowserClass implements InAppBrowserInterface {
  element: HTMLElement;
  dimensions: Dimensions;
  url: string;
  updateDimensionsEvent: PluginListenerHandle;
  pageLoadedEvent: PluginListenerHandle;
  browserPageLoadedEvent: PluginListenerHandle;
  browserPageLoadStartedEvent: PluginListenerHandle;
  browserVisibleEvent: PluginListenerHandle;
  navigationHandlerEvent: PluginListenerHandle;
  browserPageNavigationFailedEvent: PluginListenerHandle;
  pageLoadErrorEvent: PluginListenerHandle;
  resizeObserver: ResizeObserver;

  openWebView = async (options: OpenOptions): Promise<void> => {
    if (!(await this.platformCheck())) return;

    this.element = options.element;

    if (!this?.element) {
      return Promise.reject(
        'InAppBrowser Plugin: could not bind webview to DOM element. Element is either missing or wrong.',
      );
    }

    //remove previous screen captures
    if (this.element?.style) {
      this.element.style.backgroundSize = 'cover';
      this.element.style.backgroundRepeat = 'no-repeat';
      this.element.style.backgroundPosition = 'center';
    }

    const boundingBox = this.element.getBoundingClientRect() as DOMRect;
    this.resizeObserver = new ResizeObserver(entries => {
      for (const _entry of entries) {
        const boundingBox = options.element.getBoundingClientRect() as DOMRect;
        InAppBrowserPlugin.updateDimensions({
          width: Math.round(boundingBox.width),
          height: Math.round(boundingBox.height),
          x: Math.round(boundingBox.x),
          y: Math.round(boundingBox.y),
          ratio: window.devicePixelRatio,
        });
      }
    });
    this.resizeObserver.observe(this.element);

    return InAppBrowserPlugin.openWebView({
      url: options.url,
      headers: options.headers,
      width: Math.round(boundingBox.width),
      height: Math.round(boundingBox.height),
      x: Math.round(boundingBox.x),
      y: Math.round(boundingBox.y),
      ratio: window.devicePixelRatio,
    }).then(() => {
      this.url = options.url;
    });
  };

  openSystemBrowser = async (options: OpenOptions): Promise<void> => {
    return (
      (await this.platformCheck()) &&
      InAppBrowserPlugin.openSystemBrowser(options)
    );
  };

  openBrowser = async (options: OpenOptions): Promise<void> => {
    return (
      (await this.platformCheck()) && InAppBrowserPlugin.openBrowser(options)
    );
  };

  closeWebView = async (): Promise<void> => {
    if (await this.platformCheck()) {
      this.element = undefined;
      this.resizeObserver?.disconnect();
      await this.updateDimensionsEvent?.remove();
      await this.pageLoadedEvent?.remove();
      await this.browserPageLoadedEvent?.remove();
      await this.browserPageLoadStartedEvent?.remove();
      await this.browserVisibleEvent?.remove();
      await this.navigationHandlerEvent?.remove();
      await this.browserPageNavigationFailedEvent?.remove();
      await this.pageLoadErrorEvent?.remove();
      this.url = null;
      return InAppBrowserPlugin.closeWebView();
    }
  };

  showWebView = async (): Promise<void> => {
    return (await this.platformCheck()) && InAppBrowserPlugin.showWebView();
  };

  hideWebView = async (): Promise<void> => {
    return (await this.platformCheck()) && InAppBrowserPlugin.hideWebView();
  };

  loadUrl = async (options: { url: string }): Promise<void> => {
    if (await this.platformCheck()) this.url = options.url;
    await InAppBrowserPlugin.loadUrl(options);
  };

  reload = async (): Promise<void> => {
    return (await this.platformCheck()) && InAppBrowserPlugin.refresh();
  };

  async updateDimensions(dimensions: Dimensions): Promise<void> {
    return (
      (await this.platformCheck()) &&
      (await InAppBrowserPlugin.updateDimensions({
        ...dimensions,
        ratio: window.devicePixelRatio,
      }))
    );
  }

  navigateBack = async (): Promise<void> => {
    return (await this.platformCheck()) && InAppBrowserPlugin.navigateBack();
  };

  navigateForward = async (): Promise<void> => {
    return (await this.platformCheck()) && InAppBrowserPlugin.navigateForward();
  };

  onNavigation = async (
    listenerFunc: (event: NavigationEvent) => void,
  ): Promise<void> => {
    await this.addListener('navigationHandler', (event: any) => {
      listenerFunc({
        ...event,
        complete: (allow: boolean) =>
          InAppBrowserPlugin.onNavigation({ allow }),
      });
    });
    return Promise.resolve();
  };

  onPageLoadError = async (
    listenerFunc: (errorResponse: ErrorCode) => void,
  ): Promise<void> => this.addListener('pageLoadError', listenerFunc);

  onBrowserPageNavigationFailed = async (
    listenerFunc: () => void,
  ): Promise<void> =>
    this.addListener('browserPageNavigationFailed', listenerFunc);

  onPageLoaded = async (
    listenerFunc: (status: PageLoadStatus) => void,
  ): Promise<void> => this.addListener('pageLoaded', listenerFunc);

  onBrowserPageLoaded = async (listenerFunc: () => void): Promise<void> =>
    this.addListener('browserPageLoaded', listenerFunc);

  onBrowserPageLoadStarted = async (listenerFunc: () => void): Promise<void> =>
    this.addListener('browserPageLoadStarted', listenerFunc);

  onBrowserVisible = async (
    listenerFunc: (status: BrowserVisibility) => void,
  ): Promise<void> => this.addListener('browserVisible', listenerFunc);

  onUpdateDimensions = async (listenerFunc: () => void): Promise<void> =>
    this.addListener('updateDimensions', listenerFunc);

  captureScreen = async (showScreenCapture: boolean): Promise<void> => {
    if (!(await this.platformCheck())) return;

    const { width, height, x, y } =
      this.element.getBoundingClientRect() as DOMRect;
    this.dimensions = {
      width: Math.round(width),
      height: Math.round(height),
      x: Math.round(x),
      y: Math.round(y),
      ratio: window.devicePixelRatio,
    };

    const result: ScreenShot = await InAppBrowserPlugin.captureScreen(
      this.dimensions,
    );
    if (result?.src) {
      const webviewEl = this.element;
      if (webviewEl) {
        const buffer = await (
          await fetch('data:image/jpeg;base64,' + result.src)
        ).arrayBuffer();
        const blob = new Blob([buffer], { type: 'image/jpeg' });
        const blobUrl = URL.createObjectURL(blob);
        const img = new Image();

        img.onload = async () => {
          if (webviewEl.style) {
            webviewEl.style.backgroundSize = showScreenCapture
              ? 'contain'
              : 'unset';
            webviewEl.style.backgroundRepeat = showScreenCapture
              ? 'no-repeat'
              : 'unset';
            webviewEl.style.backgroundPosition = showScreenCapture
              ? 'center center'
              : 'unset';
            webviewEl.style.backgroundImage = showScreenCapture
              ? `url(${blobUrl})`
              : 'none';
            return await InAppBrowserPlugin[
              showScreenCapture ? 'hideWebView' : 'showWebView'
            ]();
          }
        };
        img.src = blobUrl;
      }
    }
  };

  private platformCheck = async (): Promise<boolean> => {
    try {
      const isNativePlatform = Capacitor.isNativePlatform();
      if (!isNativePlatform) {
        console.debug('No web implementation available');
        return false;
      }
      return true;
    } catch (err) {
      console.error(
        'An error occurred while trying to check the platform.',
        err,
      );
      return false;
    }
  };

  private addListener = async (
    listenerEventType: string,
    listenerFunc: (...args: any[]) => void,
  ): Promise<void> => {
    if (!(await this.platformCheck())) return Promise.resolve();
    await InAppBrowserPlugin.addListener(
      listenerEventType as EventListeners,
      listenerFunc,
    );
    return Promise.resolve();
  };
}
