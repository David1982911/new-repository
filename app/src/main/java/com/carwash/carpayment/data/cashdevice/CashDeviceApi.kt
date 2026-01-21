package com.carwash.carpayment.data.cashdevice

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * ITL 现金设备 REST API 接口
 * 基于 ITL_Android_API_Collection_V1.4.1.json
 */
interface CashDeviceApi {
    
    /**
     * 认证（获取 Token）
     * POST /Users/Authenticate
     */
    @POST("Users/Authenticate")
    @Headers("Content-Type: application/json")
    suspend fun authenticate(@Body request: AuthenticateRequest): AuthenticateResponse
    
    /**
     * 获取已连接的 USB 设备
     * GET /CashDevice/GetConnectedUSBDevices
     * Authorization 头由 Interceptor 自动添加
     */
    @GET("CashDevice/GetConnectedUSBDevices")
    suspend fun getConnectedUSBDevices(): List<USBDevice>
    
    /**
     * 打开连接
     * POST /CashDevice/OpenConnection
     * Authorization 头由 Interceptor 自动添加
     */
    @POST("CashDevice/OpenConnection")
    @Headers("Content-Type: application/json")
    suspend fun openConnection(
        @Body request: OpenConnectionRequest
    ): OpenConnectionResponse
    
    /**
     * 启动设备
     * POST /CashDevice/StartDevice?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 text/plain 纯文本，使用 Response<String> 处理
     */
    @POST("CashDevice/StartDevice")
    suspend fun startDevice(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 启用接收器
     * POST /CashDevice/EnableAcceptor?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 text/plain 纯文本（如 "Message: Acceptor enabled successfully."），使用 Response<ResponseBody> 处理
     */
    @POST("CashDevice/EnableAcceptor")
    suspend fun enableAcceptor(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 禁用接收器
     * POST /CashDevice/DisableAcceptor?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 text/plain 纯文本，使用 Response<ResponseBody> 处理
     */
    @POST("CashDevice/DisableAcceptor")
    suspend fun disableAcceptor(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 设置自动接受
     * POST /CashDevice/SetAutoAccept?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 text/plain 纯文本（如 "Message: Auto-accept set to True"），使用 Response<ResponseBody> 处理
     */
    @POST("CashDevice/SetAutoAccept")
    @Headers("Content-Type: application/json")
    suspend fun setAutoAccept(
        @Query("deviceID") deviceID: String,
        @Body autoAccept: Boolean
    ): Response<ResponseBody>
    
    /**
     * 获取设备状态
     * GET /CashDevice/GetDeviceStatus?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 application/json 的数组 List<DeviceStatusResponse>（可能为空 []）
     */
    @GET("CashDevice/GetDeviceStatus")
    suspend fun getDeviceStatus(
        @Query("deviceID") deviceID: String
    ): List<DeviceStatusResponse>
    
    /**
     * 断开设备连接
     * POST /CashDevice/DisconnectDevice?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器返回 text/plain 纯文本，使用 Response<ResponseBody> 处理
     */
    @POST("CashDevice/DisconnectDevice")
    suspend fun disconnectDevice(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 获取计数器（收款金额统计）
     * GET /CashDevice/GetCounters?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 用于获取当前已收金额（从 SSP 事件解析面额）
     */
    @GET("CashDevice/GetCounters")
    suspend fun getCounters(
        @Query("deviceID") deviceID: String
    ): CountersResponse
    
    /**
     * 获取所有面额库存（各面额的 Stored 数量）
     * GET /CashDevice/GetAllLevels?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 用于获取当前设备各面额的库存（Value 和 Stored），用于计算会话累计金额
     */
    @GET("CashDevice/GetAllLevels")
    suspend fun getAllLevels(
        @Query("deviceID") deviceID: String
    ): LevelsResponse
    
    /**
     * 找零（按金额）
     * POST /CashDevice/DispenseValue?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 用于发起找零请求（硬币器/纸币器吐出指定金额）
     */
    @POST("CashDevice/DispenseValue")
    @Headers("Content-Type: application/json")
    suspend fun dispenseValue(
        @Query("deviceID") deviceID: String,
        @Body request: DispenseValueRequest
    ): Response<ResponseBody>
    
    /**
     * 启用找零
     * POST /CashDevice/EnablePayout?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 用于启用设备的找零功能
     */
    @POST("CashDevice/EnablePayout")
    suspend fun enablePayout(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 禁用找零
     * POST /CashDevice/DisablePayout?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 用于禁用设备的找零功能
     */
    @POST("CashDevice/DisablePayout")
    suspend fun disablePayout(
        @Query("deviceID") deviceID: String
    ): Response<ResponseBody>
    
    /**
     * 设置面额接收（逐个面额设置"是否允许接收"）
     * POST /CashDevice/SetInhibits?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 厂商确认：SetInhibits 是设置可接收面额，要逐个面额配置
     * 约定：Inhibit = true 表示禁止接收该面额；Inhibit = false 表示允许接收该面额
     * 
     * 请求体格式（按厂商文档）：
     * {
     *   "Denominations": [
     *     { "Denomination": 2000, "Inhibit": true },   // 禁止接收 20€
     *     { "Denomination": 5000, "Inhibit": false }   // 允许接收 50€
     *   ]
     * }
     */
    @POST("CashDevice/SetInhibits")
    @Headers("Content-Type: application/json")
    suspend fun setInhibits(
        @Query("deviceID") deviceID: String,
        @Body request: SetInhibitsRequest
    ): Response<ResponseBody>
    
    /**
     * 设置单个面额路由（在线配置，不导致连接断开）
     * POST /api/CashDevice/SetDenominationRoute?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 
     * 用于快速切换单个面额的路由配置，无需断开重连
     * 
     * 请求体格式：
     * {
     *   "Value": 500,        // 面额（分）
     *   "CountryCode": "EUR",  // 货币代码
     *   "Route": 1          // 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
     * }
     * 
     * Route 值说明：
     * - 0：进入主钞箱（cashbox），不可找零
     * - 1：进入循环找零箱（recycler），可找零（Smart Payout 用这个）
     */
    @POST("CashDevice/SetDenominationRoute")
    @Headers("Content-Type: application/json")
    suspend fun setDenominationRoute(
        @Query("deviceID") deviceID: String,
        @Body request: SetDenominationRouteRequestFlat  // 扁平结构 DTO（完全对齐 Postman）
    ): Response<ResponseBody>
    
    /**
     * 设置面额路由（原始 JSON 直发模式，用于快速验证）
     * 此方法直接发送原始 JSON 字符串，绕过 DTO 序列化，用于排除序列化问题
     * 
     * 使用场景：
     * - 当 DTO 序列化模式失败时，使用此方法验证是否是序列化问题
     * - 如果此方法成功，说明问题在于 DTO 序列化（字段名、结构等）
     * - 如果此方法也失败，说明问题在于其他方面（网络、服务端等）
     * 
     * 原始 JSON 格式（扁平三字段，与 Postman 完全一致）：
     * {
     *   "Value": 1000,
     *   "CountryCode": "EUR",
     *   "Route": 1
     * }
     * 
     * @param deviceID 设备ID
     * @param rawJson 原始 JSON RequestBody（必须与 Postman 成功请求格式完全一致）
     * @return Response<ResponseBody> HTTP 响应
     */
    @POST("CashDevice/SetDenominationRoute")
    @Headers("Content-Type: application/json")
    suspend fun setDenominationRouteRaw(
        @Query("deviceID") deviceID: String,
        @Body rawJson: okhttp3.RequestBody  // 原始 JSON RequestBody
    ): Response<ResponseBody>
    
    /**
     * 设置单个面额接收状态（是否允许接收该面额）
     * POST /api/CashDevice/SetDenominationInhibits?deviceID={deviceID}
     * 用于快速切换单个面额的接收状态，无需断开重连
     * 
     * 请求体格式（按 ITL 手册）：
     * {
     *   "ValueCountryCodes": ["500 EUR"],  // 字符串数组，格式："500 EUR" / "1000 EUR"
     *   "Inhibit": false  // false=允许接收，true=禁止接收
     * }
     */
    @POST("CashDevice/SetDenominationInhibits")
    @Headers("Content-Type: application/json")
    suspend fun setDenominationInhibits(
        @Query("deviceID") deviceID: String,
        @Body request: SetDenominationInhibitsRequest
    ): Response<ResponseBody>
    
    /**
     * 设置面额路由（逐个面额设置"找零路由/是否进入可找零循环仓（recycler）"）
     * POST /CashDevice/SetRoutes?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 
     * @deprecated 该 endpoint 返回 404，请使用 OpenConnection 请求体中的 SetRoutes 字段来设置路由配置
     * 路由配置应通过 OpenConnection 请求携带，流程：DisableAcceptor → OpenConnection(带 SetRoutes) → EnableAcceptor
     * 
     * 厂商确认：SetRoutes 是设置找零面额，决定某面额进入 recycler（可找零）还是 cashbox（不可找零）
     * 
     * 请求体格式（按厂商文档）：
     * {
     *   "Denominations": [
     *     { "Denomination": 500, "Route": 1 },   // 5€ 进入 recycler（可找零）
     *     { "Denomination": 5000, "Route": 0 }   // 50€ 进入 cashbox（不可找零）
     *   ]
     * }
     * 
     * Route 值说明：
     * - 0 或 null：进入主钞箱（cashbox），不可找零
     * - 7：进入循环找零箱（recycler），可找零
     */
    @Deprecated("该 endpoint 返回 404，请使用 OpenConnection 请求体中的 SetRoutes 字段", ReplaceWith("openConnection(request)"))
    @POST("CashDevice/SetRoutes")
    @Headers("Content-Type: application/json")
    suspend fun setRoutes(
        @Query("deviceID") deviceID: String,
        @Body request: SetRoutesRequest
    ): Response<ResponseBody>
    
    /**
     * 获取存储值（当前设备累计金额）- 已废弃，服务端返回 404
     * @deprecated 请使用 getCurrencyAssignment（基于面额分配和计数器）
     */
    @Deprecated("GetStoredValue 返回 404，请使用 getCurrencyAssignment", ReplaceWith("getCurrencyAssignment(deviceID)"))
    @GET("CashDevice/GetStoredValue")
    suspend fun getStoredValue(
        @Query("deviceID") deviceID: String
    ): StoredValueResponse
    
    /**
     * 获取货币分配（面额识别 + 计数器来源）
     * GET /CashDevice/GetCurrencyAssignment?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 
     * 这是设备支持的面额表 + 当前计数器，用于：
     * - UI 面额列表展示
     * - SetInhibits / SetRoutes 的下发参数生成
     * - 金额统计（快照差分）
     * 
     * 注意：服务器返回的是数组 []，不是对象 {}
     * 
     * 返回字段：
     * - value / countryCode（面额和货币代码）
     * - channel（通道号）
     * - stored（当前库存数量）
     * - storedInCashbox（主钞箱库存数量，仅纸币器）
     * - isInhibited（是否被禁止接收）
     * - isRecyclable（是否可找零）
     * - acceptRoute（接收路由：字符串 "PAYOUT"/"CASHBOX"，不是数字）
     */
    @GET("CashDevice/GetCurrencyAssignment")
    suspend fun getCurrencyAssignment(
        @Query("deviceID") deviceID: String
    ): List<CurrencyAssignment>
    
    /**
     * 智能清空（清空循环鼓 recycler）
     * POST /CashDevice/SmartEmpty?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 
     * 用于清空纸币器的循环找零箱（recycler）
     * 请求体包含 moduleNumber / isNV4000 等参数
     */
    @POST("CashDevice/SmartEmpty")
    @Headers("Content-Type: application/json")
    suspend fun smartEmpty(
        @Query("deviceID") deviceID: String,
        @Body request: SmartEmptyRequest
    ): Response<ResponseBody>
}

/**
 * 认证请求
 */
@Serializable
data class AuthenticateRequest(
    val Username: String,
    val Password: String
)

/**
 * 认证响应
 */
@Serializable
data class AuthenticateResponse(
    val token: String? = null,
    val error: String? = null
)

/**
 * USB 设备信息
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class USBDevice(
    val Port: Int? = null,  // 注意：API 返回的是 "Port"（大写），不是 "comPort"
    val comPort: Int? = null,  // 兼容字段
    val DeviceName: String? = null,  // 设备名称（如 "FT232R USB UART"）
    @JsonNames("DeviceID", "deviceID") val deviceID: String? = null,  // 支持 "DeviceID"（大写）和 "deviceID"（小写）两种字段名
    val description: String? = null,  // 兼容字段
    val VendorId: Int? = null,
    val ProductId: Int? = null
) {
    /**
     * 获取实际的 Port 值（优先使用 Port，其次 comPort）
     */
    val actualPort: Int?
        get() = Port ?: comPort
    
    /**
     * 获取实际的 DeviceID（统一通过 deviceID 字段获取）
     */
    val actualDeviceID: String?
        get() = deviceID
}

/**
 * 打开连接请求
 */
@Serializable
data class OpenConnectionRequest(
    val ComPort: Int,
    val SspAddress: Int,
    val DeviceID: String? = null,  // 设备ID（从 GetConnectedUSBDevices 获取，可选）
    val LogFilePath: String? = null,
    val SetInhibits: List<DenominationInhibit>? = null,
    val SetRoutes: List<DenominationRoute>? = null,
    val SetCashBoxPayoutLimit: List<Int>? = null,
    val EnableAcceptor: Boolean = true,
    val EnableAutoAcceptEscrow: Boolean = true,
    val EnablePayout: Boolean = false  // 先关闭找零功能
)

/**
 * 面额禁用设置
 */
@Serializable
data class DenominationInhibit(
    val Denomination: String,
    val Inhibit: Boolean
)

/**
 * 面额路由设置
 */
@Serializable
data class DenominationRoute(
    val Denomination: String,
    val Route: Int
)

/**
 * 打开连接响应
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class OpenConnectionResponse(
    @JsonNames("DeviceID", "deviceID") val deviceID: String? = null,  // 支持 "DeviceID"（大写）和 "deviceID"（小写）两种字段名
    val error: String? = null,
    val DeviceModel: String? = null,  // 设备型号，用于识别设备类型（如 "SPECTRAL_PAYOUT"）
    val IsOpen: Boolean? = null,
    val DeviceError: String? = null,
    val Firmware: String? = null,
    val Dataset: String? = null,
    val ValidatorSerialNumber: String? = null,
    val PayoutModuleSerialNumber: String? = null
)

/**
 * 通用 API 响应
 */
@Serializable
data class ApiResponse(
    val success: Boolean = true,
    val error: String? = null
)

/**
 * 设备状态响应
 * 服务器返回的数组元素包含 Type/State/StateAsString 字段
 * 注意：服务器可能返回空数组 []，需要容错处理
 */
@Serializable
data class DeviceStatusResponse(
    @SerialName("Type") val type: String? = null,  // 设备类型（如 "BILL_ACCEPTOR", "COIN_MECH"）
    @SerialName("State") val state: String? = null,  // 状态枚举（如 "IDLE", "STARTED", "CONNECTED"）
    @SerialName("StateAsString") val stateAsString: String? = null,  // 状态字符串（如 "Idle", "Started", "Connected"）
    @Deprecated("使用 state 或 stateAsString", ReplaceWith("state ?: stateAsString"))
    val deviceID: String? = null,  // 保留兼容性字段，但服务器不返回此字段
    @Deprecated("使用 state 或 stateAsString", ReplaceWith("state ?: stateAsString"))
    val status: String? = null,  // 保留兼容性字段，但服务器不返回此字段
    @Deprecated("服务器不返回此字段")
    val error: String? = null  // 保留兼容性字段，但服务器不返回此字段
) {
    /**
     * 获取实际状态字符串（优先使用 stateAsString，其次 state）
     */
    val actualState: String?
        get() = stateAsString ?: state
    
    /**
     * 获取设备类型字符串
     */
    val actualType: String?
        get() = type
}

/**
 * 计数器响应（收款金额统计）
 * 从 GetCounters API 返回的金额信息
 */
@Serializable
data class CountersResponse(
    val deviceID: String? = null,
    val stackedTotalCents: Int = 0,  // 已收总金额（分）
    val stackedTotal: Double = 0.0,  // 已收总金额（元，可选字段）
    val stackedCounts: Map<String, Int>? = null,  // 各面额数量（可选）
    val error: String? = null
) {
    /**
     * 获取已收总金额（元）
     */
    val totalAmount: Double
        get() = stackedTotal.takeIf { it > 0 } ?: (stackedTotalCents / 100.0)
}

/**
 * 面额库存信息（单个面额）
 * GetAllLevels 响应中的单个条目
 * 注意：Value 对硬币可能是 1/2/5…（分），对纸币是 500/1000…（分），要统一解释单位
 */
@Serializable
data class LevelEntry(
    @SerialName("Value") val value: Int,  // 面额（分），如 500 表示 5€，1 表示 1分硬币
    @SerialName("Stored") val stored: Int,  // 该面额的库存数量（张/枚）
    @SerialName("CountryCode") val countryCode: String? = null  // 货币代码（如 "EUR"）
)

/**
 * 库存响应（所有面额的库存）
 * 从 GetAllLevels API 返回的库存信息
 * 服务器结构：{ "Levels": [...], "Success": true, "Message": "..." }
 * 用于计算会话累计金额：delta = Σ(value * (stored_now - stored_baseline))
 */
@Serializable
data class LevelsResponse(
    val deviceID: String? = null,
    @SerialName("Levels") val levels: List<LevelEntry>? = null,  // 所有面额的库存列表（注意：服务器返回的是 "Levels"，不是 "AllLevels"）
    @SerialName("Success") val success: Boolean? = null,  // 是否成功
    @SerialName("Message") val message: String? = null,  // 消息（可选）
    val error: String? = null  // 错误信息（如果解析失败）
) {
    /**
     * 获取所有面额库存列表（兼容字段名）
     */
    val allLevels: List<LevelEntry>?
        get() = levels
    
    /**
     * 计算总金额（分）- 所有面额的 value * stored 总和
     * 注意：Value 对硬币可能是 1/2/5…，对纸币是 500/1000…，要统一解释单位（分）
     */
    fun calculateTotalCents(): Int {
        return levels?.sumOf { it.value * it.stored } ?: 0
    }
    
    /**
     * 计算总金额（元）
     */
    fun calculateTotalAmount(): Double {
        return calculateTotalCents() / 100.0
    }
}

/**
 * 设置单个面额路由请求（扁平结构，完全对齐 Postman 成功请求）
 * SetDenominationRoute 接口的请求体
 * 
 * Postman 成功请求格式（扁平三字段）：
 * {
 *   "Value": 1000,        // 面额（分），如 1000 表示 10€
 *   "CountryCode": "EUR", // 货币代码
 *   "Route": 1            // 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
 * }
 * 
 * 注意：
 * - 字段名使用 PascalCase（与 Postman 一致）
 * - 不包含 ValueCountryCode 嵌套层
 * - 不包含 Fraud_Attempt_Value 和 Calibration_Failed_Value 字段
 */
@Serializable
data class SetDenominationRouteRequestFlat(
    @SerialName("Value") val value: Int,  // 面额（分），如 1000 表示 10€
    @SerialName("CountryCode") val countryCode: String,  // 货币代码（如 "EUR"）
    @SerialName("Route") val route: Int  // 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
)

/**
 * 设置单个面额路由请求（嵌套版，已废弃）
 * @deprecated 服务端期望扁平结构，请使用 SetDenominationRouteRequestFlat
 */
@Deprecated("服务端期望扁平结构，请使用 SetDenominationRouteRequestFlat", ReplaceWith("SetDenominationRouteRequestFlat"))
@Serializable
data class SetDenominationRouteRequestNested(
    @SerialName("ValueCountryCode") val valueCountryCode: ValueCountryCodeDto,  // 嵌套结构：Value + CountryCode
    @SerialName("Route") val route: Int  // 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
)

/**
 * 设置单个面额路由请求（平铺版，已废弃）
 * @deprecated 服务端期望嵌套结构，请使用 SetDenominationRouteRequestNested
 */
@Deprecated("服务端期望嵌套结构，请使用 SetDenominationRouteRequestNested", ReplaceWith("SetDenominationRouteRequestNested"))
@Serializable
data class SetDenominationRouteRequest(
    @SerialName("Value") val value: Int,  // 面额（分），如 500 表示 5€
    @SerialName("CountryCode") val countryCode: String = "EUR",  // 货币代码，默认 EUR
    @SerialName("Route") val route: Int  // 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
)

/**
 * 设置单个面额接收状态请求（使用 SetDenominationInhibits）
 * SetDenominationInhibits 接口的请求体
 * 服务端期望格式：{"ValueCountryCodes":["500 EUR"],"Inhibit":false}
 * ValueCountryCodes 是字符串数组，格式为 "500 EUR" / "1000 EUR"
 */
@Serializable
data class SetDenominationInhibitsRequest(
    @SerialName("ValueCountryCodes") val valueCountryCodes: List<String>,  // 字符串数组，格式："500 EUR"
    @SerialName("Inhibit") val inhibit: Boolean  // false=允许接收，true=禁止接收
)

/**
 * 设置单个面额接收状态请求（单数版，已废弃）
 * @deprecated 服务端期望 ValueCountryCodes 数组格式，请使用 SetDenominationInhibitsRequest
 */
@Deprecated("服务端期望 ValueCountryCodes 数组格式，请使用 SetDenominationInhibitsRequest", ReplaceWith("SetDenominationInhibitsRequest"))
@Serializable
data class SetDenominationInhibitRequest(
    @SerialName("ValueCountryCode") val valueCountryCode: ValueCountryCodeDto,  // 嵌套结构：Value + CountryCode
    @SerialName("Inhibit") val inhibit: Boolean  // false=允许接收，true=禁止接收
)

/**
 * 找零请求
 */
@Serializable
data class DispenseValueRequest(
    @SerialName("Value") val value: Int,  // 找零金额（分），如 200 表示 2€
    @SerialName("CountryCode") val countryCode: String = "EUR"  // 货币代码，默认 EUR
)

/**
 * 设置面额接收请求
 * SetInhibits 接口的请求体
 * 厂商确认：SetInhibits 是设置可接收面额，要逐个面额配置
 * 约定：Inhibit = true 表示禁止接收该面额；Inhibit = false 表示允许接收该面额
 * 
 * 请求体格式（按厂商文档）：
 * {
 *   "Denominations": [
 *     { "Denomination": 2000, "Inhibit": true },   // 禁止接收 20€
 *     { "Denomination": 5000, "Inhibit": false }    // 允许接收 50€
 *   ]
 * }
 */
@Serializable
data class SetInhibitsRequest(
    @SerialName("Denominations") val denominations: List<DenominationInhibitItem>
)

/**
 * 面额接收配置项
 */
@Serializable
data class DenominationInhibitItem(
    @SerialName("Denomination") val denomination: Int,  // 面额（分），如 2000 表示 20€
    @SerialName("Inhibit") val inhibit: Boolean  // true = 禁止接收，false = 允许接收
)

/**
 * 设置面额路由请求
 * SetRoutes 接口的请求体
 * 厂商确认：SetRoutes 是设置找零面额，决定某面额进入 recycler（可找零）还是 cashbox（不可找零）
 * 
 * 请求体格式（按厂商文档）：
 * {
 *   "Denominations": [
 *     { "Denomination": 500, "Route": 1 },   // 5€ 进入 recycler（可找零）
 *     { "Denomination": 5000, "Route": 0 }   // 50€ 进入 cashbox（不可找零）
 *   ]
 * }
 * 
 * Route 值说明：
 * - 0 或 null：进入主钞箱（cashbox），不可找零
 * - 1：进入循环找零箱（recycler），可找零
 */
@Serializable
data class SetRoutesRequest(
    @SerialName("Denominations") val denominations: List<DenominationRouteItem>
)

/**
 * 面额路由配置项
 */
@Serializable
data class DenominationRouteItem(
    @SerialName("Denomination") val denomination: Int,  // 面额（分），如 500 表示 5€
    @SerialName("Route") val route: Int  // 0 = cashbox（不可找零），1 = recycler（可找零）
)

/**
 * ValueCountryCode DTO（ValueCountryCode 字段是对象，不是字符串）
 * 服务器返回的 ValueCountryCode 是一个对象，包含：
 * - Value: 面额（分）
 * - CountryCode: 货币代码（如 "EUR"）
 * - Fraud_Attempt_Value: 欺诈尝试值（可选，默认 -1）
 * - Calibration_Failed_Value: 校准失败值（可选，默认 -1）
 */
/**
 * ValueCountryCode DTO（用于 SetDenominationRoute）
 * 注意：根据 Postman 成功请求，此 DTO 不应包含 Fraud_Attempt_Value 和 Calibration_Failed_Value 字段
 * 这些字段会导致服务端返回 400 INVALID_INPUT
 */
@Serializable
data class ValueCountryCodeDto(
    @SerialName("Value") val value: Int,  // 面额（分），如 500 表示 5€
    @SerialName("CountryCode") val countryCode: String? = null  // 货币代码（如 "EUR"）
    // 注意：不包含 Fraud_Attempt_Value 和 Calibration_Failed_Value，这些字段会导致 INVALID_INPUT
) {
    /**
     * 获取面额字符串（用于 SetInhibits/SetRoutes 的 Denomination 字段）
     * 格式："{value} {countryCode}"，例如 "500 EUR"
     */
    fun getDenominationString(): String {
        return if (countryCode != null) {
            "$value $countryCode"
        } else {
            value.toString()
        }
    }
}

/**
 * 货币分配项（单个面额的信息）
 * GetCurrencyAssignment 接口返回数组中的单个元素
 * 
 * 注意：服务器返回的是数组 []，不是对象 {}
 * 字段名映射（首字母大写）：
 * - "Type" -> type（String，如 "COIN"/"BANKNOTE"）
 * - "ValueCountryCode" -> valueCountryCode（ValueCountryCodeDto 对象，不是字符串）
 * - "Value" -> value（Int，面额分）
 * - "CountryCode" -> countryCode（String，货币代码）
 * - "IsInhibited" -> isInhibited（Boolean）
 * - "IsRecyclable" -> isRecyclable（Boolean）
 * - "AcceptRoute" -> acceptRoute（String，"PAYOUT"/"CASHBOX"）
 * - "Stored" -> stored（Int，当前库存数量）
 * - "StoredInCashbox" -> storedInCashbox（Int，主钞箱库存数量）
 * - "Channel" -> channel（Int，通道号）
 */
@Serializable
data class CurrencyAssignment(
    @SerialName("Type") val type: String? = null,  // 类型（如 "COIN", "BANKNOTE"）
    @SerialName("ValueCountryCode") val valueCountryCode: ValueCountryCodeDto? = null,  // 面额+货币代码对象（不是字符串）
    @SerialName("Value") val value: Int,  // 面额（分），如 500 表示 5€
    @SerialName("CountryCode") val countryCode: String? = null,  // 货币代码（如 "EUR"）
    @SerialName("Channel") val channel: Int? = null,  // 通道号
    @SerialName("Stored") val stored: Int = 0,  // 当前库存数量（总库存）
    @SerialName("StoredInCashbox") val storedInCashbox: Int = 0,  // 主钞箱库存数量（仅纸币器）
    @SerialName("IsInhibited") val isInhibited: Boolean = false,  // 是否被禁止接收
    @SerialName("IsRecyclable") val isRecyclable: Boolean = false,  // 是否可找零
    @SerialName("AcceptRoute") val acceptRoute: String? = null  // 接收路由：字符串 "PAYOUT"（可找零，进 recycler）或 "CASHBOX"（不可找零，进 cashbox）
) {
    /**
     * 获取面额字符串（用于 SetInhibits/SetRoutes 的 Denomination 字段）
     * 格式："{value} {countryCode}"，例如 "500 EUR"
     * 优先使用 valueCountryCode 对象，其次使用 value + countryCode
     */
    fun getDenominationString(): String {
        return valueCountryCode?.getDenominationString() ?: if (countryCode != null) {
            "$value $countryCode"
        } else {
            value.toString()
        }
    }
    
    /**
     * 获取循环找零箱库存数量（recycler 库存 = stored - storedInCashbox）
     */
    val storedInRecycler: Int
        get() = maxOf(0, stored - storedInCashbox)
    
    /**
     * 检查是否可找零（基于 acceptRoute 字符串）
     * @return true 表示可找零（acceptRoute == "PAYOUT"），false 表示不可找零（acceptRoute == "CASHBOX" 或 null）
     */
    val isAcceptRouteRecyclable: Boolean
        get() = acceptRoute == "PAYOUT"
}

/**
 * 货币分配响应（已废弃，服务器返回数组 []，不是对象 {}）
 * @deprecated 请直接使用 List<CurrencyAssignment>
 */
@Deprecated("服务器返回数组 []，不是对象 {}，请直接使用 List<CurrencyAssignment>", ReplaceWith("List<CurrencyAssignment>"))
@Serializable
data class CurrencyAssignmentResponse(
    val deviceID: String? = null,
    @SerialName("Assignments") val assignments: List<CurrencyAssignment>? = null,
    @SerialName("Success") val success: Boolean? = null,
    @SerialName("Message") val message: String? = null,
    val error: String? = null
)

/**
 * 智能清空请求
 * SmartEmpty 接口的请求体
 */
@Serializable
data class SmartEmptyRequest(
    @SerialName("ModuleNumber") val moduleNumber: Int = 0,  // 模块号（默认 0）
    @SerialName("IsNV4000") val isNV4000: Boolean = false  // 是否为 NV4000 型号（默认 false）
)

/**
 * 存储值响应（当前设备累计金额）- 已废弃，服务端返回 404
 * @deprecated 请使用 LevelsResponse（基于 GetAllLevels 和库存差值）
 */
@Deprecated("GetStoredValue 返回 404，请使用 LevelsResponse", ReplaceWith("LevelsResponse"))
@Serializable
data class StoredValueResponse(
    val deviceID: String? = null,
    @SerialName("Value") val value: Int = 0,  // 当前存储金额（分）
    @SerialName("ValueCents") val valueCents: Int = 0,  // 当前存储金额（分，可选字段）
    val error: String? = null
) {
    /**
     * 获取存储金额（分）- 优先使用 value，其次 valueCents
     */
    val storedCents: Int
        get() = value.takeIf { it > 0 } ?: valueCents
    
    /**
     * 获取存储金额（元）
     */
    val storedAmount: Double
        get() = storedCents / 100.0
}
