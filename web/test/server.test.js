// SPDX-License-Identifier: MIT
// bin/fukasis-web.js (この PC の中だけで配信するサーバー) のテスト.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { handle } = require('../bin/fukasis-web.js');

function get(port, path) {
  return new Promise((resolve, reject) => {
    http
      .get({ host: '127.0.0.1', port, path }, (res) => {
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => (body += chunk));
        res.on('end', () => resolve({ status: res.statusCode, type: res.headers['content-type'], body }));
      })
      .on('error', reject);
  });
}

test('ページ本体だけを配信する', async (t) => {
  const server = http.createServer(handle);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const port = server.address().port;

  const index = await get(port, '/');
  assert.strictEqual(index.status, 200);
  assert.match(index.type, /^text\/html/);
  assert.match(index.body, /js\/core\.js/);

  const core = await get(port, '/js/core.js');
  assert.strictEqual(core.status, 200);
  assert.match(core.type, /^text\/javascript/);

  assert.strictEqual((await get(port, '/css/style.css')).status, 200);

  // ページに要らないファイルや, 外へ出ようとするパスは出さない
  for (const path of ['/package.json', '/bin/fukasis-web.js', '/js/../package.json', '/js/%2e%2e/package.json', '/js/..%2fpackage.json', '/nothing.html']) {
    assert.strictEqual((await get(port, path)).status, 404, path);
  }
});
