# 基地与设施（settlement）模块 · 交接 · 2026-10-09

> 交接人：open 免费档 → 接手：DSH（deepseek-v4.1-flash）。
> 一句话现状：**基地/设施骨架全部打通；2026-10-09 下午已交付「基建工具 V1：选格放置与续建」并全链路实机验证。
> 引羊 AC 只到 1 只的根因已查清（ACX 引擎 `$origin` 解析 bug + 羊圈缺墙），已不再是阻塞项。**

> ★ **接手先读第 7 节**（V1 新交付：统一入口 `settlement` + 4 条硬机制的实机证据）。
> 第 4 节的「AC 机制坑」仍然全部有效，且新增了第 7.5 节的引擎级发现。

---

## 1. 交付物（代码）

### 1.1 新模块 `settlement-core`（host-agnostic，纯 JVM 单测）
`D:\rdd\workspace\minecraft-numen\settlement-core\`
- 内容：模块化平面网格（`PlatformPlan`/`PlatformMath`：cellBounds/cellAt/deWall/flatten/anchor）、设施登记（`FacilityRecord`/`FacilityRegistry`）、位置级保护裁决（`ProtectionRules` + `Action`/`Grant`/`BlockClassifier`）、三层验收（`AcceptanceEvaluator` + `Verdict`/`ProductionState`/`WorldProbe`）、JSON（`SettlementJson`）。
- 已 `include('settlement-core')` 进 settings.gradle。
- 单测：`gradle -p minecraft-numen :settlement-core:test --offline`（42 绿）。

### 1.2 插件 `plugins:settlement`（登记真源 + 工具 + 保护执行层 + 蓝图生成器）
`D:\rdd\workspace\minecraft-numen\plugins\settlement\`
- `SettlementPlugin`（注册 16 个工具 + 事件监听 + 两类投影）/ `SettlementService`（facilities.json + base.json 持久化 + 内存 Grant）/ `SettlementProtectionListener`（NeoForge BlockEvent 拦同伴改动）/ `McWorldProbe`。
- 工具清单：
  - 登记/查询：`settlement_register`（含显式中心 `cx/cy/cz`、`protect`/`protect_full`）、`settlement_list`、`settlement_verify`、`settlement_unregister`、`settlement_map`（网格图 + 按 cell/id 查详情）、`settlement_base`（登记基地基准）、`settlement_scan_containers`（登记箱内物品）、`count_entities_in_box`（区域实体计数）。
  - 保护/授权：`settlement_grant` / `settlement_revoke`（scope=work/facility，时间盒，不落盘）、`settlement_probe_break`（验证助手：按坐标让同伴真破坏一次）。
  - 蓝图生成器（生成 `.nbt` 到 `schematics/`，再 `blueprint action=build`）：`settlement_platform`（**动态整平**：填低削高+铺顶面）、`settlement_pen`（5×5 栅栏圈+门口栅栏顶地毯）、`settlement_house`（7×7 火柴盒核心屋：地板/墙/平顶/橡木门/床箱台炉）、`settlement_farm`（9×9 农田+中心水孔）。
- 投影：`contributeState` → `<settlement><facility .../></settlement>` 进每轮 runtime_state；`contributePlanningKnowledge` → `<facilities>` 进规划请求。

### 1.3 core 改动（已部署）
- `core/common/.../task/build/BuildCompanionTask.java`：`fixConnections()` 也接到**失败出口**（未完工也补算栅栏连接）。用 `-Module core:neoforge` 部署。

### 1.4 AC 脚本
`D:\rdd\workspace\minecraft-numen\plugins\acx\src\main\resources\acx-lib\stable\lure_animals_into_pen.ac`（v5）
+ 游戏 `config\numen\acx\lib\stable\lure_animals_into_pen.ac`。

---

## 2. 编译 / 部署

```powershell
# 唯一编译通道（一个 mutation 一个 jar）
$id = & powershell -NoProfile -File "...\rdd-selfcompile\scripts\new-mutation.ps1" -Requirement "..." -Instance gpt
& powershell -NoProfile -File "...\rdd-selfcompile\scripts\build-jar.ps1" -MutationId $id -ModuleOverride plugins:settlement   # 或 core:neoforge
$env:RDD_SELF_COMPILE_INSTANCE="gpt"
& powershell -NoProfile -File "...\rdd-selfcompile\scripts\deploy.ps1" -MutationId $id
```
- 纯逻辑单测可直接 `gradle :settlement-core:test --offline`。
- 进游戏：`launch-mc.ps1 -Instance gpt -AutoStopMinutes 180`（**后台 Start-Process，`-ArgumentList` 传一个字符串、路径自加引号**；数组形式含空格路径会静默失败）。

---

## 3. 已实机验证（live）

| 能力 | 结论 |
|---|---|
| 登记/列表/验收/注销 | ✅；空圈=结构可用/生产未就绪，放羊=生产就绪 |
| 位置级保护 | ✅ A/B：登记后同伴挖保护区被拦（`[settlement] blocked BREAK`），注销后可挖 |
| 授权作用域 | ✅ 未授权拒 / grant scope=work 只放行工作区、结构环与相邻设施都拒 / revoke 后拒 / 重启后授权清 |
| 平台整平 | ✅ 动态整平生成 `mode=level filled/cleared`，build 后顶面齐平 dirt、上方 air |
| 核心屋 | ⚠️ 185/195：地板/门（上下半）/家具/屋顶对；远侧 9 墙 `would not stay put`（见问题1） |
| 农田生成 | ✅ 生成 9×9 耕地+中心水孔（水要另灌） |
| 计数工具 | ✅ 区域实体计数正常 |
| 语义投影 | ✅ runtime_state 里含 `<facility .../>`（监测台转义 `\u003c`） |
| 引羊 AC | ⚠️ **半可用**：v5 全程跑通、引到 **1 只**进圈（n=1，差 1） |

---

## 4. 已知问题（重要）

1. **建造可达性（core）**：封闭空间里从里往外建、先把自己围住 → **够不到远侧格**（核心屋远墙 9 格、羊圈门口同源）。修法：① 先建外墙再建内部/顶、入口最后封；② 建完做一遍"补漏 pass"，换站位从外侧补。**未做。**
2. **寻路器 + 栅栏/地毯**：`BlockHelper` 把栅栏、地毯都判成不可走/不可穿；「栅栏+地毯」组合对**我们的假玩家实体不可站**（实测 TP 上去会落回栅栏层，没有可站面）。→ 门口只能靠**栅栏门**；但**过门后要记得关门**（否则羊跑）。**未做"过门自动关门"。**
3. **AC 机制坑（写 AC 必读）**：
   - **异步步**（`goto`/`mine`/`collect_items`）派发后 AC **PAUSED**，要驱动方 `acx_resume` 续；**步输出不跨暂停保留**，只有 `set` 写的 `$var` 保留 → 每次 scan 后立刻 `set`，goto 用 `$var`，**别直接引 `$step.field`**。
   - **同步工具**（`equip_item`/`interact_at` 这类 `runSync`）从 AC 直调会**回执超时** → 参数加 `"ignore_failure": true`（动作照样执行）。
   - `for/while` 循环体含异步 goto 会**续跑重来** → 引羊用**线性重复**，无法自动重试到 2 只。
   - 发布流程：`acx_publish{ac_json}`（GENERATED）→ `acx_approve{name,version}`（STABLE/active）→ `acx_execute`（可用 `ac_name`+`ac_version` 或直接 `ac_json`；inputs 走 `input`）。
4. **引羊只到 1 只**：`scan` 半径 16 时羊时远时近、`approach→lead` 只 2 趟；羊跟随窗口有限。需加趟数/更近扫描/或等羊聚拢。**可调。**
   → ★ **2026-10-09 已重新定案（见 7.5）**：主因是**羊圈不闭合**（旧 `base1` 北墙缺 4 格栅栏），
   次因是脚本选目标没筛物种（`type_filter=passive` 含猪/鸡/掉落物）+ `$pick=nearest` 坐标解析
   静默失效（**该引擎缺陷已修 commit `b310bca8`**，15 条 REF_UNRESOLVED → 0）。
   **加趟数治不了；先修圈、再按 type 筛羊。**
5. **农田水源**：蓝图跳过液体格，`settlement_farm` 只留水孔，**要另用桶灌水**（否则耕地退）。**未接灌水步骤。**
6. **大整平**受 32k 格 + 备料限制 → 大地皮要分块。
7. **未做**：授权随施工任务自动起止（需 core 挂 facilityId）；设施投影进 RDD `world_asset` 库（需跨插件缝）。

---

## 5. 下一步（按优先级）

1. **引羊 AC 调通到 ≥2**：多引几趟 / `scan` 半径收到 8-10 / `lead` 目标设在门内 1 格；或加"过门自动关门"。
2. **建造可达性**（core）：先外墙+退路 或 补漏 pass。
3. **农田灌水**：加一步（水桶 fill）/或让农田蓝图改放 `water`（需 core 允许液体格）。
4. **授权随任务自动起止**、**设施进 world_asset**。
5. 网格地图重叠拒绝 + 邻接同类区分 + 地图映射单测。

---

## 6. 关键路径速查
- 登记真源：`D:\rdd\mc\The Best of Twilight Forest\config\numen\settlement\facilities.json` / `base.json`
- 蓝图落地：`...\schematics\*.nbt`
- 验收单：`D:\rdd\docs\rdd-docs\rdd存放\基建夜间验收-20261009.md`
- 监测台：`http://127.0.0.1:8776/`（`config\numen\monitor\acx.jsonl` 看 AC 事件；XML 存成 `\u003c` 转义）

