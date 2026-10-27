const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { root, run } = require('./run.cjs');
const manifest = require('../package.json');
const [packed] = JSON.parse(run('npm', ['pack', '--dry-run', '--ignore-scripts', '--json'], { capture: true }));
const files = new Set(packed.files.map(file => file.path));
const required = [manifest.main, manifest.module, manifest.types, 'dist/plugin.js', 'Package.swift',
  'PixeltronicWebviewBrowser.podspec', 'ios/PluginTests/InAppBrowserTests.swift', 'README.md'];
for (const directory of ['ios/Plugin', 'android/src/main']) {
  for (const entry of fs.readdirSync(path.join(root, directory), { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) required.push(path.relative(root, path.join(entry.parentPath || entry.path, entry.name)).split(path.sep).join('/'));
  }
}
for (const file of required) assert(files.has(file), `Package is missing ${file}`);
for (const file of files) assert(!/(^|\/)(androidTest|test|node_modules|\.git)(\/|$)|network_security_config/.test(file), `Unexpected test-only or private package file: ${file}`);
assert(fs.statSync(path.join(root, manifest.main)).size > 0, 'Empty CommonJS entry');
console.log(`PASS: ${files.size} package files; entry points, native sources and SPM tests included; Android test transport exceptions excluded.`);
