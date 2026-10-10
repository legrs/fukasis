# PC 用ツール (web / cli) のリリース手順

`web/` と `cli/` を、GitHub Releases・npm・crates.io で配布するための手順です。
リリースは **igarinpiano/fukasis** で行います (`publish-all` はほかのリポジトリでは止まります)。

最初のバージョン 0.1.0 は 2026-10-09 に公開済みです
([GitHub Releases](https://github.com/igarinpiano/fukasis/releases)・[crates.io](https://crates.io/crates/fukasis)・[npm](https://www.npmjs.com/package/fukasis))。
次に出すときは「[2 回目以降のリリース](#2-回目以降のリリース)」の手順です。

アプリ本体 (APK) のリリースは `v*` タグ、PC 用ツールは `pc-v*` タグで、別々に行います。

ブランチの使い方や、2 つのリポジトリへ PR を出す流れは [development.md](./development.md) にあります。

## 配布物

| 配布先 | 名前 | 中身 |
|---|---|---|
| GitHub Releases | `fukasis-<バージョン>-<ターゲット>.tar.gz` / `.zip` | コマンドライン版の実行ファイル (全ターゲット) |
| GitHub Pages | <https://igarinpiano.github.io/fukasis/> | web 版 (開くだけで使える。`release` ブランチの最新) |
| GitHub Releases | `fukasis-web-<バージョン>.zip` | web 版一式 (展開して `index.html` を開く) |
| GitHub Releases | `SHA256SUMS` | 上のファイルのハッシュ値 |
| crates.io | `fukasis` | コマンドライン版 (`cargo install fukasis`) とライブラリ |
| npm | `fukasis` | 親パッケージ (`npm install -g fukasis`)。合う機種別パッケージを選んで起動する |
| npm | `@fksgeo/fukasis-bin-<機種>` | 機種別の実行ファイル。親パッケージの `optionalDependencies` で入る |

crates.io は 1 クレートだけです (`cli/` は外部クレートに依存しておらず、ライブラリと実行ファイルが同じクレートに入っています)。

## 対応する機種

ビルドするターゲットは [reusable-build-matrix.yml](../.github/workflows/reusable-build-matrix.yml) に、
そのうち npm で配るものは [npm/targets.js](../npm/targets.js) にあります。

- **必須** (失敗するとリリースが止まる): macOS (arm64 / x64)、Windows (x64 / arm64)、Linux (x64 / arm64 の glibc 版と musl 版)
- **任意** (失敗してもその機種が配られないだけ): 32 bit の Windows / Linux、ARMv5〜v7、RISC-V、PowerPC、s390x、SPARC、LoongArch、Android (Termux)、FreeBSD、NetBSD、illumos、WebAssembly (WASI) など

Windows GNU 版、macOS universal 版、WASI 版、ビッグエンディアンの PowerPC などは GitHub Releases だけで配ります。

ターゲットを足すときは、`reusable-build-matrix.yml` に 1 行足し、npm でも配るなら `npm/targets.js` と `npm/launcher.js` の表にも足します
(2 つの表がそろっていることはテストで確かめています)。`build-check` ワークフローで、何も公開せずに全ターゲットをビルドできます。

## ワークフロー

| ワークフロー | 役割 |
|---|---|
| `PC tools` | push / PR ごとのテスト |
| `build-check` | 全ターゲットをビルドし、npm のパッケージを組み立てて実際に入れてみる。何も公開しない |
| `publish-all` | リリース。GitHub Releases → npm → crates.io の順。手動実行のみ |
| `pages` | web 版を <https://igarinpiano.github.io/fukasis/> に載せる。`release` ブランチの `web/` が変わるたびに動く |

`publish-all` と `build-check` の手動実行は、ワークフローのファイルが既定のブランチに入っているリポジトリで使えます (igarinpiano/fukasis の既定のブランチは `release`)。

## 最初のリリース

0.1.0 で実施済みです。npm の各パッケージの Trusted Publishing も登録してあります。
まだなら、下の 7 (crates.io の Trusted Publishing) と 8 (Environment) を済ませてください。どちらも `publish-all` から npm / crates.io へ公開するのに必要です。
以下は記録と、やり直すときのための手順です。

npm と crates.io の Trusted Publishing (GitHub Actions からトークンなしで公開する仕組み) は、**もう存在するパッケージにしか設定できません**。
そのため最初の 1 回だけ、手元から公開します。

1. **npm に組織 `fksgeo` を作ります** (npmjs.com → Add Organization)。`@fksgeo/fukasis-bin-*` の置き場所です。
2. **`cli/Cargo.toml` と `web/package.json` の `version` をそろえて** `release` ブランチに入れます (`Cargo.lock` も更新します)。
3. **GitHub Release を作ります。** Actions → `publish-all` → Run workflow で、「Publish to GitHub Releases」だけにチェックを入れて実行します。
   タグ `pc-v<バージョン>` と Release ができ、実行ファイルが付きます。
4. **手元でログインします。**

   ```bash
   cargo login
   ```

   ```bash
   npm login
   ```

5. **まず予行演習をします** (何も公開しません)。

   ```bash
   scripts/first-publish.sh 0.1.0 --dry-run
   ```

6. **公開します。** `--trust` を付けると、公開した npm の各パッケージに Trusted Publishing も登録します。

   ```bash
   scripts/first-publish.sh 0.1.0 --trust
   ```

   途中で止まっても、もう一度実行すれば続きから進みます (公開済みのものは飛ばします)。

7. **crates.io に Trusted Publishing を登録します。** crates.io → `fukasis` → Settings → Trusted Publishing:
   owner `igarinpiano` / repo `fukasis` / workflow `publish-all.yml` / environment `crates-io`
8. **リポジトリに Environment を作ります。** Settings → Environments で `npm` と `crates-io` を作ります
   (承認が要るように設定しておくと、公開の直前に確認できます)。

crates.io も npm も、**公開したバージョンは取り消せません** (crates.io は `cargo yank` で新規の利用を止められるだけ、npm も 72 時間を過ぎると原則削除できません)。
同じバージョン番号は二度と使えません。

## 2 回目以降のリリース

1. `cli/Cargo.toml` と `web/package.json` の `version` を上げ、`cli/` で `cargo build` して `Cargo.lock` を更新し、`release` ブランチに入れます。
2. Actions → `publish-all` → Run workflow を、ブランチは `release`、全部にチェックを入れたまま実行します。

`version` の欄にバージョンを入れておくと、`cli/Cargo.toml` と違うときに止まります (取り違えの防止)。
`ref` にタグを入れると、そのタグの内容をビルドします (古いリリースに実行ファイルを足すときなど)。

## あとから機種を足したとき

新しい機種の npm パッケージはまだ存在しないので、`publish-all` の npm の段は、ほかを全部公開したうえでその名前を挙げて失敗します。
一度だけ手元から公開してください (公開済みのものは飛ばされます)。

```bash
scripts/first-publish.sh 0.1.1 --npm-only --trust
```

npm の段が失敗すると crates.io の段は飛ばされるので、`publish-all` を「Publish to crates.io」だけにしてもう一度実行します。
