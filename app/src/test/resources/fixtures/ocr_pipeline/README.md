# OCR / 气泡夹具（P1）

目录：`app/src/test/resources/fixtures/ocr_pipeline/`

每个 JSON 描述一条 **OCR 块 → Paddle 合并 → 气泡归组** 的场景。测试入口：

`app/src/test/java/com/manga/translator/app/OcrPipelineFixtureTest.kt`

## 夹具列表

| 文件 | 场景 | 期望 |
|---|---|---|
| `01_seam_half_line.json` | 内部切缝把一行切成两半 | 合并阶段保持 2 块，不跨缝 |
| `02_seam_same_side_merge.json` | 切缝同侧的两行 | 合并成 1 段 |
| `03_ocr_near_duplicate.json` | 正确两行 + 错字单行两版 | 只留正确的一份 |
| `04_same_bubble_offset_segments.json` | 同气泡错位两段、两个重叠 region | 保持 2 个气泡（不按容器合并） |
| `05_bubble_outside_continuation.json` | 气泡内逗号收尾 + 气泡外下一行 | 合回 1 个气泡 |
| `06_two_paragraphs_one_region.json` | 同 region 两段（空行分隔） | 拆成 2 个气泡 |
| `07_adjacent_solid_bubbles_stay_separate.json` | 相邻不重叠的实底气泡 + 逗号 | 保持 2 个气泡 |

## JSON 字段

- `imageWidth` / `imageHeight`：坐标参考图尺寸。
- `seamTopPx` / `seamBottomPx`：拼接图内部切缝（0 = 无）。
- `mergeWithPaddle`：是否先跑 `PaddleOcrBlockMerger`；`false` 表示块已经是最终 OCR 结果，只验证归组。
- `blocks`：`id/text/left/top/right/bottom/confidence/lineCount`。
- `regions`：`id/left/top/right/bottom/type/confidence/source/parentId?`。
- `expected`：
  - `mergedTextCount` / `mergedTextContains` / `mergedTextNotContains`
  - `bubbleCount` / `bubbleTexts` / `outsideTexts`

## 新增场景

1. 在目录里加 `NN_name.json`。
2. 在 `index.txt` 补一行文件名。
3. 跑：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.manga.translator.app.OcrPipelineFixtureTest" --offline
```

不需要改测试代码；失败信息会带夹具名和具体断言。
