# 固定 Android 开发签名

后续 Android 普通版、预览版、debug/release、本地和云端均沿用 v0.6.2 已发布版本的
同一签名。不会为每次开发更新生成新密钥。

公开证书 SHA-256 记录在根目录 `signing-certificate.sha256`：

`cad8cafde9bcc430eb5e03a8ae3b7a0a182845753e151f73e53413b98117ff19`

## 保管方式

- GitHub 仓库 Secret：`RESONANCE_ANDROID_KEYSTORE_BASE64`，保存加密托管的签名材料。
- 本地：被 Git 忽略的 `.ci-signing/debug.keystore`，与云端为同一密钥。
- 本次工作区另有 Windows 当前账户绑定的加密备份，存于源码目录之外。
- 私钥、备份解密材料和 Secret 明文不得提交到仓库、写入日志或放进下载包。

云端只从 Secret 恢复，不再依赖缓存，也不会在 Secret 缺失时临时生成密钥。
Gradle 打包同样校验证书指纹；缺少或不匹配就停止，以免发布不能覆盖升级的包。
配置相同签名适用于本项目开发更新；若将来正式发行，需单独规划发布/托管与备份策略。

## 本地构建

现有本机已配置共享密钥，原有 Gradle 命令或本地构建入口仍可使用。
新电脑需要通过安全方式恢复同一密钥至 `.ci-signing/debug.keystore`，
或用 `RESONANCE_DEBUG_KEYSTORE` 指向它。不要新生成一个替代密钥来绕过打包失败。

## 更新规则

覆盖升级需要包名相同、签名相同、versionCode 不降低。正常功能迭代仍应增加 versionCode。
普通版 `com.resonance.app` 更新普通版；预览版 `com.resonance.app.preview` 更新预览版。
两者同签名，但包名不同，所以保留为并行应用，数据也分开。

早期 v0.6.1 等云端构建的原签名未保存，不属于当前固定签名，不能恢复覆盖兼容性。
请保留旧版及数据，或在有完整备份/恢复方案后迁移；不要直接卸载或清除数据。
