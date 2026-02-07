# 现金收款统计修复总结

## 一、修复点 A：GetCounters 解析逻辑修复

### 问题定位

**文件位置**：
- `CashDeviceApi.kt:119-122` - GetCounters API 定义
- `CashDeviceRepository.kt:2242-2253` - pollCounters 方法（已废弃）
- `CashDeviceRepository.kt:2262-2320` - 新增解析逻辑

**问题现象**：
- 服务端返回文本格式：`"Stacked: 76 / Stored: 42 / Coins paid in: 10"`
- 客户端尝试 JSON 反序列化失败，导致 `deviceID=null`, `stackedTotalCents=0`

### 修复方案

**1. 修改 API 返回类型**
```kotlin
// 修改前
suspend fun getCounters(@Query("deviceID") deviceID: String): CountersResponse

// 修改后
suspend fun getCounters(@Query("deviceID") deviceID: String): Response<ResponseBody>
```

**2. 新增文本解析器**
- 文件: `CashDeviceRepository.kt:2262-2295`
- 方法: `parseCountersResponse(responseBody: ResponseBody, deviceID: String)`
- 功能: 使用正则表达式解析文本中的 `Stacked:`, `Stored:`, `Coins paid in:` 数值

**3. 新增 getPaidAmountCents 方法**
- 文件: `CashDeviceRepository.kt:2297-2320`
- 功能: 
  - 调用 `api.getCounters()` 获取文本响应
  - 解析文本获取 Stacked/Coins paid in 数值
  - ⚠️ 临时方案：使用固定面额估算（纸币 10€/张，硬币 2€/枚）
  - 结合 GetCurrencyAssignment 获取面额信息（如果可用）

### ⚠️ 关键限制

**GetCounters 只返回"张数/次数"，没有面额信息**：
- 需要结合 `GetCurrencyAssignment` 或 `GetAllLevels` 才能准确计算金额
- 当前使用临时估算方案（仅用于验证）

**建议后续优化**：
- 如果服务端有"credit/last accepted value"接口，改用该接口
- 或结合 GetCurrencyAssignment 的面额信息，根据 Stacked 数量计算准确金额

## 二、修复点 B：避免统计为0导致退钞

### 问题定位

**文件位置**：
- `PaymentViewModel.kt:373-400` - 超时失败处理逻辑

**问题现象**：
- 当 `paidSoFar=0` 时，超时后会调用 `disableAcceptor()` 导致退钞
- 但实际上可能是统计解析失败，而不是真的没有收到钱

### 修复方案

**1. 添加统计失败检测**
```kotlin
// 检测是否真的收到现金
val hasReceivedCash = billDelta != 0 || coinDelta != 0 || finalPaidSoFar > 0

if (!hasReceivedCash && finalPaidSoFar == 0) {
    // 统计解析可能失败，不调用 DisableAcceptor
    Log.w(TAG, "⚠️ 检测到统计解析可能失败：paidSoFar=0 且没有收到任何现金事件")
    Log.w(TAG, "⚠️ 不调用 DisableAcceptor，避免退钞")
    handlePaymentFailure("无法读取入账金额（设备统计解析失败），请检查设备连接或联系技术支持")
    return
}
```

**2. 增强日志输出**
- 打印调用堆栈（文件+行号）
- 打印 DisableAcceptor 调用原因和时机
- 打印失败时的详细状态信息

**3. 超时时间已延长**
- `CASH_PAYMENT_TIMEOUT_MS = 60000L` (60秒)
- 给足够时间观察日志和调试

## 三、当前使用的金额统计方案

### 实际使用的 API

**PaymentViewModel 当前使用**：
- `getCurrentStoredTotalCents(deviceID)` - 基于 `GetCurrencyAssignment` 的 `stored` 字段
- 会话累计金额 = 当前库存总金额 - 基线库存总金额

**GetCounters 修复状态**：
- ✅ API 返回类型已修复（Response<ResponseBody>）
- ✅ 文本解析器已实现
- ✅ `getPaidAmountCents()` 方法已新增
- ⚠️ 但 PaymentViewModel 还未使用（仍使用 GetCurrencyAssignment 方案）

### 建议

**如果要使用 GetCounters**：
1. 修改 PaymentViewModel 中的轮询逻辑，改用 `getPaidAmountCents()`
2. 或结合两种方案：GetCounters 作为主数据源，GetCurrencyAssignment 作为面额信息补充

## 四、验收标准

### ✅ 已实现

1. **GetCounters 解析修复**：
   - ✅ API 返回类型改为 `Response<ResponseBody>`
   - ✅ 文本解析器已实现（正则表达式提取 Stacked/Stored/Coins paid in）
   - ✅ 添加详细日志（原始响应文本 + 解析结果）

2. **避免退钞逻辑**：
   - ✅ 检测统计解析失败情况
   - ✅ 统计为0且无现金事件时不调用 DisableAcceptor
   - ✅ 增强日志输出（调用堆栈、失败原因）

### ⚠️ 待验证

1. **投入 10€ 后 paidSoFar 变化**：
   - 需要实际测试验证
   - 如果使用 GetCurrencyAssignment 方案，应该能正确统计
   - 如果使用 GetCounters 方案，需要验证文本解析是否正确

2. **不再出现"收进去又退出来"**：
   - 已添加保护逻辑，但需要实际测试验证

3. **现金支付能走到 success 分支**：
   - 需要实际测试验证

## 五、日志输出示例

### GetCounters 解析日志
```
CashDeviceRepository: 获取设备已收金额: deviceID=SPECTRAL_PAYOUT-0
CashDeviceRepository: GetCounters 原始响应文本: deviceID=SPECTRAL_PAYOUT-0, text='Stacked: 76 / Stored: 42'
CashDeviceRepository: GetCounters 解析结果: deviceID=SPECTRAL_PAYOUT-0, stacked=76, stored=42, coinsPaidIn=0, estimatedCents=76000 (760.0€)
CashDeviceRepository: GetCounters 最终结果: deviceID=SPECTRAL_PAYOUT-0, paidAmountCents=76000 (760.0€)
```

### 退钞保护日志
```
PaymentViewModel: ⚠️ 检测到统计解析可能失败：paidSoFar=0 且没有收到任何现金事件
PaymentViewModel: ⚠️ 不调用 DisableAcceptor，避免退钞
PaymentViewModel: ⚠️ 提示：无法读取入账金额/设备统计解析失败，请检查 GetCounters 解析逻辑
```

### 超时失败日志
```
PaymentViewModel: ========== 现金支付超时失败 ==========
PaymentViewModel: targetAmount=1000分 (10.0€)
PaymentViewModel: paidSoFar=0分 (0.0€)
PaymentViewModel: 为什么要 DisableAcceptor：TIMEOUT（支付超时，已确认设备没有escrow在内）
PaymentViewModel: 调用堆栈: ...
PaymentViewModel: 调用 disableAcceptor: deviceID=SPECTRAL_PAYOUT-0
```