---

# 7. ★ 基建工具 V1：选格放置与续建（2026-10-09 下午交付）

> 用户 2026-10-09 定的方向：**先把已有能力收成一个统一的「基建工具」——AI 看地图、选模块、选地块，
> 工具负责换算坐标、检查、施工和登记。引羊可以后续完善，不必挡住「建造并管理设施」这条路线。**

## 7.1 一句话用法（AI 只需决定"建什么、放哪儿"）

```text
settlement action=overview                     看基地地图 + 设施状态 + 空闲格（先看这个）
settlement action=catalog                      看可选模块：占几格/材料/入口/可用程度
settlement action=place template=pen_basic cell=B2 [rotation=0|90|180|270] [dry_run=true]
settlement action=inspect id=<设施id>          看入口/范围/结构/生产/施工账/还差什么
settlement action=resume  id=<设施id>          补料或排阻后继续原设施施工
```

**不用再让 AI 自己串**「生成蓝图 → 算锚点 → 登记 → 开权限 → 建造 → 撤权限」这一长串。

## 7.2 交付物（代码）

### settlement-core（纯 JVM，可单测）
- `logic/RotationMath` —— 蓝图局部坐标 ↔ 旋转后坐标（顺时针四分之一圈，绕中心；非正方形旋转后长宽互换）。
- `logic/PlacementMath` —— `(格, 模板, 旋转)` → 世界坐标：占地盒、蓝图外廓、锚点、入口内外站位、占用格、门外净空条。
- `logic/PlacementValidator` —— 放置前校验：**越界 / 重叠 / 入口空间 / 格占用**，全部在**改世界之前**拒绝。
- `logic/PlacementLedger` —— 去重与续建：确定性设施 id、处置判定（NEW/RESUME/ALREADY_COMPLETE/FAILED）、续建锚点/朝向一致性校验。
- `logic/TemplateCatalog` —— 模板目录（单一真源）：占地、入口朝向、料单、**可用程度**、未自动化步骤、**结构标记**。
- `logic/CompletionChecker` —— 施工收口的**世界侧**判据：按结构标记核对"这块地到底建成了没、还差哪几格"。
- `model/ConstructionStatus`、`model/FacilityTemplate`、`model/LocalPoint`；`model/ConstructionProgress` 扩账（status/template/anchor/taskId/missing/message，旧 5 参构造保留 → 老 JSON 照读）。
- `protect/GrantLedger` —— 施工授权生命周期：**所有终止路径回收**（任务结束 / 被顶替 / 过期 / 重启）。

