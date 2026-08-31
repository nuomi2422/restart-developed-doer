# Self-Compile 模块 · 工作区与请求阶段

## 当前能力

插件现在提供两个受控工具：

- `selfcompile_status`：只读返回当前模块状态；
- `selfcompile_request`：创建一次带审计 manifest 的隔离变异工作区。

`selfcompile_request` 只做以下事情：

```text
校验需求
→ 创建 config/numen/selfcompile/mutations/<mutation-id>/
→ 创建 source/classes/artifacts/reports
→ 写入 manifest.json
→ 返回 workspace_created
```

它不会执行 shell，不会调用编译器，不会修改宿主源码，不会部署 JAR。

## 安全边界

需求字符串只写入 JSON manifest，并进行 JSON 字符转义；它不是命令行参数，也不会被拼进 shell 命令。所有工作区路径都必须位于 Self-Compile 根目录下。

## 下一阶段

生成器、编译器、实验验证器和交付器必须继续经过独立状态转换和止损门禁，不能因为工作区已创建就自动进入生产流程。
