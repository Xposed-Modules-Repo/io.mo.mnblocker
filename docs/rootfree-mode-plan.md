# 免 Root 模式实施方案（NotificationListenerService）

> 本文是实施交接文档，面向直接动手改代码的人。所有结论均已核实过源码，**不要凭记忆推翻**；
> 若要推翻，请按文末「结论是怎么来的」一节复核。
> 配套阅读：仓库根目录 `CLAUDE.md`（架构约定、已知坑）。

## 1. 目标与边界

给 `io.mo.mnblocker` 新增一条**完全免 root** 的通知拦截路径，基于标准 SDK 的
`NotificationListenerService`（用户只需在「设置 → 通知使用权」授权，无需 root / Magisk / KernelSU / LSPosed）。

**红线：现有 root（Xposed hook）路径必须保持行为不变。** 本方案对 `NotificationHook.java` 的改动只有 2 行。

**两种模式互斥**：首次启动引导页二选一，设置页可随时切换。不存在两模式同时生效的情况。

> ⚠️ 澄清一个常见误解：LSPosed 框架本身的安装运行必须依赖 root，这是 Xposed 生态的硬前提。
> 本方案不是"让 LSPosed 模块免 root"，而是**另起一条不经过 LSPosed 的平行路径**，
> 让未 root 用户也能用上核心能力（能力有降级，见下）。

## 2. 已核实的硬约束（动手前必读）

### 2.1 免 root 做不到「静默永久屏蔽渠道」

`NotificationListenerService.updateNotificationChannel(String, UserHandle, NotificationChannel)`
确实是 **since=26 的公开 SDK API**（能编译），但服务端 `NotificationManagerService.verifyPrivilegedListener()`
要求调用方满足以下之一：

1. 持有 `CompanionDeviceManager` 关联，或
2. 是系统级 Notification Assistant 角色。

普通的「通知使用权」授权**两个都不满足**，调用会抛 `SecurityException`。
绕路方案（自管理 CDM 关联）需要 `REQUEST_COMPANION_SELF_MANAGED` 权限，该权限在 AOSP
`core/res/AndroidManifest.xml` 里声明为 `protectionLevel="signature|privileged"`，普通 APK 拿不到。

**结论：免 root 侧唯一可用的拦截机制是 `cancelNotification(key)`** —— 通知会**先短暂出现**
（可能有提示音/震动）再被撤销。这是相对 root 模式的真实能力降级，产品上已接受，但**必须在 UI 上明确告知用户**。

### 2.2 不要写「机会主义的 updateNotificationChannel 尝试」

曾考虑加一段 `try { updateNotificationChannel(...) } catch (SecurityException) {}`，理由是"反正无副作用"。
**这个理由不成立，已决定不做**：它只在**失败时**无副作用。一旦在某个放宽了限制的 ROM 上真的成功，
就会把别人 App 的渠道**永久**设成 `IMPORTANCE_NONE`，而免 root 侧没有特权把它改回来——
用户后续点「允许」也恢复不了，渠道永久死亡且无法从本 App 撤销。

免 root 路径**只用 `cancelNotification()`**，语义干净、天然可逆。

### 2.3 API 级别（已用本机 SDK `api-versions.xml` 核实）

| API | since | 影响 |
|---|---|---|
| `NotificationListenerService` | 18 | OK |
| `cancelNotification(String)` | 21 | OK，免 root 的核心机制 |
| `Ranking.getChannel()` | **26** | 读渠道元数据需 `SDK_INT >= 26` 门控 |
| `updateNotificationChannel(...)` | 26 | 公开但运行时被特权门拦截，见 2.1，不用 |

项目 `minSdk 24` / `compileSdk 34` / `targetSdk 34`。API 24-25 上拿不到渠道信息，
**只有内容级匹配可用**——与 `CLAUDE.md` 已记录的「minSdk 24 但 channel API 是 26+，低版本路径实际未被行使」
这一既有取舍一致，按同样方式加 `Build.VERSION.SDK_INT >= 26` 守卫即可。

### 2.4 免 root 侧 SharedPreferences 就是唯一真相源

监听器与设置 UI **同进程同 uid**，所以：