### plugins:settlement
- `tool/SettlementTool`（★ 新统一入口，5 个 action）+ 服务端 tick 清扫（`SettlementMod`）。
- `SettlementService`：新增 `grantLedger()` / `sweepGrants()` / `platformPlan()`（基地基准 → 网格计划）。
- `SettlementPenTool`：**栅栏门替代地毯门**（`gate_side=north|south|east|west`，带 `facing` 属性）；
  `settlement_pen/house/farm/platform` 均支持**显式中心**（`cx/cy/cz`）供统一入口按格落位。
- `SettlementVerifyTool` / `SettlementTool.inspect`：接上世界侧收口核对并回写施工账。

## 7.3 用户点名要的四条规则怎么落实

| 规则 | 落实方式 |
|---|---|
| 主基地固定原点，5×5 最小地块 | 一切以 `settlement_base` 登记的基准为准；**没有基准就明确拒绝**，绝不拿站位当原点（原点漂移是点名要防的）。 |
| 蓝图按占地**向上取整** | `footprintCellsX/Z` 与 `sizeX/Z` 分开：5×5→1 格、7×7→2 格、9×9→2 格；多出的位置是留白/通行空间。`footprintFits()` 是机器判据。 |
| 相邻摆放 ≠ 合并 | 第一版只做相邻独立摆放（各有边界和 ID）；重叠**拒绝**，相邻放行。合并（拆共用边界）留到以后。 |
| 模板带入口和通行要求 | 模板声明 `entranceLocal + entranceFacing + clearanceOutside`；`ENTRANCE_BLOCKED` 是独立拒绝码。**检查通过只代表允许开工，不保证必然完工**。 |

## 7.4 确定流程（用户点名的"检测到就直接修"）

```text
选模板和地块 → 检查（范围/冲突/材料/入口）→ 建稳定设施 id、预留地块并保存施工记录
→ 取得本任务限定范围的施工权限 → 调现有蓝图建造器 → 回收权限、检查实际结果
→ 更新设施状态与地图
```

两条硬要求已落实：
1. **开工前就登记为施工中**（`ConstructionStatus.PLANNED` 在动手前写盘）→ 中途停止/重启后地图显示"施工中"，不会把半成品当空地。
2. **重复调用识别已有设施**：设施 id = `基地_模板_格`（确定性），第二次同样请求必然撞上第一次的账 → `RESUME`，不建第二座。
3. **所有终止路径回收授权**：授权进 `GrantLedger` 并绑定任务 id，服务端 tick 对"当前任务 id"清扫。

## 7.5 ★ 实机验证（2026-10-09 下午，gpt 实例，ACX）

| 剧本步骤 | 结果 |
|---|---|
| `overview` 出基地地图 + 空闲格 | ✅ 原点 (737,68,1145)，5×5，7 设施 |
| `catalog` 出 4 个模块 + 可用程度 | ✅ 牧场=可完整建造 / 核心屋=可试建与续建 / 农田=仅结构可建（如实标注灌水未自动化） |
| **重叠格放置必须被拒** | ✅ `B3` → `OVERLAP` 双条拒绝，**未改世界** |
| 空格放置通过检查 | ✅ `A1`/`B1`/`C1` 通过，附 `ENTRANCE_OUTSIDE_PLATFORM` 警告 |
| 真放置 `pen_basic @ A3` | ✅ 受理，设施 id=`minecraft_overworld_pen_basic_a3`，锚点 737,68,1155 |
| **重复同一请求** | ✅ 返回 `RESUME`（不建第二座），设施总数只 +1 |
| 缺料半成品 | ✅ 世界核对 **2/8**，状态 `BLOCKED`，逐条列出缺的坐标 |
| `/give` 补料 → `resume` | ✅ 沿用原锚点，还差 6 格 |
| 补料后续建收口 | ✅ 世界核对 **8/8**，`COMPLETE`，门=关着的栅栏门 |
| 已收口再放置 | ✅ 拒绝："已收口（8/8）；不重复建造" |
| **授权回收** | ✅ 日志 `reclaimed construction grants for [minecraft_overworld_pen_basic_a3]`；随后 `settlement_probe_break` 打保护区栅栏 → `changed=false`（保护已恢复） |
| 锚点修正后重验 `pen_basic @ B1` | ✅ 蓝图**精确**落在记录的 742..746 × 1145..1149，门=744,1145=`entrance_inside`，8/8 |

### ★ 引擎级发现（已确认，已修，2026-10-09）
- **`$pick=nearest` 的坐标解析是坏的（已修 commit `b310bca8`）**：`AcxParamBinder.coords()`
  用 `m.get(field)` **平铺**取坐标，而 `$pick_fields` 本来就写成**点路径**
  （`position.x,position.y,position.z`），Numen 的实体条目又是嵌套的
  `{"position":{"x":..}}` → 平铺取必然取不到 → 每个元素被跳过 → `best` 停在 0 →
  **nearest 静默退化成「列表第一个」**。`$origin` 侧同样中招（它是裸 `{x,y,z}`，
  用 `position.x` 去读也取不到）。
  日志表现：每次参数解析刷 `REF_UNRESOLVED $origin 不是 {x,y,z} 或 [x,y,z]，退回第一个`
  （旧 run `6a609aa8` 实测 **15 条**；修后 run `22a88346` **0 条**）。
  **修法**：① `coords()` 支持点路径（平铺取不到且含 `.` 时走 `AcxValueResolver.resolvePath`）
  ② `nearestIndex()` 对裸 `{x,y,z}` origin 用默认字段回退再试一次。
  回归：新增 2 项（离线自检 217 → 219）；单测证明「嵌套 position 列表里 far 在前」时
  旧代码选 far、新代码选 near。

  **★ 影响范围要说准（不要夸大）**：`scan_nearby_entities` 的输出**本来就按距离排序**，
  所以"退化成列表第一个"在多数情况下**恰好等于**"最近的"。真正的影响是：
  ① `REF_UNRESOLVED` 事件污染（15 条/run），掩盖真问题；
  ② 一旦列表**不是**按调用方原点排序（或 `$origin` 与同伴位置不同），就会**静默选错**；
  ③ 引羊 AC 的 `type_filter=passive` 含猪/鸡/掉落物，所以"最近的 passive"**可能不是羊**——
  这与本 bug 无关，是脚本该用 `$filter` 筛 `type` 的问题。

