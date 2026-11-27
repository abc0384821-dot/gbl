# 参与 GBL 开发

欢迎提 Issue、提 PR、改文档。下面是最少必要的信息，照着做能少很多来回。

## 环境

- JDK 21，要带 `javac`、`jar`、`jpackage`
- Maven 3.9 以上
- 界面用 JavaFX 21，必须走 module-path（`module-info.java` 里已声明 `com.mclauncher`）

## 构建与运行

```bash
mvn -f pom.xml package        # 编译并打 jar，产物在 target/
mvn -f pom.xml javafx:run     # 直接起界面，调界面用这个最省事
```

Windows 上中文显示成方块的话，确认编码参数是 `-Dfile.encoding=UTF-8`（`javafx-maven-plugin` 里已经带上了）。

## 提 Issue

请带上这几样：

- GBL 版本号（设置页能看到）和操作系统
- 复现步骤：点了哪里、期望什么、实际什么
- 报错信息或日志片段
- 涉及模组、整合包时给出名字和版本

单个问题一个问题，别把几件事塞进同一个 Issue。

## 提 Pull Request

- 一个 PR 只做一件事，无关改动不要混进来。
- 提交前跑一遍 `mvn -f pom.xml package`，确保能编过。
- 说明改了什么、为什么改、怎么验证的。界面改动附前后对比截图更好。
- 不要提交 `target/`、日志、密钥、第三方二进制。

## 代码风格

- Java 按 Google Java Style：4 空格缩进，行宽 100 左右，类名大驼峰，方法名小驼峰。
- 公开类和公开方法写 Javadoc，说清用途和边界条件。
- 命名要说人话，别用 `data`、`tmp`、`flag` 这种空词。
- 界面文案面向玩家：不上内部术语，不甩异常堆栈，告诉用户下一步该做什么。
- 加依赖前先想清楚，能不加就不加。

## 目录约定

- `src/main/java/com/mclauncher/`：业务逻辑；`ui/` 放界面层，`multiplayer/` 放联机。
- `src/main/resources/`：图片、皮肤、CSS。
- `src/main/deploy/`：打包用的图标和 authlib-injector。

## 安全

仓库里不允许出现任何 API Key、token 或个人绝对路径。CurseForge Key 一律从环境变量 `GBL_CF_API_KEY`、`~/.gbl/curseforge.key` 或设置页读取。

## 协议

提交的代码默认以 MIT 协议发布。
