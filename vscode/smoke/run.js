// The extension as it ships, in a real VS Code: unpacks the .vsix that `npm run package` made, starts VS Code with
// that folder as the extension under test, and runs suite.js inside it. Once on the oldest VS Code the extension
// supports (engines.vscode) and once on the current stable, unless SMOKE_VSCODE names one version.
//
//   npm run smoke                     after npm run package; on Linux without a display, under xvfb-run -a
//
// Needs unzip (macOS, Linux) or PowerShell (Windows) to unpack the .vsix. VS Code downloads into .vscode-test/.
'use strict';

const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { runTests } = require('@vscode/test-electron');

const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const vsix = path.join(root, `duo-watchdog-${pkg.version}.vsix`);

function unpack(file, into) {
    if (process.platform === 'win32') {
        execFileSync('powershell', ['-NoProfile', '-Command',
            `Expand-Archive -LiteralPath '${file}' -DestinationPath '${into}' -Force`], { stdio: 'inherit' });
    } else {
        execFileSync('unzip', ['-q', file, '-d', into], { stdio: 'inherit' });
    }
}

async function main() {
    if (!fs.existsSync(vsix)) {
        throw new Error(`${path.basename(vsix)} is not there; run npm run package first`);
    }
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'duo-watchdog-smoke-'));
    try {
        unpack(vsix, work);
        const extension = path.join(work, 'extension');
        // An empty folder to open, and settings that keep VS Code quiet.
        const workspace = path.join(work, 'workspace');
        fs.mkdirSync(workspace);
        const oldest = String(pkg.engines.vscode).replace(/^[\^~>=\s]+/, '');
        const versions = process.env.SMOKE_VSCODE ? [process.env.SMOKE_VSCODE] : [oldest, 'stable'];
        for (const version of versions) {
            console.log(`\nDuo Watchdog ${pkg.version} from ${path.basename(vsix)} in VS Code ${version}`);
            await runTests({
                version,
                extensionDevelopmentPath: extension,
                extensionTestsPath: path.join(__dirname, 'suite.js'),
                // A fresh profile each run, in a short path: VS Code's socket in it must fit 103 characters on macOS.
                launchArgs: [workspace, '--disable-extensions', '--disable-workspace-trust', '--skip-welcome',
                    '--skip-release-notes', `--user-data-dir=${path.join(work, `ud-${version}`)}`,
                    `--extensions-dir=${path.join(work, `ext-${version}`)}`],
                extensionTestsEnv: { DUO_WATCHDOG_SMOKE_VERSION: pkg.version },
            });
        }
    } finally {
        fs.rmSync(work, { recursive: true, force: true });
    }
}

main().catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
});
