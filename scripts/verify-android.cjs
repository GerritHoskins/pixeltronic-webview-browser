const path = require('node:path');
const { root, run } = require('./run.cjs');
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--unit-only')) throw new Error('Usage: verify-android.cjs [--unit-only]');
const tasks = ['build', 'test', 'lint'];
if (!args.includes('--unit-only')) tasks.push('connectedDebugAndroidTest');
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [
  ...tasks, '-PbrowserReleaseGate=true', '--console=plain',
], { cwd: path.join(root, 'android') });
console.log(args.includes('--unit-only') ? 'PASS: Android build, lint and JVM tests. Instrumentation was not run.' : 'PASS: Android build, lint, JVM and connected-device tests.');
