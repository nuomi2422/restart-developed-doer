# `_host-touch/` —— 住在宿主模块路径下的「接缝文件」

这里的文件**是我们写的**，但它们的包路径落在宿主 Numen 的模块里
（`ai/`、`api/common/`、`core/common/`），因为 Java 的包 + 访问权限要求它们必须住在那里。

- 只收录**新增**文件（`git diff --diff-filter=A`）。**修改**宿主已有文件的差分，
  全部在 [`../patches/numen-host-modifications.patch`](../patches/numen-host-modifications.patch) 里。
- 目录结构 = 宿主里的真实路径，方便对照 apply / 回滚。
- 这里**不是**宿主源码；宿主源码请去上游或宿主 fork。

> 判据：把 `patches/numen-host-modifications.patch` apply 到干净的宿主
> `Dwinovo/minecraft-numen` 上，再把 `_host-touch/` 里的文件按原路径放回去，
> 就复原了我们改过的宿主状态。
