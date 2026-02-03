package com.carwash.carpayment.data.carwash

/**
 * 洗车机设备 API 接口
 * 
 * 通讯方式：Modbus 485 ASCII
 * 波特率：9600
 * 数据位：7位
 * 停止位：1位
 * 校验：偶校验
 * 地址：01
 */
interface CarWashDeviceApi {
    
    /**
     * 连接洗车机
     * @return 是否连接成功
     */
    suspend fun connect(): Boolean
    
    /**
     * 断开连接
     */
    suspend fun disconnect()
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean
    
    /**
     * 确保已连接（如果未连接则尝试连接）
     * @return 是否已连接（连接成功或已经连接）
     */
    suspend fun ensureConnected(): Boolean
    
    /**
     * 读取寄存器值
     * @param registerAddress 寄存器地址（如 217, 752, 102, 214 等）
     * @return 寄存器值（十六进制字符串，如 "F1" 表示有效，"F0" 表示无效）
     */
    suspend fun readRegister(registerAddress: Int): String?
    
    /**
     * 写入寄存器值
     * @param registerAddress 寄存器地址
     * @param value 要写入的值
     * @return 是否成功
     */
    suspend fun writeRegister(registerAddress: Int, value: Int): Boolean
    
    /**
     * 发送洗车模式指令
     * @param mode 洗车模式（1-4）
     * @return 是否成功
     */
    suspend fun sendWashMode(mode: Int): Boolean
    
    /**
     * 写 Mode 脉冲（V2）
     * @param mode 洗车模式（1-4）
     * @return 是否成功
     */
    suspend fun writeModePulse(mode: Int): Boolean
    
    /**
     * 发送取消指令
     * @return 是否成功
     */
    suspend fun sendCancel(): Boolean
    
    /**
     * 发送复位指令
     * @return 是否成功
     */
    suspend fun sendReset(): Boolean
    
    /**
     * 发送暂停指令
     * @return 是否成功
     */
    suspend fun sendPause(): Boolean
    
    /**
     * 发送继续指令（暂停后继续）
     * @return 是否成功
     */
    suspend fun sendResume(): Boolean
    
    /**
     * 读取故障状态（寄存器 217）
     * @return true 表示有故障，false 表示无故障，null 表示读取失败
     */
    suspend fun readFaultStatus(): Boolean?
    
    /**
     * 读取前车状态（寄存器 752）
     * @return true 表示前车还在，false 表示前车已离开，null 表示读取失败
     */
    suspend fun readPreviousCarStatus(): Boolean?
    
    /**
     * 读取可再次洗车状态（寄存器 240）
     * @return true 表示可以再次洗车，false 表示不可以，null 表示读取失败
     */
    suspend fun readCanWashAgainStatus(): Boolean?
    
    /**
     * 读取车位状态（寄存器 102）
     * @return true 表示车到位，false 表示车未到位，null 表示读取失败
     */
    suspend fun readCarPositionStatus(): Boolean?
    
    /**
     * 读取启动洗车状态（寄存器 214）
     * @return true 表示自动状态，false 表示非自动状态，null 表示读取失败
     */
    suspend fun readWashStartStatus(): Boolean?
    
    /**
     * 读取洗车总数（寄存器 2550）
     * @return 洗车总数，null 表示读取失败
     */
    suspend fun readTotalWashCount(): Int?
    
    /**
     * 读取今日洗车数（寄存器 2552）
     * @return 今日洗车数，null 表示读取失败
     */
    suspend fun readTodayWashCount(): Int?
    
    /**
     * 读取昨日洗车数（寄存器 2592）
     * @return 昨日洗车数，null 表示读取失败
     */
    suspend fun readYesterdayWashCount(): Int?
    
    /**
     * 读取洗车机状态快照（一次性读取所有关键寄存器）
     * @return 状态快照，失败返回 null
     */
    suspend fun readSnapshot(): com.carwash.carpayment.data.carwash.CarWashSnapshot?
}
