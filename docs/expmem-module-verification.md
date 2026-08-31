# expmem 经验记忆模块验证报告

> 日期：2026-08-31
> 范围：experience-core（纯 JVM）+ plugins:experience（NeoForge，mod id expmem）
> 提交：93a4bcd2（experience-core）、64832050（plugins:experience）
> 结论：**全部通过**（含 §12 重启持久化）

## §1 构建与单测 ✅

```text
:experience-core:test → 20/20 通过
  ExperienceEntryCodecTest 3 ✅
  ExperienceStoreTest 9 ✅
  LexicalExperienceRetrieverTest 8 ✅
:plugins:experience:build → BUILD SUCCESSFUL ✅
```

## §2 jar 完整性 ✅

```text
三类 class 都在：
  experience/api 8 个 ✅
  experience/core 6 个 ✅
  plugins/experience 10 个 ✅
mods.toml 已展开：modId="expmem"、loaderVersion="[4,)" ✅
无字面 ${...} ✅
```

## §3 游戏加载验证 ✅

```text
5. 加载：日志 "Experience Memory 0.1.3-dev (expmem)" + "[expmem] experience memory plugin ready" ✅
   工具总数 54，含 experience_learn/recall/verify ✅
6. 联动：rdd/selfcompile 工具也在（rdd_status/rdd_submit/selfcompile_*/rdd_whereami）✅
7. 写经验：experience_learn → id=failure|向下挖矿前检查岩浆, maturity=OBSERVED, verified_count=0 ✅
8. 检索：experience_recall("挖矿时小心岩浆") → 1 命中（score=1.56, matched_terms=岩浆）✅
   experience_recall("熔炉") → 0 命中（无关不召回）✅
9. 成熟度升级：verify success=true ×3 →
   #1 VERIFIED(count=1) → #2 VERIFIED(count=2) → #3 GENERALIZED(count=3) ✅
10. 失败反例：verify success=false → counterexamples=1 ✅
11. 去重合并：同 title 再 learn → 同 id，文件行数不增（1→1）✅
12. 重启持久化：重启后 recall("挖矿时小心岩浆") → 1 命中，maturity=GENERALIZED, verified_count=3 ✅
```

## §4 模块化回滚验证 ✅

```text
revert 64832050 → 只移除 plugins/experience 7 个文件 ✅
revert 93a4bcd2 → 只移除 experience-core 文件 ✅
settings.gradle 冲突是共享文件正常现象（revert 该 commit 会尝试移除其 include 行）
两个 commit 各自只含自己的 include 行 ✅
```

## §5 已知局限（不算 bug）

- 词法检索对中文"同义不同字"召回弱（设计内第一版，接口接缝留向量实现）
- 自动提炼/自动注入未实现（目前只能 AI 主动调 3 工具）
- experience_verify 的 success 必须来自真实观察（不认 AI 自述）

## 结论

**expmem 模块通过验证。** 20/20 单测、jar 完整、游戏加载、learn/recall/verify 全闭环、成熟度升级、失败反例、去重合并全部真实通过。
