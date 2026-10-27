# Contributing

This guide provides instructions for contributing to this Capacitor plugin.

## Developing

### Local Setup

1. Fork and clone the repo.
1. Install the dependencies.

   ```shell
   npm install
   ```

1. Install SwiftLint if you're on macOS.

   ```shell
   brew install swiftlint
   ```

### Scripts

#### `npm run build`

Build the plugin web assets and generate plugin API documentation using [`@capacitor/docgen`](https://github.com/ionic-team/capacitor-docgen).

It will compile the TypeScript code from `src/` into ESM JavaScript in `dist/esm/`. These files are used in apps with bundlers when your plugin is imported.

Then, Rollup will bundle the code into a single file at `dist/plugin.js`. This file is used in apps without bundlers by including it as a script in `index.html`.

#### `npm run verify`

Build and validate the web and native projects.

This runs the release gates and fails unless web, Android and iOS verification all complete. It requires macOS with Xcode/CocoaPods, Java 21, Android SDK 36 and a connected Android emulator/device. It does not publish or transfer source to another host.

Use `npm run verify:web` for the build, wrapper tests, public declaration tests, ESLint, package contents and README examples. Use `npm run verify:android` for native builds, JVM tests, enforced lint and instrumentation. `npm run verify:android:unit` omits instrumentation explicitly. Use `npm run verify:ios` for CocoaPods and Swift Package Manager builds and simulator tests; select an iPhone simulator with `IOS_SIMULATOR_UDID` when necessary.

Keep actual test output and iOS `.xcresult` bundles as acceptance evidence. A command that is unavailable, skipped or not executed is incomplete validation, not a pass. Device geometry tests do not substitute for visual acceptance of rotation, keyboard and host insets. See [README.md](README.md#development) for prerequisites and remaining validation limitations.

#### `npm run lint` / `npm run fmt`

Check formatting and code quality, autoformat/autofix if possible.

This template is integrated with ESLint, Prettier, and SwiftLint. Using these tools is completely optional, but the [Capacitor Community](https://github.com/capacitor-community/) strives to have consistent code style and structure for easier cooperation.

## Publishing

Publishing is a separate, explicitly authorized operation. First run `npm run verify:release`, review API migration and versioning, and inspect the package. The `prepublishOnly` hook builds assets but does not replace native acceptance gates. Only after those checks and release authorization, publish with:

```shell
npm publish
```

> **Note**: The [`files`](https://docs.npmjs.com/cli/v7/configuring-npm/package-json#files) array in `package.json` specifies which files get published. If you rename files/directories or add files elsewhere, you may need to update it.
