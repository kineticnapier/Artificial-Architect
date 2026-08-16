# Artificial Architect

AI に Minecraft のワールド情報を渡し、AI が生成した建築操作を安全にワールドへ反映する Forge 1.20.1 MOD です。

現在は最小 PoC で、Minecraft → `world.json` → AI → `actions.json` → Minecraft の往復が動作します。

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
build/libs/artificialarchitect-0.1.0.jar
```

## Usage

1. jar を Forge 1.20.1 の `mods` に入れる。
2. ワールドで建築基準位置に立つ。
3. 周囲を JSON に出力する。

```text
/architect dump 8
```

4. `.minecraft/artificialarchitect/world.json` を AI に渡す。
5. AI が生成した JSON を `.minecraft/artificialarchitect/actions.json` として保存する。
6. ワールドへ反映する。

```text
/architect apply
```

初期 PoC との互換用に `/aibridge` も同じコマンドとして使用できます。

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

- block state 未対応
- block entity / inventory / entity は dump しない
- undo 未実装
- `set` / `fill` の重複座標も placement 数に含む
- AI API との自動接続は未実装（現状は JSON を手動で受け渡す）

## Planned direction

- block state support
- stairs / slabs / doors などの向き付き建築
- undo
- AI API / agent bridge
- より大きい範囲を効率よく扱う world representation

## License

MIT
