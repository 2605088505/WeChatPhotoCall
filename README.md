# 亲情照片通话（WeChatPhotoCall）

给不识字的老人使用：主界面只显示家人照片，点头像后自动打开微信并发起视频通话。

**无需 root 权限**，全程使用 Android 无障碍服务模拟点击。

---

## 功能概述

| 功能 | 说明 |
|------|------|
| 照片主界面 | 大图展示家人头像，一键拨打 |
| 坐标校准 | 悬浮面板逐步引导，手动标定每个微信按钮位置 |
| 演示模式 | 走完全流程但不点最后"确认视频通话"，安全验证用 |
| 点击高亮 | 自动流程中每次点击前在屏幕显示红色标记，方便确认位置 |
| 输入法剪贴板粘贴 | 写入系统剪贴板 → 点输入法剪贴板按钮 → 点第一条，绕过微信屏蔽粘贴的限制 |

---

## 技术方案（无 root）

1. `PhotoCallAccessibilityService`（`:a11y` 进程）通过 `dispatchGesture()` 按坐标点击
2. 悬浮面板（`CalibrationOverlayService`）引导用户手动校准微信各按钮坐标
3. 搜索联系人时，将搜索名写入系统剪贴板，再通过输入法剪贴板面板完成粘贴
4. 两个核心服务运行在独立的 `:a11y` 进程，避免被 ColorOS/MIUI 后台冻结
5. 跨进程通信通过 `BroadcastReceiver` 实现（直接 `instance` 引用跨进程无效）

---

## 点击流程（全程坐标驱动）

```
打开微信主界面
  └─ (如不在消息列表则 BACK 一次)
     └─ 点击搜索入口（右上角搜索图标）
        └─ 点击搜索输入框（聚焦弹键盘）
           └─ 写入剪贴板
              └─ [可选] 点击输入法总菜单（有些输入法需要）
                 └─ 点击输入法剪贴板按钮
                    └─ 点击剪贴板第一条（完成粘贴）
                       └─ 点击搜索结果行
                          └─ 点击聊天页加号 (+)
                             └─ 点击视频通话
                                └─ [演示模式在此停止]
                                   └─ 点击确认视频通话 (实拨)
```

---

## 需要校准的坐标（共 8 个必选 + 3 个可选）

| 步骤 | 说明 | 必须 |
|------|------|------|
| 搜索入口 | 微信主界面右上角搜索图标 | ✅ |
| 搜索输入框 | 搜索页面输入框 | ✅ |
| 输入法剪贴板按钮 | 输入法键盘上的剪贴板入口 | ✅ |
| 剪贴板第一条 | 剪贴板面板弹出后的第一个文本项 | ✅ |
| 搜索结果行 | 搜索出联系人后的那一行 | ✅ |
| 聊天加号 (+) | 聊天页右下角 + 按钮 | ✅ |
| 视频通话 | + 面板中的"视频通话"图标 | ✅ |
| 确认视频通话 | 弹窗里的"视频通话"确认按钮 | ✅ |
| 输入法总菜单 | 某些输入法剪贴板藏在子菜单里 | 可选 |
| 微信标签 | 底部 Tab 栏微信图标 | 可选 |
| 返回 | 返回按钮 | 可选 |

---

## 使用步骤

### 1. 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

或直接把 APK 文件发到手机安装。

### 2. 授权

进入系统设置完成以下两项：

- **无障碍服务**：找到"亲情照片通话"并开启
- **悬浮窗权限**：在 App 权限设置里允许"显示在其他应用上层"

也可以在 App 内的"设置"页面点对应按钮跳转授权。

### 3. 坐标校准

1. 在 App 内打开微信（校准面板会弹出）
2. 按面板提示，逐步点选每个微信按钮的位置
3. 每步点击后可用"验证此点"按钮确认红色标记落点正确
4. 全部步骤完成后点"保存退出"

### 4. 添加联系人

在 App 内点击"+"，填写：

- **显示名**：主界面上显示的名字（如：奶奶、妈妈）
- **微信搜索词**：微信搜索框里能搜到的名字（微信昵称或备注）
- **头像**：从相册选择照片

### 5. 测试

建议先保持**演示模式**开启（设置页面），演示模式走完完整流程但不点最后的"确认视频通话"，可以反复测试确认每一步都正确。

确认无误后，在设置页面关闭演示模式即可实际拨打。

---

## 构建

### 前提条件

- JDK 17（推荐 [Eclipse Temurin](https://adoptium.net/)）
- Android SDK（API 34）
- Gradle 8.11.1

### 配置本地路径

复制 `local.properties.example` 为 `local.properties`，修改 SDK 路径：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

### 编译

Windows：
```bat
gradlew.bat :app:assembleDebug
```

Linux / macOS：
```bash
./gradlew :app:assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

---

## 项目结构

```
WeChatPhotoCall/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/family/photocall/
│       │   ├── MainActivity.kt              # 照片主界面
│       │   ├── CalibrationActivity.kt       # 校准入口 Activity
│       │   ├── ContactEditActivity.kt       # 联系人编辑
│       │   ├── SettingsActivity.kt          # 设置页
│       │   ├── data/ConfigRepository.kt     # 配置读写 (Gson JSON)
│       │   ├── model/Models.kt              # 数据模型
│       │   └── service/
│       │       ├── PhotoCallAccessibilityService.kt  # 核心，手势点击
│       │       ├── CalibrationOverlayService.kt      # 悬浮校准面板
│       │       └── CallKeepAliveService.kt           # 前台保活服务
│       └── res/
│           ├── layout/                      # 所有布局文件
│           └── xml/accessibility_service_config.xml
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── configs/
│   └── sample_config.json                  # 配置文件示例
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat                             # Windows 构建脚本
├── local.properties.example
└── README.md
```

---

## 常见问题

**Q: 点击没有反应 / 无障碍未连接**  
A: 确认无障碍服务已开启且 App 在后台策略里设置为"无限制"。ColorOS/MIUI 会主动冻结后台进程。

**Q: 点击位置偏移**  
A: 重新校准。如果微信版本更新或手机换了，按钮位置可能变化，需要重新走一遍校准流程。

**Q: 粘贴失败 / 搜索框没有内容**  
A: 检查输入法剪贴板坐标是否正确。建议用"验证此点"功能逐一确认。如果输入法剪贴板需要先点总菜单才能进入，需要额外校准"输入法总菜单"这一步。

**Q: 流程中途停止 / 提示失败**  
A: 打开演示模式，重新运行，观察每步的红色点击标记落点，找到第一个落点错误的步骤重新校准。

---

## 注意事项

- 本 App 仅通过 Android 官方无障碍 API 工作，**不需要也不使用 root 权限**
- 坐标校准数据保存在手机本地，换机需要重新校准
- 微信版本更新后如果界面布局变化，可能需要重新校准
- 演示模式下不会真的拨出电话，可以放心反复测试
