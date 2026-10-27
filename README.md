# Pixeltronic Webview Browser

Embed a native WebView inside an iOS or Android Capacitor app, positioned to match an HTML element. Control navigation, visibility, page loading, and screenshots through a TypeScript API.

The plugin uses `WKWebView` on iOS and `android.webkit.WebView` on Android. The native view sits above the app's web content; the HTML element supplies its position and dimensions.

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Navigation and visibility](#navigation-and-visibility)
- [Layout updates](#layout-updates)
- [Page events](#page-events)
- [Screenshots](#screenshots)
- [Platform differences](#platform-differences)
- [API reference](#api-reference)
- [Development](#development)

## Requirements

| Component              | Requirement                                                |
| ---------------------- | ---------------------------------------------------------- |
| Capacitor              | `8.5.3` or a later compatible `8.x` release                |
| Node.js                | `22` or later                                              |
| iOS                    | iOS `15` or later; Xcode `26` or later                     |
| iOS dependency manager | CocoaPods or Swift Package Manager                         |
| Android                | API `24` or later; compile/target SDK `36`                 |
| Android build tools    | Java `21`, Android Gradle Plugin `8.13.2`, Gradle `8.14.3` |

The Android module defaults to AppCompat `1.8.0`, Browser `1.10.0`, and CoordinatorLayout `1.3.0`. Host apps can override the versions through the corresponding Gradle project properties.

This branch targets Capacitor 8. Upgrade Capacitor 5–7 apps before integrating it. The plugin's own package version (`5.0.4`) is separate from its Capacitor compatibility requirement.

## Installation

Install from a locally built package. The package name `pixeltronic-webview-browser` is not currently available on the public npm registry.

First, clone and build the plugin:

```sh
git clone https://github.com/GerritHoskins/pixeltronic-webview-browser.git
cd pixeltronic-webview-browser
npm ci
npm run build
npm pack
```

Then, from your Capacitor app directory, install the generated tarball and sync the native projects:

```sh
npm install /path/to/pixeltronic-webview-browser/pixeltronic-webview-browser-5.0.4.tgz
npx cap sync
```

Replace the path with the tarball's actual location. Build the plugin before packing it: the package entry points use the generated `dist/` directory. Rebuild your native app after syncing.

## Quick start

Create a visible container with nonzero dimensions:

```html
<div id="browser-container" aria-label="Embedded web content"></div>
```

```css
#browser-container {
  width: 100%;
  height: 400px;
}
```

Open the WebView after the element has mounted and layout is ready:

```typescript
import { Capacitor } from '@capacitor/core';
import { InAppBrowser } from 'pixeltronic-webview-browser';

async function openBrowser(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;

  const element = document.getElementById('browser-container');
  if (!element) throw new Error('Browser container is missing');

  await InAppBrowser.openWebView({
    url: 'https://example.com',
    element,
  });
}

// Call from your framework's mounted/view-ready hook or a user action.
// Handle a rejected promise in your app's UI.
```

Pass the `HTMLElement` itself, rather than its ID. Although the type declares `element` as optional, the public wrapper requires it on native platforms. Initial coordinates and dimensions are read from `getBoundingClientRect()`.

`InAppBrowser` is a shared singleton. Keep one active WebView and close it before opening another. There is no browser/PWA implementation; guard native calls as shown above.

## Navigation and visibility

Use these controls after opening a WebView:

```typescript
import { InAppBrowser } from 'pixeltronic-webview-browser';

async function navigate(): Promise<void> {
  await InAppBrowser.loadUrl({ url: 'https://example.com/help' });
  await InAppBrowser.reload();
}

async function showBrowser(): Promise<void> {
  await InAppBrowser.showWebView();
}

async function hideBrowser(): Promise<void> {
  await InAppBrowser.hideWebView();
}

async function closeBrowser(): Promise<void> {
  await InAppBrowser.closeWebView();
}
```

`navigateBack()` and `navigateForward()` move through the existing WebView history. `hideWebView()` preserves the view; `closeWebView()` destroys it and disconnects the wrapper's resize observer. Call close when leaving the screen that owns the container.

## Layout updates

The wrapper observes the container with `ResizeObserver` and sends its current bounds when its size changes. Scrolling or moving an element without resizing it needs an explicit update:

```typescript
import { InAppBrowser } from 'pixeltronic-webview-browser';

async function syncBrowserBounds(element: HTMLElement): Promise<void> {
  const { width, height, x, y } = element.getBoundingClientRect();

  await InAppBrowser.updateDimensions({
    width: Math.round(width),
    height: Math.round(height),
    x: Math.round(x),
    y: Math.round(y),
  });
}
```

Send all four values on each manual update. Keep the element mounted while the native view is open. Its native counterpart does not inherit DOM clipping, transforms, or CSS stacking behavior.

## Page events

Register listeners once, before opening the WebView, to observe loading and errors:

```typescript
import { Capacitor } from '@capacitor/core';
import { InAppBrowser } from 'pixeltronic-webview-browser';

async function registerBrowserEvents(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;

  await InAppBrowser.onPageLoaded(({ isLoading }) => {
    console.log(isLoading ? 'Loading page' : 'Loading finished');
  });

  await InAppBrowser.onPageLoadError(({ errorCode }) => {
    console.error('WebView load error:', errorCode);
  });
}
```

Despite its name, `onPageLoaded()` reports both loading states. Error codes are platform-specific: Android reports WebView errors and HTTP status codes, while iOS reports native navigation error codes.

The listener helpers return `Promise<void>`, with no unsubscribe handle. The current wrapper does not retain the handles needed to remove these listeners on close. Re-registering on every open can accumulate callbacks.

## Screenshots

`captureScreen(true)` captures the native view, displays the image as the container's background, and hides the live WebView. This lets HTML UI appear above a static representation of the page.

```typescript
import { InAppBrowser } from 'pixeltronic-webview-browser';

async function showSnapshot(): Promise<void> {
  await InAppBrowser.captureScreen(true);
}

async function resumeLiveView(): Promise<void> {
  await InAppBrowser.captureScreen(false);
}
```

Call this after opening and loading the page. The public method returns no image data. Its promise can resolve before the image finishes loading and visibility switches; it is not a signal that the snapshot is already displayed. A screenshot is static and cannot receive page interactions.

## Platform differences

These limitations reflect the current implementation:

| Feature                | iOS                                                                                                      | Android                                                                                                |
| ---------------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `headers`              | Applies `User-Agent`; other custom headers are not attached to requests                                  | Headers are stored on open and applied by subsequent `loadUrl()` calls; the initial open bypasses them |
| `onNavigation()`       | Calls `event.complete(true)` to allow or `event.complete(false)` to cancel a pending navigation response | Emits navigation events, but `complete()` does not currently resume or cancel the pending URL          |
| `colorScheme`          | Declared in the types but not forwarded by the public wrapper                                            | Declared in the types but not forwarded by the public wrapper                                          |
| `onUpdateDimensions()` | Declared, but no corresponding native event is emitted                                                   | Declared, but no corresponding native event is emitted                                                 |

Register `onNavigation()` only when you need to intercept navigation and have verified the behavior on your target platform. On iOS, resolve each callback with `event.complete(...)` so the response can proceed or be cancelled. This callback is not a cross-platform navigation security boundary.

The native view appears above HTML content. Use the screenshot helper when an HTML overlay must cover it. The Android WebView enables JavaScript, file access, and mixed content; load content appropriate for your app's trust model.

## API reference

Import the public singleton as `InAppBrowser`. All methods below return `Promise<void>`.

| Method                                      | Purpose                                                                             |
| ------------------------------------------- | ----------------------------------------------------------------------------------- |
| `openWebView(options)`                      | Create the native view and load a URL using the container's bounds                  |
| `closeWebView()`                            | Destroy the current native view                                                     |
| `showWebView()` / `hideWebView()`           | Change visibility without destroying the view                                       |
| `navigateBack()` / `navigateForward()`      | Navigate through page history                                                       |
| `loadUrl({ url })`                          | Load a URL in the existing view                                                     |
| `reload()`                                  | Reload the current page                                                             |
| `updateDimensions({ width, height, x, y })` | Update the native view's bounds                                                     |
| `onPageLoaded(callback)`                    | Observe `{ isLoading: boolean }`                                                    |
| `onPageLoadError(callback)`                 | Observe `{ errorCode: number }`                                                     |
| `onNavigation(callback)`                    | Observe `{ url, newWindowRequest, isSameHost, complete }`; see platform differences |
| `onUpdateDimensions(callback)`              | Declared resize-event listener; currently not emitted by native code                |
| `captureScreen(showScreenCapture)`          | Switch between a snapshot background and the live view                              |

### Open options

| Option                               | Type                        | Behavior                                                                    |
| ------------------------------------ | --------------------------- | --------------------------------------------------------------------------- |
| `url`                                | `string`                    | Required URL to load                                                        |
| `element`                            | `HTMLElement`               | Required by the wrapper on native platforms; supplies the view's bounds     |
| `headers`                            | `{ [key: string]: string }` | Optional headers; see platform differences                                  |
| `colorScheme`                        | `ColorScheme`               | Declared option with no effect through the current wrapper                  |
| `width`, `height`, `x`, `y`, `ratio` | `number`                    | Inherited type fields; `openWebView()` uses measured element bounds instead |

For the complete type declarations, see [src/definitions.ts](src/definitions.ts). Some native-facing declarations, including `openSystemBrowser()` and `openBrowser()`, are not implemented by the public singleton and should not be used as public features.

## Development

```sh
npm ci
npm run verify:web
npm run eslint
```

Run Android verification with an installed Android SDK and Java 21:

```sh
npm run verify:android
```

On Windows PowerShell, invoke the wrapper directly:

```powershell
Set-Location android
.\gradlew.bat clean build test
```

Run iOS verification on macOS with Xcode and CocoaPods:

```sh
npm run verify:ios
```

`npm run verify` runs iOS, Android, and web verification in sequence. `npm run build` generates the web bundle and `dist/docs.json`. This README is maintained manually so it can explain implementation limits alongside the declared API.

The Capacitor 8 upgrade was checked with the web build and Android debug/release builds. An iOS/Xcode build and device behavior still need validation. Android's current unit-test tasks contain no test sources.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow. The package declares an MIT license in [package.json](package.json).
