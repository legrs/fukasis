#!/usr/bin/env bash
# PC 用ツールを crates.io と npm へ, 手元から初めて公開するためのスクリプト。
# 2 回目以降のリリースは .github/workflows/publish-all.yml が Trusted Publishing で行う。
# Trusted Publishing は「もう存在するパッケージ」にしか設定できないので, 最初だけこれを使う。
#
# 公開するもの:
#   crates.io  fukasis
#   npm        @fksgeo/fukasis-bin-<機種> (GitHub Release の実行ファイルから作る), fukasis (親パッケージ)
#
# 何度実行してもよい。そのバージョンがもう公開されているものは飛ばすので, 途中で止まったときの
# やり直しや, あとから機種を足したときの初回公開 (--npm-only) にも使える。
# 1 つ失敗しても残りは続け, 最後に結果をまとめて表示する。
# 親パッケージ fukasis は, 機種別パッケージが全部そろってから最後に公開する (--main で強制できる)。
#
# 事前に必要なこと:
#   - GitHub Release pc-v<バージョン> があり, fukasis-<バージョン>-<ターゲット> の実行ファイルが付いている
#     (publish-all を「GitHub Releases」だけ選んで実行する)。
#   - この PC で `cargo login` (crates.io のトークン) と `npm login` を済ませている。
#   - npmjs.com に組織 `fksgeo` があり, ログインしたユーザーがそこへ公開できる。
#   - gh, node, npm, cargo, curl が使える。
#
# crates.io と npm は, 公開したバージョンを取り消せない (同じ番号では二度と公開できない)。
# まず --dry-run で確かめること。
#
# 使い方: scripts/first-publish.sh <バージョン> [オプション]
#   --dry-run          何も公開せず, 公開されるものを確かめる
#   --crates-only      crates.io だけ
#   --npm-only         npm だけ
#   --trust            公開した npm パッケージに, このリポジトリの publish-all.yml (environment `npm`) を
#                      Trusted Publishing として登録する (`npm trust`, npm 11.10 以降)
#   --skip NAME        その npm パッケージを飛ばす (何度でも指定できる)
#   --main             機種別パッケージがそろっていなくても親パッケージ fukasis を公開する
#   --binaries DIR     GitHub Release から取らずに, DIR/<ターゲット>/fukasis(.exe) を使う
#   --ref REF          タグ pc-v<バージョン> の代わりに, その ref の内容を公開する (確認用)
set -uo pipefail

USAGE="usage: scripts/first-publish.sh <version> [--dry-run] [--crates-only|--npm-only] [--trust] [--skip NAME]... [--main] [--binaries DIR] [--ref REF]"
VERSION="${1:?$USAGE}"
shift
DRY=""; DO_CRATES=1; DO_NPM=1; TRUST=0; FORCE_MAIN=0; BINARIES=""; REF=""; SKIP=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY="--dry-run" ;;
    --crates-only) DO_NPM=0 ;;
    --npm-only) DO_CRATES=0 ;;
    --trust) TRUST=1 ;;
    --main) FORCE_MAIN=1 ;;
    --skip) SKIP+=("${2:?--skip needs a package name}"); shift ;;
    --binaries) BINARIES="${2:?--binaries needs a directory}"; shift ;;
    --ref) REF="${2:?--ref needs a git ref}"; shift ;;
    *) echo "unknown option $1" >&2; echo "$USAGE" >&2; exit 2 ;;
  esac
  shift
done

REPO=igarinpiano/fukasis
TAG="pc-v$VERSION"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'git -C "$ROOT" worktree remove --force "$WORK/src" 2>/dev/null || true; rm -rf "$WORK"' EXIT

REPORT=()
FAILED=0
report() { REPORT+=("$1"); }
summary() {
  echo
  echo "== summary"
  printf '  %s\n' "${REPORT[@]+"${REPORT[@]}"}"
}

set -e
if [ -z "$REF" ]; then
  git -C "$ROOT" fetch --tags --quiet
  git -C "$ROOT" rev-parse --verify --quiet "refs/tags/$TAG" > /dev/null \
    || { echo "tag $TAG not found — create the GitHub Release first (publish-all with only \"GitHub Releases\")" >&2; exit 1; }
  REF="$TAG"
fi
# 作業ツリーではなく, タグの内容そのものを公開する
git -C "$ROOT" worktree add --quiet --detach "$WORK/src" "$REF"
SRC="$WORK/src"
have="$(grep -m1 '^version' "$SRC/cli/Cargo.toml" | sed -E 's/version = "(.*)"/\1/')"
[ "$have" = "$VERSION" ] || { echo "cli/Cargo.toml at $REF says $have, not $VERSION" >&2; exit 1; }
set +e

# ---------------------------------------------------------------- crates.io

crate_published() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' -H "User-Agent: fukasis-first-publish (+https://github.com/$REPO)" \
    "https://crates.io/api/v1/crates/fukasis/$VERSION")" = 200 ]
}