- 规则/开关数据**已经**由 `MainActivity` / `WhitelistActivity` 每次保存时同步写进
  `getSharedPreferences("mnblocker_prefs", MODE_PRIVATE)`，免 root 侧直接读即可，**零新增管道**。
- 整个 `ConfigFileStore` / `ShellUtils` 的 root 文件桥在免 root 模式下**完全不需要**，直接跳过即可
  （UI 反而更快，见 §6）。

## 3. 可复用 vs 需新建

| 组件 | 结论 |
|---|---|
| `RuleMatcher.java` | **原样复用**。零 Android/Xposed 依赖，`compile()` / `firstBlockMatch()` / `firstAllowMatch()` 全是字符串进字符串出。包级私有，新类必须放在 `io.mo.mnblocker` 包内（本来就是单 module，无影响）。 |
| `RuleMatcher.decideBlock()` | ⚠️ **不要以为它能直接用**。它**只有 `RuleMatcherTest` 在调用**，生产代码无人使用，且**不包含 app 白名单那一步**，不是完整的优先级链。 |
| 配置数据 | **原样复用** `mnblocker_prefs` 里的全部 key（见 `RegexConfig.java:42-50`）。 |
| `ChannelRecord.java` | **原样复用**。schema 与 hook 无耦合，无 source 字段。 |
| `RegexConfig.java` | 不能直接用（围绕 `XSharedPreferences` + root 文件桥）。需一个免 root 镜像。 |
| `DetectedChannelsStore` / `ContentStatsStore` / `ContentBlockLogStore` | **三个都不能用**——见 §4，这是初版方案最大的遗漏。 |
| `OriginalChannelStateStore` | **免 root 侧不需要**。因为从不改渠道重要性，没有任何东西需要恢复（这比 root 路径更简单）。 |

## 4. ⚠️ 关键遗漏：三个存储在免 root 下都写不了

`DetectedChannelsStore`、`ContentStatsStore`、`ContentBlockLogStore` 的 `flush()` 都是
**`FileWriter` 直写 `/data/system/mnblocker/`**，这只有跑在 system_server（UID 1000）里的 hook 能做到。
免 root 监听器跑在 App 自己的 uid 下，**根本写不进去**。

若不处理，免 root 模式下会出现：

- 渠道列表永远是空的（`DetectedChannelsStore`）
- **拦截统计页全是 0**（`ContentStatsStore`，`MainActivity.java:1099`）
- **点击软件看拦截详情页全空**（`ContentBlockLogStore`，`BlockedNotificationsActivity.java:100`，
  即 commit `bb79c6e` 刚做的功能）

因此必须新建三个 App 私有目录版本（见 §5）。

> 💡 一个意外收获：`ContentBlockLogStore` 为了防止其他 uid 读到通知文本，特意把文件锁成 0600 并让 App 侧靠
> `su` 读（见其 javadoc 与 `ContentBlockLogStore.java:117-126`）。而 App 私有目录 `getFilesDir()`
> **天然就是私有的**，免 root 版无需任何权限操作、也无需 su 就能读回——比 root 版更简单。

## 5. 新增文件（全部在 `io.mo.mnblocker` 包内）

### `BlockDecision.java`
无 Android 依赖的静态判定器，返回**判定 + 理由**：

```java
static Result decide(RuleMatcher matcher, Boolean override, boolean appWhitelisted, String... candidates);
static final class Result { final boolean block; final String reason; }
```

优先级必须严格是：`override > app 白名单 > allow 规则 > block 规则 > 默认放行`。

**为什么要新建而不是用 `RuleMatcher.decideBlock()`**：后者不含 app 白名单步骤，且不产出 reason 字符串。
真正的优先级链目前在 `NotificationHook.java` 里**手写了两遍**（`:285-301` 和 `:481-496`），
就是因为还要额外产出 `decisionReason` 供日志用。免 root 路径若再手写一遍就是**第三份拷贝**，
正是 `CLAUDE.md` 点名警告过的「UI 与 hook 对『命中』判断不一致」那类历史 bug。