- **引羊只到 1 只的两重原因（重新定案）**：
  ① **羊圈不闭合**（旧 `base1` 北墙 z=1159 缺 4 格栅栏，只有门）→ 羊会漏。
     这是第 4 节「建造可达性」的同一个病。**这才是主因。**
  ② 脚本选目标没筛物种（`$pick=nearest` + `type_filter=passive` 会选到猪/鸡/掉落物），
     以及本 bug 带来的静默不确定性。
  → 结论：**先修圈（可达性/补漏），再让脚本按 `type` 筛羊**；加趟数治不了。
- **栅栏门会被寻路器打开且没人关**：`MovementHelper`/`ExecHarness` 用 `gameMode.useItemOn` 开门过路。
  实测 A3 门一度是 `open=true`（同伴穿门导致），干净站点 B1/705 建出来是 `open=false`（`g`）。
  → 所以「过门自动关门」确实是真实需求（第 4 节问题 2 仍然成立）。
- **蓝图路径的栅栏门属性是正确的**：NBT palette 里 `{"facing":"north"}` 原样落位，
  干净站点建出来就是关着的门；`build` 直写带 properties 也正确。**不是放置器 bug。**

## 7.6 下一步（优先级已按用户 2026-10-09 意见重排）

1. **模板目录补齐**：交易所（TRADE）模板与验收准备好后再进可建目录；把"未完成步骤"做成可执行的一步（如农田灌水）。
2. **农田灌水**：`settlement_farm` 只留水孔，要另用桶灌水；接一步灌水动作，或在目录里明确"仅结构可建"（现已标注）。
   **实机确认（2026-10-09）**：未灌水的农田里，耕地会**退回泥土**——D1 农田建成后 y=68 那一层
   大部分已从 `farmland` 变回 `dirt`，中心水孔（756,68,1149）是 air 等着灌。这不是 bug，是 MC 特性，
   也正是目录把它标成"仅结构可建"的原因。
3. **建造可达性（core）**：先外墙+退路 或 补漏 pass——它同时卡着核心屋远墙与旧羊圈缺墙。
4. ~~**过门自动关门**~~ —— **✅ 已完成（commit `1b84a779`）**，见 §7.8。
5. ~~**ACX 引擎修 `$origin`**~~ —— **✅ 已完成（commit `b310bca8`）**，见 §7.5。
6. 授权随施工任务自动起止已做（tick 清扫）；设施进 RDD `world_asset` 仍待跨插件缝。

## 7.8 过门自动关门（2026-10-09 交付，commit `1b84a779`）

**为什么必须有**：寻路器开门过路用的是原生右键（`gameMode.useItemOn`），走过去之后
**没有任何东西负责关**。实测：新建的羊圈门在同伴穿过后一直 `open=true`——敞着的门
等于圈没围，羊会走出去，"引羊进圈"从根上不成立。

**实现**：`settlement-core/logic/GateKeeper`（纯策略）+ `plugins/settlement/GateWatcher`（服务端 tick）。
扫已登记设施的 **SPACE（通道）区**，把敞着的门关回去；只在有设施时扫描，每 20 刻一轮，
同一扇门最多连续试 5 次（防"关了又被人打开"刷日志）。

**三条安全约束**：只关门不开门 / 门洞里有人不关 / 区块未加载不动手。

### ★ 实机抓到的真缺陷（已修，写进红线）
门洞占用最初按"**脚在哪一格**"判（`y >= gateY-0.5`）。实测同伴站在门口的 **`dirt_path`** 上，
而 dirt_path 碰撞高只有 **15/16** → 她的 `y=66.9375` 比门格 68 低 **1.06 格**，身体却正好在
门格里 → 被判成"门洞空" → **把门关在了她身上**。半砖/耕地/雪层/台阶上都会重现。
改判据为"**实体身体跨度与门格相交**"（保守带 `[gateY-2, gateY+1]`）：宁可多等一刻，
也不把方块塞进她身体里。

### 实机三支全验（2026-10-09 15:14-15:25）
| 场景 | 结果 |
|---|---|
| 门敞着 + 门洞没人（重启后 A3 门从 14:43 起一直敞着） | ✅ 日志 `auto-closed gate at 739, 68, 1155` |
| 同伴站在门口（dirt_path 上，y=66.9375） | ✅ 连续 4 次读都是 `open=true`（**不夹人**） |
| 她走开后 | ✅ 5 秒内自动关上 |

## 7.9 设施 Y 范围修正（2026-10-09 交付，commit `d48f6c27`）

**实机抓到的缺陷**：农田登记成 `y=68..69`，而它真正的支撑泥土层在 **`y=67`**——
保护区与验收带都漏掉最底下一层，等于把设施的地基留在保护之外。

**根因**：`PlacementMath.footprintBox()` 一律从 `plan.floorY()` 起算 Y，而各生成器的
蓝图 `y=0` 落在**不同层**（牧场/核心屋在 floorY，农田/平台在 floorY-1）。

**修法**：Y 起点改用 `floorY + template.anchorYOffset()`。
**实机验证**：注销旧农田后重放 D1 → 范围 `752,67,1145 .. 761,68,1154`（含 y=67 支撑层），
世界核对 8/8、施工 `COMPLETE`。

## 7.7 V1 的已知边界（如实）

- `place` 复用既有生成器，因此**继承它们的限制**：核心屋远墙可达性问题仍在（故 `core_house` 标注 `PARTIAL`）。
- 占地对齐取"蓝图 min 角 = 占地格最小角"（确定性优先）；蓝图比占地小时右侧/南侧留白。
- `resume` 不重新解算坐标，沿用登记锚点/朝向；基地基准改过会被拒绝（防错位）。
- 大范围整平受 32k 格与备料限制，大地皮要分块。
- **农田建成 ≠ 能种**：未灌水时耕地会退回泥土（MC 特性，见 §7.6 第 2 条）。
- **过门关门是"事后清扫"**：同伴穿门后由 tick 关回去（不是即时），所以门会短暂敞着；
  这也是它必须"只关不开、门洞有人不关"的原因（见 §7.8）。

## 7.10 本轮全部提交（2026-10-09，minecraft-numen @ redline-1）

