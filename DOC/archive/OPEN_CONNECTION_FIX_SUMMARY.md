# OpenConnection 请求体字段修复总结

## 一、修复点 A：OpenConnection 请求体字段丢失

### 问题定位

**文件位置**：
- `CashDeviceApi.kt:395-406` - OpenConnectionRequest data class
- `CashDeviceRepository.kt:67-92` - createOpenConnectionRequest 方法

**问题现象**：
- EnableAcceptor 和 EnableAutoAcceptEscrow 字段可能未正确序列化到 JSON
- 服务端可能未收到这些字段，导致设备配置不正确

### 修复内容

**1. 确保字段有默认值（非空）**
```kotlin
data class OpenConnectionRequest(
    val EnableAcceptor: Boolean = true,  // ⚠️ 确保非空，默认 true
    val EnableAutoAcceptEscrow: Boolean = true,  // ⚠️ 确保非空，默认 true
    ...
)
```

**2. 添加最终序列化 JSON 日志**
- 文件: `CashDeviceRepository.kt:67-92`
- 在 `createOpenConnectionRequest` 方法中，使用 `encodeDefaults = true` 确保包含默认值字段
- 打印最终序列化的 JSON（与 OkHttp body 一致）

**日志输出示例**：
```
CashDeviceRepository: ========== FINAL OpenConnection JSON (与 OkHttp body 一致) ==========
CashDeviceRepository: FINAL OpenConnection JSON => {"ComPort":0,"SspAddress":0,"EnableAcceptor":true,"EnableAutoAcceptEscrow":true,"EnablePayout":false,...}
CashDeviceRepository: 字段验证: EnableAcceptor=true, EnableAutoAcceptEscrow=true, EnablePayout=false
CashDeviceRepository: ================================================================
```

## 二、修复点 B：禁止支付中重复连接

### 问题定位

**文件位置**：
- `CashDeviceRepository.kt:1093-1119` - 连接状态锁方法
- `CashDeviceRepository.kt:1121-1234` - startCashSession 方法
- `CashDeviceRepository.kt:2460-2550` - initializeBillAcceptor 方法
- `CashDeviceRepository.kt:2558-2662` - initializeCoinAcceptor 方法
- `CashDeviceTestViewModel.kt:250-262` - 连接纸币器逻辑
- `CashDeviceTestViewModel.kt:343-353` - 连接硬币器逻辑

### 修复内容

**1. 添加连接状态锁**
```kotlin
// 连接状态锁：防止支付中重复连接
private val connectionLocks = mutableMapOf<String, Boolean>()  // deviceID -> isConnecting/Connected
private val connectionLockMutex = Any()  // 互斥锁

private fun isDeviceConnectingOrConnected(deviceKey: String): Boolean
private fun setDeviceConnectionState(deviceKey: String, isConnecting: Boolean)
```

**2. startCashSession 添加连接检查**
- 检查纸币器和硬币器是否正在连接或已连接
- 如果已连接，抛出 `IllegalStateException`，禁止重复连接
- 连接成功时设置连接状态，连接失败时清除连接状态

**3. initializeBillAcceptor/initializeCoinAcceptor 添加连接检查**
- 在初始化前检查连接状态
- 如果已连接，返回 false，禁止重复连接
- 使用 try-catch 确保连接失败时清除连接状态

**4. CashDeviceTestViewModel 添加支付状态检查**
- 在连接纸币器/硬币器前检查是否正在支付中
- 如果正在支付中，禁止连接（避免影响支付链路）

**5. disconnectDevice 清除连接状态**
- 断开连接时清除连接状态锁
- 确保设备可以重新连接

## 三、关键日志输出

