# 索引 · 自变异系统 v3 资产清单

> 本文件是自变异系统的**总索引**。指向每一项资产的位置、用途、状态。
> 更新历史见 `08-更新日志.md`。生成日期 2026-09-25，共 71 项（另加本文档）。

## A. 入口与索引
| 文件 | 用途 |
|---|---|
| `README.md` | 系统总览（是什么/三档/门禁/边界） |
| `INDEX.md` | 本文件，资产清单 |
| `08-更新日志.md` | 变更流水（以后更新都写这里） |
| `_manifest.txt` | 自动生成的完整文件清单 |

## B. 总览 `01-总览/`
| 文件 | 内容 |
|---|---|
| `全流程.md` | 自编译闭环一段话总结 + 三入口 + 状态机要点（供外部审核） |
| `系统蓝图.md` | 三档/Harness 协作/心跳/双实例/晋级门/权限边界 |
| `术语.md` | mutation/candidate/gate/DELIVERED/STOP_LOSS 等术语 |

## C. 规范 `02-规范/`
| 文件 | 内容 |
|---|---|
| `九步流与软件工程规范.md` | 开工分类、影响面、边界测试、debug、真实证据、commit、止损、闭环证据 |
| `三档任务模型.md` | 一/二/三档范围与审批链 |
| `晋级门与状态机.md` | Promotion Gate 流水线 + 自治状态机 + 权限归属 + Kill Switch |

## D. 脚本 `03-脚本/`
**怎么运行见 `03-脚本/怎么运行.md`**。脚本原件在 `03-脚本/scripts/`：

| 脚本 | 作用 |
|---|---|
| `config.ps1` | 公共配置（路径/端口/token） |
| `run-mutation.ps1` | 单轮闭环编排器（**主入口**） |
| `new-mutation.ps1` | 创建 mutation 工作区 |
| `gen-code.ps1` | 生成工具源码落盘 + 登记 |
| `build-jar.ps1` | 安全编译 + 打包 jar |
| `deploy.ps1` | 部署到实例 mods（实验） |
| `launch-mc.ps1` | 启动/关闭 gpt 存档 |
| `mcp-drive.ps1` | 通过 Numen MCP 调用工具/读事件 |
| `verify.ps1` | 读监测台 + 判定 verdict |
| `run-mutation-loop.ps1` | 止损重试循环 |
| `every-10min-drive.ps1` | 看门狗：每 600s 注入「继续」（**防死第一序**） |
| `mc-watchdog.ps1` | MC/监测台看门狗 |
| `opencode-harness.ps1` | opencode CLI harness（readonly/implement + guard） |
| `start-ai-bridge.ps1` / `open-ai-bridge-chrome.ps1` | ai-bridge 启动/Chrome CDP |
| `brain-drive.js` / `brain-command.json` | 外脑链路驱动 |
| `feed-ds-ws.js` | DeepSeek WS 喂料 |
| `selfcompile-queue.json` | 看门狗任务队列 |
| `templates/numentool-sys.txt` | NumenTool 生成系统提示模板 |
| `rdd-selfcompile-README.md` | 外部编排层原始 README |

## E. Skills `04-Skills/`
| Skill | 无触发暗号 | 用途 |
|---|---|---|
| `rdd-selfcompile` | 自编译/自变异 | 生成工具→安全编译→打包→部署→MCP 验证→止损 |
| `mcp-mc-quest` | 数字1 / 通关MC | 长任务轮询通关 MC（需 watchdog） |
| `module-blueprint` | 数字2 / 模块模式 | 目标边界补齐单模块 |
| `gpt-brain` | 数字3 / GPT外脑 | ai-bridge 快速对话 ChatGPT 固化会话 |

装配说明见 `04-Skills/装配说明.md`。

## F. MCP `05-MCP/`
| 文件 | 内容 |
|---|---|
| `接线说明.md` | Numen MCP(8765) + opencode ai-bridge 的接线与端口 |
| `numen/mcp_server.json` | Numen MCP 服务配置（token 已脱敏） |
| `numen/mcp_clients.json` | MCP 客户端配置（已脱敏） |

## G. 历史文档 `06-历史文档/`
| 子目录 | 内容 |
|---|---|
| `散落文档/` | `self-compile-*.md`(5) / `selfcompile-*.md`(2) / `numen-review-index-*.md` / `rdd-*.md`(6) / `MC通关指南.md` / `Codex指挥官运行规范.md` |
| `自编译系统原档/` | 既有 `rdd架构\自编译系统\` 的全部 md/jsonl（roles/bridge/checklists/mutation/self-compile/runtime 报告等） |

## H. 证据 `07-证据/`
| 文件 | 内容 |
|---|---|
| `report/relay-stability-candidates-20260917.md` | 中转 API 稳定性备选 |
| `report/source-bundle-20260924.txt` | 源码打包记录 |
| `report/numen-src-20260925.zip` | Numen 源码审核包（10.76MB，1710 项） |
| `自编译系统原档/run-evidence/*.jsonl` | 运行证据 |

## I. 关键代码位置（原位，不在本目录）
| 组件 | 位置 |
|---|---|
| Numen 源码 | `E:\restart developing doer\minecraft-numen\src` |
| 自编译插件 | `minecraft-numen\plugins\selfcompile` + `plugins\rdd` |
| 外部编排 | `E:\restart developing doer\rdd-selfcompile\`（真源） |
| 游戏实例 | `E:\.minecraft\versions\The Best of Twilight Forest\`（gpt 存档） |
| 监测台 | `E:\restart developing doer\rdd-monitor-publish-20260913\monitoring-station\` |
