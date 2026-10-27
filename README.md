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
- [API migration](#api-migration)
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

This branch targets Capacitor 8. Upgrade Capacitor 5–7 apps before integrating it. The plugin's own package version (`0.0.1`) is separate from its Capacitor compatibility requirement.

## Installation

### npm (recommended)

Install the published package from the public npm registry:

```sh
npm install @gerrithoskins/pixeltronic-webview-browser@0.0.1
npx cap sync
```

If this scope was previously configured for GitHub Packages, set the following in your app's `.npmrc` to use npm instead:

```ini
@gerrithoskins:registry=https://registry.npmjs.org/
```

### GitHub Packages

GitHub Packages uses the same package name and version but a different registry. Configure the scope in your app's `.npmrc`:

```ini
@gerrithoskins:registry=https://npm.pkg.github.com/
```

Authenticate with your GitHub username and a personal access token (classic) with `read:packages`. Use the token as the password at the prompt; do not put it in the project's `.npmrc` or commit it.

```sh
npm login --scope=@gerrithoskins --auth-type=legacy --registry=https://npm.pkg.github.com/
npm install @gerrithoskins/pixeltronic-webview-browser@0.0.1
npx cap sync
```

GitHub requires authentication even for public npm packages hosted in GitHub Packages. If the package is private, your GitHub account must also have access. See [GitHub's npm registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry).

### Build from source

To use a local build instead of either registry:

```sh
git clone https://github.com/GerritHoskins/pixeltronic-webview-browser.git
cd pixeltronic-webview-browser
npm ci
npm run build
npm pack
```

From your Capacitor app directory, install the generated tarball:

```sh
npm install /path/to/pixeltronic-webview-browser/gerrithoskins-pixeltronic-webview-browser-0.0.1.tgz
npx cap sync
```

Build before packing: the package entry points use the generated `dist/` directory. Rebuild your native app after installing and syncing through any of these paths.
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
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

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

Pass the required `HTMLElement` itself, rather than its ID. Initial coordinates and dimensions are read from `getBoundingClientRect()`.

`InAppBrowser` is a shared singleton. Keep one active WebView and close it before opening another. Browser/PWA calls reject with `UNIMPLEMENTED`; guard native calls as shown above.

## Navigation and visibility

Use these controls after opening a WebView:

```typescript
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

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

`navigateBack()` and `navigateForward()` move through the existing WebView history. `hideWebView()` preserves the view; `closeWebView()` destroys it and removes listeners, layout observers and snapshot resources. Call close when leaving the screen that owns the container.

The default document policy allows HTTPS navigation at the initial origin. Configure exact additional origins before opening when redirects or login pages require them:

```typescript
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

async function openTrustedBrowser(element: HTMLElement): Promise<void> {
  await InAppBrowser.openWebView({
    url: 'https://example.com',
    element,
    navigationPolicy: {
      allowedOrigins: ['https://example.com', 'https://login.example.com'],
    },
  });
}
```

Popups, file/content URLs, active schemes and URL credentials are blocked. HTTP document navigation requires `allowHttp: true` and compatible host transport settings; it does not enable mixed content.

This is a **document policy, not network isolation**. Subresources and renderer-managed redirects can send requests before a document callback runs. In the Android emulator regression, a cross-origin HTTP 307 POST redirect sent its body before the final document was blocked. Avoid treating the origin list as a network firewall.

## Layout updates

The wrapper coalesces container resizing, captured scroll events, window/visual-viewport resizing and DOM layout notifications. Native attachment tracks the host WebView's position and size. Detached, zero-sized and offscreen containers hide the native view until usable again.

To force a remeasurement, call `updateDimensions()` without arguments. For explicit CSS bounds:

```typescript
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

async function syncBrowserBounds(element: HTMLElement): Promise<void> {
  const { width, height, x, y } = element.getBoundingClientRect();

  await InAppBrowser.updateDimensions({
    width: width,
    height: height,
    x: x,
    y: y,
  });
}
```

Send all four values on each manual update. Fractional CSS coordinates are preserved until native conversion. Viewport clipping is supported, but arbitrary DOM ancestor clipping, transforms, CSS stacking and continuous animation are not reproduced by a native overlay. Device visual checks are still needed for rotation, keyboards and app-specific insets.

## Page events

Register listeners once, before opening the WebView, to observe loading and errors:

```typescript
import { Capacitor } from '@capacitor/core';
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

async function registerBrowserEvents(): Promise<() => Promise<void>> {
  if (!Capacitor.isNativePlatform()) return async () => {};

  const loading = await InAppBrowser.onPageLoaded(({ isLoading }) => {
    console.log(isLoading ? 'Loading page' : 'Loading finished');
  });

  const errors = await InAppBrowser.onPageLoadError(({ errorCode }) => {
    console.error('WebView load error:', errorCode);
  });

  return async () => {
    await Promise.all([loading.remove(), errors.remove()]);
  };
}
```

Despite its name, `onPageLoaded()` reports both loading states. Error codes are platform-specific: Android reports WebView errors and HTTP status codes, while iOS reports native navigation error codes.

Every listener helper returns a `PluginListenerHandle`. Await `handle.remove()` to unsubscribe independently; concurrent or repeated removal is safe. Close also removes retained handles and suppresses queued callbacks. Register new listeners for a new session after close.

## Screenshots

`captureScreen(true)` captures the native view, displays the image as the container's background, and hides the live WebView. This lets HTML UI appear above a static representation of the page.

```typescript
import { InAppBrowser } from '@gerrithoskins/pixeltronic-webview-browser';

async function showSnapshot(): Promise<void> {
  await InAppBrowser.captureScreen(true);
}

async function resumeLiveView(): Promise<void> {
  await InAppBrowser.captureScreen(false);
}
```

Call this after opening and loading the page. The public method returns no image data. Its promise waits for image decoding and the native visibility switch. Captures use JPEG quality 85% with a maximum edge of 2048 pixels. `captureScreen(false)` restores the live view without recapturing, restores owned background styles and releases the object URL. Manual visibility, loading and layout visibility remain independent. A screenshot is static and cannot receive page interactions.

## Platform differences

| Feature                | iOS and Android behavior                                                                                                                                                                                                              |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `headers`              | Initial and explicit `loadUrl()` requests receive custom headers only at the initial origin; User-Agent applies to the session. Redirects, subresources, history and reload are platform-managed and have no custom-header guarantee. |
| `onNavigation()`       | Observes `allowed`, `reason`, `isMainFrame` and URL metadata after a native decision. Callbacks cannot stall or override it.                                                                                                          |
| `onUpdateDimensions()` | Reports applied layout changes as `{ width, height, x, y }` in CSS pixels.                                                                                                                                                            |
| Native overlay         | Appears above the host's HTML. Use a snapshot when HTML controls must cover it.                                                                                                                                                       |

The legacy `event.complete()` method always rejects with `UNIMPLEMENTED`. Configure `navigationPolicy` instead. Android disables file/content access, file-origin cross-origin access and mixed content.

## API reference

Import the public singleton as `InAppBrowser`. Commands return `Promise<void>`; the four listener helpers return `Promise<PluginListenerHandle>`.

| Method                                      | Purpose                                                                       |
| ------------------------------------------- | ----------------------------------------------------------------------------- |
| `openWebView(options)`                      | Create the native view and load a URL using the container's bounds            |
| `closeWebView()`                            | Destroy the current native view                                               |
| `showWebView()` / `hideWebView()`           | Change visibility without destroying the view                                 |
| `navigateBack()` / `navigateForward()`      | Navigate through page history                                                 |
| `loadUrl({ url })`                          | Load a URL in the existing view                                               |
| `reload()`                                  | Reload the current page                                                       |
| `updateDimensions({ width, height, x, y })` | Update the native view's bounds                                               |
| `onPageLoaded(callback)`                    | Observe `{ isLoading: boolean }`                                              |
| `onPageLoadError(callback)`                 | Observe `{ errorCode: number }`                                               |
| `onNavigation(callback)`                    | Observe `{ url, newWindowRequest, isSameHost, isMainFrame, allowed, reason }` |
| `onUpdateDimensions(callback)`              | Observe applied CSS bounds                                                    |
| `captureScreen(showScreenCapture)`          | Switch between a snapshot background and the live view                        |

### Open options

| Option             | Type                        | Behavior                                                                |
| ------------------ | --------------------------- | ----------------------------------------------------------------------- |
| `url`              | `string`                    | Required URL to load                                                    |
| `element`          | `HTMLElement`               | Required by the wrapper on native platforms; supplies the view's bounds |
| `headers`          | `{ [key: string]: string }` | Optional headers; see platform differences                              |
| `navigationPolicy` | `NavigationPolicy`          | Optional exact document origins and HTTP opt-in                         |

For the public types, see [src/definitions.ts](src/definitions.ts). DOM options are separate from internal, serializable native payloads.

## API migration

- `element` is required in `OpenOptions`; it was already required at runtime. Initial bounds come from that element.
- Manual `Dimensions` require all four fields. Omit the argument to remeasure. `ratio` and viewport metadata are internal.
- Listener registrations now return removable handles. Code that awaits and ignores the result continues to work.
- Unsupported `colorScheme` and initial bounds fields were removed from `OpenOptions`. The exported `ColorScheme` type remains deprecated for migration.
- Unimplemented native methods and `updateScreenCapture` were removed from declarations. `NativeInterface` is internal; use the singleton's public API.
- `NavigationEvent.complete()` and the raw `ScreenShot` type remain deprecated compatibility declarations.

These declaration changes can require updates to explicitly annotated consumer code. No package version was changed or release published as part of this cleanup.

## Development

```sh
npm ci
npm run verify:web
```

The web gate builds fresh bundles, runs wrapper regressions and strict declaration tests, checks ESLint, validates README examples/links, and checks package contents. The package check rejects missing entry points/native sources and accidental inclusion of Android test transport exceptions.

With Java 21, Android SDK 36 and a connected emulator/device:

```sh
npm run verify:android
```

This runs debug/release builds, JVM tests, lint with errors enforced, and instrumentation. `npm run verify:android:unit` omits instrumentation and reports that explicitly. The launcher supports Windows and Unix.

On macOS with Xcode, an installed iOS simulator runtime and CocoaPods:

```sh
npm run verify:ios
```

This builds the CocoaPods workspace and Swift package for iOS, then runs each test suite on an available iPhone simulator. Set `IOS_SIMULATOR_UDID` to select one. Result bundles are retained under `.build/release-gates/`. The command fails on unsupported hosts; it never reports skipped native checks as a pass.

`npm run verify:release` (also `npm run verify`) requires all web, Android and iOS gates to pass. It does not publish, commit or push. Native build scripts require their platform tools and do not transfer source to another machine. CocoaPods/SPM resolution may create local lockfiles; review these separately.

The current local evidence includes wrapper and Android tests. The iOS sources, tests and these Xcode commands remain **uncompiled and unrun** in the Windows-only validation environment. Android geometry and screenshot-dimension tests do not replace visual acceptance on devices.

This README is maintained manually; docgen produces `dist/docs.json` without replacing its sections. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.
