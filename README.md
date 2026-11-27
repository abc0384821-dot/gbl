# GBL 启动器

Minecraft 启动器，界面用 JavaFX，运行在 Java 21 上。

## 构建

需要 JDK 21（含 javac / jar / jpackage）和 Maven。

```
mvn -f pom.xml package
```

编译产物在 `target/` 下。要打成带 Java 运行时的完整包，用 `jpackage`（本项目发布脚本里有完整流程）。

## CurseForge Key

仓库里不放任何 key。要访问 CurseForge，二选一：

- 环境变量 `GBL_CF_API_KEY`
- 本机文件 `~/.gbl/curseforge.key`，内容是一行 key

两样都没有时，CurseForge 相关功能不可用，其它功能照常。

## 关于这份源码

这里只有 GBL 自己的源码。不含 HMCL 项目的源码，也不含任何 token 或 API Key。

联机引擎的第三方二进制（`vendor/engines/terracotta/`）和 Java 运行时不在仓库里，
发布流程会在打包时自行下载并校验 SHA256。
