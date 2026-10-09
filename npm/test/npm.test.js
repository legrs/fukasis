// SPDX-License-Identifier: MIT
// npm/launcher.js と npm/assemble.js のテスト (パッケージには入らない).
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { PLATFORMS, findBinary, launchErrorMessage } = require('../launcher.js');
const { TARGETS } = require('../targets.js');
const { assemble } = require('../assemble.js');

const X64 = '@fksgeo/fukasis-bin-linux-x64';
const installed = (...pkgs) => (pkg, exe) => (pkgs.includes(pkg) ? `/nm/${pkg}/bin/${exe}` : null);
const linux = (musl, resolve, arch = 'x64') => ({ platform: 'linux', arch, endianness: 'LE', musl: () => musl, resolve });

test('glibc の機械では glibc 版を使う', () => {
  const f = findBinary(linux(false, installed(X64, `${X64}-musl`)));
  assert.strictEqual(f.pkg, X64);
  assert.strictEqual(f.muslFallback, false);
});

test('glibc の機械に musl 版しか無ければ musl 版を使う (静的リンクなので動く)', () => {
  const f = findBinary(linux(false, installed(`${X64}-musl`)));
  assert.strictEqual(f.pkg, `${X64}-musl`);
  assert.strictEqual(f.muslFallback, false);
});

test('musl の機械では musl 版を選ぶ', () => {
  const f = findBinary(linux(true, installed(X64, `${X64}-musl`)));
  assert.strictEqual(f.pkg, `${X64}-musl`);
});

test('musl の機械に glibc 版しか無いときは, そのことが分かる', () => {
  const f = findBinary(linux(true, installed(X64)));
  assert.strictEqual(f.pkg, X64);
  assert.strictEqual(f.muslFallback, true);
  const message = launchErrorMessage(f, Object.assign(new Error('spawn ENOENT'), { code: 'ENOENT' }), 'x64');
  assert.match(message, /musl system/);
  assert.match(message, /fukasis-bin-linux-x64-musl/);
});

test('ARMv6 の機械では ARMv7 版を選ばない', () => {
  const all = installed(...['arm', 'arm-musl', 'armv6', 'armv6-musl'].map((p) => `@fksgeo/fukasis-bin-linux-${p}`));
  const v6 = findBinary({ ...linux(false, all, 'arm'), armV6: () => true });
  assert.strictEqual(v6.pkg, '@fksgeo/fukasis-bin-linux-armv6');
  const v7 = findBinary({ ...linux(false, all, 'arm'), armV6: () => false });
  assert.strictEqual(v7.pkg, '@fksgeo/fukasis-bin-linux-arm');
});

test('Windows では .exe を探す', () => {
  const f = findBinary({ platform: 'win32', arch: 'x64', resolve: installed('@fksgeo/fukasis-bin-win32-x64') });
  assert.match(f.path, /fukasis\.exe$/);
});

test('対応していない機種やパッケージが無いときは理由を返す', () => {
  assert.match(findBinary(linux(false, installed())).error, /could not find the binary package/);
  assert.match(findBinary({ platform: 'aix', arch: 'ppc64' }).error, /no prebuilt binary/);
  // ppc64 はリトルエンディアン版だけ
  const be = findBinary({ ...linux(false, installed('@fksgeo/fukasis-bin-linux-ppc64'), 'ppc64'), endianness: 'BE' });
  assert.match(be.error, /no prebuilt binary/);
});

test('launcher.js の表と targets.js の一覧が一致している', () => {
  const fromLauncher = new Set();
  for (const [key, entry] of Object.entries(PLATFORMS)) {
    const [platform, arch] = key.split(' ');
    for (const field of ['pkg', 'muslPkg', 'v6Pkg', 'v6MuslPkg']) {
      if (!entry[field]) continue;
      fromLauncher.add(entry[field]);
      const target = Object.values(TARGETS).find((t) => t.pkg === entry[field]);
      assert.ok(target, `${entry[field]} が targets.js に無い`);
      assert.strictEqual(target.os, platform);
      assert.strictEqual(target.cpu, arch);
      assert.strictEqual(target.libc === 'musl', field.endsWith('MuslPkg') || field === 'muslPkg');
    }
  }
  const fromTargets = new Set(Object.values(TARGETS).map((t) => t.pkg));
  assert.deepStrictEqual([...fromLauncher].sort(), [...fromTargets].sort());
});

test('assemble.js: あるバイナリの分だけパッケージを作る', (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'fukasis-npm-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
  const binaries = path.join(tmp, 'binaries');
  for (const [target, exe] of [
    ['x86_64-unknown-linux-gnu', 'fukasis'],
    ['x86_64-unknown-linux-musl', 'fukasis'],
    ['x86_64-pc-windows-msvc', 'fukasis.exe'],
    ['wasm32-wasip1', 'fukasis.wasm'], // npm では配らない
  ]) {
    fs.mkdirSync(path.join(binaries, target), { recursive: true });
    fs.writeFileSync(path.join(binaries, target, exe), 'binary');
  }
  const out = path.join(tmp, 'out');
  const result = assemble({ version: '1.2.3', binaries, out });
  assert.strictEqual(result.packages.length, 3);

  const main = JSON.parse(fs.readFileSync(path.join(out, 'fukasis', 'package.json'), 'utf8'));
  assert.strictEqual(main.name, 'fukasis');
  assert.strictEqual(main.version, '1.2.3');
  assert.deepStrictEqual(main.optionalDependencies, {
    '@fksgeo/fukasis-bin-win32-x64': '1.2.3',
    '@fksgeo/fukasis-bin-linux-x64': '1.2.3',
    '@fksgeo/fukasis-bin-linux-x64-musl': '1.2.3',
  });
  assert.ok(fs.existsSync(path.join(out, 'fukasis', 'bin', 'fukasis.js')));
  assert.ok(fs.existsSync(path.join(out, 'fukasis', 'README.md')));

  const musl = path.join(out, '@fksgeo', 'fukasis-bin-linux-x64-musl');
  const manifest = JSON.parse(fs.readFileSync(path.join(musl, 'package.json'), 'utf8'));
  assert.deepStrictEqual([manifest.os, manifest.cpu, manifest.libc], [['linux'], ['x64'], ['musl']]);
  assert.ok(fs.existsSync(path.join(musl, 'LICENSE')));
  if (process.platform !== 'win32') {
    assert.ok(fs.statSync(path.join(musl, 'bin', 'fukasis')).mode & 0o111, '実行権限が付いている');
  }
  assert.ok(fs.existsSync(path.join(out, '@fksgeo', 'fukasis-bin-win32-x64', 'bin', 'fukasis.exe')));

  assert.throws(() => assemble({ version: '1.2.3', binaries: path.join(tmp, 'none'), out }), /no binaries found/);
});
