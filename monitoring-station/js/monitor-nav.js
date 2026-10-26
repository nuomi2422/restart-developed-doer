/* ============================================================
   MonitorNav — 监测台两级导航的**单一真源**（2026-10-03）

   ★ 为什么单独一份：原来 15 个页签在 index.html 里写死一份、
     monitor-app.js 的 titles 又写一份、monitor-store.js 的
     setPage 白名单再写一份 —— 三处各说各话，加页签必漏一处。
     现在只有这里一份：index.html 的两条导航栏由它生成，
     monitor-app.js 的标题由它取，store 白名单由它导出。

★ 五个分析区块 = 用户 2026-10-03 拍板的那五个：
        ① Agent Context（输入输出 + 内容 + 本体）
        ② 工具
        ③ 任务链
        ④ ACX
        ⑤ 经验携带器
      第六个 zone「⑥ 实验统计」是 2026-10-05 加的横切视角：前五块各自看一层，
      它把「每只 AI 在每个目标下的死亡 / AC 调用 / 行动次数」并排放在一起。
      第七个 zone「⑦ 世界与经济」是 2026-10-05 加的**骨架页**：对应第三批
      T3-01（共同事实）与 T3-07（经济/开关）—— 架构里有，监测台原来一个数字都看不到。
      「附」不是分析区块，是脚手架（诊断 / 介绍 / 文档 / 独立 ACX 页）——
      它们不属于任何一条分析线，混进去会污染计数，所以单列并在标签上写明。

   ★ 每个 sub 都有 desc（一句「这一小块在看什么」）。它是用户说的
     「分析又清晰」的落点：标题只说是什么，desc 说为什么看它。
   ============================================================ */