| commit | 内容 |
|---|---|
| `8680a328` | settlement 基建工具 V1（统一入口 + 占地校验 + 模板目录 + 施工账 + 授权回收 + 栅栏门替代地毯门） |
| `b310bca8` | acx 修 `$pick=nearest` 坐标解析静默退化（引羊选错目标的引擎根因） |
| `1b84a779` | settlement 过门自动关门（+ 实机抓到的夹人缺陷修正） |
| `d48f6c27` | settlement 修正设施 Y 范围（占地盒必须带模板 anchorYOffset） |
| `b2ced88d` | settlement 修正入口净空方向（必须按旋转后朝向算） |
| `0a294e90` | settlement 收口核对补家具标记（缺箱子曾谎报 COMPLETE） |
| `57c0b1da` | settlement resume 先问世界再判"已收口"（否则缺件永远补不上） |

单测：`settlement-core` **107 / 0 / 0**；`plugins:acx` 离线自检 BUILD SUCCESSFUL（3 项已知红保持）。
门禁：harness postflight **VERDICT=PASS**（0 BLOCK）。

## 7.14 交接给下一位：先做这三件

1. **重测核心屋可达性**（干净空地、两种朝向各一次），据实决定 `core_house` 能否从
   `PARTIAL` 升到 `READY`。别照抄旧结论——本轮没复现。
2. **农田灌水接一步**（水桶 fill）：目录已如实标 `STRUCTURE_ONLY`，补上这一步才能变 `READY`。
3. **引羊 ≥2**（仍未达成，见 §7.15）：圈已补好、引擎缺陷已修、脚本已迭代到 v8，
   但**羊仍进不去**。缺的是真·驱赶动作，不是再改脚本流程。

## 7.15 ★ 引羊：本轮做到哪一步、真实瓶颈是什么（如实）

**已修好的前提（三条都实测过）**：
- ✅ **圈闭合了**：旧 `sheep_pen` 北墙 z=1159 缺 4 格栅栏 → 已用
  `settlement_grant(scope=facility)` + `build` 补上，现在是 `FFgFF` 闭合圈，**门会自己关**
  （日志 `auto-closed gate at 747,68,1159`）。
- ✅ **引擎缺陷修了**：`$pick=nearest` 坐标解析（`b310bca8`），`REF_UNRESOLVED` 15 → **0**。
- ✅ **脚本会筛羊了**：v6 起每趟 `$filter` 只留 `entity.minecraft.sheep`
  （实测：扫描列表第一条是掉落物，脚本仍正确挑中羊）。

**仍未达成**：`n` 一直是 **0**，一只都没进。

**★ 真正的瓶颈（实测确认，别再往"流程"上使劲）**：
1. **羊的跟随窗口只有约 8 格**（MC 机制）。v6/v7 每趟"一步 goto 圈心"，而羊常在
   18-33 格外 → 羊走到一半就停住/掉头。v8 改成两次短跳（各约 1/3 路程）**仍不够**。
2. **没有"驱赶"动作**。拿着小麦只会让羊**跟着**你，不会**被推进门**；人穿门后羊常常
   停在门外，或跟进来后又被你带出去。实测羊最终停在圈外（z≈1155 / 1139）。
   → 需要的是真·驱赶：推挤、或用"先困 1×1 临时栏再放开"的诱导法，而不是继续加路点。

**★ 一个我自己的误判（写下来防复发）**：
收尾读到 `equipment:{}`（空手）一度被当成"没拿小麦 → 羊不跟"。查 `acx.jsonl` 后确认：
AC 里 `equip_item` 的 `STEP_FAILED` 是**同步工具回执超时 5000ms**（动作其实已执行，
`ignore_failure` 让它继续），而空手是收尾 `drop`（卸麦）**之后**的正常状态。
→ **AC 里同步工具（equip_item/interact_at）的 STEP_FAILED ≠ 没执行。**

**当前版本**：`lure_animals_into_pen` **v8**（39 步，已 STABLE/active）。
提交：`1288e386`（v6）→ `61610714`（v8）。

## 7.11 ★★ 最重要的一条：收口核对必须采到"人真正在乎的东西"

**实机抓到的假绿（2026-10-09）**：D3 核心屋的 `inspect` 报
`世界核对=11/11  施工=COMPLETE`，而**箱子根本没落地**。
翻 `events.jsonl` 才看到那次 build 其实是 **FAILED**：

```
built 193/195 and ran out — still needs chest x1; 1 items short in total.
```

**根因**：模板的结构标记只采了**结构**（四角墙/门/屋顶中心/地板中心），
**家具不在采样里** → 缺一件家具判不出来 → 直接谎报"全部命中"。

**这条比它修的是什么更重要**：`CompletionChecker` 的标记是**采样**，
采样漏掉的东西会被判成"没问题"。所以**每加一种"用户会去检查的东西"，
就必须同步加进标记**——否则工具会用一个漂亮的数字把缺失盖掉。
这正是用户说的"看起来做了、其实没生效"的形态，也是 Q8 要防的那一类。

**修法**：`core_house` 的 marks 补上四件家具（工作台/熔炉/箱子/床）。
**实测复验**：同一座屋现在报
`世界核对=14/15  还差=[753,69,1156=chest(实为 grass_block)]`——不再谎报。
**回归**：新增 1 项，故意不摆箱子时必须判未完成且**点名 chest**。

## 7.12 其他两个实机抓到的缺陷（已修）

- **入口净空方向用了旋转前的朝向**（`b2ced88d`）：`clearanceBox()` 原先拿模板声明的
  `entranceFacing`（**旋转前**）算方向，而 `entranceOutside` 已经旋转过 → 旋转 90° 后
  门在东侧、净空条却仍朝北延伸 → "入口被堵"判在**错的一侧**（漏报真堵/误报假堵）。
  修法：方向从**已旋转的** `entranceInside/Outside` 之差推出来。
- **过门关门把门关在同伴身上**（见 §7.8 的 dirt_path 案例）。

## 7.13 核心屋实机结果（如实，最终）

D3 核心屋（rotation=90，图纸 195 格去重后）最终 **15/15 世界核对通过、COMPLETE**。
逐格全图纸对账（`verify_bp.js`）**194/195**，唯一"不符"是床的**自动生成的另一半**
（vanilla `setPlacedBy` 造床头）——**属预期行为，不是缺陷**。

