# gpt-brain（数字3 · GPT 外脑快速对话链路）

> 用 ai-bridge（MCP 9527 → Chrome 扩展 → 已登录 ChatGPT）做**快速外脑对话**：要计划、要方案、要代码审查
> 都在固化会话里续问，回复落盘 `brain-replies/` 当参考（不当真理，对真实源码核）。

## 前置（每次先用）

1. `ai-bridge check_connection` → 必须"已连接"（否则提示用户检查扩展/Token，见 `ai-bridge/AGENTS.md`）。
2. 确认 ChatGPT 标签页登录态（扩展链路靠它，掉登录会 phase1 超时报"30 秒内未检测到新回复"）。

## 固化会话（2026-09-24 人工上传源码后验证可用）

```
sessionUrl = https://chatgpt.com/c/6ab526f0-8e10-83ec-9720-0dc8c1d961a6
platform   = chatgpt
```

- 该会话已含**用户上传的 `numen-src-full.zip`（minecraft-numen 全源码）**，ChatGPT 明确确认可在此会话出架构方案/计划（如 P0-1 资产真相源）。
- **ai-bridge 源码（含 rdd-selfcompile 全部核心文件）也已于 2026-09-24 上传本会话**：用户手动上传 `source-bundle-20260924.txt`（16 文件/154KB），GPT 已复述出 content.js 发送链加固与快失败要点（`PHASE1_TIMEOUT_MS=30000`、多候选发送按钮/Enter 全事件/清空校验），确认理解到位。→ 未来问 ai-bridge 实现细节直接本会话 ask_ai，无需再传。
- **任何新外脑需求优先在本会话 `ask_ai` 续问，不要 `new_session` 重建**——重建会丢源码上下文。
- 回复速度：实测秒级返回（快速对话链路正常，2026-09-24 验证）。

## 让 GPT 发计划（workflow）

1. `ask_ai(message=需求描述, sessionUrl=固化会话, platform=chatgpt)` —— 描述要细、附证据/前置结论。
2. 回复内容写入 `E:\新建文件夹\rdd架构\brain-replies\gpt-plan-<ts>.md`。
3. 当参考不当真理：涉及代码必须对着真实源码核；函数名 AI 会编造。
4. 计划落地按工程规范（九步流/测试/日志）执行，测试日志记档。

## ai-bridge 源码上下文（已齐备）

固化会话现同时包含 **Numen 全源码 + ai-bridge/rdd-selfcompile 源码（16 文件 bundle）**，无需再补传。
如需更新源码到该会话（代码又改了）：
- 自动：`node E:\restart developing doer\rdd-selfcompile\ai-bridge\feed-gpt-source.cjs`（从文件分块经 9527 真链路发送，成功后把新 sessionUrl 更新到本文档）→ 已知缺口：大负载首条可能遇扩展通道关闭（`A listener indicated an asynchronous response...`），普通对话不受影响。
- 手动：让用户把最新 `E:\restart developing doer\rdd-selfcompile\report\source-bundle-20260924.txt`（重新生成同路径）上传进本会话。

## 已知问题/注意

- `background.js` 的 bfcache 重试正则不覆盖 "asynchronous response / message channel closed" 变体 → 大负载 feed 可能断，待修（`ai-bridge/AGENTS.md` 第三轮有记录）。
- GPT 偶发 >30s 才开答时 phase1 会误报一次，重试即可（属预期取舍）。
- 免费模型限流/额度：GPT 链路非烧额度（浏览器自动化），但不可同时开太多长任务。