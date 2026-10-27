import type { PluginListenerHandle } from '@capacitor/core';
import { Capacitor, registerPlugin } from '@capacitor/core';
import ResizeObserver from 'resize-observer-polyfill';

import type {
  InAppBrowserInterface,
  Dimensions,
  OpenOptions,
  NavigationEvent,
  ErrorCode,
  EventListeners,
  BrowserEventMap,
  PageLoadStatus,
  NavigationPolicy,
} from './definitions';
import type { NativeInterface, NativeBounds, ViewportMetrics } from './native';

const InAppBrowserPlugin =
  registerPlugin<NativeInterface>('InAppBrowserPlugin');
type State = 'closed' | 'opening' | 'open' | 'closing' | 'failed';
type Bounds = NativeBounds;

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
  private requestedHidden = false;
  private layoutVisible = true;
  private visibilityRevision = 0;
  private lastBounds?: Bounds;
  private layoutFrame?: number;
  private layoutActive = false;
  private layoutPending = false;
  private layoutCleanup: (() => void)[] = [];
  private mutationObserver?: MutationObserver;
  private snapshotStyles?: Record<
    string,
    { original: string; priority: string; applied: string }
  >;

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
    this.requestedHidden = false;
    this.layoutVisible = true;
    this.navigationPolicy = policy;
    this.element = options.element;
    const generation = ++this.generation;
    let nativeOpened = false;
    let lifecycleListener: PluginListenerHandle | undefined;
    let loadingListener: PluginListenerHandle | undefined;
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
          void Promise.all(
            handles.map(async handle => {
              await handle.remove();
              this.listeners.delete(handle);
            }),
          ).catch(error =>
            console.error('WebView listener cleanup failed.', error),
          );
        },
      );
      if (generation !== this.generation) {
        await lifecycleListener.remove();
        throw failure('CANCELLED', 'The WebView was closed while opening.');
      }
      this.listeners.add(lifecycleListener);
      loadingListener = await InAppBrowserPlugin.addListener(
        'pageLoaded',
        status => {
          if (generation !== this.generation || !status.isLoading) return;
          ++this.visibilityRevision;
          this.releaseSnapshot();
        },
      );
      if (generation !== this.generation) {
        await loadingListener.remove();
        throw failure('CANCELLED', 'The WebView was closed while opening.');
      }
      this.listeners.add(loadingListener);
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
      this.lastBounds = bounds;
      this.installLayoutTracking(options.element, generation);
      await this.syncElementLayout(generation);
    } catch (error) {
      if (generation === this.generation) {
        if (nativeOpened) await this.closeWebView();
        else {
          this.state = 'closed';
          this.clearViewResources();
          if (loadingListener) {
            this.listeners.delete(loadingListener);
            await loadingListener.remove();
          }
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
          await Promise.all(
            listeners.map(async listener => {
              await listener.remove();
              this.listeners.delete(listener);
            }),
          );
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
    await this.changeVisibility(false);
  }

  async hideWebView(): Promise<void> {
    await this.changeVisibility(true);
  }

  async loadUrl(options: { url: string }): Promise<void> {
    this.requireOpen();
    const url = this.validateUrl(options?.url);
    this.enforcePolicy(url, this.navigationPolicy);
    const resume = this.resumeLiveView();
    if (resume) await resume;
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
    const resume = this.resumeLiveView();
    if (resume) await resume;
    await InAppBrowserPlugin.refresh();
  }

  async updateDimensions(dimensions?: Dimensions): Promise<void> {
    this.requireOpen();
    const bounds =
      dimensions === undefined
        ? this.getDimensions(this.element)
        : this.validateBounds({ ...this.viewportMetrics(), ...dimensions });
    const generation = this.generation;
    await InAppBrowserPlugin.updateDimensions(bounds);
    if (generation !== this.generation)
      throw failure('CANCELLED', 'The WebView was closed.');
    this.lastBounds = bounds;
  }

  async navigateBack(): Promise<void> {
    this.requireOpen();
    const resume = this.resumeLiveView();
    if (resume) await resume;
    await InAppBrowserPlugin.navigateBack();
  }

  async navigateForward(): Promise<void> {
    this.requireOpen();
    const resume = this.resumeLiveView();
    if (resume) await resume;
    await InAppBrowserPlugin.navigateForward();
  }

  async onNavigation(
    listener: (event: NavigationEvent) => void,
  ): Promise<PluginListenerHandle> {
    this.requireNative();
    if (typeof listener !== 'function')
      throw failure('INVALID_ARGUMENT', 'An event callback is required.');
    return this.addListener('navigationHandler', event =>
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

  async onPageLoadError(
    listener: (error: ErrorCode) => void,
  ): Promise<PluginListenerHandle> {
    return this.addListener('pageLoadError', listener);
  }

  async onPageLoaded(
    listener: (status: PageLoadStatus) => void,
  ): Promise<PluginListenerHandle> {
    return this.addListener('pageLoaded', listener);
  }

  async onUpdateDimensions(
    listener: (bounds: Dimensions) => void,
  ): Promise<PluginListenerHandle> {
    return this.addListener('updateDimensions', listener);
  }

  async captureScreen(showScreenCapture: boolean): Promise<void> {
    this.requireOpen();
    if (typeof showScreenCapture !== 'boolean')
      throw failure('INVALID_ARGUMENT', 'A boolean is required.');
    if (this.capturing)
      throw failure('BUSY', 'A screenshot is already in progress.');
    const generation = this.generation;
    if (!showScreenCapture) {
      await InAppBrowserPlugin.setSnapshotMode({ enabled: false });
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      this.releaseSnapshot();
      return;
    }
    this.requireCaptureVisible();
    if (this.snapshotUrl) return;
    this.capturing = true;
    const visibilityRevision = this.visibilityRevision;
    let blobUrl: string | undefined;
    let switchingSnapshotMode = false;
    try {
      const result = await InAppBrowserPlugin.captureScreen();
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      this.requireCaptureVisible();
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
              failure('SNAPSHOT_FAILED', 'Screenshot decoding timed out.'),
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
      this.requireCaptureVisible();
      // Prepare the DOM image underneath the live view before native switches visibility.
      if (visibilityRevision !== this.visibilityRevision)
        throw failure('CANCELLED', 'The browser visibility or page changed.');
      this.applySnapshotStyle(blobUrl);
      this.snapshotUrl = blobUrl;
      blobUrl = undefined;
      switchingSnapshotMode = true;
      await InAppBrowserPlugin.setSnapshotMode({ enabled: true });
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      this.requireCaptureVisible();
      if (visibilityRevision !== this.visibilityRevision)
        throw failure('CANCELLED', 'The browser visibility or page changed.');
    } catch (error) {
      if (generation === this.generation) {
        this.releaseSnapshot();
        if (switchingSnapshotMode) {
          try {
            await InAppBrowserPlugin.setSnapshotMode({ enabled: false });
          } catch (cleanupError) {
            console.error(
              'WebView snapshot visibility recovery failed.',
              cleanupError,
            );
          }
        }
      }
      throw error;
    } finally {
      if (blobUrl) URL.revokeObjectURL(blobUrl);
      this.capturing = false;
    }
  }

  private async addListener<K extends EventListeners>(
    event: K,
    listener: (value: BrowserEventMap[K]) => void,
  ): Promise<PluginListenerHandle> {
    this.requireNative();
    if (typeof listener !== 'function')
      throw failure('INVALID_ARGUMENT', 'An event callback is required.');
    if (this.state === 'closing')
      throw failure('BUSY', 'The WebView is closing.');
    const generation = this.listenerGeneration;
    let active = true;
    const handle = await InAppBrowserPlugin.addListener(event, value => {
      if (active && generation === this.listenerGeneration) listener(value);
    });
    let removed = false;
    let removing: Promise<void> | undefined;
    const owned: PluginListenerHandle = {
      remove: () => {
        active = false; // Suppress queued callbacks even when native removal needs a retry.
        if (removed) return Promise.resolve();
        if (!removing) {
          removing = Promise.resolve()
            .then(() => handle.remove())
            .then(
              () => {
                removed = true;
                this.listeners.delete(owned);
                removing = undefined;
              },
              error => {
                removing = undefined;
                throw error;
              },
            );
        }
        return removing;
      },
    };
    if (generation !== this.listenerGeneration) {
      await owned.remove();
      throw failure(
        'CANCELLED',
        'The listener was registered while the WebView was closing.',
      );
    }
    this.listeners.add(owned);
    return owned;
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
          Array.from(value).some(character => {
            const code = character.charCodeAt(0);
            return (code < 32 && code !== 9) || code === 127;
          }),
      )
    )
      throw failure(
        'INVALID_ARGUMENT',
        'Headers must be an object of valid header names and string values.',
      );
    const names = Object.keys(headers).map(name => name.toLowerCase());
    if (new Set(names).size !== names.length)
      throw failure(
        'INVALID_ARGUMENT',
        'Header names must be unique ignoring case.',
      );
  }

  private validateBounds(bounds: NativeBounds): Bounds {
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
      ...this.validateViewport(bounds),
    };
  }

  private validateViewport(bounds: ViewportMetrics): ViewportMetrics {
    for (const key of [
      'viewportWidth',
      'viewportHeight',
      'offsetLeft',
      'offsetTop',
    ]) {
      if (
        bounds[key] !== undefined &&
        (typeof bounds[key] !== 'number' ||
          !Number.isFinite(bounds[key]) ||
          (key.startsWith('viewport') && bounds[key] <= 0))
      ) {
        throw failure(
          'INVALID_ARGUMENT',
          'Viewport metrics must be finite, with positive width and height.',
        );
      }
    }
    return {
      viewportWidth: bounds.viewportWidth,
      viewportHeight: bounds.viewportHeight,
      offsetLeft: bounds.offsetLeft ?? 0,
      offsetTop: bounds.offsetTop ?? 0,
    };
  }

  private viewportMetrics(): ViewportMetrics {
    const viewport = window.visualViewport;
    const width = viewport?.width ?? window.innerWidth;
    const height = viewport?.height ?? window.innerHeight;
    return {
      viewportWidth: Number.isFinite(width) && width > 0 ? width : undefined,
      viewportHeight:
        Number.isFinite(height) && height > 0 ? height : undefined,
      offsetLeft: viewport?.offsetLeft ?? 0,
      offsetTop: viewport?.offsetTop ?? 0,
    };
  }

  private installLayoutTracking(
    element: HTMLElement,
    generation: number,
  ): void {
    const schedule = () => this.scheduleLayout(generation);
    this.resizeObserver = new ResizeObserver(schedule);
    this.resizeObserver.observe(element);
    if (typeof document !== 'undefined' && document.documentElement)
      this.resizeObserver.observe(document.documentElement);
    const watch = (target: EventTarget, event: string, capture = false) => {
      if (typeof target?.addEventListener !== 'function') return;
      target.addEventListener(event, schedule, { capture, passive: true });
      this.layoutCleanup.push(() =>
        target.removeEventListener(event, schedule, capture),
      );
    };
    watch(window, 'scroll', true);
    watch(window, 'resize');
    watch(window, 'orientationchange');
    watch(window.visualViewport, 'scroll');
    watch(window.visualViewport, 'resize');
    if (
      typeof MutationObserver !== 'undefined' &&
      typeof document !== 'undefined' &&
      document.documentElement
    ) {
      this.mutationObserver = new MutationObserver(schedule);
      this.mutationObserver.observe(document.documentElement, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['style', 'class', 'hidden'],
      });
    }
  }

  private scheduleLayout(generation: number): void {
    if (
      generation !== this.generation ||
      this.state !== 'open' ||
      this.layoutFrame !== undefined
    )
      return;
    const run = () => {
      if (generation !== this.generation) return;
      this.layoutFrame = undefined;
      void this.syncElementLayout(generation).catch(error =>
        console.error('WebView layout update failed.', error),
      );
    };
    if (typeof window.requestAnimationFrame === 'function')
      this.layoutFrame = window.requestAnimationFrame(run);
    else {
      this.layoutFrame = -1;
      void Promise.resolve().then(run);
    }
  }

  private async syncElementLayout(generation: number): Promise<void> {
    if (this.layoutActive) {
      this.layoutPending = true;
      return;
    }
    this.layoutActive = true;
    try {
      do {
        this.layoutPending = false;
        if (
          generation !== this.generation ||
          this.state !== 'open' ||
          !this.element
        )
          return;
        const rect = this.element.getBoundingClientRect();
        const metrics = this.viewportMetrics();
        const visibleHeight =
          window.visualViewport?.height ?? window.innerHeight;
        const visible =
          this.element.isConnected !== false &&
          [rect.width, rect.height, rect.x, rect.y].every(Number.isFinite) &&
          rect.width > 0 &&
          rect.height > 0 &&
          (metrics.viewportWidth === undefined ||
            (rect.x + rect.width > metrics.offsetLeft &&
              rect.x < metrics.offsetLeft + metrics.viewportWidth)) &&
          (visibleHeight === undefined ||
            (visibleHeight > 0 &&
              rect.y + rect.height > metrics.offsetTop &&
              rect.y < metrics.offsetTop + visibleHeight));
        if (visible) {
          const bounds = this.validateBounds({
            width: rect.width,
            height: rect.height,
            x: rect.x,
            y: rect.y,
            ...metrics,
          });
          const keys = [
            'width',
            'height',
            'x',
            'y',
            'viewportWidth',
            'viewportHeight',
            'offsetLeft',
            'offsetTop',
          ];
          if (
            !this.lastBounds ||
            keys.some(key => bounds[key] !== this.lastBounds[key])
          ) {
            await InAppBrowserPlugin.updateDimensions(bounds);
            if (generation !== this.generation) return;
            this.lastBounds = bounds;
          }
        }
        if (generation !== this.generation) return;
        if (this.layoutVisible !== visible) {
          await InAppBrowserPlugin.setLayoutVisibility({ visible });
          if (generation !== this.generation) return;
          this.layoutVisible = visible;
        }
      } while (this.layoutPending);
    } finally {
      if (generation === this.generation) this.layoutActive = false;
    }
  }

  private async changeVisibility(hidden: boolean): Promise<void> {
    this.requireOpen();
    const generation = this.generation,
      revision = ++this.visibilityRevision,
      previous = this.requestedHidden;
    this.requestedHidden = hidden;
    try {
      await InAppBrowserPlugin[hidden ? 'hideWebView' : 'showWebView']();
      if (generation !== this.generation)
        throw failure('CANCELLED', 'The WebView was closed.');
      if (revision === this.visibilityRevision) this.releaseSnapshot();
    } catch (error) {
      if (
        generation === this.generation &&
        revision === this.visibilityRevision
      )
        this.requestedHidden = previous;
      throw error;
    }
  }

  private resumeLiveView(): Promise<void> | undefined {
    if (this.capturing) throw failure('BUSY', 'A screenshot is in progress.');
    return this.snapshotUrl ? this.captureScreen(false) : undefined;
  }

  private requireCaptureVisible(): void {
    if (
      this.requestedHidden ||
      !this.layoutVisible ||
      !this.element ||
      this.element.isConnected === false
    ) {
      throw failure(
        'NOT_VISIBLE',
        'The browser container is hidden or detached.',
      );
    }
  }

  private styleValue(style: CSSStyleDeclaration, key: string): string {
    return typeof style.getPropertyValue === 'function'
      ? style.getPropertyValue(key)
      : style[key.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] ??
          '';
  }

  private setStyleValue(
    style: CSSStyleDeclaration,
    key: string,
    value: string,
    priority = '',
  ): void {
    if (typeof style.setProperty === 'function') {
      if (value) style.setProperty(key, value, priority);
      else style.removeProperty(key);
    } else
      style[key.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] =
        value;
  }

  private applySnapshotStyle(url: string): void {
    const style = this.element.style;
    this.snapshotStyles = {};
    for (const [key, value] of Object.entries({
      'background-image': `url(${url})`,
      'background-size': 'contain',
      'background-repeat': 'no-repeat',
      'background-position': 'center center',
    })) {
      const original = this.styleValue(style, key);
      const priority =
        typeof style.getPropertyPriority === 'function'
          ? style.getPropertyPriority(key)
          : '';
      this.snapshotStyles[key] = { original, priority, applied: original };
      this.setStyleValue(style, key, value, priority);
      this.snapshotStyles[key].applied = this.styleValue(style, key);
    }
  }

  private getDimensions(element: HTMLElement): Bounds {
    if (!element)
      throw failure('NOT_OPEN', 'The browser container is unavailable.');
    const { width, height, x, y } = element.getBoundingClientRect();
    return this.validateBounds({
      width,
      height,
      x,
      y,
      ...this.viewportMetrics(),
    });
  }

  private releaseSnapshot(): void {
    if (this.element && this.snapshotStyles) {
      for (const [key, saved] of Object.entries(this.snapshotStyles)) {
        if (this.styleValue(this.element.style, key) === saved.applied) {
          this.setStyleValue(
            this.element.style,
            key,
            saved.original,
            saved.priority,
          );
        }
      }
    }
    this.snapshotStyles = undefined;
    if (this.snapshotUrl) URL.revokeObjectURL(this.snapshotUrl);
    this.snapshotUrl = undefined;
  }

  private clearViewResources(): void {
    this.cancelSnapshot?.();
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.mutationObserver?.disconnect();
    this.mutationObserver = undefined;
    for (const cleanup of this.layoutCleanup.splice(0)) cleanup();
    if (
      this.layoutFrame !== undefined &&
      this.layoutFrame >= 0 &&
      typeof window.cancelAnimationFrame === 'function'
    ) {
      window.cancelAnimationFrame(this.layoutFrame);
    }
    this.layoutFrame = undefined;
    this.layoutActive = false;
    this.layoutPending = false;
    this.lastBounds = undefined;
    this.releaseSnapshot();
    this.element = undefined;
    this.navigationPolicy = undefined;
  }
}
