# LiuChat

[![Maven CI](https://github.com/liuxingyu2001/LiuChat/actions/workflows/maven.yml/badge.svg)](https://github.com/liuxingyu2001/LiuChat/actions/workflows/maven.yml)

Paper / Leaf 1.21+ 聊天插件（Java 21，零第三方框架依赖）。当前使用 Paper 1.21.11 API 编译；1.21 早期小版本尚未验证，部分功能可能需要较新版本。

- **平台**：完整物品悬浮及 Dialogs 使用 Paper API
- **存储**：SQLite（单服）/ MySQL（跨服共享），驱动经 plugin.yml `libraries` 由 Paper 自动下载
- **跨服**：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持，协议 9）；可选 `cross-server.transport: redis` 改走 Redis pub/sub（手写 RESP 零依赖，空服可收发，失败自动回落代理）
- **可选依赖**：PlaceholderAPI、CustomNameplates、CraftEngine、SoulSpace、Citizens；未安装时基础聊天可用

## 功能一览

聊天格式化（hover/click 节点）、物品与头像快照（`[i]` 与多件展示 `[i12]`/`[i1-9]`/`[盔甲]`/`[副手]`、潜影盒/收纳袋预览）、灵魂空间戒指只读预览、公屏 AI 聊天、@ 提及、全服喇叭、快捷触发、屏蔽与昵称、跨服聊天与跨服私聊（回执式送达确认）、禁言与 MySQL 跨服共享禁言、聊天冷却、重复检测、颜色权限、PAPI 变量、AI 聊天审核与多助手问答、每日聊天日志。

详见 [docs/features.md](docs/features.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [功能一览](docs/features.md) | 全部功能的详细说明 |
| [命令](docs/commands.md) | 命令列表、权限、部署提示 |
| [配置要点](docs/configuration.md) | 各配置文件、聊天颜色、私聊格式 |
| [AI 功能](docs/ai.md) | 聊天审核、AI 助手、会话与省 token |
| [跨服](docs/cross-server.md) | 跨服私聊链路、Velocity 兼容性、部署清单 |
| [插件 API](docs/api.md) | `broadcastAnnouncement` / `broadcastItemAnnouncement` |
| [架构](docs/architecture.md) | 包结构与关键设计 |
| [路线图](docs/roadmap.md) | 开发计划 |
| [端到端验收](docs/e2e-acceptance.md) | 实际代理、双后端、客户端验收矩阵与发布门禁 |

## 构建

```bash
mvn clean package
# 产物: target/Liu-LiuChat-<项目版本>.jar（含单元测试）
```

CI（`.github/workflows/maven.yml`）在 push / pull request 时以 Java 21 执行 `mvn -B clean verify`（编译、单元测试、打包）。

## 开源协议

LiuChat 使用 [MIT License](LICENSE) 发布。你可以自由使用、复制、修改和分发本项目，但必须保留版权声明和许可证文本。Paper、Leaf、Adventure、SQLite JDBC、MySQL Connector/J、PlaceholderAPI 以及服务器中安装的其他可选插件均按各自项目的许可证提供，不因 LiuChat 使用 MIT License 而改变其许可证。
