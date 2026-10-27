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
  EventListeners,
  PageLoadStatus,
  NavigationPolicy,
} from './definitions';

const InAppBrowserPlugin =
  registerPlugin<NativeInterface>('InAppBrowserPlugin');
type State = 'closed' | 'opening' | 'open' | 'closing' | 'failed';
type Bounds = Required<Pick<Dimensions, 'width' | 'height' | 'x' | 'y'>>;

function failure(code: string, message: string): Error {
  return Object.assign(new Error(message), { code });
}

export class InAppBrowserClass implements InAppBrowserInterface {
  private state: State = 'closed';
  private generation = 0;
  private listenerGeneration = 0;
  private listeners = new Set<PluginListenerHandle>();
  private element?: HTMLElement;
  private resizeObserver?: ResizeObserver;
  private closing?: Promise<void>;
  private snapshotUrl?: string;
  private cancelSnapshot?: () => void;
  private capturing = false;
  private navigationPolicy?: Required<NavigationPolicy>;

  async openWebView(options: OpenOptions): Promise<void> {
    this.requireNative();
    if (this.state !== 'closed')
      throw failure(
        'BUSY',
        'Close the existing WebView before opening another.',
      );
    if (
      !options ||
      !options.element ||
      typeof options.element.getBoundingClientRect !== 'function'
    ) {
      throw failure(
        'INVALID_ARGUMENT',
        'A mounted container element is required.',
      );
    }
    const url = this.validateUrl(options.url);
    const policy = this.preparePolicy(url, options.navigationPolicy);
    this.enforcePolicy(url, policy);
    this.validateHeaders(options.headers);
    const bounds = this.getDimensions(options.element);
    this.state = 'opening';
    this.navigationPolicy = policy;
    this.element = options.element;
    const generation = ++this.generation;
    let nativeOpened = false;
    let lifecycleListener: PluginListenerHandle | undefined;
    try {
      lifecycleListener = await InAppBrowserPlugin.addListener(
        'browserClosed',
        () => {
          if (generation !== this.generation) return;
          this.state = 'closed';
          ++this.generation;
          ++this.listenerGeneration;
          this.clearViewResources();
          const handles = [...this.listeners];
          this.listeners.clear();
          void Promise.all(handles.map(handle => handle.remove())).catch(
            error => console.error('WebView listener cleanup failed.', error),
          );
        },
      );
      if (generation !== this.generation) {
        await lifecycleListener.remove();
        throw failure('CANCELLED', 'The WebView was closed while opening.');
      }
      this.listeners.add(lifecycleListener);
      await InAppBrowserPlugin.openWebView({
        url: url.href,
        headers: options.headers,
        navigationPolicy: policy,
        ...bounds,
      });
      nativeOpened = true;
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed while opening.');
      this.state = 'open';
      const element = options.element;
      this.resizeObserver = new ResizeObserver(() => {
        if (this.state !== 'open' || generation !== this.generation) return;
        // Observers cannot return their promise to a caller. Handle failures explicitly.
        void Promise.resolve()
          .then(() => this.updateDimensions(this.getDimensions(element)))
          .catch(error => console.error('WebView resize failed.', error));
      });
      this.resizeObserver.observe(element);
    } catch (error) {
      if (generation === this.generation) {
        if (nativeOpened) await this.closeWebView();
        else {
          this.state = 'closed';
          this.clearViewResources();
          if (lifecycleListener) {
            this.listeners.delete(lifecycleListener);
            await lifecycleListener.remove();
          }
        }
      }
      throw error;
    }
  }

  async closeWebView(): Promise<void> {
    this.requireNative();
    if (this.closing) return this.closing;
    const hadView = this.state !== 'closed';
    this.state = 'closing';
    ++this.generation;
    ++this.listenerGeneration;
    this.clearViewResources();
    let nativeClosed = !hadView;
    this.closing = (async () => {
      try {
        // Closing must reach native even if removing an event listener fails.
        try {
          if (hadView) {
            await InAppBrowserPlugin.closeWebView();
            nativeClosed = true;
          }
        } finally {
          const listeners = [...this.listeners];
          this.listeners.clear();
          await Promise.all(listeners.map(listener => listener.remove()));
        }
      } finally {
        // Preserve the ability to retry native cleanup if the bridge close failed.
        this.state = nativeClosed ? 'closed' : 'failed';
        this.closing = undefined;
      }
    })();
    try {
      await this.closing;
    } finally {
      this.closing = undefined;
    }
  }

  async showWebView(): Promise<void> {
    this.requireOpen();
    await InAppBrowserPlugin.showWebView();
  }

  async hideWebView(): Promise<void> {
    this.requireOpen();
    await InAppBrowserPlugin.hideWebView();
  }

