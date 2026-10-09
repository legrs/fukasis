#!/usr/bin/env node
// SPDX-License-Identifier: MIT
// web 版をこの PC の中だけで配信する小さなサーバー. 依存パッケージは無い.
//   fukasis-web [--port <番号>] [--open]
'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const ROOT = path.join(__dirname, '..');
const DEFAULT_PORT = 8377;
// 配信するのはページ本体だけ (パッケージの他のファイルは出さない)
const SERVED = /^\/(index\.html|css\/[\w.-]+\.css|js\/[\w.-]+\.js)$/;
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
};

function usage() {
  console.log('usage: fukasis-web [--port <number>] [--open]');
}

function parseArgs(argv) {
  const opts = { port: DEFAULT_PORT, portGiven: false, open: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--open') {
      opts.open = true;
    } else if (a === '--port' || a === '-p') {
      const port = Number(argv[++i]);
      if (!Number.isInteger(port) || port < 0 || port > 65535) {
        console.error('fukasis-web: --port needs a number between 0 and 65535');
        process.exit(2);
      }
      opts.port = port;
      opts.portGiven = true;
    } else if (a === '--help' || a === '-h') {
      usage();
      process.exit(0);
    } else if (a === '--version' || a === '-V') {
      console.log(require('../package.json').version);
      process.exit(0);
    } else {
      console.error(`fukasis-web: unknown option ${a}`);
      usage();
      process.exit(2);
    }
  }
  return opts;
}

function handle(req, res) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch (e) {
    pathname = '';
  }
  if (pathname === '/') pathname = '/index.html';
  if ((req.method !== 'GET' && req.method !== 'HEAD') || !SERVED.test(pathname)) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('not found\n');
    return;
  }
  fs.readFile(path.join(ROOT, pathname), (err, body) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found\n');
      return;
    }
    res.writeHead(200, {
      'Content-Type': TYPES[path.extname(pathname)],
      'Content-Length': body.length,
      'Cache-Control': 'no-cache',
    });
    res.end(req.method === 'HEAD' ? undefined : body);
  });
}

function openBrowser(url) {
  const [cmd, args] =
    process.platform === 'darwin'
      ? ['open', [url]]
      : process.platform === 'win32'
        ? ['cmd', ['/c', 'start', '', url]]
        : ['xdg-open', [url]];
  // ブラウザを開けなくても, URL は表示してあるので続ける
  spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref();
}

function start(opts) {
  const server = http.createServer(handle);
  server.on('error', (err) => {
    // 既定のポートが使われていたら, 空いているポートを OS に選んでもらう
    if (err.code === 'EADDRINUSE' && !opts.portGiven) {
      start({ ...opts, port: 0, portGiven: true });
      return;
    }
    console.error(`fukasis-web: ${err.message}`);
    process.exit(1);
  });
  // この PC の外からはつながらないよう, 127.0.0.1 だけで待ち受ける
  server.listen(opts.port, '127.0.0.1', () => {
    const url = `http://127.0.0.1:${server.address().port}/`;
    console.log(`FUKASIS for PC: ${url}`);
    console.log('Ctrl+C to stop');
    if (opts.open) openBrowser(url);
  });
  return server;
}

if (require.main === module) {
  start(parseArgs(process.argv.slice(2)));
}

module.exports = { handle };