if [ "$DO_CRATES" = 1 ]; then
  if crate_published; then
    report "already published  fukasis $VERSION (crates.io)"
  else
    echo "== crates.io: fukasis $VERSION"
    # ビルドの出力は, 終わったら消える作業用の場所に置く
    if (cd "$SRC/cli" && CARGO_TARGET_DIR="$WORK/target" cargo publish --locked $DRY); then
      report "published          fukasis $VERSION (crates.io)${DRY:+ (dry run)}"
    else
      report "FAILED             fukasis $VERSION (crates.io) — see the cargo error above"
      FAILED=1
    fi
  fi
fi

# ---------------------------------------------------------------- npm

npm_published() { npm view "$1@$VERSION" version > /dev/null 2>&1; }
skipped() {
  local s
  for s in "${SKIP[@]+"${SKIP[@]}"}"; do [ "$s" = "$1" ] && return 0; done
  return 1
}

# 次のリリースから publish-all.yml が OIDC で公開できるようにする
trust() {
  [ "$TRUST" = 1 ] && [ -z "$DRY" ] || return 0
  if npm trust github "$1" --file publish-all.yml --repo "$REPO" --env npm --allow-publish --yes; then
    report "trusted publisher  $1"
  else
    report "NO trusted publisher for $1 — see the npm error above (\`npm trust list $1\` shows what is registered)"
    FAILED=1
  fi
}

# 公開できたら (もう公開されていたら) 0. まだ registry に無いままなら 1
publish_dir() {
  local dir="$1" name
  name="$(node -p "require('$dir/package.json').name")"
  if npm_published "$name"; then
    report "already published  $name@$VERSION"
    return 0
  fi
  if skipped "$name"; then
    report "skipped            $name"
    return 1
  fi
  echo
  echo "== npm publish $name@$VERSION"
  if (cd "$dir" && npm publish --access public $DRY); then
    report "published          $name@$VERSION${DRY:+ (dry run)}"
    [ -n "$DRY" ] && return 1 # dry run では registry に無いまま
    trust "$name"
    return 0
  fi
  report "FAILED             $name@$VERSION — see the npm error above"
  FAILED=1
  return 1
}

if [ "$DO_NPM" = 1 ]; then
  # ログインしていないと, 新しいパッケージの公開は紛らわしい「404 Not Found」になる
  if [ -z "$DRY" ]; then
    who="$(npm whoami 2>/dev/null)" || { echo "npm: not logged in (or the session expired). Run \`npm login\` and try again." >&2; summary; exit 1; }
    echo "== npm user: $who"
  fi

  if [ -z "$BINARIES" ]; then
    echo "== npm: downloading the release binaries of $TAG"
    BINARIES="$WORK/binaries"
    mkdir -p "$WORK/assets" "$WORK/x" "$BINARIES"
    gh release download "$TAG" -R "$REPO" -D "$WORK/assets" -p "fukasis-$VERSION-*" \
      || { echo "could not download the assets of release $TAG" >&2; summary; exit 1; }
    for a in "$WORK/assets"/*; do
      base="$(basename "$a")"
      target="${base#fukasis-$VERSION-}"; target="${target%.tar.gz}"; target="${target%.zip}"
      mkdir -p "$BINARIES/$target"
      case "$a" in
        *.zip) unzip -q -o "$a" -d "$WORK/x" ;;
        *.tar.gz) tar -xzf "$a" -C "$WORK/x" ;;
        *) continue ;;
      esac
      # README.md や LICENSE ではなく, 実行ファイルだけを取り出す
      cp "$WORK/x/fukasis-$VERSION-$target"/fukasis* "$BINARIES/$target/"
    done
  fi

  # パッケージを組み立てる道具はこのチェックアウトのもの, README と LICENSE はタグのものを使う
  node "$ROOT/npm/assemble.js" --version "$VERSION" --binaries "$BINARIES" --out "$WORK/npm" --docs "$SRC" \
    || { echo "could not assemble the npm packages" >&2; summary; exit 1; }

  # 親パッケージの optionalDependencies が解決できるよう, 機種別パッケージを先に公開する
  missing=0
  for dir in "$WORK/npm"/@fksgeo/*; do
    [ -d "$dir" ] || continue
    publish_dir "$dir" || missing=$((missing + 1))
  done
  if [ "$missing" -eq 0 ] || [ "$FORCE_MAIN" = 1 ] || [ -n "$DRY" ]; then
    publish_dir "$WORK/npm/fukasis" || true
  else
    report "held back          fukasis@$VERSION — $missing platform package(s) are not on the registry yet (--main publishes it anyway)"
    FAILED=1
  fi
fi

summary
if [ -z "$DRY" ]; then
  echo
  echo "Next: add Trusted Publishing (see the header of .github/workflows/publish-all.yml)."
  echo "  crates.io: fukasis — owner igarinpiano / repo fukasis / workflow publish-all.yml / environment crates-io"
  [ "$TRUST" = 1 ] || echo "  npmjs.com: fukasis and every @fksgeo/fukasis-bin-* — workflow publish-all.yml / environment npm (or run this again with --trust)"
fi
exit "$FAILED"
