# 開発の流れ (ブランチ・PR・公開)

変更を 2 つのリポジトリ (legrs / fksgeoscience) に PR として出し、PC 用ツール (web / cli) を公開するまでの流れです。
公開そのものの細かい手順は [releasing.md](./releasing.md)、アプリ・web 版・CLI 版の違いは [platforms.md](./platforms.md) にあります。

## リポジトリとブランチの役割

| リポジトリ | 役割 |
|---|---|
| `legrs/fukasis` | フォーク元 |
| `fksgeoscience/fukasis` | チームのリポジトリ。開発の起点にする |
| `igarinpiano/fukasis` | 作業用のフォーク。PR はここのブランチから出し、公開もここから行う |

`igarinpiano/fukasis` のブランチ:

| ブランチ | 役割 |
|---|---|
| `release` (既定) | 公開用。npm / crates.io / GitHub Releases はここから出す。push すると web 版 (GitHub Pages) が更新される |
| `master` | `legrs/fukasis` の master と同じ状態に保つ。ここでは開発しない |
| `feature/○○` など | 変更ごとに 1 本。PR の元になる |

手元の remote の名前は、`origin` = igarinpiano、`fks` = fksgeoscience、`upstream` = legrs です。

## 決まり

1. **変更は必ずブランチで行い、`fks/master` から作る。** `release` や `master` に直接コミットしない。
2. **そのブランチを、PR として 2 つのリポジトリに出し、同じブランチを `release` にマージする。**
3. **マージは常に「Create a merge commit」。squash と rebase は使わない。**
   3 つのリポジトリで同じコミットを共有しているので、コミットを作り直す方法でマージすると履歴が食い違い、
   次の PR に関係のない差分が出たり、「マージできない」と表示されたりします。

## 1. 変更を作って PR を出す

```bash
git fetch --all
git switch -c feature/○○ fks/master
```

変更をコミットして push します。push すると `igarinpiano/fukasis` の CI が動きます。

```bash
git push -u origin feature/○○
```

CI が通ったら PR を出します。

```bash
gh pr create -R fksgeoscience/fukasis --base master --head igarinpiano:feature/○○
gh pr create -R legrs/fukasis --base master --head igarinpiano:feature/○○
```

- 同じブランチから 2 つの PR を出せます。ブランチに push すると両方の PR が同時に更新されます。
- レビューやマージは PR ごとに別々です。片方がマージされても、もう片方は open のまま残ります。
- まだどこにもマージされていない別の変更に依存するときは、そのブランチから作ります。
  その場合、元の変更の差分も PR に表示されます (元の変更が先にマージされれば消えます)。

### 「マージできない」と表示されたら

中身が衝突していなくても、履歴のつながり方が食い違うと GitHub がそう表示することがあります
(ある PR が、別の PR の内容を先に含んでいて、その別の PR が先にマージされた場合など)。
PR 先の master を、PR のブランチに取り込めば直ります。ファイルの中身は変わりません。

```bash
git switch feature/○○
git merge fks/master
git push
```

## 2. `release` に入れる

```bash
git switch release
git pull
git merge feature/○○
git push
```

- `web/` を変えていれば、この push で <https://igarinpiano.github.io/fukasis/> が更新されます (`pages` ワークフロー)。
  web 版だけの変更なら、ここで終わりです。
- fksgeoscience や legrs で PR がマージされたあとは、その master を `release` に取り込んでおきます。
  履歴がつながり、次の PR がきれいになります。

  ```bash
  git fetch --all
  git switch release
  git merge fks/master
  git push
  ```

## 3. 公開する (npm / crates.io / GitHub Releases)

`cli/` や `npm/` を変えたときに行います。

1. バージョンを上げる変更も、ほかの変更と同じようにブランチで作ります (`fks/master` から)。
   上げるのは `cli/Cargo.toml`、`cli/Cargo.lock`、`web/package.json` の 3 か所で、同じ番号にします。
2. そのブランチを `release` にマージして push し、CI (`PC tools`、`CI`) が通るのを待ちます。
3. Actions → `publish-all` → Run workflow を、ブランチは `release`、全部にチェックを入れたまま実行します。
   `version` の欄に番号を入れておくと、取り違えを防げます。10 分ほどで終わります。
4. 公開された番号を確かめます。公開直後は npm の反映が数分遅れることがあります。

   ```bash
   npm view fukasis version
   ```

5. バージョンを上げたブランチも、PR として 2 つのリポジトリに出します。

npm と crates.io に公開した番号は取り消せません。同じ番号では二度と公開できないので、公開の前に CI が通っていることを確かめます。

## 4. 自分宛の PR を取り込む

- **できれば fksgeoscience 宛に出してもらいます。** マージされたら、上の「`release` に入れる」のとおり
  `fks/master` を `release` にマージするだけで済みます。
- **`igarinpiano/fukasis` 宛に来た場合**は、`release` に「Create a merge commit」でマージします。
  そのあと、同じ内容をブランチにして 2 つのリポジトリへ PR を出し、揃えます。

  ```bash
  gh pr checkout <番号> -R igarinpiano/fukasis
  git switch -c feature/○○
  git push -u origin feature/○○
  ```

## いまの状態 (2026-10-10 時点。片づいたらこの節は消す)

上の流れがそのまま使えるようになるまで、あと 2 つ残っています。

- **`release` の履歴が、PR 側のブランチとまだつながっていません。** 中身は同じですが別系統で、
  変更のたびに同じコミットを両方へ入れて揃えています。fksgeoscience の PR 6 がマージされたら、
  `fks/master` を `release` にマージしてつなぎます (マージしても中身が変わらないことは確認済み)。
  **PR 6 がマージされる前に `fks/master` を `release` にマージしてはいけません** (中身が変わってしまいます)。
- **legrs は、PR 9 がマージされるまで、どの PR にも全部の変更が載ります。** それまでは新しい PR を出さず、
  PR 9 のブランチ (`feature/brightness-rotation-i18n`) に足していきます。legrs でマージするのは PR 9 だけです。

## 確認に使うもの

| ワークフロー | いつ動くか | 何を確かめるか |
|---|---|---|
| `CI` | push / PR | Android アプリのビルド・単体テスト・lint、C++ / Swift のテスト、iOS のビルド |
| `PC tools` | `web/` `cli/` `npm/` `testdata/` を変えたとき | web と cli のテスト (Linux / macOS / Windows) |
| `build-check` | ビルドの設定や `npm/` を変えたとき | 全ターゲットのビルドと、npm パッケージの組み立て |
| `pages` | `release` の `web/` を変えたとき | web 版を公開ページに載せる |
| `publish-all` | 手動 | 公開 |

Android アプリは手元ではビルドしていません。確認は CI で行います。

計算を変えたとき (アプリの共通コア `core/`、`cli/`、`web/` のどれか) は、3 つの出力が一致することをテストで確かめています。
正解データの作り直しは [testdata/README.md](../testdata/README.md) を参照してください。