**范围限制（已决策）**：本次 `BlockDecision` **只在免 root 路径调用**，
`NotificationHook.java` 那两处手写链**一行都不要动**——root 路径零风险优先。
把 hook 那两处也收敛过来是合理的后续工作，但请单开 PR 并配真机回归。

### `RootFreeConfig.java`
`RegexConfig` 的免 root 镜像。直接 `context.getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE)`，
复用 `RuleMatcher.compile()` 编出 channel matcher 与 content matcher（**content matcher 复用同一份 allow 规则**，
与 `RegexConfig.java:174-176` 的既有语义保持一致）。

暴露：`isMasterEnabled()`、`isRootFreeMode()`、`isMatchDescription()`、`isContentEnabled()`、
`isAppWhitelisted(pkg)`、`overrideFor(pkg, id)`、`channelMatcher()`、`contentMatcher()`。

配置变更需要能被监听器感知：注册 `SharedPreferences.OnSharedPreferenceChangeListener` 重建
（同进程，比 `RegexConfig.reloadIfChanged()` 的 mtime 轮询简单得多）。

### `RootFreeChannelStore.java`
`DetectedChannelsStore` 的免 root 版，存 `getFilesDir()/rootfree_channels.json`，复用 `ChannelRecord`，
保留 `MAX_ENTRIES = 1000` 上限。

### `RootFreeStatsStore.java` 〔补 §4 遗漏〕
`ContentStatsStore` 的免 root 版，存 `getFilesDir()/rootfree_stats.json`。
**沿用完全相同的 `{count, lastBlocked, perApp}` JSON 结构和 `Snapshot` 形状**，
好让 `MainActivity.renderStats()` 的渲染代码可以直接复用。

### `RootFreeBlockLogStore.java` 〔补 §4 遗漏〕
`ContentBlockLogStore` 的免 root 版，存 `getFilesDir()/rootfree_block_log.json`。
沿用相同的 `{"perApp": {"pkg": [{"t","ti","tx","r"}]}}` 结构与 `Entry` 形状，
保留 `MAX_PER_APP=100` / `MAX_APPS=300` / `MAX_TEXT_LEN=500` 三个上限。无需任何 0600/chmod 操作（见 §4 注）。

### `NotificationAccessUtils.java`
- `isListenerAccessGranted(Context)` —— 解析 `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`。
  **不要用** `NotificationManager.getEnabledNotificationListeners()`，它是 `@SystemApi`/`@hide`。
  **不要引入 AndroidX** 的 `NotificationManagerCompat`——项目零 AndroidX 依赖且必须保持（见 `CLAUDE.md`）。
- `openListenerSettings(Activity)` —— `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` 跳转 Intent。

### `RootFreeNotificationListener.java`
免 root 拦截的唯一入口，见 §7。

### `SetupModeActivity.java`
首启模式选择引导页。**纯 Java View、无 XML / AndroidX / Material / RecyclerView**（`CLAUDE.md` 硬约定），
跟随 `MainActivity` 现有的 `cardLayout()` / `sectionTitle()` / `cleanSwitch()` 风格。
两个选项各自说明前置条件与限制：

- **Root 模式** —— 需要 Root + LSPosed；完全静默拦截。
- **免 Root 模式** —— 只需通知使用权；通知会先闪现再撤销。

选择后写入 `KEY_OPERATING_MODE`，`startActivity(MainActivity)` + `finish()`。

## 6. 需修改的现有文件

| 文件 | 改动 |
|---|---|
| `MainActivity.java` | 改动大头，见 §8 |
| `BlockedNotificationsActivity.java` | `:100` 的 `ContentBlockLogStore.readForApp(p)` 按模式路由到 `RootFreeBlockLogStore` |
| `ConfigFileStore.java` | 加 mode 字段，见 §9 |
| `RegexConfig.java` | 加 `KEY_OPERATING_MODE` + `isRootModeActive()`，见 §9 |
| `NotificationHook.java` | **仅 2 行**，见 §9 |
| `AndroidManifest.xml` | 新增 `<service>`（仓库首个）+ `SetupModeActivity`，见 §10 |

