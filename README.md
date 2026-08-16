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
build/libs/artificialarchitect-0.2.1.jar
```

## Usage

### 1. world.json を保存

建築基準位置に立って次を実行します。

```text
/architect dump 8
```

クライアント側で Windows 標準の保存ダイアログが開くので、`world.json` を好きな場所に保存します。初期ディレクトリは通常 `Downloads` です。

サーバー側にも最新 snapshot の内部コピーが `.minecraft/artificialarchitect/world.json` として保存されます。これは `actions.json` の `snapshotId`、origin、bounds、dimension の検証に使われます。

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

Forge の SimpleChannel を使って、ファイルダイアログとワールド操作をクライアント/サーバー間で分離しています。Minecraft/Prism の JVM は AWT headless になる場合があるため、v0.2.1 では AWT `FileDialog` を使わず、Windows PowerShell の `System.Windows.Forms.SaveFileDialog` / `OpenFileDialog` を呼び出します。

```text
/architect dump <radius>
server: world snapshot 作成
        ↓
S2C: world.json 本文
        ↓
client: Windows 保存ダイアログ

/architect apply
server: 読み込み要求
        ↓
S2C: ファイルダイアログを開く
        ↓
client: actions.json 選択・読み込み
        ↓
C2S: JSON 本文
        ↓
server: 検証 → 施工
```

JSON のネットワーク転送には現在 900,000 文字の上限があります。大きすぎる `world.json` の場合は `dump` radius を小さくしてください。

## world.json

プレイヤー位置を `origin` とし、ブロックは相対座標で格納します。`blocks` に存在しない座標は `minecraft:air` として扱います。

主な情報:

- schema version
- snapshot ID
- dimension
- player facing
- origin
- dump bounds
- non-air blocks

## actions.json schema v1

```json
{
  "schema": 1,
  "snapshotId": "world.json と同じ値",
  "actions": [
    {
      "type": "set",
      "p": [0, 1, 0],
      "block": "minecraft:stone_bricks"
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

### Supported actions

- `set` — 1ブロック設置
- `fill` — 直方体を一括設置

### Validation before apply

- `schema == 1`
- `snapshotId` が最新 `world.json` と一致
- dimension が一致
- dump 範囲内のみ
- block ID が Forge registry に存在
- 1回最大4096 placements
- world border / build height 内
- 対象 chunk がロード済み
- 全 action の検証成功後に施工

## Current limitations

- file dialog は現在 Windows のみ対応
- block state 未対応
- block entity / inventory / entity は dump しない
- undo 未実装
- `set` / `fill` の重複座標も placement 数に含む
- JSON 転送上限 900,000 文字
- AI API との自動接続は未実装

## Planned direction

- block state support
- stairs / slabs / doors などの向き付き建築
- undo
- 大きな snapshot の圧縮転送
- AI API / agent bridge
- より大きい範囲を効率よく扱う world representation

## License

MIT
