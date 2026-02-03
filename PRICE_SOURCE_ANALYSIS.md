# 套餐价格来源分析报告

## 一、价格数据流（调用链）

### 1. 数据源（最终生效来源）

**文件**: `carpayment/app/src/main/java/com/carwash/carpayment/data/config/ProgramConfigRepository.kt`

**函数**: `DEFAULT_CONFIG` (硬编码默认值)

**当前价格**:
- quick: 5.00€ (500分)
- standard: 10.00€ (1000分)
- premium: 15.00€ (1500分)

### 2. 数据流路径

```
ProgramConfigRepository.DEFAULT_CONFIG (硬编码)
    ↓
ProgramConfigRepository.configFlow (Flow<WashProgramConfigList>)
    ↓ (如果 DataStore 中有保存的配置，会覆盖 DEFAULT_CONFIG)
HomeViewModel.programs (StateFlow<List<WashProgram>>)
    ↓
SelectProgramScreen.programs (UI显示)
    ↓
HomeViewModel.selectedProgram (StateFlow<WashProgram?>)
    ↓
PaymentViewModel.currentState.selectedProgram
    ↓
targetAmountCents = round(program.price * 100).toInt()
```

### 3. 关键文件位置

| 文件 | 作用 | 价格来源 |
|------|------|----------|
| `ProgramConfigRepository.kt` | 配置仓库（DataStore） | `DEFAULT_CONFIG` 或 DataStore 保存值 |
| `HomeViewModel.kt` | 首页 ViewModel | `configRepository.configFlow` |
| `SelectProgramScreen.kt` | UI 渲染 | `homeViewModel.programs` |
| `PaymentViewModel.kt` | 支付 ViewModel | `currentState.selectedProgram.price` |

## 二、覆盖源识别

### ⚠️ 关键问题：DataStore 缓存

**如果 UI 显示的价格没有变化，最可能的原因是：**

1. **DataStore 中保存了旧配置**
   - 位置: `context.dataStore` (Preferences DataStore)
   - 键名: `wash_program_config` (stringPreferencesKey)
   - 存储路径: `/data/data/{package_name}/datastore/program_config.preferences_pb`

2. **覆盖逻辑**:
   ```kotlin
   // ProgramConfigRepository.configFlow
   if (configJson != null) {
       // ⚠️ 如果 DataStore 中有保存的配置，会覆盖 DEFAULT_CONFIG
       Json.decodeFromString<WashProgramConfigList>(configJson)
   } else {
       // 首次使用，保存默认配置
       saveConfig(DEFAULT_CONFIG)
       DEFAULT_CONFIG
   }
   ```

## 三、解决方案

### 方案 A: 清除 DataStore 缓存（推荐）

**方法 1: 通过代码重置**
```kotlin
// 在 HomeViewModel 或任何地方调用
homeViewModel.resetConfig()
```

**方法 2: 卸载重装应用**
- 卸载应用会清除所有 DataStore 数据
- 重新安装后，会使用新的 `DEFAULT_CONFIG`

**方法 3: 手动清除 DataStore**
```bash
# 通过 adb 清除应用数据
adb shell pm clear com.carwash.carpayment
```

### 方案 B: 强制更新 DataStore（如果不想清除所有数据）

修改 `ProgramConfigRepository.kt`，在 `init` 或首次启动时强制更新：

```kotlin
// 在 Application 或 MainActivity 中
viewModelScope.launch {
    val currentConfig = programConfigRepository.getConfig()
    val defaultConfig = ProgramConfigRepository.DEFAULT_CONFIG
    
    // 如果价格不匹配，强制更新
    if (currentConfig.programs.any { program ->
        val default = defaultConfig.programs.find { it.id == program.id }
        default != null && default.price != program.price
    }) {
        programConfigRepository.resetToDefault()
    }
}
```

## 四、验证步骤

### 1. 检查日志输出

运行应用后，查看 Logcat 中的以下标签：