  async loadUrl(options: { url: string }): Promise<void> {
    this.requireOpen();
    const url = this.validateUrl(options?.url);
    this.enforcePolicy(url, this.navigationPolicy);
    const generation = this.generation;
    try {
      await InAppBrowserPlugin.loadUrl({ url: url.href });
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
    } catch (error) {
      if (
        generation === this.generation &&
        [
          'LOAD_FAILED',
          'LOAD_TIMEOUT',
          'DESTROYED',
          'NAVIGATION_BLOCKED',
        ].includes(error?.code)
      ) {
        this.state = 'closed';
        ++this.generation;
        this.clearViewResources();
      }
      throw error;
    }
  }

  async reload(): Promise<void> {
    this.requireOpen();
    await InAppBrowserPlugin.refresh();
  }

  async updateDimensions(dimensions: Dimensions): Promise<void> {
    this.requireOpen();
    const bounds = this.validateBounds(dimensions);
    await InAppBrowserPlugin.updateDimensions({
      ...bounds,
      ratio: window.devicePixelRatio,
    });
  }

  async navigateBack(): Promise<void> {
    this.requireOpen();
    await InAppBrowserPlugin.navigateBack();
  }

  async navigateForward(): Promise<void> {
    this.requireOpen();
    await InAppBrowserPlugin.navigateForward();
  }

  async onNavigation(
    listener: (event: NavigationEvent) => void,
  ): Promise<void> {
    await this.addListener('navigationHandler', event =>
      listener({
        ...event,
        complete: () =>
          Promise.reject(
            failure(
              'UNIMPLEMENTED',
              'Navigation is decided natively. Configure navigationPolicy before opening.',
            ),
          ),
      }),
    );
  }

  async onPageLoadError(listener: (error: ErrorCode) => void): Promise<void> {
    await this.addListener('pageLoadError', listener);
  }

  async onPageLoaded(
    listener: (status: PageLoadStatus) => void,
  ): Promise<void> {
    await this.addListener('pageLoaded', listener);
  }

  async onUpdateDimensions(listener: () => void): Promise<void> {
    await this.addListener('updateDimensions', listener);
  }

  async captureScreen(showScreenCapture: boolean): Promise<void> {
    this.requireOpen();
    if (typeof showScreenCapture !== 'boolean')
      throw failure('INVALID_ARGUMENT', 'A boolean is required.');
    if (this.capturing)
      throw failure('BUSY', 'A screenshot is already in progress.');
    this.capturing = true;
    const generation = this.generation;
    const element = this.element;
    let blobUrl: string | undefined;
    try {
      const result = await InAppBrowserPlugin.captureScreen(
        this.getDimensions(element),
      );
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      if (!result?.src)
        throw failure(
          'SNAPSHOT_FAILED',
          'Native screenshot returned no image.',
        );
      const buffer = await (
        await fetch('data:image/jpeg;base64,' + result.src)
      ).arrayBuffer();
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      blobUrl = URL.createObjectURL(new Blob([buffer], { type: 'image/jpeg' }));
      await new Promise<void>((resolve, reject) => {
        const img = new Image();
        const finish = (error?: Error) => {
          clearTimeout(timer);
          img.onload = null;
          img.onerror = null;
          this.cancelSnapshot = undefined;
          error ? reject(error) : resolve();
        };
        const timer = setTimeout(
          () =>
            finish(
              failure(
                'SNAPSHOT_FAILED',
                'Screenshot image decoding timed out.',
              ),
            ),
          30000,
        );
        this.cancelSnapshot = () =>
          finish(failure('CANCELLED', 'The WebView was closed.'));
        img.onload = () => finish();
        img.onerror = () =>
          finish(
            failure(
              'SNAPSHOT_FAILED',
              'Screenshot image could not be decoded.',
            ),
          );
        img.src = blobUrl;
      });
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      await InAppBrowserPlugin[
        showScreenCapture ? 'hideWebView' : 'showWebView'
      ]();
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      this.releaseSnapshot();
      element.style.backgroundSize = showScreenCapture ? 'contain' : 'unset';
      element.style.backgroundRepeat = showScreenCapture
        ? 'no-repeat'
        : 'unset';
      element.style.backgroundPosition = showScreenCapture
        ? 'center center'
        : 'unset';
      element.style.backgroundImage = showScreenCapture
        ? `url(${blobUrl})`
        : 'none';
      if (showScreenCapture) {
        this.snapshotUrl = blobUrl;
        blobUrl = undefined;
      }
    } finally {
      if (blobUrl) URL.revokeObjectURL(blobUrl);
      this.capturing = false;
    }
  }

  private async addListener(
    event: EventListeners,
    listener: (...args: any[]) => void,
  ): Promise<void> {
    this.requireNative();
    if (typeof listener !== 'function')
      throw failure('INVALID_ARGUMENT', 'An event callback is required.');
    if (this.state === 'closing')
      throw failure('BUSY', 'The WebView is closing.');
    const generation = this.listenerGeneration;
    const handle = await InAppBrowserPlugin.addListener(event, listener);
    if (generation !== this.listenerGeneration) {
      await handle.remove();
      throw failure(
        'CANCELLED',
        'The listener was registered while the WebView was closing.',
      );
    }
    this.listeners.add(handle);
  }