过程与结论：
- 第一次 build 报 `FAILED: built 193/195 ... still needs chest x1`（**缺料**，不是够不到）；
- 补料后 `resume` → 15/15 收口，箱子真的落地（`753,69,1156 = minecraft:chest`）。
- ★ **之前记录的"远侧 9 格 `would not stay put`"本次没有复现**——这次只差一件**材料**。
  → 「建造可达性」这个结论**需要重新实测**（可能早期版本的病，也可能与朝向/站位有关）。
  在重测之前，`core_house` 的 `PARTIAL` 标注**暂时保留**（宁可保守）。

**★ 连带修掉的一条真缺陷（`57c0b1da`）**：`inspect` 正确报 `14/15 缺 chest`，而
`resume` 却回"已收口，无需续建"——两条路读同一份账，但 `resume` 只看账不看世界，
于是缺的那件东西**永远补不上**。修法：`resume` 在 `status==COMPLETE` 时**先问世界**
（按结构标记核对），不通过就允许续建并把账重新打开。

---

# 8. 2026-10-09 晚 · V2 交接（7×7 模块 + 14×14 一级地皮）

> 接手人：opencode 免费档。上一轮把 V2 写到一半即中断（编译/编码问题）。本节是**当前真实状态**。

## 8.1 本轮修掉的三处「拦路 bug」（都已部署）

1. **`SettlementMod.java:86` 编译不过**：引用了不存在的 `Companions.byUuid`。
   改成 `server.getPlayerList().getPlayer(uuid)` + `instanceof NumenPlayer`。
2. **★ 插件 jar 版本号非法 → 游戏开不起来（实测崩）**：
   `InvalidModFileException: Illegal version number specified version`。
   根因：上一轮把 `plugins/settlement/build.gradle` 的 **`processResources`（展开 `${version}`）删掉了**，
   mods.toml 里 `${version}` 字面量没被替换。**已补回**（照 `plugins/rdd/build.gradle`）。
   → 教训：**新插件模块的 build.gradle 必须有 processResources 展开段**，否则 mods.toml 版本非法、游戏直接崩。
3. **模块放不上地皮**：`PlacementValidator` 的「入口净空」检查（第 4 项）**没豁免一级地皮**，
   模块放在 `platform_*` 上必然报 `ENTRANCE_BLOCKED`。已补豁免（与重叠/占格两处同口径：
   `platform_*` 与建筑模块互相放行）。**已部署，未重测。**

## 8.2 V2 已实现并实测的部分

- **模板目录（`TemplateCatalog`, 7×7 统一）**：`pen_sheep` / `pen_cow` / `core_house` /
  `farm_basic` / `trade_post`（两格高栅栏 + 4 床 + 门）/ `platform_cobble14`（14×14 圆石，2×2 格）。
  每个都带材料单、入口朝向、**可用程度（READY/PARTIAL/STRUCTURE_ONLY）**、未自动化工序、
  收口标记（含家具/床）。
- **`settlement` 统一入口**：`action=overview / catalog / place / inspect / resume`。
  实测：catalog 正常列出 6 个模板；base set（cell=7）正常；overview 出 6×6 网格地图；
  `place template=platform_cobble14 cell=E4 dry_run=true` 通过、正式放置受理（439 格，task 派出）。
- **平台 vs 模块豁免**：重叠/占格检查已互放行（本轮又补了入口净空）。
- 过门自动关门、Y 范围、入口朝向、收口家具标记、resume 先问世界——**上一轮的成果都在**（见 §7）。

## 8.3 ★ 未完成 / 待修（接手从这里继续）

1. **一级地皮没建完（实机）**：E4 的 `platform_cobble14` 放置受理了，但落点表面（770,66,1176 等）
   **多是 `dirt`、只有一角是 `cobblestone`** → 建造没跑完（或缺料/可达性）。且它的施工账停在
   `BUILDING 0/439`——**因为 `platform_cobble14` 的 `marks=[]`（整平是地形相关的，没标）**，
   `inspect` 的世界核对跳过 → 账永不自动更新。**要修**：给平台加"顶面若干采样点是圆石"的标记，
   或让平台不用 marks 而按"surface 高度"判。
   → **2026-10-09 晚（接手）已修（走的 marks 方案）**：`TemplateCatalog.platformMarks14()` 给平台
   加了 9 个顶面采样点（四角 + 四边中点 + 中心，局部 Y=1，方块 `minecraft:cobblestone`）。
   `inspect` 从此会对平台做世界核对：顶面全圆石 → `COMPLETE`；多数是泥土 → `BLOCKED` 并点名还差的坐标。
   单测 `CompletionCheckerTest.platformMarksSampleTopSurfaceAndCatchUnleveledGround` 覆盖
   （顶面全圆石=收口；只剩一角圆石=未完成且点名圆石）。`settlement-core:test` 绿；
   `run-mutation.ps1 -Module plugins:settlement -SkipLaunch -SkipMcp` → `BUILD_OK` / `DEPLOY_OK`
   （jar 22:28 已落 live，mutation-20261009-222821-09b3fd）。
   ★ **2026-10-09 晚 实机已通过**：起 live → `settlement action=inspect
   id=minecraft_overworld_platform_cobble14_e4` → 施工账从 `BUILDING 0/439` **自动更新为
   `COMPLETE 9/9`（世界核对 9/9）**。即账现在会读世界、能收口。
   `place template=pen_sheep cell=F5 dry_run=true` 也通过（F5 是平台格）→ 上一轮部署但未重测的
   **入口净空豁免（#3）同时获得实机验证**（修复前会报 `ENTRANCE_BLOCKED`）。
   ⚠ 边界：模块压在地皮上会把覆盖处的顶面换成模块方块，那格核对会如实报"缺"——地皮核对用在
   "铺完地皮、未压模块"时；压了模块就看模块自己的账。

   ### ★ 本轮顺带抓到并修掉的第二个缺陷：陈旧 `cells` 误报 CELL_TAKEN
   **`FacilityRecord.cells` 是放置当时的快照，基地基准改过之后会变陈旧**，而
   `PlacementValidator` 第 5 项 `CELL_TAKEN` 读的就是这个陈旧字段（原 `PlacementValidator.java:159`），
   于是**误报"格已被占用"**。实测：`core_house_d3` 登记 `cells=[D3,D4,E3,E4]`，而它的
   `bounds=752,68,1155..761,71,1164`（真实只落 B/C 列），E4 根本不在它占地里 →
   `place pen_sheep cell=E4` 被这陈旧记录误拒（`CELL_TAKEN: 格 E4 已被 'minecraft_overworld_core_house_d3' 登记占用`）。
   注意：`overview` 用的是**按 bounds 现算**的格（地图上 house 显示在 B/C 列），所以地图与校验器**口径不一致**。
   → **2026-10-09 晚（接手）已修**：`PlacementValidator` 第 5 项改为按 `f.bounds()` + 当前 `plan`
   **现算占用格**（新增 `cellsCovering()`，与 `overview` 的 `cellsOf` 同口径），不再读 `f.cells()` 快照。
   单测：`PlacementTest.rejectsCellAlreadyClaimedByRegistryCells`（改为 bounds 真覆盖该格）+ 新增
   `staleRegistryCellsDoNotFalselyBlockAnotherCell`（陈旧 cells 不许误拒）。`settlement-core:test` 绿；
   重编译部署（mutation-20261009-224902-29fe84）→ 重启 live 实机验证：
   `place template=pen_sheep cell=E4 dry_run=true` **通过**（修复前 E4 必被误拒），
   平台 `inspect` 仍 `COMPLETE 9/9`。
