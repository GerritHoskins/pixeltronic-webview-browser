const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { root, run } = require('./run.cjs');
if (process.platform !== 'darwin') {
  console.error('INCOMPLETE: iOS verification requires macOS, Xcode and CocoaPods. No iOS build or test was run.');
  process.exit(1);
}
run('xcodebuild', ['-version']);
run('pod', ['--version']);
const devices = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', 'available', '--json'], { capture: true }));
const available = Object.values(devices.devices).flat().filter(device => device.isAvailable && device.name.startsWith('iPhone'));
const requested = process.env.IOS_SIMULATOR_UDID;
const simulator = requested ? available.find(device => device.udid === requested) :
  available.find(device => device.state === 'Booted') || available[0];
if (!simulator) throw new Error('No available iPhone simulator. Install an iOS runtime or select IOS_SIMULATOR_UDID.');
const destination = `platform=iOS Simulator,id=${simulator.udid}`;
const output = path.join(root, '.build', 'release-gates', randomUUID());
run('pod', ['install'], { cwd: path.join(root, 'ios') });
const pods = ['-workspace', 'ios/Plugin.xcworkspace'];
run('xcodebuild', [...pods, '-scheme', 'Plugin', '-configuration', 'Release', '-destination', 'generic/platform=iOS',
  '-derivedDataPath', path.join(output, 'pods'), 'CODE_SIGNING_ALLOWED=NO', 'build']);
run('xcodebuild', [...pods, '-scheme', 'PluginTests', '-destination', destination,
  '-derivedDataPath', path.join(output, 'pods'), '-resultBundlePath', path.join(output, 'pods.xcresult'), 'CODE_SIGNING_ALLOWED=NO', 'test']);
// Run in the package root so Xcode discovers Package.swift independently of the CocoaPods project.
const spm = ['-scheme', 'PixeltronicWebviewBrowser', '-derivedDataPath', path.join(output, 'spm')];
run('xcodebuild', [...spm, '-configuration', 'Release', '-destination', 'generic/platform=iOS', 'CODE_SIGNING_ALLOWED=NO', 'build']);
run('xcodebuild', [...spm, '-destination', destination, '-resultBundlePath', path.join(output, 'spm.xcresult'), 'CODE_SIGNING_ALLOWED=NO', 'test']);
console.log(`PASS: CocoaPods and SPM builds and simulator tests. Results: ${output}`);