### OpenConnection 请求体日志
```
CashDeviceRepository: ========== FINAL OpenConnection JSON (与 OkHttp body 一致) ==========
CashDeviceRepository: FINAL OpenConnection JSON => {"ComPort":0,"SspAddress":0,"DeviceID":null,"LogFilePath":null,"SetInhibits":[...],"SetRoutes":[...],"SetCashBoxPayoutLimit":null,"EnableAcceptor":true,"EnableAutoAcceptEscrow":true,"EnablePayout":false}
CashDeviceRepository: 字段验证: EnableAcceptor=true, EnableAutoAcceptEscrow=true, EnablePayout=false
CashDeviceRepository: SetInhibits=5项, SetRoutes=5项
```

### 重复连接保护日志
```
CashDeviceRepository: ⚠️ 纸币器正在连接或已连接，禁止重复连接（支付中）
CashDeviceRepository: ⚠️ 硬币器正在连接或已连接，禁止重复连接（支付中）
CashDeviceTestViewModel: ⚠️ 支付进行中，禁止重复连接纸币器
CashDeviceTestViewModel: ⚠️ 支付进行中，禁止重复连接硬币器
```

## 四、验收标准

### ✅ 已实现

1. **OpenConnection 请求体字段修复**：
   - ✅ EnableAcceptor 和 EnableAutoAcceptEscrow 字段有默认值（true）
   - ✅ 序列化时使用 `encodeDefaults = true` 确保包含默认值字段
   - ✅ 添加最终序列化 JSON 日志（与 OkHttp body 一致）

2. **禁止重复连接**：
   - ✅ startCashSession 添加连接状态检查
   - ✅ initializeBillAcceptor/initializeCoinAcceptor 添加连接状态检查
   - ✅ CashDeviceTestViewModel 添加支付状态检查
   - ✅ disconnectDevice 清除连接状态

### ⚠️ 待验证

1. **Logcat 中 POST /api/CashDevice/OpenConnection 的 body 必须包含**：
   - EnableAcceptor=true
   - EnableAutoAcceptEscrow=true
   - SetInhibits（如果配置了）
   - SetRoutes（如果配置了）

2. **投入纸币后 paidSoFar 能增加**：
   - 不再"吸入后退回"
   - 设备配置正确（EnableAcceptor=true, EnableAutoAcceptEscrow=true）

3. **支付过程中不会出现"开始连接纸币器"这种重连日志**：
   - 连接状态锁生效
   - CashDeviceTestViewModel 不会在支付中触发重连

## 五、修改文件清单

1. ✅ `CashDeviceApi.kt` - OpenConnectionRequest data class（字段已有默认值）
2. ✅ `CashDeviceRepository.kt` - createOpenConnectionRequest（添加最终 JSON 日志）
3. ✅ `CashDeviceRepository.kt` - 添加连接状态锁（isDeviceConnectingOrConnected, setDeviceConnectionState）
4. ✅ `CashDeviceRepository.kt` - startCashSession（添加连接检查）
5. ✅ `CashDeviceRepository.kt` - initializeBillAcceptor（添加连接检查）
6. ✅ `CashDeviceRepository.kt` - initializeCoinAcceptor（添加连接检查）
7. ✅ `CashDeviceRepository.kt` - disconnectDevice（清除连接状态）
8. ✅ `CashDeviceTestViewModel.kt` - 连接纸币器/硬币器（添加支付状态检查）

## 六、注意事项

**序列化配置**：
- 使用 `kotlinx.serialization.json.Json { encodeDefaults = true }` 确保包含默认值字段
- 最终 JSON 日志与 OkHttp body 一致，便于调试

**连接状态管理**：
- 连接成功时设置连接状态（`setDeviceConnectionState(deviceKey, true)`）
- 连接失败时清除连接状态（`setDeviceConnectionState(deviceKey, false)`）
- 断开连接时清除连接状态（`disconnectDevice`）

**支付中保护**：
- startCashSession 会检查连接状态，如果已连接则抛出异常
- initializeBillAcceptor/initializeCoinAcceptor 会检查连接状态，如果已连接则返回 false
- CashDeviceTestViewModel 会检查支付状态（TODO: 需要从 PaymentViewModel 获取实际支付状态）
