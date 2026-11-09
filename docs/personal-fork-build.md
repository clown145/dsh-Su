# 个人 Fork 的 APK 构建与更新

本 Fork 保留 DSH SU 的 Root 运行时，合入 DSH Go 的通用功能，使用自己固定的签名密钥。应用更新源是本 Fork 的 Release，不跟随原版 DSH SU 的发布；是否合入上游源码，由维护者自行决定。

## 首次安装与后续更新

包名仍为 `io.github.rakanrkk.dsh_su`。个人签名与原版签名不同，**首次不能直接覆盖已安装的原版 DSH SU**。请先备份需要保留的数据，再卸载原版并安装本 Fork；卸载通常会清除应用数据。

后续本 Fork 使用同一个签名密钥和更高的 `versionCode`，即可作为覆盖更新安装。请长期保存这把密钥；换密钥会破坏已有安装的更新连续性。真实 Root 授权、厂商系统兼容性、手机操控和数据迁移仍需在目标手机上验收。

## 配置固定个人签名

使用为此 Fork 创建的一把长期签名密钥，在仓库的 **Settings → Secrets and variables → Actions** 配置以下四个 repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 个人 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 对应私钥口令 |

将 keystore、两个口令和别名保存在安全的离线备份中；只设置 GitHub Secrets 不能代替备份。以后构建继续使用同一份 keystore，不要每次重新生成密钥。

工作流缺少任意一个 Secret 就会明确失败，不会生成 debug 或临时随机签名的安装包。它读取配置密钥的公开证书指纹，在构建后用 `apksigner` 验证 APK 确实由该密钥签名。私钥和 `app/android/key.properties` 不提交到 Git、不作为 artifact 上传，工作流结束后删除临时签名材料。

## 下载构建产物

Fork 后需要先在 GitHub 的 Actions 页面启用工作流。工作流在 `main` 或 `integrate/**` 分支推送后运行，也可以打开 **Actions → Build SU APK → Run workflow** 手动运行。

| 构建模式 | 行为 |
| --- | --- |
| `personal-signed`（默认，自动推送构建也使用） | 用四个 Secrets 中的固定个人密钥签名，缺少配置时失败 |
| `unsigned`（手动选择） | 明确关闭 release 签名，用于检查或离线签名，文件名含 `UNSIGNED` |

打开成功构建页面，下载底部的 artifact。压缩包包含 ARM64 APK、`SHA256SUMS`、APK 信息、签名检查记录和 `BUILD-INFO.txt`。`PERSONAL-SIGNED` 是个人签名安装包；**`UNSIGNED` APK 不能安装**。

工作流核对 ZIP 完整性、包名、运行时 ABI、SU 插件和 runc。签名模式还会核对 APK 与配置密钥的证书指纹；未签名模式确认 APK 不含 JAR 签名文件或 APK 签名块，且 `apksigner verify` 不能通过。

Actions 构建自动将 `DSH_UPDATE_REPO` 设置为当前仓库 `${{ github.repository }}`，此处即 `clown145/dsh-Su`。只上传 Actions artifact 不会成为 App 的更新源；需要维护者另行发布符合现有更新协议的个人签名 Release 与元数据。

## 同步上游

以后需要同步时，由维护者自行打开 **Actions → Upstream Sync → Run workflow** 手动运行。它会把 `ccch1mneyyy/dsh-Go` 的 `main` 合到同步分支，并创建或更新 PR；合并冲突时停止，由维护者处理。没有定时或 PR 事件触发，也不会自动合并 PR、发布 APK、部署服务或删除分支；同步分支在合入后由维护者手动清理。

如创建 PR 报权限错误，在仓库的 **Settings → Actions → General → Workflow permissions** 允许 GitHub Actions 创建 Pull Request。工作流使用当前仓库的 `GITHUB_TOKEN`，不需要上游作者的令牌。

## 本地构建

按 README 准备 Flutter、JDK 17、Android SDK 和运行时构建依赖。个人签名构建前，在不提交到 Git 的 `app/android/key.properties` 中填写固定密钥的 `storeFile`（建议绝对路径）、`storePassword`、`keyAlias` 和 `keyPassword`，并确认指向与 Actions 相同的 keystore。

```bash
ROOT_RUNTIME=1 bash runtime/build-runtime.sh arm64-v8a
DSH_UNSIGNED_RELEASE=0 DSH_UPDATE_REPO=clown145/dsh-Su \
  bash tooling/build-root-apk.sh -f arm64 --keep-daemon
```

本地 Gradle 保留原项目的行为：未提供 `key.properties` 时可能回退到 debug 签名。因此，本地可安装发行包必须先配置上述固定密钥，并在交付前用 Android SDK 的 `apksigner verify --verbose --print-certs` 核对证书；Actions 已强制执行这些签名检查。

需要未签名检查包时，显式使用：

```bash
DSH_UNSIGNED_RELEASE=1 DSH_UPDATE_REPO=clown145/dsh-Su \
  bash tooling/build-root-apk.sh -f arm64 --keep-daemon
```

`ROOT_RUNTIME=1` 会把 SU 专用插件装入运行时；APK 构建脚本另外启用 Root 模式并准备 runc。`DSH_UNSIGNED_RELEASE=1` 传入 `dsh.unsignedRelease=true`，使 Gradle 的 release `signingConfig` 为 `null`，即使存在本地签名配置也不签名。

Flutter 输出路径均为 `app/build/app/outputs/flutter-apk/app-arm64-release.apk`，请根据构建模式区分。`DSH_UPDATE_REPO` 可显式指定更新仓库；未设置时优先使用 `GITHUB_REPOSITORY`，本地默认 `clown145/dsh-Su`。
