// SPDX-License-Identifier: MIT
// npm で配る機種別パッケージの一覧. assemble.js が使い, launcher.js の表と一致することをテストで確かめる.
//
// キーは Rust のターゲット名. os / cpu は Node の process.platform / process.arch,
// libc は同じ CPU の glibc 版と musl 版を分けるための指定 (npm 9 以降が見る).
// 32 bit ARM の ARMv6 と ARMv7 は npm には区別できないので, launcher.js が実行時に選ぶ.
//
// ここに無いターゲット (Windows GNU, macOS universal, WASI など) は GitHub Releases だけで配る.
'use strict';

const SCOPE = '@fksgeo';
const pkg = (platform) => `${SCOPE}/fukasis-bin-${platform}`;

const TARGETS = {
  'aarch64-apple-darwin': { pkg: pkg('darwin-arm64'), os: 'darwin', cpu: 'arm64' },
  'x86_64-apple-darwin': { pkg: pkg('darwin-x64'), os: 'darwin', cpu: 'x64' },
  'x86_64-pc-windows-msvc': { pkg: pkg('win32-x64'), os: 'win32', cpu: 'x64' },
  'aarch64-pc-windows-msvc': { pkg: pkg('win32-arm64'), os: 'win32', cpu: 'arm64' },
  'i686-pc-windows-msvc': { pkg: pkg('win32-ia32'), os: 'win32', cpu: 'ia32' },
  'x86_64-unknown-linux-gnu': { pkg: pkg('linux-x64'), os: 'linux', cpu: 'x64', libc: 'glibc' },
  'x86_64-unknown-linux-musl': { pkg: pkg('linux-x64-musl'), os: 'linux', cpu: 'x64', libc: 'musl' },
  'aarch64-unknown-linux-gnu': { pkg: pkg('linux-arm64'), os: 'linux', cpu: 'arm64', libc: 'glibc' },
  'aarch64-unknown-linux-musl': { pkg: pkg('linux-arm64-musl'), os: 'linux', cpu: 'arm64', libc: 'musl' },
  'i686-unknown-linux-gnu': { pkg: pkg('linux-ia32'), os: 'linux', cpu: 'ia32', libc: 'glibc' },
  'i686-unknown-linux-musl': { pkg: pkg('linux-ia32-musl'), os: 'linux', cpu: 'ia32', libc: 'musl' },
  'armv7-unknown-linux-gnueabihf': { pkg: pkg('linux-arm'), os: 'linux', cpu: 'arm', libc: 'glibc' },
  'armv7-unknown-linux-musleabihf': { pkg: pkg('linux-arm-musl'), os: 'linux', cpu: 'arm', libc: 'musl' },
  'arm-unknown-linux-gnueabihf': { pkg: pkg('linux-armv6'), os: 'linux', cpu: 'arm', libc: 'glibc' },
  'arm-unknown-linux-musleabihf': { pkg: pkg('linux-armv6-musl'), os: 'linux', cpu: 'arm', libc: 'musl' },
  'riscv64gc-unknown-linux-gnu': { pkg: pkg('linux-riscv64'), os: 'linux', cpu: 'riscv64', libc: 'glibc' },
  'riscv64gc-unknown-linux-musl': { pkg: pkg('linux-riscv64-musl'), os: 'linux', cpu: 'riscv64', libc: 'musl' },
  'powerpc64le-unknown-linux-gnu': { pkg: pkg('linux-ppc64'), os: 'linux', cpu: 'ppc64', libc: 'glibc' },
  's390x-unknown-linux-gnu': { pkg: pkg('linux-s390x'), os: 'linux', cpu: 's390x', libc: 'glibc' },
  'loongarch64-unknown-linux-gnu': { pkg: pkg('linux-loong64'), os: 'linux', cpu: 'loong64', libc: 'glibc' },
  'aarch64-linux-android': { pkg: pkg('android-arm64'), os: 'android', cpu: 'arm64' },
  'armv7-linux-androideabi': { pkg: pkg('android-arm'), os: 'android', cpu: 'arm' },
  'x86_64-linux-android': { pkg: pkg('android-x64'), os: 'android', cpu: 'x64' },
  'i686-linux-android': { pkg: pkg('android-ia32'), os: 'android', cpu: 'ia32' },
  'x86_64-unknown-freebsd': { pkg: pkg('freebsd-x64'), os: 'freebsd', cpu: 'x64' },
  'i686-unknown-freebsd': { pkg: pkg('freebsd-ia32'), os: 'freebsd', cpu: 'ia32' },
  'x86_64-unknown-netbsd': { pkg: pkg('netbsd-x64'), os: 'netbsd', cpu: 'x64' },
  'x86_64-unknown-illumos': { pkg: pkg('sunos-x64'), os: 'sunos', cpu: 'x64' },
};

module.exports = { SCOPE, TARGETS };
