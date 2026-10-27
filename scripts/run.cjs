const { spawnSync } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

function run(command, args, options = {}) {
  const capture = options.capture === true;
  // Only fixed npm/Gradle command names use the Windows command interpreter.
  const batch = process.platform === 'win32' && (command === 'npm' || command === 'gradlew.bat');
  if (batch && ![command, ...args].every(value => /^[a-zA-Z0-9_:./=-]+$/.test(value))) {
    throw new Error('Only fixed, shell-safe npm and Gradle arguments are accepted.');
  }
  const executable = batch ? process.env.ComSpec || 'cmd.exe' : command;
  const parameters = batch ? ['/d', '/s', '/c', [command, ...args].join(' ')] : args;
  const result = spawnSync(executable, parameters, {
    cwd: options.cwd || root, stdio: capture ? 'pipe' : 'inherit', encoding: 'utf8', shell: false,
  });
  if (result.error || result.status !== 0) {
    if (capture) process.stderr.write(result.stderr || result.stdout || '');
    throw new Error(`${command} failed${result.error ? ': ' + result.error.message : ' with exit code ' + result.status}`);
  }
  return result.stdout;
}
module.exports = { root, run };