2. **地皮四边接地面阶梯**（用户点名）：目录里只写了"未自动化"注记，**没实现**。
   想法：放模块前先用 `build` 在四边各垫 1-2 级台阶（落差 >1 格时）。
3. **引羊 ≥2 仍未达成**（见 §7.15）：真瓶颈是 MC 羊跟随窗口 ~8 格 + 无驱赶动作。
   **用户新思路（2026-10-09 晚）**：*同伴持麦**先进圈深处**，羊跟到门口；再**卸麦**；
   同伴**自己走出门**、**从外面关门**；然后……（用户原话略乱）*。
   → 实现方向建议：持麦 `goto` 圈内深处（羊跟到门）→ 卸麦 → `goto` 门外点（过门，过门关门 tick 会自动关）
   → 再 `count_entities_in_box` 数。**注意**：过门关门是"事后清扫"，同伴出圈后门会自己关上（§7.8）。
4. **接口到监测台**（用户点名）：未见实现。需把 settlement 的事件（放置/施工/收口/授权）发到
   `config/numen/monitor/`（照 `RddMonitor.publish` 的做法），让 8776 看得到。
5. **核心屋可达性重测**（§7.14 第 1 条）仍未做。
6. **农田灌水**（§7.14 第 2 条）仍未做。

## 8.4 怎么跑（快速复现 V2）

```powershell
# 编译部署（一个 mutation 一个 jar）
$id = & powershell -NoProfile -File "...\new-mutation.ps1" -Requirement "..." -Instance gpt
& powershell -NoProfile -File "...\build-jar.ps1" -MutationId $id -ModuleOverride plugins:settlement
$env:RDD_SELF_COMPILE_INSTANCE="gpt"; & powershell -NoProfile -File "...\deploy.ps1" -MutationId $id
# 纯核心测试
gradle -p minecraft-numen :settlement-core:test --offline
# 游戏（后台；-ArgumentList 传一个字符串、路径自带引号，别用数组）
Start-Process powershell.exe -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File "…\launch-mc.ps1" -Instance gpt -AutoStopMinutes 240' -WindowStyle Hidden
```

```powershell
# MCP 驱动（V2 主流程）
node ...\mcp-call.js settlement ACX "action=catalog"
node ...\mcp-call.js settlement_base ACX "action=set cell_size=7 cols=6 rows=6"
node ...\mcp-call.js settlement ACX "action=overview"
node ...\mcp-call.js settlement ACX "action=place template=platform_cobble14 cell=E4 dry_run=true"
node ...\mcp-call.js settlement ACX "action=place template=platform_cobble14 cell=E4"
node ...\mcp-call.js settlement ACX "action=inspect id=minecraft_overworld_platform_cobble14_e4"
node ...\mcp-call.js settlement ACX "action=place template=pen_sheep cell=F5"    # 放地皮上（本轮已加豁免）
```

## 8.5 本轮提交状态

- 未提交（工作区脏）：`plugins/settlement/build.gradle`、`SettlementMod.java`、
  `settlement-core/.../PlacementValidator.java`（本轮三处修复）+ 上一轮大量 V2 未提交改动。
- **接手注意**：工作区里有**并行会话**的改动（`api/common/*`、`plugins/rdd/*`、`RddAlarms`、`StopCommand`）。
  **提交必须精确 `git add <路径>`，禁止 `git add -A`**（2026-10-01 出过两次事故）。
- 游戏：本轮开始时开着，**交接时按用户要求已关**（见下）。



---

# 9. 2026-10-10 凌晨 · 基地实机落地（2×2 模块 + cell=8 通道 + 分层地皮）

> 接手人：opencode 免费档。用户 2026-10-09 晚指出问题并定了尺寸（2×2）。

## 9.1 用户点名的三个问题 → 处理
1. **地皮顶面和模块同层**（栅栏/地板与地皮重合、羊圈里全是石头）→ **地皮顶面压到模块脚下低一层**：
   `settlement_base floor_y=F`；地皮用 `settlement_platform floor_y=F-1` 直接生成 + `blueprint build`
   （不走 place，故不登记为设施）。模块（anchorYOffset=0）落在 F，正好坐在地皮顶（F-1）上。
2. **模块挨太紧、没有通道**（走一半卡）→ **基地格 cell=7 改为 cell=8**：模块 7×7 + 1 格缝，
   相邻模块之间自然出现 1 格宽通道。
3. **要火把 + 扩宽** → 通道/边缘 `/setblock minecraft:torch`；地皮 generator size 任意，可铺更大。