var MonitorNav = (function () {
  "use strict";

  var ZONES = [
    {
      id: "agent",
      label: "① Agent Context",
      gloss: "两个 AI 到底收到了什么、交出了什么 —— 输入、输出、内容、本体",
      subs: [
        { id: "supervisor", label: "1.1 Supervisor 输入/输出", desc: "规划 AI 实际收到的目标 / 经验 / 策略约束 / 工具定义，与它交回的规划。不混入 Numen 执行上下文。" },
        { id: "numen", label: "1.2 Numen 输入/输出", desc: "执行 AI 实际收到的任务 / 世界上下文 / 工具目录，与它交回的输出和失败。不混入规划请求。" },
        { id: "learner", label: "1.3 Learner 复盘", desc: "学习者判了什么、七字段经验长什么样、携带器判了什么、这条链路健不健康。" },
        { id: "context", label: "1.4 历史上下文", desc: "context.jsonl 的增量索引，最近 12 条原文 + 元信息（来源 / 目标 / 任务 id）。" },
        { id: "ai", label: "1.5 历史对话", desc: "ai.jsonl：用户输入、AI 决策、工具调用、回合边界，按时间倒着看。" },
        // 2026-10-04 新增：原版只有游戏内 /usage 能问 token，网页一套都没有。
        //   放在「Agent Context」下是因为它量的就是这两个 AI 每轮烧掉多少上下文。
        { id: "tokens", label: "1.6 Token 消耗速率", desc: "逐轮 usage 差分出的速率（5/30 分 / 2 时 / 24 时 / 全程）+ 每分钟走势 + 会话台账累计与缓存命中率 + 上下文水位离自动压缩还有多远。三套口径不可相加。" }
      ]
    },
    {
      id: "tools",
      label: "② 工具",
      gloss: "AI 手上有哪些工具、真的调了哪些、调用留下什么痕迹",
      subs: [
        { id: "tools", label: "2.1 Numen 工具能力", desc: "MCP 当前真实暴露的工具目录（只读查询）+ 已记录的调用与结果证据。目录读不到时会明说读不到，不用历史冒充现状。" },
        { id: "rddtools", label: "2.2 任务链工具面", desc: "RDD / ACX / learner 这一族工具在游戏里被真的调过几次、都是哪些、失败没有 —— 回答「工具面到底通没通」。" },
        { id: "instrumentation", label: "2.3 埋点", desc: "instrumentation.jsonl 独立日志区：饿死 / 死亡 / 空转 / 重复采集 / 资产不匹配 / 恢复失败。" },
        { id: "settlement", label: "2.4 基地与设施", desc: "settlement.jsonl：基建的语义事件——survey 勘测 / place 受理与被拒 / inspect 施工账与收口 / resume 续建。选格、放置、缺料、还差几格都在这。" }
      ]
    },
    {
      id: "taskchain",
      label: "③ 任务链",
      gloss: "本体怎么推进、资产够不够、支线卡在哪、断在哪、还能不能拍醒",
      subs: [
        { id: "chainbody", label: "3.1 任务链本体", desc: "一级阶段横排 + 当前阶段二级小步与验收条件 + 完整链按需展开。" },
        { id: "assets", label: "3.2 资产", desc: "asset_snapshot 的真实观测数量与状态。资产是独立事件类型，不在 taskChain 字段里。" },
        { id: "branches", label: "3.3 支线", desc: "每个一级阶段下面挂的二级小步、它们的完成条件，以及卡住 / 恢复 / 等资产的支线信号。" },
        { id: "gaps", label: "3.4 断点与缺口", desc: "走独立 /api/chain：死亡链、规划缺口、注入缺口、经验库缺口、断点置顶。游戏没跑也看得到。" },
        { id: "supervision", label: "3.5 监督开关", desc: "暂停 / 恢复监督（空转止血）。写游戏端 flag，Detector ~1 秒心跳生效。" }
        ,{ id: "semantics", label: "3.6 语义资产", desc: "rdd-semantics：这个东西为什么对这个目标重要（IMPORTANT/SECONDARY/IRRELEVANT），派生自需求清单。只读，不写回语义。" }
      ]
    },
    {
      id: "acx",
      label: "④ ACX",
      gloss: "脚本写了什么、每一步走到哪、整轮跑成什么样",
      subs: [
        { id: "acx-content", label: "4.1 内容", desc: "状态机 / 脚本的本体：有哪些脚本、各跑了多少次、成功失败分布、事件种类。" },
        { id: "acx-lifecycle", label: "4.2 生命周期日志", desc: "把 72 次运行的步骤按时间摊平 —— 每一步开始 / 成功 / 失败 / 暂停，卡在第几步一眼看见。" },
        { id: "acx-runs", label: "4.3 运行", desc: "按运行记录看：点开任意一次看它的步骤、耗时、终态、停在第几步。" }
      ]
    },
    {
      id: "carrier",
      label: "⑤ 经验携带器",
      gloss: "经验怎么存、怎么流动、队列里积了什么、携带器判了什么",
      subs: [
        { id: "explib", label: "5.1 经验库内容", desc: "按标题翻、按七项看原文、格式冲突显式标出（旧格式读不进去会被跳过，不是丢了）。" },
        { id: "expchain", label: "5.2 经验链 · 数据流", desc: "经验从产生到被检索的整条链，量到多少、哪几格看不见、盲区真源在哪。" },
        { id: "queue", label: "5.3 统一队列与学习者", desc: "AC / 经验库 / 携带器三条线投进 MemoQueue 的深度、复盘产出、以及规划知识被检索与被学到的次数。" },
        { id: "carrier-assess", label: "5.4 携带器判定", desc: "携带器每次评估判了什么、有没有短路（后续级别未求值）。" }
      ]
    },
    {
      id: "stats",
      label: "⑥ 实验统计",
      gloss: "每只 AI 在每个目标下干了什么、死了几次、AC 调了多少 —— 以及面板可自己重排",
      subs: [
        { id: "exp", label: "6.1 统计台（可重排面板）", desc: "AI × 目标 交叉表 + 死亡 / 行动 / AC / 学习四组曲线与柱。面板能拖、能折、能关，排法会记住。读不到的格子一律写「未接入」。" }
      ]
    },
    {
      id: "worldeco",
      label: "⑦ 世界与经济",
      gloss: "世界长什么样（一份事实）＋ 她经营得怎么样、能调什么开关",
      subs: [
        { id: "world-facts", label: "7.1 单一世界 · 环境事实", desc: "把 rdd-assets / rdd-facts / rdd-history / rdd-ledger 拼成同一个世界视图，逐条回答第三批 N1/N2 契约现在答不答得上。答不上的写「未接入」。" },
        { id: "economy", label: "7.2 经济与开关", desc: "经营侧：AC 脚本有哪些、跑了多久、成败分布、产量记没记。配置侧：能不能关模块 / 限经验注入 / 禁目标 —— 逐条标未接入。" }
      ]
    },
    {
      id: "ops",
      label: "附 · 运行与文档",
      gloss: "不是分析线，是脚手架：链路诊断、项目介绍、文档、独立 ACX 页",
      subs: [
        { id: "health", label: "连接诊断", desc: "监测台服务 / RDD 数据时效 / 日志可读性。看门狗边界写在页面上。" },
        { id: "intro", label: "项目介绍", desc: "RDD 八模块、定位、验收边界。" },
        { id: "docs", label: "文档", desc: "架构总览 / 模块契约 / 接口清单 / 回归清单 / 交接文档。" }
      ]
    }
  ];

  /* 首页默认落在哪：① 的第一块（原来默认是「任务链 AI」= supervisor）。 */
  var DEFAULT_SUB = "supervisor";

  var bySub = Object.create(null);
  var byZone = Object.create(null);
  for (var z = 0; z < ZONES.length; z++) {
    var zone = ZONES[z];
    byZone[zone.id] = zone;
    for (var s = 0; s < zone.subs.length; s++) {
      bySub[zone.subs[s].id] = { sub: zone.subs[s], zone: zone };
    }
  }

  return {
    zones: ZONES,
    defaultSub: DEFAULT_SUB,
    /* 已知子块 → 它的 zone / sub。未知 id 一律回落到默认区块的第一块，
       免得 hash 被人手改坏之后整页空白。 */
    resolve: function (id) { return bySub[id] || bySub[DEFAULT_SUB]; },
    zoneOf: function (id) { return this.resolve(id).zone; },
    subOf: function (id) { return this.resolve(id).sub; },
    /* 展开成一串子块 id —— store 白名单直接吃这个，不会漏。 */
    subIds: function () {
      var out = [];
      for (var i = 0; i < ZONES.length; i++) {
        for (var j = 0; j < ZONES[i].subs.length; j++) out.push(ZONES[i].subs[j].id);
      }
      return out;
    },
    /* 给 <a href="#sub"> 用的稳定锚点。 */
    anchorOf: function (id) { return "#" + this.resolve(id).sub.id; },
    fromHash: function () {
      var raw = String(location.hash || "").replace(/^#/, "");
      return raw && bySub[raw] ? raw : DEFAULT_SUB;
    }
  };
}());

if (typeof window !== "undefined") { window.MonitorNav = MonitorNav; }