**明确不改**：`ShellUtils.java`、`RuleMatcher.java`、`OriginalChannelStateStore.java`、
`DetectedChannelsStore.java`、`ContentStatsStore.java`、`ContentBlockLogStore.java`、
`XposedEntry.java`、`ChannelRecord.java`、`SafetyManager.java`。

## 7. 核心拦截逻辑

```java
@Override
public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
    try {
        RootFreeConfig cfg = config;                        // onListenerConnected() 里构建
        if (cfg == null || !cfg.isRootFreeMode() || !cfg.isMasterEnabled()) return;

        String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return;           // 绝不处理自己的通知
        if (cfg.isAppWhitelisted(pkg)) return;

        Notification n = sbn.getNotification();
        // 前台服务通知绝不能碰（对齐 hook 侧既有铁律）
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;

        NotificationChannel channel = null;                  // Ranking.getChannel() 需 API 26+
        if (Build.VERSION.SDK_INT >= 26 && rankingMap != null) {
            Ranking r = new Ranking();
            if (rankingMap.getRanking(sbn.getKey(), r)) channel = r.getChannel();
        }

        recordObserved(pkg, channel, n);                     // → RootFreeChannelStore（无论判定如何都记录）

        // ---- 渠道级判定 ----
        BlockDecision.Result d = BlockDecision.decide(
                cfg.channelMatcher(),
                channel != null ? cfg.overrideFor(pkg, channel.getId()) : null,
                false,                                       // app 白名单上面已提前 return
                channelCandidates(channel, cfg.isMatchDescription()));

        // ---- 内容级判定（仅在渠道级未命中且用户开启时）----
        if (!d.block && cfg.isContentEnabled()) {
            d = BlockDecision.decide(cfg.contentMatcher(), null, false, extractContentCandidates(n));
            if (d.block) {
                blockLogStore.record(pkg, title(n), text(n), d.reason);   // 仅内容级留详情
            }
        }

        if (d.block) {
            cancelNotification(sbn.getKey());
            statsStore.recordBlock(pkg);
        }
    } catch (Throwable t) {
        // 绝不能崩掉自己的 App 进程。对齐 NotificationHook 的 try/catch(Throwable) 约定
        // （hook 侧是"绝不能崩 system_server"，这里降级为"绝不能崩本 App"）。
    }
}
```

实现要点：

- **没有**「发出前拦截」的接口（免 root 没有 `enqueueNotificationInternal` 的等价物，也没有
  `param.setResult(null)`）。渠道级与内容级判定**统一**走 `onNotificationPosted` + `cancelNotification()`
  —— 这就是「先闪一下再撤销」的根源，且对**所有**拦截生效，不只是内容级。
- **无法枚举旧渠道**（没有非特权枚举 API）。hook 侧靠 query hook 回填已存在渠道的能力，免 root 侧**没有**。
  渠道列表只能随通知实际发生逐步"学习"，必须在 UI 文案里说明。
- **只有内容级拦截产生详情条目**——与 root 版 `ContentBlockLogStore` 的语义保持一致
  （渠道级在 root 版只是降重要性、不拦具体通知，所以没有逐条记录）。免 root 版沿用同样语义，
  避免两模式的详情页含义不同。
- `extractContentCandidates(n)` 可参照 `NotificationHook.java:724` 附近的既有实现。

## 8. `MainActivity` 改造（改动大头）

免 root 模式下，现有这些 su 调用点会**全线踩空**，必须逐点按模式门控。
先加一个私有 helper `private boolean rootFree()`（只读 pref，**不 spawn su**），然后：