  private requireNative(): void {
    if (!Capacitor.isNativePlatform())
      throw failure('UNIMPLEMENTED', 'This plugin requires iOS or Android.');
  }

  private requireOpen(): void {
    this.requireNative();
    if (this.state !== 'open')
      throw failure(
        this.state === 'closed' ? 'NOT_OPEN' : 'BUSY',
        'The WebView is not ready.',
      );
  }

  private validateUrl(url: string): URL {
    try {
      if (typeof url !== 'string' || !url || /\s/.test(url)) throw new Error();
      const parsed = new URL(url);
      if (
        parsed.port &&
        (Number(parsed.port) < 1 || Number(parsed.port) > 65535)
      )
        throw new Error();
      return parsed;
    } catch {
      throw failure(
        'INVALID_ARGUMENT',
        'An absolute URL without whitespace is required.',
      );
    }
  }

  private preparePolicy(
    url: URL,
    policy?: NavigationPolicy,
  ): Required<NavigationPolicy> {
    if (
      policy !== undefined &&
      (!policy || typeof policy !== 'object' || Array.isArray(policy))
    ) {
      throw failure('INVALID_ARGUMENT', 'navigationPolicy must be an object.');
    }
    if (
      policy?.allowHttp !== undefined &&
      typeof policy.allowHttp !== 'boolean'
    ) {
      throw failure('INVALID_ARGUMENT', 'allowHttp must be a boolean.');
    }
    let origins = [url.origin];
    if (policy?.allowedOrigins !== undefined) {
      if (
        !Array.isArray(policy.allowedOrigins) ||
        policy.allowedOrigins.length === 0
      ) {
        throw failure(
          'INVALID_ARGUMENT',
          'allowedOrigins must be a nonempty array of exact origins.',
        );
      }
      origins = policy.allowedOrigins.map(origin => {
        const parsed = this.validateUrl(origin);
        if (
          !['https:', 'http:'].includes(parsed.protocol) ||
          !parsed.hostname ||
          parsed.hostname.includes('*') ||
          parsed.username ||
          parsed.password ||
          parsed.pathname !== '/' ||
          origin.includes('?') ||
          origin.includes('#')
        ) {
          throw failure(
            'INVALID_ARGUMENT',
            'allowedOrigins must contain exact HTTP(S) origins without paths or credentials.',
          );
        }
        return parsed.origin;
      });
    }
    return {
      allowedOrigins: [...new Set(origins)],
      allowHttp: policy?.allowHttp ?? false,
    };
  }

  private enforcePolicy(url: URL, policy: Required<NavigationPolicy>): void {
    if (!['https:', 'http:'].includes(url.protocol))
      throw failure('POLICY_BLOCKED', 'SCHEME_BLOCKED');
    if (url.username || url.password)
      throw failure('POLICY_BLOCKED', 'CREDENTIALS_BLOCKED');
    if (url.protocol === 'http:' && !policy.allowHttp)
      throw failure('POLICY_BLOCKED', 'HTTPS_REQUIRED');
    if (!policy.allowedOrigins.includes(url.origin))
      throw failure('POLICY_BLOCKED', 'ORIGIN_BLOCKED');
  }

  private validateHeaders(headers: OpenOptions['headers']): void {
    if (headers === undefined) return;
    if (
      !headers ||
      typeof headers !== 'object' ||
      Array.isArray(headers) ||
      Object.entries(headers).some(
        ([key, value]) =>
          !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(key) ||
          typeof value !== 'string' ||
          /[\r\n]/.test(value),
      )
    )
      throw failure(
        'INVALID_ARGUMENT',
        'Headers must be an object of valid header names and string values.',
      );
  }

  private validateBounds(bounds: Dimensions): Bounds {
    if (
      !bounds ||
      ['width', 'height', 'x', 'y'].some(
        key => typeof bounds[key] !== 'number' || !Number.isFinite(bounds[key]),
      ) ||
      bounds.width <= 0 ||
      bounds.height <= 0
    ) {
      throw failure(
        'INVALID_ARGUMENT',
        'Finite x/y and positive width/height are required.',
      );
    }
    return {
      width: bounds.width,
      height: bounds.height,
      x: bounds.x,
      y: bounds.y,
    };
  }

  private getDimensions(element: HTMLElement): Bounds {
    if (!element)
      throw failure('NOT_OPEN', 'The browser container is unavailable.');
    const { width, height, x, y } = element.getBoundingClientRect();
    return this.validateBounds({
      width: Math.round(width),
      height: Math.round(height),
      x: Math.round(x),
      y: Math.round(y),
    });
  }

  private releaseSnapshot(): void {
    if (!this.snapshotUrl) return;
    if (this.element) this.element.style.backgroundImage = 'none';
    URL.revokeObjectURL(this.snapshotUrl);
    this.snapshotUrl = undefined;
  }

  private clearViewResources(): void {
    this.cancelSnapshot?.();
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.releaseSnapshot();
    this.element = undefined;
    this.navigationPolicy = undefined;
  }
}
