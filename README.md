# Wu相机

原生 Android 拍照应用，使用 Kotlin 和 CameraX，支持 Android 10（API 29）及以上。

## 功能

- 预览、拍照，预览和成片共用裁剪区域，JPEG 照片保存到系统相册的 `Pictures/WCamera`。
- 切换前后摄像头，支持的设备可切换闪光灯关闭、自动和开启。
- 点击预览对焦，双指缩放。
- 点击最近拍摄照片的缩略图，使用系统图片查看应用打开。

不同设备的摄像头、闪光灯、对焦及缩放能力可能不同。应用需要相机权限；Android 10 及以上保存本应用拍摄的照片不需要存储权限。

## 构建

使用 Android Studio 打开此目录，安装 Android SDK 35，并使用 JDK 17。等待 Gradle 同步完成后运行 `app`，或在命令行运行：

```powershell
.\gradlew.bat assembleDebug
```

APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。拍摄功能需要有摄像头的 Android 设备或配置了摄像头的模拟器。

## 在 GitHub 上构建

将项目文件按原有目录结构上传到 GitHub 仓库，尤其要包含 `.github/workflows/android-build.yml`、`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar`。不需要上传 `local.properties`、`.gradle` 或 `build` 目录。

仓库启用 Actions 后，推送代码或提交 pull request 会自动构建；也可以在 **Actions → Android debug build** 中选择 **Run workflow** 手动运行。构建成功后，在该次运行页面的 **Artifacts** 下载 `WuCamera-debug`，解压即可获得 `app-debug.apk`。构建失败时可在该页面查看日志。

工作流使用 JDK 17、Android SDK 35 和项目自带的 Gradle 8.9 Wrapper。生成的是用于安装测试的调试包，不是正式发布包；不同运行生成的调试签名可能不同，覆盖安装失败时需卸载旧调试包（卸载会清除应用数据）。