| 位置 | 现状 | 免 root 下的问题 | 处理 |
|---|---|---|---|
| `onCreate:152-157` | 无条件后台跑 `ShellUtils.fixDirPermissions()`，失败弹 `main_no_root_hint` | **每次启动都对免 root 用户弹「Root 未授权」**，引导完全错误 | 整段跳过 |
| `loadSwitchesAndRules:915` | `Bg.load(ConfigFileStore::readForApp, ...)` | 无谓 su spawn（100ms ~ 1s+） | 跳过 `Bg.load`，直接用已同步读到的 pref 值。现有代码本就是 `disk.hasValue ? disk.x : prefX`，免 root 下 pref 即真相，**反而更快** |
| `loadWhitelistState:954` | 同上 | 同上 | 同上 |
| `reloadChannelsAndOverrides:1042-1045` | `DetectedChannelsStore.readAllFromDiskForApp()` + `ConfigFileStore.readForApp()` + `ShellUtils.isSafeModeTripped()` | 三个 su 点，且渠道数据源不对 | 渠道改读 `RootFreeChannelStore`；overrides 用 pref；`safeMode` 恒 false |
| `persistSwitches:990`、`persistOverrides:1366` | `persistConfigFile(false, null)` | 无谓 su 写 | 跳过 |
| `onSaveRules:1020-1023` | su 写失败弹 `toast_saved_sync_failed`（「保存并同步失败」） | **免 root 用户每次存规则都看到吓人报错**，其实一切正常 | 跳过 su 写，直接弹保存成功 |
| `refreshStatus:1975-1979` | 显示 safe-mode 状态 | 安全模式是 root 专属概念（SystemUI 崩溃保护） | 免 root 下不显示；状态文案改为反映**监听器连接状态** |
| `refreshStats:1099` | `Bg.load(ContentStatsStore::readForApp, ...)` | 统计全 0 | 按模式路由到 `RootFreeStatsStore` |
| `:1337-1339` | `ContentStatsStore.resetFromApp()` / `ContentBlockLogStore.resetFromApp()` | su 写 | 按模式路由到免 root 版 reset（直接删文件） |

另需：

- **首启跳转**：`onCreate` **最开头**（务必在 `:152` 那个 su 线程之前）检查
  `prefs().contains(KEY_OPERATING_MODE)`，未设置则 `startActivity(SetupModeActivity); finish(); return;`
- **模式开关**：`globalCard()`（`:686-703`，现有 `masterSwitch` / `matchDescSwitch` 所在卡片）新增
  「使用免 Root 模式」开关，按现有 `cleanSwitch()` → listener → persist 模式实现。
  切到免 root 时若 `NotificationAccessUtils.isListenerAccessGranted()` 为 false，展示跳转按钮引导授权；
  `onResume` 时复查（系统设置页**没有** result 回调，只能 resume 时轮询）。
  注意用 `setCheckedSilently(...)`（`:2165-2188`）避免程序化 `setChecked()` 触发持久化循环。
- **常驻限制说明**（不是 `showFadeHint` 那种渐隐提示，这是长期能力限制不是瞬时错误）：
  > 免 Root 模式下通知会先短暂出现再被撤销；无法感知开启前就存在、之后未再触发的旧渠道；
  > 统计数据与 Root 模式分开记录。如需完全静默拦截请使用 Root + LSPosed 模式。
- 互斥后，root 专属控件（清除安全模式按钮等）在免 root 模式下隐藏。
- 所有新增文案走 `res/values*/strings.xml`，注意仓库有 `values-en/`，中英都要加。

## 9. 模式如何传播给 hook（关键正确性问题）

### 9.1 hook 停手必须「恢复」而不是「直接 return」

若 hook 只是 `if (!cfg.isRootModeActive()) return;`，那么之前被压成 `IMPORTANCE_NONE` 的渠道会
**永久静音且无法恢复**（免 root 侧没有特权改回来），用户只能切回 root 模式自救。

**正确做法：复用现有的 master-off 惯例。** 现有代码在 master 关闭时走的是
`restoreChannelIfAllowed(..., true, "master-off")`（见 `NotificationHook.java:303-312` 与 `:476-479`）
—— 即**恢复**渠道而非直接返回。所以模式门控只需把那两处的条件扩一下：

```java
// NotificationHook.java:303 和 :476，各一行
if (!cfg.isMasterEnabled() || !cfg.isRootModeActive()) {
    // ... 沿用既有 restoreChannelIfAllowed(...) 调用，reason 传 "rootfree-mode"
}
```

这样切到免 root 时，hook 会随着渠道再次经过 create/update/query hook 把它们**还原回原重要性**。

> 已知限制：只有再次被触碰到的渠道才会被还原，长期不活动的渠道会停留在静音态。
> 这与现有 master-off 的行为**完全一致**，是既有的已接受行为，不是本次新增的问题。

