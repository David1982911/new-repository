# Postman SetDenominationRoute 成功请求记录

## 重要说明
此文档记录 Postman 中成功调用 SetDenominationRoute 的完整请求信息，用于对齐 App 代码实现。

## 请求信息（从 Postman 导出）

### URL
```
POST /api/CashDevice/SetDenominationRoute?deviceID=SPECTRAL_PAYOUT-0
```
**注意**: Query 参数名为 `deviceID`（小写 d，大写 ID）

### Headers
```
Content-Type: application/json
Authorization: Bearer <token>
```

### Request Body（原始 JSON - 扁平结构）
```json
{
  "Value": 1000,
  "CountryCode": "EUR",
  "Route": 1
}
```

**关键字段说明**:
- `Value`: 面额（分），如 1000 表示 10€（顶层字段，PascalCase）
- `CountryCode`: 货币代码，如 "EUR"（顶层字段，PascalCase）
- `Route`: 路由值，0=CASHBOX，1=RECYCLER（顶层字段，PascalCase）

**注意**: 
- 字段名使用 PascalCase（首字母大写）
- 扁平结构：Value、CountryCode、Route 三个字段都在 JSON 顶层
- 不包含 `ValueCountryCode` 嵌套层
- 不包含 `Fraud_Attempt_Value` 和 `Calibration_Failed_Value` 字段

### 成功响应
```
HTTP 200 OK
```

## 与当前代码的差异

### 当前代码问题
1. `ValueCountryCodeDto` 包含 `Fraud_Attempt_Value: -1` 和 `Calibration_Failed_Value: -1`，这些字段可能导致服务端返回 INVALID_INPUT
2. 需要确认字段名大小写是否完全匹配

### 修复方向
1. 移除或条件性排除 `Fraud_Attempt_Value` 和 `Calibration_Failed_Value` 字段
2. 确保字段名使用 PascalCase（与 Postman 一致）
3. 确保嵌套结构正确
