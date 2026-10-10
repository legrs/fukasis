#!/usr/bin/env node
// SPDX-License-Identifier: MIT
// リリース用の実行ファイルから npm のパッケージを組み立てる. 依存パッケージは無い.
//
//   node npm/assemble.js --version X.Y.Z --binaries <dir> --out <dir> [--docs <dir>]
//
// 入力: <binaries>/<ターゲット>/fukasis(.exe)
// 出力: <out>/fukasis/ (小さな起動役. launcher.js) と,
//       ターゲットごとの <out>/@fksgeo/fukasis-bin-<機種>/ (optionalDependencies で入る).
// --docs は README.md と LICENSE を取るリポジトリの場所 (既定はこのリポジトリ).
'use strict';

const fs = require('fs');
const path = require('path');
const { TARGETS } = require('./targets.js');

const MAIN = 'fukasis';
const DESCRIPTION =
  'Process spectra captured with FUKASIS-app on a PC: dark subtraction, wavelength calibration, CSV export and SVG graphs';
// npm の provenance は, ここがビルドしたリポジトリと一致していることを確かめる
const REPOSITORY = { type: 'git', url: 'git+https://github.com/igarinpiano/fukasis.git' };
const HOMEPAGE = 'https://github.com/igarinpiano/fukasis';
const LICENSE = 'MIT';

function parseArgs(argv) {
  const opts = {};
  for (let i = 0; i < argv.length; i += 2) {
    const name = argv[i];
    if (!['--version', '--binaries', '--out', '--docs'].includes(name) || argv[i + 1] === undefined) {
      throw new Error('usage: assemble.js --version X.Y.Z --binaries <dir> --out <dir> [--docs <dir>]');
    }
    opts[name.slice(2)] = argv[i + 1];
  }
  for (const required of ['version', 'binaries', 'out']) {
    if (!opts[required]) throw new Error(`--${required} is required`);
  }
  return opts;
}

function writeJson(file, value) {
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
}

function assemble({ version, binaries, out, docs }) {
  const root = docs || path.join(__dirname, '..');
  const license = path.join(root, 'LICENSE');
  const optional = {};
  const skipped = [];

  for (const [target, t] of Object.entries(TARGETS)) {
    const exe = t.os === 'win32' ? 'fukasis.exe' : 'fukasis';
    const src = path.join(binaries, target, exe);
    if (!fs.existsSync(src)) {
      skipped.push(target);
      continue;
    }
    const dir = path.join(out, ...t.pkg.split('/'));
    fs.mkdirSync(path.join(dir, 'bin'), { recursive: true });
    fs.copyFileSync(src, path.join(dir, 'bin', exe));
    // Actions の成果物を通すと実行権限が落ちるので付け直す
    fs.chmodSync(path.join(dir, 'bin', exe), 0o755);
    fs.copyFileSync(license, path.join(dir, 'LICENSE'));
    const manifest = {
      name: t.pkg,
      version,
      description: `fukasis binary for ${target}`,
      repository: REPOSITORY,
      homepage: HOMEPAGE,
      license: LICENSE,
      os: [t.os],
      cpu: [t.cpu],
      files: ['bin/', 'LICENSE'],
    };
    if (t.libc) manifest.libc = [t.libc];
    writeJson(path.join(dir, 'package.json'), manifest);
    optional[t.pkg] = version;
  }
  if (Object.keys(optional).length === 0) throw new Error(`no binaries found in ${binaries}`);

  const main = path.join(out, MAIN);
  fs.mkdirSync(path.join(main, 'bin'), { recursive: true });
  fs.copyFileSync(path.join(__dirname, 'launcher.js'), path.join(main, 'bin', 'fukasis.js'));
  fs.chmodSync(path.join(main, 'bin', 'fukasis.js'), 0o755);
  fs.copyFileSync(path.join(root, 'cli', 'README.md'), path.join(main, 'README.md'));
  fs.copyFileSync(license, path.join(main, 'LICENSE'));
  writeJson(path.join(main, 'package.json'), {
    name: MAIN,
    version,
    description: DESCRIPTION,
    keywords: ['spectroscopy', 'astronomy', 'spectrum', 'calibration', 'tiff', 'cli'],
    repository: REPOSITORY,
    homepage: HOMEPAGE,
    bugs: 'https://github.com/igarinpiano/fukasis/issues',
    license: LICENSE,
    bin: { fukasis: 'bin/fukasis.js' },
    files: ['bin/', 'README.md', 'LICENSE'],
    engines: { node: '>=16' },
    optionalDependencies: optional,
  });
  return { main: MAIN, packages: Object.keys(optional), skipped };
}

if (require.main === module) {
  try {
    const result = assemble(parseArgs(process.argv.slice(2)));
    for (const target of result.skipped) console.log(`skip ${target} (no binary)`);
    console.log(`assembled: ${result.main} + ${result.packages.length} platform packages`);
  } catch (e) {
    console.error(`assemble.js: ${e.message}`);
    process.exit(1);
  }
}

module.exports = { assemble };