- `ProgramConfigRepository`: 显示配置来源（DataStore 或 DEFAULT_CONFIG）
- `HomeViewModel`: 显示程序列表转换过程
- `ProgramPrice`: 显示 UI 渲染时的价格
- `PaymentViewModel`: 显示支付目标金额

### 2. 预期日志输出

**如果使用 DEFAULT_CONFIG（首次安装或已清除缓存）**:
```
ProgramConfigRepository: ========== ProgramConfigRepository 首次使用，保存默认配置 ==========
ProgramConfigRepository: source=DEFAULT_CONFIG (硬编码默认值，首次保存到 DataStore)
ProgramConfigRepository: DEFAULT_CONFIG.programs=[quick: price=5.0€ (500分), standard: price=10.0€ (1000分), premium: price=15.0€ (1500分)]
```

**如果使用 DataStore 保存值（可能显示旧价格）**:
```
ProgramConfigRepository: ========== ProgramConfigRepository 从 DataStore 加载配置 ==========
ProgramConfigRepository: source=DataStore (本地持久化存储)
ProgramConfigRepository: loadedConfig.programs=[quick: price=3.5€ (350分), standard: price=6.0€ (600分), premium: price=10.0€ (1000分)]
ProgramConfigRepository: ⚠️ 注意：如果这里显示旧价格，说明 DataStore 中保存了旧配置，需要清除缓存或重置
```

### 3. 验证价格一致性

**UI 显示**:
```
ProgramPrice: ========== SelectProgramScreen 价格来源 ==========
ProgramPrice: source=HomeViewModel.programs (来自 ProgramConfigRepository.configFlow)
ProgramPrice: programs=[quick: price=5.0€ (500分), standard: price=10.0€ (1000分), premium: price=15.0€ (1500分)]
```

**支付目标金额**:
```
ProgramPrice: ========== PaymentViewModel targetAmountCents 来源 ==========
ProgramPrice: source=currentState.selectedProgram (来自 HomeViewModel.selectedProgram)
ProgramPrice: selectedProgramId=standard
ProgramPrice: selectedProgram.price=10.0€
ProgramPrice: 最终 targetAmountCents=1000分 (10.0€)
```

## 五、修改记录

### 已修改的文件

1. ✅ `ProgramConfigRepository.kt` - DEFAULT_CONFIG 价格已改为 5€/10€/15€
2. ✅ `SelectProgramScreen.kt` - 添加价格来源日志
3. ✅ `HomeViewModel.kt` - 添加程序列表转换日志
4. ✅ `PaymentViewModel.kt` - 添加 targetAmountCents 来源日志
5. ✅ `ProgramConfigRepository.kt` - 添加 DataStore 加载日志

### 需要用户操作

**如果 UI 仍显示旧价格，请执行以下任一操作：**

1. **清除应用数据**（推荐）:
   ```bash
   adb shell pm clear com.carwash.carpayment
   ```

2. **通过代码重置**:
   - 在应用中找到"重置配置"功能（如果有）
   - 或调用 `homeViewModel.resetConfig()`

3. **卸载重装应用**

## 六、结论

**最终生效的来源**:
- **文件**: `ProgramConfigRepository.kt`
- **函数**: `DEFAULT_CONFIG` (硬编码) 或 `configFlow` (DataStore 保存值)
- **调用链**: `DEFAULT_CONFIG` → `configFlow` → `HomeViewModel.programs` → `SelectProgramScreen` (UI) / `PaymentViewModel` (支付)

**为什么改了源码但 UI 没变**:
- 因为 DataStore 中保存了旧配置，覆盖了 `DEFAULT_CONFIG`
- 需要清除 DataStore 缓存或调用 `resetToDefault()`

**价格一致性保证**:
- ✅ UI 显示和支付目标金额使用同一份 `WashProgram` 对象
- ✅ 都来自 `HomeViewModel.programs` (StateFlow)
- ✅ 最终来源都是 `ProgramConfigRepository.configFlow`