## 9.2 实机落地的基地（live · overworld · 稀疏丛林）
- 基准：`origin=(10820,79,2196) cols=4 rows=4 cell=8`。
- 地皮：`settlement_platform cx=10827 cz=2203 size=17 floor_y=78 depth=6 clear_height=10 floor_block=cobblestone`
  → `blueprint action=build file=basepad x=10819 y=72 z=2195`（17×17，顶面 y=78）。
- 模块（floorY=79，坐在地皮上）：
  - `core_house` @ A1 rotation=0（门朝北）
  - `farm_basic` @ B1 rotation=180（门朝北）；水孔 `/setblock 10831 79 2199 minecraft:water`
  - `pen_sheep` @ A2 rotation=180（门朝南，空圈）
  - `trade_post` @ B2 rotation=180（门朝南，空交易所，4 床）
- 四个 `inspect` 全 COMPLETE（house 15/15、farm 8/8、pen 8/8、trade 13/13）。
- 23:xx 那轮的旧基地（platform_cobble14_d1 + 4 模块）先 `unregister`，再被新地皮的清空层覆盖抹掉。

## 9.3 顺带抓到的缺陷（未修，留给下一位）
- **`PlacementMath.footprintBox` 按 `footprintCells*cellSize` 算盒**：cell=8 时一个 7×7 模块的
  登记/保护盒变成 8×8，**把旁边 1 格通道也算进占用** → 邻居模块门净空判 `ENTRANCE_BLOCKED`
  （实测 pen A2 朝北被 house A1 挡）。本轮绕法：所有模块**朝外**开门。
  正解：footprintBox 改用 `template.sizeX()/sizeZ()`（= 模块真实占地，通道不算）。
- **`place` 的 `ENTRANCE_OUTSIDE_PLATFORM` 警告文案打印模板声明的 `entranceFacing`（旋转前）**，
  旋转 180° 后仍写原文，与实际不符（仅文案，坐标是对的）。

## 9.4 复现命令
```
settlement_base action=set origin_x=.. origin_z=.. floor_y=F cols=4 rows=4 cell_size=8
settlement_platform cx=<中心x> cz=<中心z> size=17 floor_y=F-1 depth=6 clear_height=10 floor_block=minecraft:cobblestone name=basepad
blueprint action=build file=basepad x=<minX> y=<F-1-depth> z=<minZ>
settlement action=place template=core_house cell=A1 rotation=0
settlement action=place template=farm_basic cell=B1 rotation=180
settlement action=place template=pen_sheep cell=A2 rotation=180
settlement action=place template=trade_post cell=B2 rotation=180
/setblock <farm中心x> F <farm中心z> minecraft:water
/setblock ... minecraft:torch   # 通道/边缘
```

## 9.5 未做 / 跳过
- 引羊/引动物（用户明确让跳过）。
- 阶梯（落差不大，暂未修）。
- footprintBox 缺陷（9.3）只绕未修。


---

# 10. 2026-10-10 · 底座基准点（baseLocal）交付

用户 2026-10-10 点名：「每个蓝图都加一个底座坐标标记，说明它站哪、怎么和地基合并。」

- `FacilityTemplate` 加 `baseLocal`（底座基准点局部坐标，缺省 = 底座正中 `(sizeX/2,sizeZ/2)`）。
  各模板：`pen_sheep`/`pen_cow`/`core_house`/`farm_basic`/`trade_post` = `(3,3)`；`platform_cobble14` = `(7,7)`。
- `PlacementMath.basePoint(plan, template)` = 锚点 + 旋转后的 baseLocal，Y = 模块最底层。
- `settlement action=place` 回执新增字段：`base`（世界坐标 + 「坐在地基 y=.. 上」）、`base_local`、`docks_on_y`。
- `settlement action=catalog`：每个模板列出「底座基准点（局部）=(x,z)  最底层相对 floorY 偏移 N」。
- 测试：`PlacementTest.basePointLandsAtAnchorPlusBaseLocal`；`settlement-core:test` 绿。
- 提交 `1a60923a`（7 文件）；部署 `mutation-20261010-064946-adbd08`；实机已验证 place/catalog 输出。


---

# 11. 2026-10-10 · 接到监测台（settlement.jsonl + 「2.4 基地与设施」页）

用户 2026-10-10 点名「接到监测台我看一眼」。

## 11.1 游戏侧（minecraft-numen）
- `SettlementMonitor.publish(type, data)` → 直接 `FMLPaths.GAMEDIR/config/numen/monitor/settlement.jsonl`
  追加 JSONL（category=`settlement`）。照 `AcxMonitor` 写法，**不依赖** api/common 的 `MonitoringJournal`
  ——那个包不在插件编译类路径上（第一次这么写导致 BUILD_FAIL）。
- `SettlementTool` 在 5 处发事件：`survey` / `place` / `place_rejected` / `inspect` / `resume`。
  事件带语义：模板 / 格 / 底座基准点 base / docks_on_y / 受理还是被拒 / 收口 completed/total。
- 提交：`bf7fc308`（修）；并行会话已把其余扫进 `d0cfa8c2`。

## 11.2 监测台侧（rdd-monitor-publish）
- `server.mjs` 的 `CATEGORIES` 加 `"settlement"`；`js/monitor-store.js` pages 加 `settlement`；
  `js/monitor-nav.js` 加子块「2.4 基地与设施」；`js/monitor-app.js` 加 `settlementPage()` 渲染。
- 打开 `http://127.0.0.1:8776/` → ② 工具 → **2.4 基地与设施**。
- 验证：`settlement.jsonl` 写入 place/inspect；`/api/snapshot` 的 `categories.settlement` 读到 16 条。

## 11.3 ★ 事故与教训（务必看）
把 `platform_cobble14` 放到了**已有模块的格**上（A1）——因为"地皮 ↔ 模块互相放行"的豁免，
放置被受理，地皮的清空层（clear 80..89）削掉了模块上半：house 3/15、pen 3/8、trade 11/13。
**已修复**：unregister 全部 5 个 → `/fill 10819 79 2195 10836 88 2212 air`（清掉 79..88，保住 78 的地皮）
→ 重放 4 模块 + 灌水 → 全 COMPLETE。
**教训**：platform↔模块豁免让"地皮压在模块上"变成**合法且破坏性**。应加判据：
候选地皮占地内**已有模块（非 platform）**时，拒绝或强警告。**留给下一位。**
