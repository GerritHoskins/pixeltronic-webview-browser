import { InAppBrowser } from '../dist/esm';
import type { Dimensions, OpenOptions, EventListeners } from '../dist/esm';
import type { NativeInterface } from '../dist/esm/native';
import type { PluginListenerHandle } from '@capacitor/core';
// @ts-expect-error NativeInterface is not part of the exported public API.
import type { NativeInterface as PublicNativeInterface } from '../dist/esm';

declare const element: HTMLElement;
const options: OpenOptions = {
  url: 'https://example.com',
  element,
  headers: { Authorization: 'fixture' },
};
void InAppBrowser.openWebView(options);
void InAppBrowser.updateDimensions();
void InAppBrowser.updateDimensions({
  width: 300.25,
  height: 400.5,
  x: -5,
  y: 10,
});
const registration: Promise<PluginListenerHandle> = InAppBrowser.onPageLoaded(
  event => {
    const loading: boolean = event.isLoading;
    void loading;
  },
);
void registration.then(handle => handle.remove());
void InAppBrowser.onUpdateDimensions(bounds => {
  const width: number = bounds.width;
  void width;
});
void InAppBrowser.onNavigation(event => {
  const allowed: boolean = event.allowed;
  void allowed;
});

// @ts-expect-error The mounted element is required.
void InAppBrowser.openWebView({ url: 'https://example.com' });
// @ts-expect-error Partial bounds are rejected at runtime and by the type checker.
void InAppBrowser.updateDimensions({ width: 100 });
const unsupported: OpenOptions = {
  url: 'https://example.com',
  element,
  // @ts-expect-error Unsupported toolbar options are not advertised as functional.
  colorScheme: {},
};
void unsupported;
// @ts-expect-error Events that are never emitted are not part of the API.
const event: EventListeners = 'updateScreenCapture';
void event;
declare const native: NativeInterface;
declare const bounds: Dimensions;
void native.openWebView({ url: 'https://example.com', ...bounds });
// @ts-expect-error DOM elements cannot cross the native bridge.
void native.openWebView({ url: 'https://example.com', ...bounds, element });
// @ts-expect-error The native bridge has no system-browser implementation.
void native.openSystemBrowser({ url: 'https://example.com' });
