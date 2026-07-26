# 渐进式知识库：快速开始

## 三层加载

1. **元数据**：`name` + `description` 始终在 system 索引里，用于触发判断。
2. **SKILL.md 正文**：`load_skill` 后注入，写清怎么用 references。
3. **references/**：Agent 按需 `read_file`，默认不全部加载。

## 最小工作流

```
用户提问 → 匹配 description → load_skill
         → 看 INDEX 摘要 → read_file 目标文档 → 回答
```

保持每个 reference 文件粒度适中：太碎则 INDEX 难维护，太大则单次加载浪费 token。
