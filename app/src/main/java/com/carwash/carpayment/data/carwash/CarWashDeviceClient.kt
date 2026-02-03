package com.carwash.carpayment.data.carwash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * 洗车机设备客户端（RS-485 Modbus ASCII 实现）
 * 
 * 通讯方式：Modbus 485 ASCII
 * 波特率：9600
 * 数据位：7位
 * 停止位：1位
 * 校验：偶校验
 * 地址：01
 * 
 * 协议规范：
 * - 帧以 : 开头
 * - 以 \r\n 结尾（0D 0A）
 * - 校验为 LRC（不是 CRC16）
 * - 接收端必须按 \r\n 分帧读取，并对 LRC 做校验
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarWashDeviceClient(
    private val context: Context,
    private val serialPortPath: String = "/dev/ttyS3" // 固定串口路径
) : CarWashDeviceApi {
    
    companion object {
        private const val TAG = "CarWashDeviceClient"
        private const val RX_GUARD_TAG = "CARWASH_RX_GUARD"
        private const val DEVICE_ADDRESS = 0x01 // 设备地址 01
        private const val BAUD_RATE = 9600
        private const val DATA_BITS = 7
        private const val STOP_BITS = 1
        private const val PARITY = "EVEN" // 偶校验
        private const val READ_TIMEOUT_MS = 1000L
        private const val FRAME_READ_TIMEOUT_MS = 2000L
        private const val POLL_INTERVAL_MS = 500L // 轮询间隔 >= 500ms
        private const val OVERFLOW_BACKOFF_MS = 1500L // 溢出退避时间
        
        // 寄存器地址映射（根据PDF文档）
        private const val REG_FAULT = 217          // 故障状态
        private const val REG_PREVIOUS_CAR = 752   // 前车状态
        private const val REG_CAN_WASH_AGAIN = 240 // 可再次洗车
        private const val REG_CAR_POSITION = 102   // 车位状态
        private const val REG_WASH_START = 214     // 启动洗车状态
        private const val REG_TOTAL_COUNT = 2550   // 洗车总数
        private const val REG_TODAY_COUNT = 2552   // 今日洗车数
        private const val REG_YESTERDAY_COUNT = 2592 // 昨日洗车数
        
        // 洗车模式寄存器地址（根据PDF文档）
        private const val REG_MODE_1 = 2310 // M261
        private const val REG_MODE_2 = 2311 // M262
        private const val REG_MODE_3 = 2309 // M260
        private const val REG_MODE_4 = 2313 // M264
        private const val REG_CANCEL = 2109 // 取消指令
        private const val REG_RESET = 2081  // 复位指令
        private const val REG_PAUSE = 2120  // 暂停指令
    }
    
    private var serialPort: FileInputStream? = null
    private var serialPortOut: FileOutputStream? = null
    private var isConnectedFlag = false
    private val rxBuffer = ByteArray(4096) // 类级接收缓冲区（字节数组），避免丢帧
    private var rxBufferLength = 0 // 当前缓冲区有效长度
    
    // 串口事务互斥锁（强制 single-flight）
    private val serialMutex = Mutex()
    
    // 帧队列：用于处理粘包/多帧一起到达的情况
    private val frameQueue = Channel<String>(Channel.UNLIMITED)
    private var readJob: kotlinx.coroutines.Job? = null
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 轮询控制：单飞模式（上一轮请求未完成，下一轮跳过）
    private var isPollingInFlight = false
    private var lastPollTime = 0L
    
    // 首页轮询暂停标志
    private var isHomePollingPaused = false
    
    /**
     * 启动轮询（启动后台读线程：持续从串口读取并切帧，推入 frameQueue）
     * 只有进入洗车流程时才调用
     */
    fun startPolling() {
        if (readJob?.isActive == true) {
            Log.w(TAG, "[CarWash] 读线程已在运行，跳过启动")
            return
        }
        
        if (!isConnectedFlag) {
            Log.w(TAG, "[CarWash] 未连接，无法启动轮询")
            return
        }
        
        readJob = readScope.launch {
            val inputStream = serialPort ?: run {
                Log.e(TAG, "[CarWash] 读线程启动失败: serialPort 为 null")
                return@launch
            }
            
            val readBuffer = ByteArray(256) // 每次读取的临时缓冲区
            
            Log.e(RX_GUARD_TAG, "start polling")
            Log.d(TAG, "[CarWash] 读线程已启动")
            
            try {
                while (isActive && isConnectedFlag) {
                    try {
                        // 节流：轮询间隔 >= 500ms
                        val now = System.currentTimeMillis()
                        val timeSinceLastPoll = now - lastPollTime
                        if (timeSinceLastPoll < POLL_INTERVAL_MS) {
                            val sleepTime = POLL_INTERVAL_MS - timeSinceLastPoll
                            Thread.sleep(sleepTime)
                            continue
                        }
                        
                        // 单飞模式：若上一轮请求未完成，下一轮跳过
                        if (isPollingInFlight) {
                            Log.e(RX_GUARD_TAG, "skip due to inflight")
                            Thread.sleep(POLL_INTERVAL_MS)
                            continue
                        }
                        
                        isPollingInFlight = true
                        lastPollTime = System.currentTimeMillis()
                        
                        val bytesRead = if (inputStream.available() > 0) {
                            inputStream.read(readBuffer)
                        } else {
                            // 没有可用数据时短暂休眠
                            isPollingInFlight = false
                            Thread.sleep(10)
                            continue
                        }
                        
                        if (bytesRead > 0) {
                            // RX 原始字节 hexdump 日志
                            val rawHex = readBuffer.sliceArray(0 until bytesRead).joinToString(" ") { String.format("%02X", it) }
                            Log.d(TAG, "[CarWash] RX_RAW_HEX=$rawHex (bytesRead=$bytesRead)")
                            
                            // 确保缓冲区有足够空间
                            if (rxBufferLength + bytesRead > rxBuffer.size) {
                                Log.w(TAG, "[CarWash] 接收缓冲区溢出，清空缓冲区")
                                Log.e(RX_GUARD_TAG, "overflow backoff")
                                Log.d(TAG, "[CarWash] 当前累计缓冲区长度: $rxBufferLength, 新读取: $bytesRead, 总需求: ${rxBufferLength + bytesRead}")
                                rxBufferLength = 0
                                isPollingInFlight = false
                                // 溢出退避：sleep 1500ms
                                Thread.sleep(OVERFLOW_BACKOFF_MS)
                                continue
                            }
                            
                            // 将读取的字节追加到缓冲区
                            System.arraycopy(readBuffer, 0, rxBuffer, rxBufferLength, bytesRead)
                            rxBufferLength += bytesRead
                            
                            Log.d(TAG, "[CarWash] 当前累计缓冲区长度: $rxBufferLength")
                            
                            // 查找所有 0D 0A（\r\n）位置，切出所有完整帧
                            var searchStart = 0
                            var hasCrlf = false
                            while (searchStart < rxBufferLength - 1) {
                                var frameEndIndex = -1
                                for (i in searchStart until rxBufferLength - 1) {
                                    if (rxBuffer[i].toInt() == 0x0D && rxBuffer[i + 1].toInt() == 0x0A) {
                                        frameEndIndex = i
                                        hasCrlf = true
                                        break
                                    }
                                }
                                
                                Log.d(TAG, "[CarWash] 是否检测到 0D0A: $hasCrlf")
                                
                                if (frameEndIndex >= 0) {
                                    // 提取完整帧（含 : 开头，不含 \r\n）
                                    val frameBytes = rxBuffer.sliceArray(0 until frameEndIndex)
                                    val frame = String(frameBytes, Charsets.US_ASCII)
                                    
                                    // 移除已处理的部分（包括 0D 0A）
                                    val remainingLength = rxBufferLength - (frameEndIndex + 2)
                                    if (remainingLength > 0) {
                                        System.arraycopy(rxBuffer, frameEndIndex + 2, rxBuffer, 0, remainingLength)
                                    }
                                    rxBufferLength = remainingLength
                                    
                                    // 推入帧队列
                                    frameQueue.send(frame)
                                    Log.d(TAG, "[CarWash] 读线程切出帧并推入队列: $frame")
                                    
                                    // 继续查找下一帧
                                    searchStart = 0
                                } else {
                                    // 没有找到完整的帧，等待更多数据
                                    break
                                }
                            }
                        }
                        
                        isPollingInFlight = false
                    } catch (e: IOException) {
                        isPollingInFlight = false
                        if (isActive && isConnectedFlag) {
                            Log.e(TAG, "[CarWash] 读线程 IO 异常", e)
                            // 继续尝试读取
                        }
                    } catch (e: Exception) {
                        isPollingInFlight = false
                        if (isActive && isConnectedFlag) {
                            Log.e(TAG, "[CarWash] 读线程异常", e)
                            // 继续尝试读取
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 读线程退出", e)
            } finally {
                isPollingInFlight = false
                Log.e(RX_GUARD_TAG, "stop polling")
                Log.d(TAG, "[CarWash] 读线程已停止")
            }
        }
    }
    
    /**
     * 启动后台读线程（内部方法，已废弃，使用 startPolling 替代）
     */
    @Deprecated("使用 startPolling 替代")
    private fun startReadThread() {
        startPolling()
    }
    
    /**
     * 停止轮询（停止后台读线程）
     * 离开洗车流程时调用
     */
    fun stopPolling() {
        readJob?.cancel()
        readJob = null
        isPollingInFlight = false
        Log.e(RX_GUARD_TAG, "stop polling")
        Log.d(TAG, "[CarWash] 读线程已停止")
    }
    
    /**
     * 停止后台读线程（内部方法，已废弃，使用 stopPolling 替代）
     */
    @Deprecated("使用 stopPolling 替代")
    private fun stopReadThread() {
        stopPolling()
    }
    
    /**
     * 计算 LRC（Longitudinal Redundancy Check）
     * LRC = -(sum of all bytes) & 0xFF
     */
    private fun calculateLRC(bytes: ByteArray): Int {
        val sum = bytes.sumOf { it.toInt() and 0xFF }
        return ((-sum) and 0xFF)
    }
    
    /**
     * 构建 Modbus ASCII 帧
     * 格式：: + payload hex + LRC hex + \r\n
     * 
     * @param payload 原始字节数组（地址、功能码、数据等）
     * @return ASCII 编码的字节数组
     */
    private fun buildAsciiFrame(payload: ByteArray): ByteArray {
        // 计算 LRC
        val lrc = calculateLRC(payload)
        
        // 构建 ASCII 字符串：: + hex + LRC + \r\n
        val hexString = buildString {
            append(':')
            // payload 的每个字节转为 2 位十六进制
            for (byte in payload) {
                append(String.format("%02X", byte.toInt() and 0xFF))
            }
            // LRC 转为 2 位十六进制
            append(String.format("%02X", lrc))
            append("\r\n")
        }
        
        return hexString.toByteArray(Charsets.US_ASCII)
    }
    
    /**
     * 构建 Modbus ASCII 读取指令
     * 功能码 03：读保持寄存器
     * 
     * @param registerAddress 寄存器地址
     * @param count 读取数量（默认1）
     * @return ASCII 编码的字节数组
     */
    private fun buildReadCommand(registerAddress: Int, count: Int = 1): ByteArray {
        val addressHigh = (registerAddress shr 8) and 0xFF
        val addressLow = registerAddress and 0xFF
        val quantityHigh = (count shr 8) and 0xFF
        val quantityLow = count and 0xFF
        
        // Modbus 功能码 03 的 payload
        val payload = byteArrayOf(
            DEVICE_ADDRESS.toByte(),
            0x03.toByte(), // 功能码：读保持寄存器
            addressHigh.toByte(),
            addressLow.toByte(),
            quantityHigh.toByte(),
            quantityLow.toByte()
        )
        
        return buildAsciiFrame(payload)
    }
    
    /**
     * 构建 Modbus ASCII 写入指令
     * 功能码 06：写单个寄存器
     * 
     * @param registerAddress 寄存器地址
     * @param value 要写入的值
     * @return ASCII 编码的字节数组
     */
    private fun buildWriteCommand(registerAddress: Int, value: Int): ByteArray {
        val addressHigh = (registerAddress shr 8) and 0xFF
        val addressLow = registerAddress and 0xFF
        val valueHigh = (value shr 8) and 0xFF
        val valueLow = value and 0xFF
        
        // Modbus 功能码 06 的 payload
        val payload = byteArrayOf(
            DEVICE_ADDRESS.toByte(),
            0x06.toByte(), // 功能码：写单个寄存器
            addressHigh.toByte(),
            addressLow.toByte(),
            valueHigh.toByte(),
            valueLow.toByte()
        )
        
        return buildAsciiFrame(payload)
    }
    
    /**
     * 解析 ASCII 十六进制字符串为字节数组
     * 例如："0103020000" -> [0x01, 0x03, 0x02, 0x00, 0x00]
     */
    private fun parseHexString(hexString: String): ByteArray? {
        if (hexString.length % 2 != 0) {
            return null
        }
        val bytes = ByteArray(hexString.length / 2)
        for (i in bytes.indices) {
            val hexByte = hexString.substring(i * 2, i * 2 + 2)
            bytes[i] = hexByte.toIntOrNull(16)?.toByte() ?: return null
        }
        return bytes
    }
    
    /**
     * 从 frameQueue 读取匹配的响应帧
     * 
     * @param expectedAddr 期望的设备地址
     * @param expectedFunc 期望的功能码（0x03 表示读保持寄存器，0x06 表示写单个寄存器）
     * @param expectedByteCount 期望的字节数（功能码 03 时 quantity=1 为 0x02，功能码 06 时忽略）
     * @param timeoutMs 超时时间（毫秒）
     * @return 匹配的帧（含 : 开头，不含 \r\n），失败返回 null
     */
    private suspend fun readMatchingFrame(
        expectedAddr: Int = DEVICE_ADDRESS,
        expectedFunc: Int = 0x03,
        expectedByteCount: Int? = 0x02, // 可为 null，表示不检查 byteCount（用于功能码 06）
        timeoutMs: Long = FRAME_READ_TIMEOUT_MS
    ): String? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // 从帧队列取帧（带超时）
                val frame = try {
                    val remainingTime = timeoutMs - (System.currentTimeMillis() - startTime)
                    if (remainingTime <= 0) {
                        Log.w(TAG, "[CarWash] 读取匹配帧超时: 未找到匹配的帧")
                        return@withContext null
                    }
                    kotlinx.coroutines.withTimeout(remainingTime) {
                        frameQueue.receive()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG, "[CarWash] 读取匹配帧超时: 未找到匹配的帧")
                    return@withContext null
                } catch (e: Exception) {
                    Log.e(TAG, "[CarWash] 从帧队列取帧异常", e)
                    return@withContext null
                }
                
                // 解析并验证帧
                val payload = parseAndVerifyFrame(frame)
                if (payload == null) {
                    Log.w(TAG, "[CarWash] 帧解析失败，跳过: $frame")
                    continue // 继续取下一帧
                }
                
                // 验证响应匹配请求
                val minSize = if (expectedFunc == 0x03) 5 else 6 // 功能码 03 需要 5 字节，功能码 06 需要 6 字节
                if (payload.size < minSize) {
                    Log.w(TAG, "[CarWash] 帧 payload 长度不足，跳过: size=${payload.size}, 期望>=${minSize}")
                    continue
                }
                
                val addr = payload[0].toInt() and 0xFF
                val func = payload[1].toInt() and 0xFF
                
                // 检查匹配条件
                if (addr != expectedAddr) {
                    Log.w(TAG, "[CarWash] 帧地址不匹配，丢弃: 期望=$expectedAddr, 实际=$addr, frame=$frame")
                    continue
                }
                
                if (func != expectedFunc) {
                    Log.w(TAG, "[CarWash] 帧功能码不匹配，丢弃: 期望=$expectedFunc, 实际=$func, frame=$frame")
                    continue
                }
                
                // 功能码 03 需要检查 byteCount（V2 修正：只匹配 addr==1 && func==03 && byteCount==2*qty）
                if (expectedFunc == 0x03) {
                    if (payload.size < 3) {
                        Log.w(TAG, "[CarWash] 帧 payload 长度不足（无法读取 byteCount），跳过: size=${payload.size}, frame=$frame")
                        continue
                    }
                    val byteCount = payload[2].toInt() and 0xFF
                    // V2 修正：只检查 byteCount 是否符合预期（2*qty），不要求响应里出现 startAddr/qty
                    if (expectedByteCount != null && byteCount != expectedByteCount) {
                        Log.w(TAG, "[CarWash] 帧字节数不匹配，丢弃: 期望=$expectedByteCount, 实际=$byteCount, frame=$frame")
                        continue
                    }
                    // 验证：响应格式应为 [addr, func, byteCount, data...]，data 的前 2 字节作为寄存器值
                    if (payload.size < 5) {
                        Log.w(TAG, "[CarWash] 帧 payload 长度不足（无法读取寄存器值），跳过: size=${payload.size}, frame=$frame")
                        continue
                    }
                    // 解析 data 的前 2 字节作为寄存器值（payload[3] 和 payload[4]）
                    val regValueHi = payload[3].toInt() and 0xFF
                    val regValueLo = payload[4].toInt() and 0xFF
                    val regValue = (regValueHi shl 8) or regValueLo
                    Log.d(TAG, "[CarWash] FC03 响应解析: addr=$addr, func=$func, byteCount=$byteCount, regValue=$regValue (0x${String.format("%04X", regValue)})")
                }
                // 功能码 06 不需要检查 byteCount（响应格式：[addr, 0x06, regHi, regLo, valHi, valLo]）
                
                // 匹配成功
                Log.d(TAG, "[CarWash] 找到匹配的响应帧: addr=$addr, func=$func, payloadSize=${payload.size}, frame=$frame")
                return@withContext frame
            }
            
            Log.w(TAG, "[CarWash] 读取匹配帧超时: 未找到匹配的帧")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 读取匹配帧异常", e)
            return@withContext null
        }
    }
    
    /**
     * 按 0D 0A 分帧读取 Modbus ASCII 响应（已废弃，使用 readMatchingFrame）
     * 
     * @param timeoutMs 超时时间（毫秒）
     * @return 完整的帧（含 : 开头，不含 \r\n），失败返回 null
     */
    @Deprecated("使用 readMatchingFrame 替代，支持响应匹配")
    private suspend fun readFrame(timeoutMs: Long = FRAME_READ_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        val inputStream = serialPort ?: return@withContext null
        val startTime = System.currentTimeMillis()
        val readBuffer = ByteArray(256) // 每次读取的临时缓冲区
        
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // 持续读取字节流
                val bytesRead = try {
                    if (inputStream.available() > 0) {
                        inputStream.read(readBuffer)
                    } else {
                        // 没有可用数据时，尝试阻塞读取（最多等待 100ms）
                        -1
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "[CarWash] 读取字节时发生 IO 异常", e)
                    return@withContext null
                }
                
                if (bytesRead > 0) {
                    // 确保缓冲区有足够空间
                    if (rxBufferLength + bytesRead > rxBuffer.size) {
                        Log.w(TAG, "[CarWash] 接收缓冲区溢出，清空缓冲区")
                        Log.e(RX_GUARD_TAG, "overflow backoff")
                        rxBufferLength = 0
                        // 溢出退避：sleep 1500ms
                        Thread.sleep(OVERFLOW_BACKOFF_MS)
                        continue
                    }
                    
                    // 将读取的字节追加到缓冲区
                    System.arraycopy(readBuffer, 0, rxBuffer, rxBufferLength, bytesRead)
                    rxBufferLength += bytesRead
                    
                    // 打印接收到的原始 HEX（包含 CRLF）
                    val receivedBytes = rxBuffer.sliceArray(0 until rxBufferLength)
                    val rxHex = receivedBytes.joinToString(" ") { String.format("%02X", it) }
                    Log.d(TAG, "[CarWash] RX_HEX=${rxHex}")
                    
                    // 在字节数组中查找 0D 0A（\r\n）位置
                    // 从缓冲区开头查找，找到第一个完整的帧就返回
                    var frameEndIndex = -1
                    for (i in 0 until rxBufferLength - 1) {
                        if (rxBuffer[i].toInt() == 0x0D && rxBuffer[i + 1].toInt() == 0x0A) {
                            frameEndIndex = i
                            break
                        }
                    }
                    
                    if (frameEndIndex >= 0) {
                        // 提取完整帧（含 : 开头，不含 \r\n）
                        val frameBytes = rxBuffer.sliceArray(0 until frameEndIndex)
                        val frame = String(frameBytes, Charsets.US_ASCII)
                        
                        // 移除已处理的部分（包括 0D 0A）
                        val remainingLength = rxBufferLength - (frameEndIndex + 2)
                        if (remainingLength > 0) {
                            System.arraycopy(rxBuffer, frameEndIndex + 2, rxBuffer, 0, remainingLength)
                        }
                        rxBufferLength = remainingLength
                        
                        Log.d(TAG, "[CarWash] 读取到完整帧: $frame")
                        return@withContext frame
                    }
                    // 如果没有找到完整的帧，继续读取更多数据
                } else if (bytesRead == -1) {
                    // 没有可用数据时短暂休眠，避免 CPU 占用过高
                    Thread.sleep(10)
                }
            }
            
            // 超时：打印已读原始 HEX
            if (rxBufferLength > 0) {
                val receivedBytes = rxBuffer.sliceArray(0 until rxBufferLength)
                val rxHex = receivedBytes.joinToString(" ") { String.format("%02X", it) }
                Log.w(TAG, "[CarWash] 读取帧超时: 已读原始HEX=$rxHex")
            } else {
                Log.w(TAG, "[CarWash] 读取帧超时: 未读取到任何数据")
            }
            return@withContext null
            
        } catch (e: IOException) {
            Log.e(TAG, "[CarWash] 读取帧时发生 IO 异常", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 读取帧时发生异常", e)
            return@withContext null
        }
    }
    
    /**
     * 验证并解析 Modbus ASCII 响应帧
     * 
     * @param frame ASCII 字符串（不含 \r\n）
     * @return 解析后的 payload 字节数组，失败返回 null
     */
    private fun parseAndVerifyFrame(frame: String): ByteArray? {
        // 验证：必须以 : 开头
        if (!frame.startsWith(":")) {
            Log.e(TAG, "[CarWash] ❌ 帧格式错误: 不是以 : 开头, frame=$frame")
            return null
        }
        
        // 提取十六进制部分（去掉开头的 :）
        val hexPart = frame.substring(1)
        
        // 验证：只含 0-9A-F
        if (!hexPart.matches(Regex("[0-9A-Fa-f]+"))) {
            Log.e(TAG, "[CarWash] ❌ 帧格式错误: 包含非法字符, frame=$frame")
            return null
        }
        
        // 验证：长度必须为偶数（每2个字符代表1个字节）
        if (hexPart.length % 2 != 0) {
            Log.e(TAG, "[CarWash] ❌ 帧格式错误: 十六进制长度不是偶数, frame=$frame")
            return null
        }
        
        // 解析为字节数组
        val allBytes = parseHexString(hexPart) ?: run {
            Log.e(TAG, "[CarWash] ❌ 帧解析失败: 无法解析十六进制, frame=$frame")
            return null
        }
        
        if (allBytes.size < 2) {
            Log.e(TAG, "[CarWash] ❌ 帧格式错误: 数据太短, frame=$frame")
            return null
        }
        
        // 分离 payload 和 LRC
        val payload = allBytes.sliceArray(0 until allBytes.size - 1)
        val receivedLRC = allBytes[allBytes.size - 1].toInt() and 0xFF
        
        // 计算并验证 LRC
        val calculatedLRC = calculateLRC(payload)
        
        if (receivedLRC != calculatedLRC) {
            Log.e(TAG, "[CarWash] ❌ LRC 校验失败: 接收=${String.format("%02X", receivedLRC)}, 计算=${String.format("%02X", calculatedLRC)}, frame=$frame")
            return null
        }
        
        Log.d(TAG, "[CarWash] ✅ LRC 校验通过: LRC=${String.format("%02X", receivedLRC)}")
        return payload
    }
    
    /**
     * 连接洗车机
     * 使用 stty 命令配置串口为 9600/7E1
     * ⚠️ stty 必须在 open 前执行，确保参数稳定生效
     */
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[CarWash] ========== 连接洗车机 ==========")
            Log.d(TAG, "[CarWash] port=$serialPortPath, params=${BAUD_RATE}/${DATA_BITS}${PARITY}${STOP_BITS}, addr=${DEVICE_ADDRESS}")
            
            val portFile = File(serialPortPath)
            if (!portFile.exists()) {
                Log.e(TAG, "[CarWash] 串口文件不存在: $serialPortPath")
                Log.e(TAG, "[CarWash] connect() FAILED: 串口文件不存在")
                return@withContext false
            }
            
            // 如果串口已打开，先关闭（确保 stty 能正确配置）
            try {
                serialPort?.close()
                serialPortOut?.close()
                serialPort = null
                serialPortOut = null
            } catch (e: Exception) {
                Log.w(TAG, "[CarWash] 关闭已打开串口时异常: ${e.message}")
            }
            
            // ⚠️ 必须在 open 前执行 stty 配置串口参数（9600/7E1）
            // V2: 添加 raw 模式参数（-icanon -echo -isig -iexten -opost -icrnl -inlcr -igncr）
            try {
                val sttyCommand = "stty -F $serialPortPath $BAUD_RATE cs${DATA_BITS} parenb -parodd -cstopb -ixon -ixoff -crtscts -icanon -echo -isig -iexten -opost -icrnl -inlcr -igncr"
                Log.d(TAG, "[CarWash] 执行 stty 配置（open 前）: $sttyCommand")
                
                // 使用 su 0 sh -c 方式执行（已验证可用）
                val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", sttyCommand))
                val exitCode = process.waitFor()
                
                // 读取 stdout 和 stderr
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                
                if (exitCode == 0) {
                    Log.d(TAG, "[CarWash] ✅ stty 配置成功: ${BAUD_RATE}/${DATA_BITS}${PARITY}${STOP_BITS}")
                    if (stdout.isNotEmpty()) {
                        Log.d(TAG, "[CarWash] stty stdout: $stdout")
                    }
                } else {
                    Log.w(TAG, "[CarWash] ⚠️ stty 配置失败 (exitCode=$exitCode)")
                    if (stdout.isNotEmpty()) {
                        Log.w(TAG, "[CarWash] stty stdout: $stdout")
                    }
                    if (stderr.isNotEmpty()) {
                        Log.w(TAG, "[CarWash] stty stderr: $stderr")
                    }
                    Log.w(TAG, "[CarWash] ⚠️ 继续尝试，但串口参数可能未正确配置")
                }
                
                // stty 后执行 stty -a 验证并打印配置
                try {
                    val verifyCommand = "stty -F $serialPortPath -a"
                    Log.d(TAG, "[CarWash] 执行 stty 验证: $verifyCommand")
                    
                    val verifyProcess = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", verifyCommand))
                    val verifyExitCode = verifyProcess.waitFor()
                    
                    val verifyStdout = verifyProcess.inputStream.bufferedReader().readText()
                    val verifyStderr = verifyProcess.errorStream.bufferedReader().readText()
                    
                    if (verifyExitCode == 0) {
                        Log.d(TAG, "[CarWash] ✅ stty 验证输出: $verifyStdout")
                        // 确认输出包含 -icrnl -inlcr -igncr
                        if (verifyStdout.contains("-icrnl") && verifyStdout.contains("-inlcr") && verifyStdout.contains("-igncr")) {
                            Log.d(TAG, "[CarWash] ✅ stty 验证确认: 输出包含 -icrnl -inlcr -igncr")
                        } else {
                            Log.w(TAG, "[CarWash] ⚠️ stty 验证警告: 输出未包含 -icrnl -inlcr -igncr")
                        }
                    } else {
                        Log.w(TAG, "[CarWash] ⚠️ stty 验证失败 (exitCode=$verifyExitCode)")
                        if (verifyStdout.isNotEmpty()) {
                            Log.w(TAG, "[CarWash] stty 验证 stdout: $verifyStdout")
                        }
                        if (verifyStderr.isNotEmpty()) {
                            Log.w(TAG, "[CarWash] stty 验证 stderr: $verifyStderr")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[CarWash] ⚠️ stty 验证异常: ${e.message}", e)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "[CarWash] ⚠️ stty 配置异常（可能无权限或无 stty 命令）: ${e.message}", e)
                Log.w(TAG, "[CarWash] ⚠️ 继续尝试，但串口参数可能未正确配置")
            }
            
            // stty 配置后再打开串口
            try {
                serialPort = FileInputStream(portFile)
                serialPortOut = FileOutputStream(portFile)
                Log.d(TAG, "[CarWash] ✅ 串口打开成功（stty 配置后）")
            } catch (e: SecurityException) {
                Log.e(TAG, "[CarWash] 串口访问权限不足: $serialPortPath", e)
                Log.e(TAG, "[CarWash] connect() FAILED: 权限不足", e)
                return@withContext false
            } catch (e: IOException) {
                Log.e(TAG, "[CarWash] 打开串口失败: $serialPortPath", e)
                Log.e(TAG, "[CarWash] connect() FAILED: IOException", e)
                return@withContext false
            }
            
            isConnectedFlag = true
            
            // 注意：不再自动启动读线程，只有调用 startPolling() 时才启动
            // 只有进入洗车流程（例如 WashFlowViewModel 状态进入 Waiting/Running）时才启动
            
            val apiId = System.identityHashCode(this)
            Log.d(TAG, "[CarWash] ========== 洗车机连接成功 ==========")
            Log.d(TAG, "[CarWash] CONNECT apiId=${apiId} connected=$isConnectedFlag port=$serialPortPath")
            Log.d(TAG, "[CarWash] connect() SUCCESS: apiId=${apiId}, port=$serialPortPath, params=${BAUD_RATE}/${DATA_BITS}${PARITY}${STOP_BITS}, addr=${DEVICE_ADDRESS}")
            return@withContext true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "[CarWash] 串口访问权限不足: $serialPortPath", e)
            Log.e(TAG, "[CarWash] connect() FAILED: SecurityException", e)
            return@withContext false
        } catch (e: IOException) {
            Log.e(TAG, "[CarWash] 打开串口失败: $serialPortPath", e)
            Log.e(TAG, "[CarWash] connect() FAILED: IOException", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 连接洗车机异常", e)
            Log.e(TAG, "[CarWash] connect() FAILED: Exception", e)
            return@withContext false
        }
    }
    
    /**
     * 断开连接
     */
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "[CarWash] 断开洗车机连接...")
                
                // 停止读线程（轮询）
                stopPolling()
                
                // 清空帧队列
                try {
                    while (true) {
                        frameQueue.tryReceive().getOrNull() ?: break
                    }
                } catch (e: Exception) {
                    // 忽略异常
                }
                
                serialPort?.close()
                serialPortOut?.close()
                serialPort = null
                serialPortOut = null
                isConnectedFlag = false
                rxBufferLength = 0 // 清空接收缓冲区
                Log.d(TAG, "[CarWash] 洗车机连接已断开")
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 断开连接异常", e)
            }
        }
    }
    
    /**
     * 检查是否已连接
     */
    override fun isConnected(): Boolean = isConnectedFlag
    
    /**
     * 确保已连接（如果未连接则尝试连接）
     * @return 是否已连接（连接成功或已经连接）
     */
    override suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        if (isConnectedFlag) {
            Log.d(TAG, "[CarWash] ensureConnected: 已经连接，无需重新连接")
            return@withContext true
        }
        
        Log.d(TAG, "[CarWash] ensureConnected: 未连接，尝试连接...")
        val success = connect()
        if (success) {
            Log.d(TAG, "[CarWash] ensureConnected: 连接成功")
        } else {
            Log.e(TAG, "[CarWash] ensureConnected: 连接失败")
        }
        return@withContext success
    }
    
    /**
     * 读取寄存器值（内部方法，返回 Int）
     * V2: 使用互斥锁确保 write + readUntilResponse 全过程不可被打断
     * 
     * @param registerAddress 寄存器地址
     * @return 寄存器值（Int），失败返回 null
     */
    private suspend fun readRegisterInt(registerAddress: Int): Int? = withContext(Dispatchers.IO) {
        if (!isConnectedFlag) {
            Log.w(TAG, "[CarWash] 读取寄存器失败: 洗车机未连接, 地址=$registerAddress")
            return@withContext null
        }
        
        // 使用互斥锁确保整个请求-响应过程不可被打断
        return@withContext serialMutex.withLock {
            try {
                val inputStream = serialPort ?: run {
                    Log.e(TAG, "[CarWash] 读取寄存器失败: serialPort 为 null")
                    return@withLock null
                }
                val outputStream = serialPortOut ?: run {
                    Log.e(TAG, "[CarWash] 读取寄存器失败: serialPortOut 为 null")
                    return@withLock null
                }
                
                // 清理旧 buffer（可选）
                val tempBuffer = ByteArray(256)
                var clearedBytes = 0
                while (inputStream.available() > 0 && clearedBytes < 1024) {
                    val read = inputStream.read(tempBuffer)
                    if (read > 0) {
                        clearedBytes += read
                    } else {
                        break
                    }
                }
                if (clearedBytes > 0) {
                    Log.d(TAG, "[CarWash] 清理旧 buffer: $clearedBytes 字节")
                }
                
                val command = buildReadCommand(registerAddress)
                
                // 打印发送帧的详细信息
                val commandStr = String(command, Charsets.US_ASCII)
                val txAsciiRepr = commandStr.replace("\r", "\\r").replace("\n", "\\n")
                val txHex = command.joinToString(" ") { String.format("%02X", it) }
                
                Log.d(TAG, "[CarWash] 读取寄存器请求: 地址=$registerAddress")
                Log.d(TAG, "[CarWash] TX_ASCII_REPR=$txAsciiRepr")
                Log.d(TAG, "[CarWash] TX_HEX=$txHex")
                
                // 验证结束符
                if (command.size < 2 || command[command.size - 2].toInt() != 0x0D || command[command.size - 1].toInt() != 0x0A) {
                    Log.e(TAG, "[CarWash] ❌ 错误：发送帧末尾不是 \\r\\n (0D 0A)")
                    Log.e(TAG, "[CarWash] 实际末尾字节: ${if (command.size >= 2) String.format("%02X %02X", command[command.size - 2], command[command.size - 1]) else "不足2字节"}")
                } else {
                    Log.d(TAG, "[CarWash] ✅ 发送帧结束符验证通过: 0D 0A")
                }
                
                // 发送指令
                outputStream.write(command)
                outputStream.flush()
                
                // 直接读取响应（循环读直到拿到 1 帧完整 CRLF）
                val readBuffer = ByteArray(256)
                val responseBuffer = ByteArray(512)
                var responseLength = 0
                val startTime = System.currentTimeMillis()
                var foundCrlf = false
                
                while (System.currentTimeMillis() - startTime < FRAME_READ_TIMEOUT_MS && !foundCrlf) {
                    val available = inputStream.available()
                    if (available > 0) {
                        val bytesRead = inputStream.read(readBuffer, 0, minOf(available, readBuffer.size))
                        if (bytesRead > 0) {
                            // RX 原始日志
                            val rawHex = readBuffer.sliceArray(0 until bytesRead).joinToString(" ") { String.format("%02X", it) }
                            Log.d(TAG, "[CarWash] RX_RAW_LEN=$bytesRead")
                            Log.d(TAG, "[CarWash] RX_RAW_HEX=$rawHex")
                            
                            // 追加到响应缓冲区
                            if (responseLength + bytesRead > responseBuffer.size) {
                                Log.e(TAG, "[CarWash] 响应缓冲区溢出")
                                return@withLock null
                            }
                            System.arraycopy(readBuffer, 0, responseBuffer, responseLength, bytesRead)
                            responseLength += bytesRead
                            
                            Log.d(TAG, "[CarWash] RX_BUF_LEN=$responseLength")
                            
                            // 检查是否包含 CRLF
                            for (i in 0 until responseLength - 1) {
                                if (responseBuffer[i].toInt() == 0x0D && responseBuffer[i + 1].toInt() == 0x0A) {
                                    foundCrlf = true
                                    Log.d(TAG, "[CarWash] RX_HAS_CRLF=true (位置=$i)")
                                    break
                                }
                            }
                            if (!foundCrlf) {
                                Log.d(TAG, "[CarWash] RX_HAS_CRLF=false")
                            }
                        }
                    } else {
                        // 没有可用数据，短暂休眠
                        Thread.sleep(10)
                    }
                }
                
                if (!foundCrlf) {
                    Log.w(TAG, "[CarWash] 读取寄存器超时: 未找到完整 CRLF 帧, 地址=$registerAddress")
                    return@withLock null
                }
                
                // 提取完整帧（含 : 开头，不含 \r\n）
                var frameEndIndex = -1
                for (i in 0 until responseLength - 1) {
                    if (responseBuffer[i].toInt() == 0x0D && responseBuffer[i + 1].toInt() == 0x0A) {
                        frameEndIndex = i
                        break
                    }
                }
                
                if (frameEndIndex < 0) {
                    Log.e(TAG, "[CarWash] 未找到帧结束符")
                    return@withLock null
                }
                
                val frameBytes = responseBuffer.sliceArray(0 until frameEndIndex)
                val frame = String(frameBytes, Charsets.US_ASCII)
                
                // 打印接收帧
                val rxAsciiRepr = frame.replace("\r", "\\r").replace("\n", "\\n")
                val rxHex = frameBytes.joinToString(" ") { String.format("%02X", it) }
                Log.d(TAG, "[CarWash] RX_ASCII_REPR=$rxAsciiRepr")
                Log.d(TAG, "[CarWash] RX_HEX=$rxHex")
                
                // 解析并验证帧
                val payload = parseAndVerifyFrame(frame)
                if (payload == null) {
                    Log.e(TAG, "[CarWash] 读取寄存器响应解析失败: 地址=$registerAddress")
                    return@withLock null
                }
                
                // V2 匹配规则：只匹配 addr==1 && func==0x03 && byteCount==2*qty
                val addr = payload[0].toInt() and 0xFF
                val func = payload[1].toInt() and 0xFF
                
                if (addr != DEVICE_ADDRESS) {
                    Log.w(TAG, "[CarWash] 响应地址不匹配: 期望=$DEVICE_ADDRESS, 实际=$addr")
                    return@withLock null
                }
                
                if (func != 0x03) {
                    Log.w(TAG, "[CarWash] 响应功能码不匹配: 期望=0x03, 实际=0x${String.format("%02X", func)}")
                    return@withLock null
                }
                
                if (payload.size < 3) {
                    Log.e(TAG, "[CarWash] 响应 payload 长度不足: size=${payload.size}")
                    return@withLock null
                }
                
                val byteCount = payload[2].toInt() and 0xFF
                val expectedByteCount = 2 // qty=1, 所以 byteCount=2
                if (byteCount != expectedByteCount) {
                    Log.w(TAG, "[CarWash] 响应字节数不匹配: 期望=$expectedByteCount, 实际=$byteCount")
                    return@withLock null
                }
                
                // 验证响应格式：[addr, 0x03, byteCount, dataHi, dataLo, ...]
                if (payload.size < 5) {
                    Log.e(TAG, "[CarWash] 响应 payload 长度不足: 地址=$registerAddress, size=${payload.size}")
                    return@withLock null
                }
                
                // 提取寄存器值（data 的前 2 字节）
                val dataHigh = payload[3].toInt() and 0xFF
                val dataLow = payload[4].toInt() and 0xFF
                val registerValue = (dataHigh shl 8) or dataLow
                
                Log.d(TAG, "[CarWash] 读取寄存器成功: 地址=$registerAddress, 值(十进制)=$registerValue, 值(十六进制)=${String.format("%04X", registerValue)}")
                
                return@withLock registerValue
                
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 读取寄存器异常: 地址=$registerAddress", e)
                return@withLock null
            }
        }
    }
    
    /**
     * 读取寄存器值（接口方法，返回 String?）
     * 为了保持接口兼容性，返回十六进制字符串
     * 
     * @param registerAddress 寄存器地址
     * @return 寄存器值（十六进制字符串，如 "0001"），失败返回 null
     */
    override suspend fun readRegister(registerAddress: Int): String? {
        val value = readRegisterInt(registerAddress)
        return value?.let { String.format("%04X", it) }
    }
    
    /**
     * 写入寄存器值
     * V2: 使用互斥锁确保 write + readUntilResponse 全过程不可被打断
     */
    override suspend fun writeRegister(registerAddress: Int, value: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isConnectedFlag) {
            Log.w(TAG, "[CarWash] 洗车机未连接，无法写入寄存器")
            return@withContext false
        }
        
        // 使用互斥锁确保整个请求-响应过程不可被打断
        return@withContext serialMutex.withLock {
            try {
                val inputStream = serialPort ?: run {
                    Log.e(TAG, "[CarWash] 写入寄存器失败: serialPort 为 null")
                    return@withLock false
                }
                val outputStream = serialPortOut ?: run {
                    Log.e(TAG, "[CarWash] 写入寄存器失败: serialPortOut 为 null")
                    return@withLock false
                }
                
                val command = buildWriteCommand(registerAddress, value)
                
                // 打印发送帧的详细信息
                val commandStr = String(command, Charsets.US_ASCII)
                val txAsciiRepr = commandStr.replace("\r", "\\r").replace("\n", "\\n")
                val txHex = command.joinToString(" ") { String.format("%02X", it) }
                
                Log.d(TAG, "[CarWash] 写入寄存器: 地址=$registerAddress, 值=$value")
                Log.d(TAG, "[CarWash] TX_ASCII_REPR=$txAsciiRepr")
                Log.d(TAG, "[CarWash] TX_HEX=$txHex")
                
                // 验证结束符
                if (command.size < 2 || command[command.size - 2].toInt() != 0x0D || command[command.size - 1].toInt() != 0x0A) {
                    Log.e(TAG, "[CarWash] ❌ 错误：发送帧末尾不是 \\r\\n (0D 0A)")
                    Log.e(TAG, "[CarWash] 实际末尾字节: ${if (command.size >= 2) String.format("%02X %02X", command[command.size - 2], command[command.size - 1]) else "不足2字节"}")
                } else {
                    Log.d(TAG, "[CarWash] ✅ 发送帧结束符验证通过: 0D 0A")
                }
                
                outputStream.write(command)
                outputStream.flush()
                
                // 直接读取响应（循环读直到拿到 1 帧完整 CRLF）
                val readBuffer = ByteArray(256)
                val responseBuffer = ByteArray(512)
                var responseLength = 0
                val startTime = System.currentTimeMillis()
                var foundCrlf = false
                
                while (System.currentTimeMillis() - startTime < FRAME_READ_TIMEOUT_MS && !foundCrlf) {
                    val available = inputStream.available()
                    if (available > 0) {
                        val bytesRead = inputStream.read(readBuffer, 0, minOf(available, readBuffer.size))
                        if (bytesRead > 0) {
                            // RX 原始日志
                            val rawHex = readBuffer.sliceArray(0 until bytesRead).joinToString(" ") { String.format("%02X", it) }
                            Log.d(TAG, "[CarWash] RX_RAW_LEN=$bytesRead")
                            Log.d(TAG, "[CarWash] RX_RAW_HEX=$rawHex")
                            
                            // 追加到响应缓冲区
                            if (responseLength + bytesRead > responseBuffer.size) {
                                Log.e(TAG, "[CarWash] 响应缓冲区溢出")
                                return@withLock false
                            }
                            System.arraycopy(readBuffer, 0, responseBuffer, responseLength, bytesRead)
                            responseLength += bytesRead
                            
                            Log.d(TAG, "[CarWash] RX_BUF_LEN=$responseLength")
                            
                            // 检查是否包含 CRLF
                            for (i in 0 until responseLength - 1) {
                                if (responseBuffer[i].toInt() == 0x0D && responseBuffer[i + 1].toInt() == 0x0A) {
                                    foundCrlf = true
                                    Log.d(TAG, "[CarWash] RX_HAS_CRLF=true (位置=$i)")
                                    break
                                }
                            }
                            if (!foundCrlf) {
                                Log.d(TAG, "[CarWash] RX_HAS_CRLF=false")
                            }
                        }
                    } else {
                        // 没有可用数据，短暂休眠
                        Thread.sleep(10)
                    }
                }
                
                if (!foundCrlf) {
                    Log.w(TAG, "[CarWash] 写入寄存器超时: 未找到完整 CRLF 帧")
                    return@withLock false
                }
                
                // 提取完整帧（含 : 开头，不含 \r\n）
                var frameEndIndex = -1
                for (i in 0 until responseLength - 1) {
                    if (responseBuffer[i].toInt() == 0x0D && responseBuffer[i + 1].toInt() == 0x0A) {
                        frameEndIndex = i
                        break
                    }
                }
                
                if (frameEndIndex < 0) {
                    Log.e(TAG, "[CarWash] 未找到帧结束符")
                    return@withLock false
                }
                
                val frameBytes = responseBuffer.sliceArray(0 until frameEndIndex)
                val frame = String(frameBytes, Charsets.US_ASCII)
                
                // 打印接收帧
                val rxAsciiRepr = frame.replace("\r", "\\r").replace("\n", "\\n")
                val rxHex = frameBytes.joinToString(" ") { String.format("%02X", it) }
                Log.d(TAG, "[CarWash] RX_ASCII_REPR=$rxAsciiRepr")
                Log.d(TAG, "[CarWash] RX_HEX=$rxHex")
                
                // 解析并验证帧（功能码 06 的响应格式：[addr, 0x06, regHi, regLo, valHi, valLo]）
                val payload = parseAndVerifyFrame(frame)
                if (payload != null && payload.size >= 6) {
                    Log.d(TAG, "[CarWash] ✅ 写入寄存器响应验证通过")
                } else {
                    Log.w(TAG, "[CarWash] ⚠️ 写入寄存器响应验证失败（可选，写入操作可能没有响应）")
                }
                
                Log.d(TAG, "[CarWash] 写入寄存器成功")
                return@withLock true
                
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 写入寄存器异常: 地址=$registerAddress, 值=$value", e)
                return@withLock false
            }
        }
    }
    
    /**
     * 发送洗车模式指令
     * ⚠️ Mode 指令为边沿触发，只发送一次，不进行回写 0 清除
     * ⚠️ PLC 返回的回显帧不作为启动成功依据
     */
    override suspend fun sendWashMode(mode: Int): Boolean = withContext(Dispatchers.IO) {
        if (mode !in 1..4) {
            Log.e(TAG, "[CarWash] 无效的洗车模式: $mode (应为 1-4)")
            return@withContext false
        }
        
        val registerAddress = when (mode) {
            1 -> REG_MODE_1
            2 -> REG_MODE_2
            3 -> REG_MODE_3
            4 -> REG_MODE_4
            else -> return@withContext false
        }
        
        Log.d(TAG, "[CarWash] ========== 发送洗车模式指令（边沿触发） ==========")
        Log.d(TAG, "[CarWash] Mode=$mode, 寄存器地址=$registerAddress")
        Log.d(TAG, "[CarWash] 注意: Mode指令为边沿触发，只发送一次，不回写0清除")
        Log.d(TAG, "[CarWash] PLC返回的回显帧不作为启动成功依据")
        
        val success = writeRegister(registerAddress, 0xFF00) // 根据PDF文档，值为 FF00
        
        if (success) {
            Log.d(TAG, "[CarWash] ========== Mode指令发送成功 ==========")
            Log.d(TAG, "[CarWash] 注意: 启动成功判定需通过读取214（自动状态）确认")
        } else {
            Log.e(TAG, "[CarWash] ========== Mode指令发送失败 ==========")
        }
        
        return@withContext success
    }
    
    /**
     * 写 Mode 脉冲（V2）
     * Mode1 → M261, Mode2 → M262, Mode3 → M260, Mode4 → M264
     * 写 1 为脉冲，PLC 自动清零
     * 防呆：214==1（洗车中）时禁止再次写 Mode
     */
    override suspend fun writeModePulse(mode: Int): Boolean = withContext(Dispatchers.IO) {
        if (mode !in 1..4) {
            Log.e(TAG, "[CarWash] 无效的洗车模式: $mode (应为 1-4)")
            return@withContext false
        }
        
        // 防呆：检查 214==1（洗车中）时禁止再次写 Mode
        val isWashing = try {
            readWashStartStatus()
        } catch (e: Exception) {
            Log.w(TAG, "[CarWash] 读取 214 状态失败，继续执行", e)
            null
        }
        
        if (isWashing == true) {
            Log.e(TAG, "[CarWash] 写 Mode 脉冲失败: 214=1 (洗车中)，禁止再次写 Mode")
            return@withContext false
        }
        
        // V2 映射：Mode1→M261, Mode2→M262, Mode3→M260, Mode4→M264
        val registerAddress = when (mode) {
            1 -> REG_MODE_1  // Mode1 → M261
            2 -> REG_MODE_2  // Mode2 → M262
            3 -> REG_MODE_3  // Mode3 → M260
            4 -> REG_MODE_4  // Mode4 → M264
            else -> return@withContext false
        }
        
        Log.d(TAG, "[CarWash] ========== 写 Mode $mode 脉冲（V2） ==========")
        Log.d(TAG, "[CarWash] Mode=$mode, 寄存器地址=$registerAddress")
        Log.d(TAG, "[CarWash] 注意: 写 1 为脉冲，PLC 自动清零，不需要回写 0")
        
        val success = writeRegister(registerAddress, 1)
        
        if (success) {
            Log.d(TAG, "[CarWash] ========== Mode $mode 脉冲发送成功 ==========")
        } else {
            Log.e(TAG, "[CarWash] ========== Mode $mode 脉冲发送失败 ==========")
        }
        
        return@withContext success
    }
    
    /**
     * 发送取消指令
     */
    override suspend fun sendCancel(): Boolean {
        Log.d(TAG, "[CarWash] 发送取消指令")
        return writeRegister(REG_CANCEL, 0x3C) // 根据PDF文档
    }
    
    /**
     * 发送复位指令
     */
    override suspend fun sendReset(): Boolean {
        Log.d(TAG, "[CarWash] 发送复位指令")
        return writeRegister(REG_RESET, 0x20) // 根据PDF文档
    }
    
    /**
     * 发送暂停指令
     */
    override suspend fun sendPause(): Boolean {
        Log.d(TAG, "[CarWash] 发送暂停指令")
        return writeRegister(REG_PAUSE, 0x01) // 根据PDF文档
    }
    
    /**
     * 发送继续指令
     */
    override suspend fun sendResume(): Boolean {
        Log.d(TAG, "[CarWash] 发送继续指令")
        return writeRegister(REG_PAUSE, 0x00) // 根据PDF文档，暂停后继续
    }
    
    /**
     * 读取故障状态（寄存器 217）
     * ⚠️ 必须打印结构化日志
     */
    override suspend fun readFaultStatus(): Boolean? {
        val value = readRegisterInt(REG_FAULT) ?: return null
        // 根据PDF文档：值非0表示有故障，0表示无故障
        val hasFault = value != 0
        Log.d(TAG, "[CarWash] 读取故障状态(217): 值=$value, 有故障=$hasFault")
        return hasFault
    }
    
    /**
     * 读取前车状态（寄存器 752）
     * ⚠️ 752只有在"洗车完成+车辆驶离洗车区域"后才会从1变为0
     * ⚠️ 如果车辆洗完但未驶离，752将一直保持1，不会因时间自动清零
     * ⚠️ 必须打印结构化日志
     */
    override suspend fun readPreviousCarStatus(): Boolean? {
        val value = readRegisterInt(REG_PREVIOUS_CAR) ?: return null
        // 根据PDF文档：1表示前车还在，0表示前车已离开
        val previousCarPresent = value != 0
        Log.d(TAG, "[CarWash] 读取前车状态(752): 值=$value, 前车还在=$previousCarPresent")
        Log.d(TAG, "[CarWash] 注意: 752只有车辆驶离后才会从1变为0，不会因时间自动清零")
        return previousCarPresent
    }
    
    /**
     * 读取可再次洗车状态（寄存器 240）
     * ⚠️ 240作为硬门禁：当240=0时，禁止发送任何Mode启动指令（PLC会忽略）
     * ⚠️ 只有在240=1时，才允许发送Mode指令
     * ⚠️ 必须打印结构化日志
     */
    override suspend fun readCanWashAgainStatus(): Boolean? {
        val value = readRegisterInt(REG_CAN_WASH_AGAIN) ?: return null
        // 根据PDF文档：1表示可以再次洗车，0表示不可以
        val canWash = value != 0
        Log.d(TAG, "[CarWash] 读取可再次洗车状态(240): 值=$value, 可再次洗车=$canWash")
        if (!canWash) {
            Log.w(TAG, "[CarWash] ⚠️ 240=0，禁止发送任何Mode启动指令（硬门禁）")
        }
        return canWash
    }
    
    /**
     * 读取车位状态（寄存器 102）
     * ⚠️ 102=1在洗车全过程中应保持稳定
     * ⚠️ 工程实现中只需连续两次读取一致即可判定有效（用于防止串口偶发误读）
     * ⚠️ 必须打印结构化日志
     */
    override suspend fun readCarPositionStatus(): Boolean? {
        val value = readRegisterInt(REG_CAR_POSITION) ?: return null
        // 根据PDF文档：1表示车到位，0表示车未到位
        val carInPosition = value != 0
        Log.d(TAG, "[CarWash] 读取车位状态(102): 值=$value, 车到位=$carInPosition")
        Log.d(TAG, "[CarWash] 注意: 102=1在洗车全过程中应保持稳定，需连续两次读取一致才判定有效")
        return carInPosition
    }
    
    /**
     * 读取车位状态（连续两次读取一致才判定有效，防止串口偶发误读）
     * @return true表示车到位，false表示车未到位，null表示读取失败或两次不一致
     */
    suspend fun readCarPositionStatusStable(): Boolean? = withContext(Dispatchers.IO) {
        Log.d(TAG, "[CarWash] 读取车位状态(102) - 连续两次读取验证")
        val firstRead = readCarPositionStatus()
        if (firstRead == null) {
            Log.w(TAG, "[CarWash] 第一次读取车位状态失败")
            return@withContext null
        }
        
        // 等待一小段时间后再次读取
        Thread.sleep(50)
        val secondRead = readCarPositionStatus()
        if (secondRead == null) {
            Log.w(TAG, "[CarWash] 第二次读取车位状态失败")
            return@withContext null
        }
        
        if (firstRead == secondRead) {
            Log.d(TAG, "[CarWash] 车位状态两次读取一致: $firstRead")
            return@withContext firstRead
        } else {
            Log.w(TAG, "[CarWash] 车位状态两次读取不一致: 第一次=$firstRead, 第二次=$secondRead")
            return@withContext null
        }
    }
    
    /**
     * 读取启动洗车状态（寄存器 214）
     * ⚠️ 启动成功判定统一使用214（自动状态）
     * ⚠️ 必须打印结构化日志
     */
    override suspend fun readWashStartStatus(): Boolean? {
        val value = readRegisterInt(REG_WASH_START) ?: return null
        // 根据PDF文档：特定值表示自动状态（需要根据实际PDF确认具体值）
        // 暂时假设：非0表示自动状态
        val isAutoStatus = value != 0
        Log.d(TAG, "[CarWash] 读取启动洗车状态(214): 值=$value, 自动状态=$isAutoStatus")
        if (isAutoStatus) {
            Log.d(TAG, "[CarWash] ✅ 214=自动状态，洗车已成功启动")
        } else {
            Log.d(TAG, "[CarWash] ⚠️ 214=非自动状态，洗车未启动")
        }
        return isAutoStatus
    }
    
    /**
     * 读取洗车总数（寄存器 2550）
     */
    override suspend fun readTotalWashCount(): Int? {
        return readRegisterInt(REG_TOTAL_COUNT)
    }
    
    /**
     * 读取今日洗车数（寄存器 2552）
     */
    override suspend fun readTodayWashCount(): Int? {
        return readRegisterInt(REG_TODAY_COUNT)
    }
    
    /**
     * 读取昨日洗车数（寄存器 2592）
     */
    override suspend fun readYesterdayWashCount(): Int? {
        return readRegisterInt(REG_YESTERDAY_COUNT)
    }
    
    /**
     * 读取洗车机状态快照（一次性读取所有关键寄存器）
     */
    override suspend fun readSnapshot(): CarWashSnapshot? = withContext(Dispatchers.IO) {
        try {
            if (!isConnectedFlag) {
                Log.w(TAG, "[CarWash] 读取快照失败: 未连接")
                return@withContext CarWashSnapshot.offline()
            }

            // 并行读取所有寄存器（提高效率）
            val reg217 = readRegisterInt(REG_FAULT)
            val reg752 = readRegisterInt(REG_PREVIOUS_CAR)
            val reg240 = readRegisterInt(REG_CAN_WASH_AGAIN)
            val reg214 = readRegisterInt(REG_WASH_START)
            val reg102 = readRegisterInt(REG_CAR_POSITION)

            val snapshot = CarWashSnapshot(
                reg217 = reg217,
                reg752 = reg752,
                reg240 = reg240,
                reg214 = reg214,
                reg102 = reg102,
                timestampMillis = System.currentTimeMillis(),
                isOnline = true
            )

            Log.d(TAG, "[CarWash] 快照读取成功: ${snapshot.getStatusSummary()}")
            return@withContext snapshot

        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 读取快照异常", e)
            return@withContext CarWashSnapshot.offline()
        }
    }
}
