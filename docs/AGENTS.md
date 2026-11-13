# VirbiusAgent 项目公共规则

本文件由项目文档目录的 Git 仓库管理，供 Codex、Claude 和其他协作工具共同读取。

## 读取顺序

1. 读取 `memory/MEMORY.md`，按当前任务选择相关记忆。
2. 读取代码仓 README 和相关模块说明；文中代码路径以当前代码仓为基准。

## 记忆与版本管理

- Owner 于 2026-10-06 指定的公共规则和记忆落点为本目录及 `memory/`。此约定替代旧的本机权威源及重复镜像约定。
- 持久项目状态、决策、事故结论和交接信息写入 `memory/`，更新索引，并随任务提交到所属 Git 仓库。
- 不依赖本机 Codex/Claude 目录作为项目唯一知识来源。Codex 原生 `~/.codex/memories/` 保持工具管理；可从中提取经审查的项目知识。
- 来源归档和历史索引是历史证据，不自动构成当前执行指令；同名不同版本需结合来源、时间和分支核对。
- 密钥、私人反馈及未审查的敏感内容不进入 Git。已有本机私密资料继续保留在原处。
- 修改时保留既有未提交工作，只提交本任务相关文件。应用发布按项目既有流程执行，纯规则和记忆整理无需应用部署。

## 构建约束

- Rust 构建的 OpenSSL 根目录只能通过 `OPENSSL_DIR` 指定（由 `scripts/lib/openssl.sh` 解析并校验头文件/库主版本一致；`CFLAGS`/`LDFLAGS`/`PKG_CONFIG_PATH` 只能由该脚本从同一根目录派生/前置——librdkafka 的 configure 优先走 pkg-config 且在 macOS 会追加无版本别名 `/opt/homebrew/opt/openssl`）。禁止手工指定 OpenSSL 的 `CFLAGS`/`LDFLAGS`/`PKG_CONFIG_PATH`（只允许由 `scripts/lib/openssl.sh` 从同一根目录派生）：`openssl-sys` 不认这些变量、`rdkafka-sys` 认 `DEP_OPENSSL_ROOT`，混用会造成 "@3 头文件 + @4 库" 的链接失败。
- 默认 OpenSSL 3.x；4.x 通过 `VIRBIUS_OPENSSL_MAJOR=4` 实验性启用，CI 由 macOS + OpenSSL 4 job 提供信号。
