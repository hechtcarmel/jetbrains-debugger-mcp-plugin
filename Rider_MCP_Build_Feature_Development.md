# Rider MCP 插件 - 编译功能开发总结

> **日期**: 2026-05-22
> **项目**: jetbrains-debugger-mcp-plugin
> **简述**: 为 Rider MCP 插件添加编译相关的 4 个工具（start_build、get_build_status、wait_for_build、get_build_output），主要难点在于 Rider 使用独立的插件 ClassLoader 和 RD Reactive 框架，需要特殊处理才能正确访问 BuildHost 类和获取编译状态。

---

## 目录

1. [新增功能](#1-新增功能)
2. [核心实现](#2-核心实现)
3. [遇到的坑](#3-遇到的坑)
   - [坑1: BuildHost 类加载失败](#31-坑1-buildhost-类加载失败---classnotfoundexception)
   - [坑2: 找不到正确的 Rider 插件](#32-坑2-找不到正确的-rider-插件)
   - [坑3: getBuilding() 返回的不是 Boolean](#33-坑3-getbuilding-返回的不是-boolean)
4. [经验教训](#4-经验教训)

---

## 1. 新增功能

为 `jetbrains-debugger-mcp-plugin` 添加了 **4 个编译相关的 MCP 工具**：

| 工具名 | 功能 |
|--------|------|
| `start_build` | 启动项目编译 |
| `get_build_status` | 获取当前编译状态（是否在编译中、上次是否成功等） |
| `wait_for_build` | 阻塞等待编译完成 |
| `get_build_output` | 获取编译输出（错误、警告、消息列表） |

---

## 2. 核心实现

### 2.1 BuildStatusTracker - 编译状态追踪器

作为 Project Service 注册，负责：
- 监听编译事件
- 追踪编译状态（进行中/完成）
- 收集编译消息（错误/警告）
- 提供 `CountDownLatch` 实现阻塞等待

### 2.2 Rider 特殊处理

Rider 的编译系统与 IntelliJ IDEA 不同，需要通过 `BuildHost` 类获取编译状态。

---

## 3. 遇到的坑

### 3.1 坑1: BuildHost 类加载失败 - ClassNotFoundException

**现象**：
```
Class.forName("com.jetbrains.rider.build.BuildHost") 抛 ClassNotFoundException
```

**日志**：
```
Rider BuildHost class not found with any classloader - not running in Rider
```

**原因**：Rider 的插件使用独立的 ClassLoader，`BuildHost` 类不在默认 ClassLoader 中。JetBrains IDE 的插件系统为每个插件创建独立的 `PluginClassLoader`，跨插件访问类需要找到正确的 ClassLoader。

**解决方案**：通过 `PluginManager.getPlugins()` 遍历所有插件，找到 Rider 插件的 ClassLoader 来加载类：

```kotlin
val allPlugins = getPluginsMethod.invoke(pluginManager) as? Array<*>
for (plugin in allPlugins) {
    val pluginId = plugin.getPluginId()
    if (pluginId.startsWith("com.jetbrains.rider") || pluginId.contains("rider")) {
        val pluginClassLoader = plugin.getPluginClassLoader()
        val loadedClass = Class.forName(className, false, pluginClassLoader)
    }
}
```

---

### 3.2 坑2: 找不到正确的 Rider 插件

**现象**：代码只搜索 `com.jetbrains.rider.frontend` 和 `com.jetbrains.rider`，但 `BuildHost` 不在这些插件中。

**日志**：
```
Found Rider plugin (com.jetbrains.rider.frontend)
Rider classloader: com.intellij.ide.plugins.cl.PluginClassLoader
Class com.jetbrains.rider.build.BuildHost not found in Rider classloader:
  PluginClassLoader(plugin=...id=com.jetbrains.rider.frontend...path=C:\...\rider-frontend)
Rider plugin not found among loaded plugins
BuildHost class not found
```

**原因**：`BuildHost` 类实际在 `com.jetbrains.rider.plugins.noesis` 插件中，不在最初搜索的两个插件 ID 中。

**解决方案**：扩大搜索范围，尝试所有 Rider 相关插件：

```kotlin
if (pluginId.startsWith("com.jetbrains.rider") || 
    pluginId == "com.jetbrains.dotCommon" ||
    pluginId.contains("rider", ignoreCase = true)) {
    // 尝试从这个插件的 ClassLoader 加载类
    try {
        val loadedClass = Class.forName(className, false, pluginClassLoader)
        LOG.info("Successfully loaded $className via plugin $pluginId classloader")
        return loadedClass
    } catch (_: ClassNotFoundException) {
        // 这个插件没有这个类，继续尝试下一个
    }
}
```

**成功日志**：
```
Found 122 plugins to search for Rider classloader
Successfully loaded com.jetbrains.rider.build.BuildHost via plugin com.jetbrains.rider.plugins.noesis classloader
Found Rider BuildHost class via classloader
Got BuildHost instance: com.jetbrains.rider.build.BuildHost
BuildHost methods: cancel(), getBuilding(), getReady(), requestBuild()...
BuildStatusTracker initialized for project: LoF
```

---

### 3.3 坑3: getBuilding() 返回的不是 Boolean

**现象**：编译明明结束了，但 `get_build_status` 一直返回 `"status": "building"`，`wait_for_build` 无法检测到编译完成。

**日志**：
```
BuildHost.getBuilding() = com.jetbrains.rd.util.reactive.Property@526bb9ef
BuildHost.getBuilding() = com.jetbrains.rd.util.reactive.Property@526bb9ef
BuildHost.getBuilding() = com.jetbrains.rd.util.reactive.Property@526bb9ef
... (持续轮询，永远检测不到编译完成)
```

**原因**：`BuildHost.getBuilding()` 返回的是 **RD Reactive Property 对象**（`Property<Boolean>`），不是直接的 `Boolean` 值。Rider 使用 JetBrains 的 RD (Reactive Distributed) 框架，很多 API 返回响应式包装对象。

原代码错误地检查：
```kotlin
val result = method.invoke(buildHost)
if (result is Boolean && !result) {  // result 不是 Boolean，这个条件永远不满足
    onRiderBuildCompleted(buildHost)
}
```

**解决方案**：从 Property 对象中提取实际值：

```kotlin
val result = method.invoke(buildHost)
// Result might be a Reactive Property (com.jetbrains.rd.util.reactive.Property)
// Need to call .value or .getValue() to get the actual Boolean
val actualValue: Boolean? = when {
    result is Boolean -> result
    result != null -> {
        // 从 Reactive Property 获取 .value
        try {
            val valueMethod = result.javaClass.getMethod("getValue")
            valueMethod.invoke(result) as? Boolean
        } catch (_: Exception) {
            try {
                val valueField = result.javaClass.getField("value")
                valueField.get(result) as? Boolean
            } catch (_: Exception) {
                null
            }
        }
    }
    else -> null
}
LOG.info("BuildHost.${method.name}() = $result, actualValue = $actualValue")
if (actualValue == false) {
    // Build is no longer active/running
    onRiderBuildCompleted(buildHost)
    return true
}
```

**修复后日志**：
```
BuildHost.getBuilding() = com.jetbrains.rd.util.reactive.Property@526bb9ef, actualValue = false
Build completed successfully
```

---

## 4. 经验教训

### 4.1 JetBrains 插件的 ClassLoader 隔离

不同插件有独立的 ClassLoader，跨插件访问类需要：
1. 通过 `PluginManager.getPlugins()` 获取所有插件
2. 遍历找到目标插件的 ClassLoader
3. 使用 `Class.forName(className, false, pluginClassLoader)` 加载类

### 4.2 Rider 的 RD (Reactive Distributed) 框架

Rider 大量使用响应式编程，很多 API 返回的是响应式包装对象（如 `Property<T>`、`Signal<T>`），需要：
- 调用 `.getValue()` 或访问 `.value` 字段获取实际值
- 或者订阅变化事件 `.advise()` / `.view()`

### 4.3 日志是调试利器

添加详细日志可以快速定位问题：
```kotlin
LOG.info("BuildHost.${method.name}() = $result")  // 打印返回值
// 发现返回的是 Property 对象而不是 Boolean
```

### 4.4 不要假设 API 返回类型

即使方法名是 `getBuilding()`，也不能假设返回 `Boolean`。应该：
1. 打印实际返回值的类型 `result.javaClass.name`
2. 查看源码或文档确认返回类型
3. 做好类型兼容处理

---

## 附录：关键代码位置

| 文件 | 说明 |
|------|------|
| `BuildStatusTracker.kt` | 编译状态追踪核心逻辑 |
| `StartBuildTool.kt` | start_build 工具实现 |
| `GetBuildStatusTool.kt` | get_build_status 工具实现 |
| `WaitForBuildTool.kt` | wait_for_build 工具实现 |
| `GetBuildOutputTool.kt` | get_build_output 工具实现 |
| `BuildModels.kt` | 编译相关数据模型 |

插件安装包位置：
```
e:\GitHub\jetbrains-debugger-mcp-plugin\build\distributions\jetbrains-debugger-mcp-plugin-4.2.0.zip
```