内容级 hook（`enqueueCallback`）不需要恢复逻辑，直接早返回即可（内容拦截不留持久状态）。

### 9.2 `ConfigFileStore` 必须改（初版说「不改」是错的）

hook 在 system_server 里，只能通过 XSharedPreferences 或 `config.json` 得知模式变化，
而 `CLAUDE.md` 已说明：**部分 ROM 上 XSharedPreferences 读不到，`config.json` 正是为此存在**。
所以 mode 必须进 config.json：

- `ConfigFileStore.writeFromApp()` 增加 mode 参数并 `o.put(RegexConfig.KEY_OPERATING_MODE, ...)`
- `ConfigFileStore.parse()` 读出该字段
- `ConfigFileStore.ConfigSnapshot` 增加 `operatingMode` 字段（构造器 10 → 11 参，注意 `empty()` 同步改）
- `RegexConfig` 增加 `KEY_OPERATING_MODE` 常量、`operatingMode` 字段、`isRootModeActive()`；
  在 `reload()` 的 XSharedPreferences 段（`:119-137`）和 config.json 段（`:139-155`）各读一次，
  沿用现有「两源合并、后者覆盖」的写法。

**默认值必须是 `"root"`** —— 老用户升级后没有这个 key，hook 必须照常工作，不能静默停摆。

### 9.3 何时推送

**规则：仅在模式发生变化时，后台做一次尽力而为的 `persistConfigFile()` 全量推送（含新 mode 字段），失败静默忽略。**

- `root → rootfree`：用户本就有 root，su 成功，hook 读到 mode=rootfree 并开始还原渠道。
- `rootfree → root`：全量推送顺带把免 root 期间改过的规则同步给 hook，避免 config.json 陈旧。
- 首启直接选 rootfree（无 root 设备）：su 会失败，但**这种设备上根本没有 hook，无需告知**
  → **必须静默忽略失败**，绝不能弹「Root 未授权」。
- 首启选 rootfree 但设备其实 rooted + 装了 LSPosed：su 成功，hook 正确停手。

这一次后台 su 尝试**只在设置模式时发生**，不是每次启动。

## 10. `AndroidManifest.xml`

当前 manifest **一个 `<service>` 都没有**。在 `<application>` 内新增：

```xml
<service
    android:name=".RootFreeNotificationListener"
    android:exported="true"
    android:label="@string/rootfree_service_label"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

<activity
    android:name=".SetupModeActivity"
    android:exported="false" />
```

注意：

- `android:exported="true"` 是必需的（系统需跨进程 bind），这是所有第三方通知监听 App 的标准写法。
- **不要**加 `android:process` —— 监听器必须与设置 UI 同进程，这是 §2.4 成立的前提。
- **不需要**新增 `<uses-permission>`：`BIND_NOTIFICATION_LISTENER_SERVICE` 是声明在 `<service>` 上的，
  实际授权走系统设置页，不是 manifest 权限申请。
- **不要**加 `REQUEST_COMPANION_SELF_MANAGED`（见 §2.1，拿不到，纯噪音）。

## 11. 明确不做

- ❌ 依赖 `updateNotificationChannel` 做持久渠道屏蔽（§2.1 已核实基本不可能成功；§2.2 说明为何连"顺手试一下"都不做）
- ❌ 发出前拦截（无非特权等价 API）
- ❌ 旧渠道回填 / 枚举（无非特权等价 API）
- ❌ Notification Assistant 角色路径（系统级唯一角色，跨 OEM 可选性不稳定，超出本次范围；
  它的 `onNotificationEnqueued` + `Adjustment` 理论上能解决"闪现"问题，值得作为独立 spike，但不是现在）
- ❌ `SafetyManager` 的免 root 版（免 root 跑在自己进程，最坏只崩自己，`try/catch(Throwable)` 已兜住）
- ❌ 重构 `NotificationHook` 的两处手写判定链（合理但请单开 PR，本次 root 路径零风险优先）
- ❌ 引入 AndroidX / Robolectric / 任何新依赖

## 12. 测试策略

### 可上纯 JVM 单测（`app/src/test`，跟随现有 `RuleMatcherTest` 写法）

