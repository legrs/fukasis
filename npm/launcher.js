#!/usr/bin/env node
// SPDX-License-Identifier: MIT
// npm で配る fukasis の起動役. optionalDependencies で一緒に入る機種別パッケージ
// (@fksgeo/fukasis-bin-*) から実行ファイルを探して起動する.
// esbuild や swc, Biome と同じやり方.
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const s = (platform) => `@fksgeo/fukasis-bin-${platform}`;

// pkg は既定 (Linux では glibc 版), muslPkg は musl 版 (Alpine など).
// 32 bit ARM の v6Pkg / v6MuslPkg は ARMv6 版 (Raspberry Pi 1 / Zero).
// os / cpu だけでは ARMv6 と ARMv7, glibc と musl を区別できず複数入ることがあるので, ここで選ぶ.
const PLATFORMS = {
  'darwin arm64': { pkg: s('darwin-arm64') },
  'darwin x64': { pkg: s('darwin-x64') },
  'win32 x64': { pkg: s('win32-x64') },
  'win32 arm64': { pkg: s('win32-arm64') },
  'win32 ia32': { pkg: s('win32-ia32') },
  'linux x64': { pkg: s('linux-x64'), muslPkg: s('linux-x64-musl') },
  'linux arm64': { pkg: s('linux-arm64'), muslPkg: s('linux-arm64-musl') },
  'linux ia32': { pkg: s('linux-ia32'), muslPkg: s('linux-ia32-musl') },
  'linux arm': {
    pkg: s('linux-arm'),
    muslPkg: s('linux-arm-musl'),
    v6Pkg: s('linux-armv6'),
    v6MuslPkg: s('linux-armv6-musl'),
  },
  'linux riscv64': { pkg: s('linux-riscv64'), muslPkg: s('linux-riscv64-musl') },
  // 配っているのはリトルエンディアン (ppc64le) 版だけ
  'linux ppc64': { pkg: s('linux-ppc64'), littleEndianOnly: true },
  'linux s390x': { pkg: s('linux-s390x') },
  'linux loong64': { pkg: s('linux-loong64') },
  'android arm64': { pkg: s('android-arm64') },
  'android arm': { pkg: s('android-arm') },
  'android x64': { pkg: s('android-x64') },
  'android ia32': { pkg: s('android-ia32') },
  'freebsd x64': { pkg: s('freebsd-x64') },
  'freebsd ia32': { pkg: s('freebsd-ia32') },
  'netbsd x64': { pkg: s('netbsd-x64') },
  'sunos x64': { pkg: s('sunos-x64') },
};

// Node の process.report には, glibc 版の Node なら glibc のバージョンが入っている (musl 版には無い).
// process.report が無い古い Node では ldd の中身で判断する.
function isMusl() {
  if (process.platform !== 'linux') return false;
  if (!process.report || typeof process.report.getReport !== 'function') {
    try {
      return fs.readFileSync('/usr/bin/ldd', 'utf8').includes('musl');
    } catch (e) {
      return false;
    }
  }
  const { glibcVersionRuntime } = process.report.getReport().header;
  return !glibcVersionRuntime;
}

// ARMv6 の機械では ARMv7 版は動かない. Node はビルドされた ARM のバージョンを覚えている
function isArmV6() {
  return process.arch === 'arm' && String(process.config.variables.arm_version) === '6';
}

function resolveFromPkg(pkg, exe) {
  try {
    return require.resolve(`${pkg}/bin/${exe}`);
  } catch (e) {
    // node_modules/fukasis/bin/ から見た node_modules/<pkg>/bin/
    const local = path.join(__dirname, '..', '..', ...pkg.split('/'), 'bin', exe);
    return fs.existsSync(local) ? local : null;
  }
}

// 試すパッケージを, 合うものから順に並べる.
// musl 版は静的リンクなので glibc の機械でも動く. glibc 版が入っていないときの控えにする
function candidatesFor(entry, onMusl, armV6) {
  const glibc = armV6 ? [entry.v6Pkg] : [entry.pkg, entry.v6Pkg];
  const musl = armV6 ? [entry.v6MuslPkg] : [entry.muslPkg, entry.v6MuslPkg];
  return (onMusl ? [...musl, ...glibc] : [...glibc, ...musl]).filter(Boolean);
}

// 起動する実行ファイルを { path, pkg, muslFallback } で返す. 見つからなければ { error }.
// muslFallback は「musl の機械なのに glibc 版しか見つからなかった」とき true.
// その glibc 版はたいてい起動できず, ただの ENOENT になるので, 呼び出し側で説明を足す.
function findBinary({
  platform = process.platform,
  arch = process.arch,
  endianness = os.endianness(),
  musl = isMusl,
  armV6 = isArmV6,
  resolve = resolveFromPkg,
} = {}) {
  const key = `${platform} ${arch}`;
  const entry = PLATFORMS[key];
  if (!entry || (entry.littleEndianOnly && endianness !== 'LE')) {
    return {
      error:
        `fukasis: this platform (${key}) has no prebuilt binary on npm.\n` +
        'See https://github.com/igarinpiano/fukasis/releases for more builds, or build from source: cargo install fukasis',
    };
  }
  const exe = platform === 'win32' ? 'fukasis.exe' : 'fukasis';
  const onMusl = Boolean(entry.muslPkg) && musl();
  const candidates = candidatesFor(entry, onMusl, arch === 'arm' && armV6());
  const muslPkgs = [entry.muslPkg, entry.v6MuslPkg].filter(Boolean);
  for (const pkg of candidates) {
    const resolved = resolve(pkg, exe);
    if (resolved) return { path: resolved, pkg, muslFallback: onMusl && !muslPkgs.includes(pkg) };
  }
  return {
    error:
      `fukasis: could not find the binary package (${candidates.join(' / ')}).\n` +
      'Try reinstalling: npm install -g fukasis (without --omit=optional / --no-optional).',
  };
}

function launchErrorMessage(found, error, arch = process.arch) {
  let msg = `fukasis: failed to launch: ${error.message}`;
  if (found.muslFallback && error.code === 'ENOENT') {
    const muslPkg = PLATFORMS[`linux ${arch}`] && PLATFORMS[`linux ${arch}`].muslPkg;
    msg +=
      `\nfukasis: this looks like a musl system (Alpine, etc.), but only the glibc build (${found.pkg}) is installed,` +
      ' and it cannot run without glibc.' +
      `\nInstall the musl build: npm install -g ${muslPkg}`;
  }
  return msg;
}

function main() {
  const found = findBinary();
  if (found.error) {
    console.error(found.error);
    process.exit(1);
  }
  let result = spawnSync(found.path, process.argv.slice(2), { stdio: 'inherit' });
  // 実行権限が落ちていたら付け直してもう一度
  if (result.error && result.error.code === 'EACCES') {
    try {
      fs.chmodSync(found.path, 0o755);
      result = spawnSync(found.path, process.argv.slice(2), { stdio: 'inherit' });
    } catch (e) {
      // 下でもとのエラーを表示する
    }
  }
  if (result.error) {
    console.error(launchErrorMessage(found, result.error));
    process.exit(1);
  }
  process.exit(result.status === null ? 1 : result.status);
}

if (require.main === module) {
  main();
}

module.exports = { PLATFORMS, findBinary, launchErrorMessage };
