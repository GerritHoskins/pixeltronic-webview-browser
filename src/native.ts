import type { PluginListenerHandle } from '@capacitor/core';

import type {
  BrowserEventMap,
  Dimensions,
  OpenOptions,
  ScreenShot,
} from './definitions';

// Bridge payloads must be serializable and cannot contain DOM nodes or callbacks.
export type ViewportMetrics = {
  viewportWidth?: number;
  viewportHeight?: number;
  offsetLeft?: number;
  offsetTop?: number;
};
export type NativeBounds = Dimensions & ViewportMetrics;
type NativeEvents = BrowserEventMap & { browserClosed: { reason: string } };

export interface NativeInterface {
  openWebView(
    options: Omit<OpenOptions, 'element'> & NativeBounds,
  ): Promise<void>;
  closeWebView(): Promise<void>;
  showWebView(): Promise<void>;
  hideWebView(): Promise<void>;
  navigateBack(): Promise<void>;
  navigateForward(): Promise<void>;
  refresh(): Promise<void>;
  loadUrl(options: { url: string }): Promise<void>;
  updateDimensions(options: NativeBounds): Promise<void>;
  captureScreen(): Promise<ScreenShot>;
  setLayoutVisibility(options: { visible: boolean }): Promise<void>;
  setSnapshotMode(options: { enabled: boolean }): Promise<void>;
  addListener<K extends keyof NativeEvents>(
    event: K,
    listener: (value: NativeEvents[K]) => void,
  ): Promise<PluginListenerHandle>;
}
