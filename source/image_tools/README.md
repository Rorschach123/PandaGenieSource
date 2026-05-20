# Image Tools Module 接入说明

## 图片来源区分

图片扫描 API 现在明确区分两种来源：

- 相册范围：用户说“相册、图库、默认相册、相机胶卷、全部相册、某个相册”时使用 `album`，例如 `album=default`、`album=all`、`album=Screenshots`。
- 具体图片范围：用户选择、附加或指定一张/多张图片时使用 `imagePaths`。传入 `imagePaths` 后模块只扫描这些图片，并忽略 `album`。

支持的具体图片参数别名：`imagePaths`、`images`、`paths`、`inputPaths`、`files`、`imagePath`、`path`。

示例：

```json
{
  "module": "image_tools",
  "action": "findSimilarImages",
  "params": {
    "imagePaths": ["${input_file_1}", "${input_file_2}"],
    "maxDistance": 16
  }
}
```

不要把“这张图、选中的图片、附件图片”的请求改成 `album=default`。只有用户明确要扫描默认相册时才使用 `album=default`。

## 相似照片分析能力

`findSimilarImages` 用于扫描默认相册、全部相册或指定相册，按视觉相似度把照片分组，并给出每组的保留建议和可复核候选。模块只输出结构化分析结果，不直接删除照片。

典型提示词：

- 扫描默认相册，找出相似照片，按组展示，告诉我每组建议保留哪一张，先不要删除。
- Scan all albums, group similar photos, show suggested keeps and review candidates, do not delete automatically.

## APP 与模块责任边界

APP 通用能力：

- 识别 `_richContent.type = image_groups`。
- 在 APP 内打开分组详情页，逐组展示建议保留图和候选图。
- 展示缩略图、文件名、尺寸、大小、相似度、置信度、标签和原因。
- 统一提供文件选择能力：候选项勾选、全选、清空选择、已选数量统计。
- 统一提供文件删除能力：删除前弹确认框，只有用户明确确认后才删除。
- 统一处理系统删除与媒体库刷新，避免相册残留。
- 这些选择/删除按钮属于 APP 通用文件操作能力，不由模块在 HTML、copyable 或其他富内容中自定义输出。

模块能力：

- 根据用户意图读取 `album`、`limit`、`maxDistance` 等参数。
- 使用 `dHash64 + aHash64 + color` 生成视觉指纹。
- 输出 `candidateGroups` 和 `_richContent.image_groups`。
- 为每组输出 `keepSuggestion`、`candidates`、`confidence`、`matchType`、`reason`。
- 为每张图片输出 `deleteRecommendation`、`recommendationLabel`、`tags`，帮助 APP 给普通用户展示可理解的判断与可选择删除候选。
- 模块只输出列表信息、建议保留项、可复核候选和标签；不要额外输出“全选/删除/复制候选路径”等操作按钮。
- 模块不得自动删除照片，`safeToDeleteAutomatically` 必须保持 `false`。

## findSimilarImages 参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `album` | string | `default` | 相册范围。`default` 表示默认相册或相机胶卷，`all` 表示全部相册，也可以传具体相册名。 |
| `limit` | number | `5000` | 最大扫描图片数。未设置 `fullScan` 时会保护性限制到 300 张，避免首次扫描过慢。 |
| `fullScan` | boolean | `false` | 仅在明确需要完整扫描全部相册或指定相册时传 `true`。 |
| `timeLimitMs` | number | `65000` | 模块内部限时，默认 65 秒，避免超过 App 的 90 秒执行超时。 |
| `maxDistance` | number | `14` | 严格视觉指纹汉明距离，越小越严格。 |
| `looseMaxDistance` | number | `24` | 宽松候选阈值，只用于人工复核，不代表可直接删除。 |
| `ratioTolerance` | number | `0.25` | 宽高比容差。相册截图、裁剪图较多时可放宽到 `0.35`。 |
| `minEdge` | number | `32` | 参与比较的最短边像素，小于该值的图片会跳过。 |
| `maxGroups` | number | `50` | 最多返回的相似分组数。 |

## image_groups 富内容结构

模块返回 JSON 中会包含：

```json
{
  "_richContent": [
    {
      "type": "image_groups",
      "title": "相似照片分组",
      "description": "发现 3 组相似照片，5 张候选需要人工复核。",
      "category": "similar_images",
      "value": "{...}"
    }
  ]
}
```

`value` 是字符串化的 JSON，结构如下：

```json
{
  "title": "相似照片分组",
  "summary": "发现 3 组相似照片，5 张候选需要人工复核。",
  "album": "default",
  "scannedImages": 300,
  "fingerprintedImages": 294,
  "candidateImageCount": 5,
  "safeToDeleteAutomatically": false,
  "groups": [
    {
      "id": "similar-group-1",
      "title": "相似照片组 1",
      "confidence": "high",
      "matchType": "visualSimilar",
      "reason": "Images share a close perceptual hash and aspect ratio; review before deleting.",
      "keepSuggestion": {
        "path": "/storage/emulated/0/DCIM/Camera/IMG_001.jpg",
        "name": "IMG_001.jpg",
        "width": 4000,
        "height": 3000,
        "sizeFormatted": "3.2 MB",
        "role": "keep",
        "deleteRecommendation": "keep",
        "recommendationLabel": "建议保留：通常是更清晰、更大或更新的一张",
        "tags": ["建议保留", "质量优先", "4000×3000", "3.2 MB"]
      },
      "candidates": [
        {
          "path": "/storage/emulated/0/DCIM/Camera/IMG_001_copy.jpg",
          "name": "IMG_001_copy.jpg",
          "width": 1280,
          "height": 960,
          "sizeFormatted": "430.0 KB",
          "role": "candidate",
          "deleteRecommendation": "review_before_delete",
          "recommendationLabel": "可考虑删除：与保留图视觉相似，先点开对比",
          "tags": ["可复核候选", "视觉相似", "相似度 92.1%", "尺寸更小", "删除前先点开确认"]
        }
      ]
    }
  ]
}
```

## 删除安全要求

- 模块只能标记候选，不能执行删除。
- `deleteRecommendation = keep` 的照片应禁用删除勾选。
- `review_before_delete` 可以在 APP 里允许勾选，但必须弹确认框。
- `manual_review_only` 表示低置信候选，APP 可以展示但应提醒用户谨慎处理。
- 任何批量删除都必须来自用户显式勾选和确认。
- 后续其他文件类模块如果要支持用户选择与删除，也应复用 APP 的通用文件操作能力：模块传列表与 `deleteRecommendation`，APP 负责渲染选择器、确认框和真实删除。