- `BlockDecisionTest` —— 覆盖 override / app 白名单 / allow / block / 无匹配 五条分支，
  以及各自的 reason 字符串。
- 三个 Store —— **构造器注入目录**（而不是像 `DetectedChannelsStore` 那样硬编码路径），
  用 `java.io.File` 测 JSON 读写与上限裁剪（`MAX_ENTRIES` / `MAX_PER_APP` / `MAX_APPS` / `MAX_TEXT_LEN`）。
  这是相对现有 store 的**可测性改进**（现有的都没单测）。

`RootFreeNotificationListener` 继承框架 Service，需要 binder 连接，**无法单测**——
与 `CLAUDE.md` 对 `NotificationHook` 的结论一致，只能真机手测。

### 真机手测

1. 装 debug APK → 首启走 `SetupModeActivity` 选「免 Root 模式」→ 授予通知使用权。
2. 用渠道名含「营销」的测试 App 发通知，确认：通知短暂出现后被撤销；渠道进入列表；
   统计页计数 +1；内容级规则命中的进详情页。
3. 点「允许」后，同一渠道的后续通知不再被撤销（无需重新授权）。
4. **🔴 红线：确认免 root 全流程零 su** —— 全程不应弹 root 授权框，不应出现「Root 未授权」
   或「保存并同步失败」提示。
5. **🔴 红线：root 路径回归**（rooted + LSPosed 设备）：
   - 切回「Root 模式」，确认 `NotificationHook` 拦截行为与改动前**完全一致**；
   - 切到「免 Root 模式」，确认 hook 通过 `isRootModeActive()` 停手，
     且之前被压掉的渠道随着再次触发被**还原回原重要性**（验证 §9.1）。

> 真机测试注意事项见记忆中的既有经验：改 hook 需重启；PowerShell 的 `>` 重定向会毁坏二进制；
> 多设备时用 `adb -t` 定位。

## 13. 实现顺序

1. `BlockDecision.java` + `BlockDecisionTest`（先测后用）
2. `RootFreeConfig.java`；`RootFreeChannelStore` / `RootFreeStatsStore` / `RootFreeBlockLogStore` + 单测
3. `NotificationAccessUtils.java`
4. `RootFreeNotificationListener.java`，接入 1-3
5. `AndroidManifest.xml` 新增 `<service>` + `SetupModeActivity`；`SetupModeActivity.java`
6. `ConfigFileStore` / `RegexConfig` 加 mode；`NotificationHook` 两行门控（§9）
7. `MainActivity` 改造（§8 逐点）+ `BlockedNotificationsActivity` 路由
8. 真机手测（§12）

建议 1-4 步先落地并单测通过，再动 6-7 步（触及既有 root 路径的部分），便于出问题时定位。

## 14. 验证

```
gradlew.bat testDebugUnitTest     # BlockDecisionTest + 各 Store 测试通过，RuleMatcherTest 不受影响
gradlew.bat assembleDebug         # 编译通过
```

真机按 §12 验证，其中第 4、5 点（免 root 零 su、root 路径回归）是本次改动的关键红线。

## 15. 结论是怎么来的（复核线索）

- `updateNotificationChannel` 的特权门 —— 查 AOSP
  `services/core/java/com/android/server/notification/NotificationManagerService.java`
  的 `verifyPrivilegedListener()`；`REQUEST_COMPANION_SELF_MANAGED` 的保护级别查 AOSP
  `core/res/AndroidManifest.xml`。
- API 级别 —— 本机 `E:\Android\Sdk\platforms\android-35\data\api-versions.xml`，
  搜 `NotificationListenerService$Ranking` 与 `updateNotificationChannel`。
- 三个 store 写不了 —— 看各自 `flush()` 里的 `FileWriter` + `HookLogger.DIR` 常量，
  对照 `CLAUDE.md` 的「UI process: every /data/system touch is an su spawn」一节。
- `decideBlock()` 无人使用 —— `grep -rn "decideBlock" app/`，只有 `RuleMatcherTest` 命中。
- master-off 走的是恢复而非 return —— `NotificationHook.java:303-312`、`:476-479`。
