# Artificial Architect

AI に Minecraft のワールド情報を渡し、AI が生成した建築操作を安全にワールドへ反映する Forge 1.20.1 MOD です。

現在は PoC 段階で、Minecraft → `world.json` → AI → `actions.json` → Minecraft の往復が動作します。

## Requirements

- Minecraft 1.20.1
- Forge 47.4.22
- JDK 17 (64-bit)
- Windows PowerShell 5+ または PowerShell 7+
- 初回ビルド時のみインターネット接続

Gradle の事前インストールは不要です。`gradlew` が Gradle 8.8 を `.gradle-bootstrap/` に取得します。

## Build

PowerShell:

```powershell
.\gradlew build
```

または:

```powershell
.\build.ps1
```

生成物:

```text
build/libs/artificialarchitect-0.5.0.jar
```

## Usage

### 1. world.json を保存

建築基準位置に立って次を実行します。

```text
/architect dump 8
```

クライアント側で Windows 標準の保存ダイアログが開くので、`world.json` を好きな場所に保存します。初期ディレクトリは通常 `Downloads` です。

サーバー側にも最新 snapshot の内部コピーが `.minecraft/artificialarchitect/world.json` として保存されます。これは `actions.json` の `snapshotId`、origin、bounds、dimension の検証に使われます。

v0.5.0 では dump の hot path を軽量化しています。`BlockPos.MutableBlockPos` を再利用し、同一 `BlockState` の registry ID と property serialization をキャッシュし、巨大な Gson DOM を構築せず `JsonWriter` で直接 JSON を生成します。成功メッセージには build / write / gzip+send / total の時間と、観測した unique BlockState 数も表示されます。

### 2. AI に渡す

保存した `world.json` を AI に渡し、schema v1 の `actions.json` を生成します。

### 3. actions.json を読み込んで施工

```text
/architect apply
```

クライアント側で Windows 標準のファイル選択ダイアログが開くので、AI が生成した `actions.json` を選択します。

選択した JSON 本文がサーバーへ送られ、既存の検証をすべて通過した場合のみワールドへ反映されます。Prism Launcher の instance フォルダへ手作業でファイルをコピーする必要はありません。

初期 PoC との互換用に `/aibridge` も同じコマンドとして使用できます。

## File-dialog bridge

Forge の SimpleChannel を使って、ファイルダイアログとワールド操作をクライアント/サーバー間で分離しています。Minecraft/Prism の JVM は AWT headless になる場合があるため、v0.2.1 以降では AWT `FileDialog` を使わず、Windows PowerShell の `System.Windows.Forms.SaveFileDialog` / `OpenFileDialog` を呼び出します。

v0.4.0 から、`world.json` と `actions.json` のネットワーク転送は UTF-8 JSON を gzip 圧縮した byte array で行います。保存されるファイル自体は従来通り通常の `.json` です。

```text
/architect dump <radius>
server: world snapshot 作成
        ↓
gzip圧縮
        ↓
S2C: compressed world.json
        ↓
client: gzip展開 → Windows 保存ダイアログ

/architect apply
server: 読み込み要求
        ↓
S2C: ファイルダイアログを開く
        ↓
client: actions.json 選択・読み込み → gzip圧縮
        ↓
C2S: compressed actions.json
        ↓
server: gzip展開 → 検証 → 施工
```

転送制限は、展開後 JSON が最大 16 MiB、gzip 圧縮後 payload が最大 1,800,000 bytes です。gzip 展開時も 16 MiB を超えた時点で拒否するため、異常に大きく展開される payload は受理しません。

`/architect dump` の成功メッセージには、展開前と gzip 圧縮後の転送サイズが表示されます。

## world.json

プレイヤー位置を `origin` とし、ブロックは相対座標で格納します。`blocks` に存在しない座標は `minecraft:air` として扱います。

v0.3.0 から、BlockState property を持つブロックには `state` が含まれます。バニラだけでなく、Forge registry に登録されている MOD ブロックも同じ形式で出力されます。

```json
{
  "p": [4, 0, -2],
  "block": "minecraft:oak_stairs",
  "state": {
    "facing": "east",
    "half": "bottom",
    "shape": "straight",
    "waterlogged": "false"
  }
}
```

主な情報:

- schema version
- snapshot ID
- dimension
- player facing
- origin
- dump bounds
- non-air blocks
- block state properties

## actions.json schema v1

`state` は任意です。省略した場合は、そのブロックの default BlockState が使われます。

```json
{
  "schema": 1,
  "snapshotId": "world.json と同じ値",
  "actions": [
    {
      "type": "set",
      "p": [0, 1, 0],
      "block": "minecraft:oak_stairs",
      "state": {
        "facing": "north",
        "half": "bottom",
        "shape": "straight",
        "waterlogged": "false"
      }
    },
    {
      "type": "fill",
      "from": [-3, 0, -3],
      "to": [3, 0, 3],
      "block": "minecraft:stone_bricks"
    }
  ]
}
```

`state` の property 名と値は、そのブロックの StateDefinition に存在するものだけ受理されます。存在しない property や不正な値は施工前に reject されます。

### Supported actions

- `set` — 1ブロック設置
- `fill` — 直方体を同一 BlockState で一括設置

### Validation before apply

- `schema == 1`
- `snapshotId` が最新 `world.json` と一致
- dimension が一致
- dump 範囲内のみ
- block ID が Forge registry に存在
- BlockState property が対象ブロックに存在
- BlockState value が対象 property の許可値に含まれる
- 1回最大4096 placements
- world border / build height 内
- 対象 chunk がロード済み
- 全 action の検証成功後に施工

## Current limitations

- file dialog は現在 Windows のみ対応
- block entity / inventory / entity は dump しない
- door / bed など複数ブロックで1構造になるものは自動で相方を生成しない
- neighbor update によって state がMinecraft側で再計算されるブロックがある
- undo 未実装
- `set` / `fill` の重複座標も placement 数に含む
- 展開後 JSON は最大 16 MiB、gzip payload は最大 1,800,000 bytes
- AI API との自動接続は未実装

## Planned direction

- stairs / wall / line など高レベル建築 primitive
- door / bed など複数ブロック構造の補助
- undo
- chunked transfer for snapshots that still exceed the gzip payload limit
- AI API / agent bridge
- より大きい範囲を効率よく扱う world representation

## License

MIT
