# 发布说明

## 1. 修改版本号

编辑 [app/build.gradle](/root/workspace/app/build.gradle)。

- `versionCode`：每次发布必须递增（整数，不能回退）。
- `versionName`：展示给用户的版本号（如 `4.3.3`）。

示例：

```gradle
versionCode = 154
versionName = "4.3.3"
```

## 2. GitHub Action 触发方式

当前仓库会在 GitHub Release 发布时自动构建 APK。

- 工作流文件：[.github/workflows/build-apk.yml](/root/workspace/.github/workflows/build-apk.yml)
- 触发事件：`release.published`
- 构建任务：`:app:assembleLibreRelease`
- 上传产物路径：`app/build/outputs/apk/libre/release/*.apk`

## 3. 在 GitHub 配置签名证书

进入仓库：

`Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`

新增以下 4 个 Secrets：

1. `ANDROID_KEYSTORE_BASE64`
2. `ANDROID_KEYSTORE_PASSWORD`
3. `ANDROID_KEY_ALIAS`
4. `ANDROID_KEY_PASSWORD`

### 3.1 生成 `ANDROID_KEYSTORE_BASE64`

Linux：

```bash
base64 -w 0 your-release-key.jks
```

macOS：

```bash
base64 your-release-key.jks | tr -d '\n'
```

PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your-release-key.jks"))
```

将输出结果完整复制到 `ANDROID_KEYSTORE_BASE64`。

### 3.2 CI 如何使用这些 Secrets

工作流会解码证书并生成临时 `keystore.properties`：

```properties
storeFile=keystore.jks
storePassword=<ANDROID_KEYSTORE_PASSWORD>
keyAlias=<ANDROID_KEY_ALIAS>
keyPassword=<ANDROID_KEY_PASSWORD>
```

`app/build.gradle` 会读取 `keystore.properties`。如果字段完整，Release APK 会自动使用该证书签名。

## 4. 本地签名（可选）

本地构建签名包时，也可以在仓库根目录创建 `keystore.properties`（字段同上）。

`keystore.properties` 已被 `.gitignore` 忽略，不会提交到仓库。

## 5. 发布流程

1. 修改 `versionCode` 和 `versionName`。
2. 推送代码到 GitHub。
3. 在 GitHub 创建并发布一个新的 Release。
4. 等待 Actions 工作流 `Build And Upload Libre APK` 执行完成。
5. 在该 Release 页面检查并下载上传的 APK 资产。
