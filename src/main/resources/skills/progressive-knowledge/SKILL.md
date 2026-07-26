---
name: progressive-knowledge
description: |
  演示如何用 Skill + references/ 做渐进式知识库：少量内聚文档、运行时按需 read_file，替代轻量场景下的向量 RAG。
  触发场景：用户询问 BetterCLI Skill 知识库用法、如何组织 INDEX.md、渐进式加载参考文档、或点名 progressive-knowledge。
version: "1.0.0"
author: BetterCLI
tags: [skill, knowledge, references, progressive]
---

# progressive-knowledge Skill

用 **SKILL.md（决策手册）+ references/（按需资料）** 承载内聚知识，而不是把全文塞进 system prompt。

## 加载协议（必须遵守）

1. 你已通过 `load_skill` 得到本手册；工具结果里若含 **INDEX.md 摘要**，先据此选型。
2. 需要细节时，对 `references/` 下文件调用 `read_file`（绝对路径见 load_skill 确认文案）。
3. **先读** `references/INDEX.md`（若摘要不够），再读具体文档；**禁止**一次读入全部 references。
4. 回答用户时引用你实际读过的文件名，避免臆测未加载内容。

## 适用边界

| 适合 | 不适合 |
|---|---|
| 文档 ≤ 约 100–200 篇、主题内聚 | 大规模通用文档库（优先代码/记忆 RAG） |
| 需要完整章节上下文 | 只要段落级碎片命中 |

## 维护提示

- 新增文档：放入 `references/`，并更新 `INDEX.md` 一行摘要。
- 保持 SKILL.md 简短；细节只放 references，且相对 SKILL.md **一级链接**。
