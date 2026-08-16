# Artificial Architect

AI に Minecraft のワールド情報を渡し、AI が生成した建築操作を安全にワールドへ反映する Forge 1.20.1 MOD です。

現在は PoC 段階で、Minecraft → `world.json` → AI → `actions.json` → Minecraft の往復が動作します。

## Requirements

- Minecraft 1.20.1
- Forge 47.4.22
- JDK 17 (64-bit)
- Windows PowerShell 5+ または PowerShell 7+
- 初回ビルド時のみインターネット接続

## Build

```powershell
.\gradlew build
```

または:

```powershell
.\build.ps1
```

生成物:

```text
build/libs/artificialarchitect-0.7.0.jar
```

## Usage

### 1. world.json を保存

建築基準位置に立って実行します。

```text
/architect dump 32
```

radius は 1〜128 です。Windows 標準の保存ダイアログが開きます。

サーバー側にも最新 snapshot の内部コピーが `.minecraft/artificialarchitect/world.json` として保存され、`actions.json` の snapshotId / origin / bounds / dimension 検証に使われます。

v0.6.0 以降は chunk section 単位で走査し、完全な air section を丸ごとスキップします。BlockState は palette ID に変換し、section snapshot の RLE 生成を最大8 workerで並列化します。Minecraft world 自体は server thread からのみ読みます。

v0.7.0 では AI 入力サイズを減らすため、schema v2 の `world.json` を minified JSON として出力し、同じ Y/Z 行で同じ BlockState が section 境界をまたいで連続する run を結合します。意味情報は削らず、palette / BlockState / bounds / origin は保持します。

### 2. AI に渡す

保存した `world.json` を AI に渡し、schema v1 の `actions.json` を生成します。

### 3. actions.json を読み込んで施工

```text
/architect apply
```

Windows 標準のファイル選択ダイアログから `actions.json` を選択します。全検証に成功した場合だけワールドへ反映されます。

互換用に `/aibridge` も同じコマンドとして使用できます。

## Chunked file-dialog bridge

v0.4.0 以降、JSON のネットワーク転送には gzip を使います。v0.7.0 から `world.json` は gzip 後のデータを最大 900,000 bytes ごとに分割して複数の S2C packet で送信し、client で再結合してから展開します。

```text
/architect dump <radius>
server: section scan → palette/RLE snapshot
        ↓
minified JSON → gzip
        ↓
900 KB 以下の chunks に分割
        ↓
S2C: chunk 0, 1, 2, ...
        ↓
client: 再結合 → gzip展開 → Windows 保存ダイアログ

/architect apply
server: 読み込み要求
        ↓
client: actions.json 選択 → gzip圧縮
        ↓
C2S: compressed actions.json
        ↓
server: gzip展開 → 検証 → 施工
```

world snapshot の制限:

- 展開後 JSON: 最大 128 MiB
- gzip 全体: 最大 32 MiB
- 1 chunk: 最大 900,000 bytes
- 最大 64 chunks

`actions.json` は従来通り展開後 16 MiB / gzip 1,800,000 bytes 上限です。

`/architect dump` の成功メッセージには palette 数、結合後 run 数、section 内 run 数、section 数、air skip 数、worker 数、raw/gzip サイズ、chunk 数、各処理時間が表示されます。

## world.json schema v2

world snapshot は palette + X方向RLE形式です。`defaultBlock` は `minecraft:air` なので、`runs` に存在しない座標は air です。

実際の v0.7.0 ファイルは AI 向けに改行・indent なしで保存されます。読みやすく整形すると次の形です。

```json
{
  "schema": 2,
  "snapshotId": "...",
  "dimension": "minecraft:overworld",
  "facing": "north",
  "origin": [100, 64, 100],
  "bounds": {"min": [-32, -32, -32], "max": [32, 32, 32]},
  "defaultBlock": "minecraft:air",
  "encoding": "palette-rle-x-v1",
  "runFormat": "[x,y,z,length,paletteIndex], length advances +X",
  "palette": [
    {"block": "minecraft:stone"},
    {"block": "minecraft:oak_stairs", "state": {"facing": "east", "half": "bottom"}}
  ],
  "runs": [
    [-32, -10, -32, 65, 0],
    [4, 0, -2, 1, 1]
  ]
}
```

`runs` の各要素は `[x, y, z, length, paletteIndex]` です。座標は origin からの相対座標で、`length` は +X 方向に同じ palette entry が何ブロック続くかを表します。v0.7.0 では連続していれば chunk/section 境界をまたいで1 runに結合できます。

palette entry は block ID と任意の BlockState property を持ちます。バニラだけでなく Forge registry に登録された MOD block/state も同じ形式です。

## actions.json schema v1

`actions.json` は schema 1 のままです。world schema 1 / 2 のどちらから生成しても、最新 snapshot metadata で検証します。

```json
{
  "schema": 1,
  "snapshotId": "world.json と同じ値",
  "actions": [
    {"type": "set", "p": [0, 1, 0], "block": "minecraft:oak_stairs", "state": {"facing": "north"}},
    {"type": "fill", "from": [-3, 0, -3], "to": [3, 0, 3], "block": "minecraft:stone_bricks"}
  ]
}
```

### Supported actions

- `set` — 1ブロック設置
- `fill` — 直方体を同一 BlockState で一括設置

### Validation before apply

- world schema が 1 または 2
- actions schema が 1
- `snapshotId` が最新 `world.json` と一致
- dimension が一致
- dump 範囲内のみ
- block ID / BlockState property / value が有効
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
- 極端にランダムな BlockState 配置では RLE 効果が小さくなる
- AI API との自動接続は未実装

## Planned direction

- stairs / wall / line など高レベル建築 primitive
- door / bed など複数ブロック構造の補助
- undo
- AI API / agent bridge

## License

MIT
