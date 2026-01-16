package com.carwash.carpayment.data.cashdevice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
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
     */
    @POST("CashDevice/StartDevice")
    suspend fun startDevice(
        @Query("deviceID") deviceID: String
    ): ApiResponse
    
    /**
     * 启用接收器
     * POST /CashDevice/EnableAcceptor?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     */
    @POST("CashDevice/EnableAcceptor")
    suspend fun enableAcceptor(
        @Query("deviceID") deviceID: String
    ): ApiResponse
    
    /**
     * 禁用接收器
     * POST /CashDevice/DisableAcceptor?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     */
    @POST("CashDevice/DisableAcceptor")
    suspend fun disableAcceptor(
        @Query("deviceID") deviceID: String
    ): ApiResponse
    
    /**
     * 设置自动接受
     * POST /CashDevice/SetAutoAccept?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     */
    @POST("CashDevice/SetAutoAccept")
    @Headers("Content-Type: application/json")
    suspend fun setAutoAccept(
        @Query("deviceID") deviceID: String,
        @Body autoAccept: Boolean
    ): ApiResponse
    
    /**
     * 获取设备状态
     * GET /CashDevice/GetDeviceStatus?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     * 注意：服务器可能返回空数组 [] 或对象 {}，需要容错处理
     */
    @GET("CashDevice/GetDeviceStatus")
    suspend fun getDeviceStatus(
        @Query("deviceID") deviceID: String
    ): DeviceStatusResponse
    
    /**
     * 断开设备连接
     * POST /CashDevice/DisconnectDevice?deviceID={deviceID}
     * Authorization 头由 Interceptor 自动添加
     */
    @POST("CashDevice/DisconnectDevice")
    suspend fun disconnectDevice(
        @Query("deviceID") deviceID: String
    ): ApiResponse
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
 * 注意：服务器可能返回空数组 []，需要容错处理
 */
@Serializable
data class DeviceStatusResponse(
    val deviceID: String? = null,
    val status: String? = null,
    val error: String? = null
)
