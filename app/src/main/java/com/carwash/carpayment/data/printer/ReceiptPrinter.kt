package com.carwash.carpayment.data.printer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ReceiptPrinter"

/**
 * 打印机服务
 * 使用反射封装 CSNPrinterSDK 的调用（USB 模式）
 */
class ReceiptPrinter(private val context: Context) {
    
    private var pos: Any? = null
    private var usbIO: Any? = null
    private var isConnected = false
    private var currentUsbDevice: UsbDevice? = null
    private var openOk = false  // open 是否成功
    private var lastError: String? = null  // 最后一次错误
    
    init {
        try {
            // 使用反射加载 CSNPrinterSDK 类
            val posClass = Class.forName("com.csnprintersdk.csnio.CSNPOS")
            val usbIOClass = Class.forName("com.csnprintersdk.csnio.CSNUSBPrinting")
            val csnioBaseClass = Class.forName("com.csnprintersdk.csnio.csnbase.CSNIO")
            
            pos = posClass.getConstructor().newInstance()
            usbIO = usbIOClass.getConstructor().newInstance()
            
            // 调用 pos.Set(usbIO as CSNIO)
            // Set 方法的参数类型是 CSNIO（基类），而 CSNUSBPrinting 继承自 CSNIO
            val setMethod = posClass.getMethod("Set", csnioBaseClass)
            setMethod.invoke(pos, usbIO)
            
            Log.d(TAG, "CSNPrinterSDK 初始化成功（USB 模式）")
        } catch (t: Throwable) {
            // 捕获所有异常（包括 Error 类，如 LinkageError、NoClassDefFoundError）
            Log.e(TAG, "CSNPrinterSDK 初始化失败: ${t::class.java.name}, message=${t.message}", t)
            pos = null
            usbIO = null
        }
    }
    
    /**
     * Dump USB 设备的接口和端点信息（用于诊断）
     */
    private fun dumpUsbDevice(device: UsbDevice) {
        try {
            Log.d(TAG, "=== USB 设备详细信息 ===")
            Log.d(TAG, "设备名: ${device.deviceName}")
            Log.d(TAG, "VID: ${device.vendorId}, PID: ${device.productId}")
            Log.d(TAG, "设备类: ${device.deviceClass}, 子类: ${device.deviceSubclass}, 协议: ${device.deviceProtocol}")
            Log.d(TAG, "接口数量: ${device.interfaceCount}")
            
            var hasPrinterInterface = false
            var hasBulkOut = false
            
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                Log.d(TAG, "  Interface[$i]: class=${usbInterface.interfaceClass}, " +
                        "subclass=${usbInterface.interfaceSubclass}, " +
                        "protocol=${usbInterface.interfaceProtocol}, " +
                        "endpointCount=${usbInterface.endpointCount}")
                
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    hasPrinterInterface = true
                }
                
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    val direction = if (endpoint.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                    val type = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        else -> "UNKNOWN"
                    }
                    Log.d(TAG, "    Endpoint[$j]: direction=$direction, type=$type, " +
                            "address=${endpoint.address}, maxPacketSize=${endpoint.maxPacketSize}")
                    
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        hasBulkOut = true
                    }
                }
            }
            
            Log.d(TAG, "=== 设备摘要 ===")
            Log.d(TAG, "hasPrinterInterface: $hasPrinterInterface")
            Log.d(TAG, "hasBulkOut: $hasBulkOut")
            Log.d(TAG, "==================")
        } catch (t: Throwable) {
            Log.e(TAG, "dumpUsbDevice 失败: ${t::class.java.name}, message=${t.message}", t)
        }
    }
    
    /**
     * 连接打印机（USB 模式）
     * @param usbDevice USB 设备
     * @return 是否连接成功
     */
    suspend fun connect(usbDevice: UsbDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            if (usbIO == null || pos == null) {
                Log.e(TAG, "CSNPrinterSDK 未初始化")
                return@withContext false
            }
            
            if (isConnected) {
                Log.w(TAG, "打印机已连接，先断开")
                disconnect()
            }
            
            Log.d(TAG, "正在连接打印机（USB）: deviceName=${usbDevice.deviceName}, vendorId=${usbDevice.vendorId}, productId=${usbDevice.productId}")
            
            // 在连接前 dump 设备信息
            dumpUsbDevice(usbDevice)
            
            // 验证 VID/PID 是否匹配目标设备
            val TARGET_VID = 0x0FE6
            val TARGET_PID = 0x811E
            if (usbDevice.vendorId != TARGET_VID || usbDevice.productId != TARGET_PID) {
                openOk = false
                lastError = "设备 VID/PID 不匹配：期望 VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}，实际 VID=0x${usbDevice.vendorId.toString(16).uppercase()}, PID=0x${usbDevice.productId.toString(16).uppercase()}"
                Log.e(TAG, "打印机连接失败（USB）: ${usbDevice.deviceName} - $lastError")
                return@withContext false
            }
            
            // 放宽限制：不要求 Printer interface (class=7)，只要存在 Bulk OUT endpoint 即可
            var hasBulkOut = false
            var selectedInterface: UsbInterface? = null
            var interfaceClass: Int? = null
            
            for (i in 0 until usbDevice.interfaceCount) {
                val usbInterface = usbDevice.getInterface(i)
                interfaceClass = usbInterface.interfaceClass
                
                // 检查是否有 Bulk OUT endpoint（写通道）
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        hasBulkOut = true
                        selectedInterface = usbInterface
                        Log.d(TAG, "检测到 Bulk OUT endpoint: interfaceClass=${usbInterface.interfaceClass} (class=${usbInterface.interfaceClass}), endpointAddress=${endpoint.address}")
                        break
                    }
                }
                if (hasBulkOut) break
            }
            
            // 只要存在 Bulk OUT endpoint 就允许连接（不要求 Printer interface class=7）
            if (!hasBulkOut) {
                openOk = false
                lastError = "设备没有 Bulk OUT endpoint（写通道），无法打印"
                Log.e(TAG, "打印机连接失败（USB）: ${usbDevice.deviceName}, VID=0x${usbDevice.vendorId.toString(16).uppercase()}, PID=0x${usbDevice.productId.toString(16).uppercase()} - $lastError")
                return@withContext false
            }
            
            // 记录匹配信息
            val matchMode = if (interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                "Printer Class (class=7)"
            } else {
                "Vendor-Specific (class=${interfaceClass ?: "unknown"}) with Bulk OUT"
            }
            Log.d(TAG, "打印机设备匹配: VID=0x${usbDevice.vendorId.toString(16).uppercase()}, PID=0x${usbDevice.productId.toString(16).uppercase()}, matchMode=$matchMode, selectedInterface=${selectedInterface?.interfaceClass}")
            
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "无法获取 UsbManager")
                return@withContext false
            }
            
            // 调用 CSNUSBPrinting.Open(usbManager, usbDevice, context)
            val usbIOClass = usbIO!!::class.java
            val openMethod = usbIOClass.getMethod("Open", 
                UsbManager::class.java, 
                UsbDevice::class.java, 
                Context::class.java)
            val opened = openMethod.invoke(usbIO, usbManager, usbDevice, context) as? Boolean ?: false
            
            if (opened) {
                isConnected = true
                currentUsbDevice = usbDevice
                openOk = true
                lastError = null
                Log.d(TAG, "打印机连接成功（USB）: ${usbDevice.deviceName}, VID=0x${usbDevice.vendorId.toString(16).uppercase()}, PID=0x${usbDevice.productId.toString(16).uppercase()}, matched=true, matchMode=$matchMode, openOk=true")
            } else {
                openOk = false
                lastError = "SDK Open() 返回 false（可能是设备没有 Bulk OUT endpoint 或 SDK 无法识别，或权限不足）"
                Log.e(TAG, "打印机连接失败（USB）: ${usbDevice.deviceName}, VID=0x${usbDevice.vendorId.toString(16).uppercase()}, PID=0x${usbDevice.productId.toString(16).uppercase()}, matched=${usbDevice.vendorId == 0x0FE6 && usbDevice.productId == 0x811E}, matchMode=$matchMode, openOk=false, lastError=$lastError")
            }
            
            opened
        } catch (t: Throwable) {
            openOk = false
            lastError = "${t::class.java.simpleName}: ${t.message}"
            Log.e(TAG, "连接打印机异常（USB）: ${t::class.java.name}, message=${t.message}", t)
            false
        }
    }
    
    /**
     * 断开打印机连接
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (isConnected && usbIO != null) {
                val usbIOClass = usbIO!!::class.java
                val closeMethod = usbIOClass.getMethod("Close")
                closeMethod.invoke(usbIO)
                isConnected = false
                currentUsbDevice = null
                openOk = false
                lastError = null
                Log.d(TAG, "打印机已断开（USB）")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "断开打印机异常: ${t::class.java.name}, message=${t.message}", t)
            isConnected = false
            openOk = false
        }
    }
    
    /**
     * 检查打印机状态
     * @return 状态码：0=正常，负数=错误
     */
    /**
     * 检查打印机接口是否可用
     * @return true 如果打印机接口可用，false 如果不可用
     */
    fun hasPrinterInterface(): Boolean {
        return try {
            if (pos == null || usbIO == null) {
                Log.w(TAG, "hasPrinterInterface: CSNPrinterSDK 未初始化")
                return false
            }
            
            if (currentUsbDevice == null) {
                Log.w(TAG, "hasPrinterInterface: 未连接 USB 设备")
                return false
            }
            
            // 检查 USB 设备是否有打印机接口
            var hasPrinterInterface = false
            for (i in 0 until currentUsbDevice!!.interfaceCount) {
                val usbInterface = currentUsbDevice!!.getInterface(i)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    hasPrinterInterface = true
                    break
                }
            }
            
            if (!hasPrinterInterface) {
                Log.w(TAG, "hasPrinterInterface: USB 设备没有打印机接口（USB_CLASS_PRINTER）")
            }
            
            hasPrinterInterface
        } catch (e: Exception) {
            Log.e(TAG, "hasPrinterInterface 异常", e)
            false
        }
    }
    
    suspend fun checkStatus(): Int = withContext(Dispatchers.IO) {
        if (!isConnected || pos == null) {
            Log.w(TAG, "打印机未连接")
            return@withContext -4  // 打印机脱机
        }
        
        try {
            val status = ByteArray(1)
            val posClass = pos!!::class.java
            
            // 查询状态（RTQueryStatus: status, timeout, retry, type）
            val queryMethod = posClass.getMethod("POS_RTQueryStatus", ByteArray::class.java, 
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            if (queryMethod.invoke(pos, status, 3, 1000, 2) as? Boolean == true) {
                // 检查切刀状态
                if ((status[0].toInt() and 0x08) == 0x08) {
                    Log.w(TAG, "切刀异常")
                    return@withContext -2
                }
                
                // 检查打印头温度
                if ((status[0].toInt() and 0x40) == 0x40) {
                    Log.w(TAG, "打印头过热")
                    return@withContext -3
                }
                
                // 再次查询状态
                if (queryMethod.invoke(pos, status, 2, 1000, 2) as? Boolean == true) {
                    // 检查合盖状态
                    if ((status[0].toInt() and 0x04) == 0x04) {
                        Log.w(TAG, "上盖打开")
                        return@withContext -6
                    }
                    
                    // 检查缺纸状态
                    if ((status[0].toInt() and 0x20) == 0x20) {
                        Log.w(TAG, "打印机缺纸")
                        return@withContext -5
                    }
                    
                    // 状态正常
                    return@withContext 0
                } else {
                    Log.w(TAG, "状态查询失败（第二次）")
                    return@withContext -7
                }
            } else {
                Log.w(TAG, "状态查询失败（第一次）")
                return@withContext -8
            }
        } catch (t: Throwable) {
            Log.e(TAG, "检查打印机状态异常: ${t::class.java.name}, message=${t.message}", t)
            return@withContext -8
        }
    }
    
    /**
     * 打印小票
     * @param receiptData 小票数据
     * @return 是否打印成功
     */
    suspend fun print(receiptData: ReceiptData): Boolean = withContext(Dispatchers.IO) {
        // 打印关键日志：是否命中 0FE6:811E
        val currentDevice = currentUsbDevice
        val matched = currentDevice != null && currentDevice.vendorId == 0x0FE6 && currentDevice.productId == 0x811E
        val hasPermission = currentDevice?.let { device ->
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManager?.hasPermission(device) ?: false
        } ?: false
        val hasBulkOut = currentDevice?.let { device ->
            var found = false
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            found
        } ?: false
        
        Log.d(TAG, "========== 开始打印 ==========")
        Log.d(TAG, "selectedPrinter=${currentDevice?.deviceName ?: "null"}, VID=${currentDevice?.vendorId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, PID=${currentDevice?.productId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, matched=$matched, hasPermission=$hasPermission, openOk=$openOk, bulkOut=$hasBulkOut")
        Log.d(TAG, "invoiceId=${receiptData.invoiceId}")
        Log.d(TAG, "==============================")
        
        if (!isConnected || pos == null) {
            Log.e(TAG, "打印机未连接，无法打印")
            return@withContext false
        }
        
        try {
            // 检查状态
            val status = checkStatus()
            if (status != 0) {
                Log.e(TAG, "打印机状态异常: $status, matched=$matched, openOk=$openOk")
                return@withContext false
            }
            
            // 格式化小票内容
            val lines = ReceiptFormatter.format(receiptData)
            
            val posClass = pos!!::class.java
            
            // 重置打印机
            val resetMethod = posClass.getMethod("POS_Reset")
            resetMethod.invoke(pos)
            
            val feedLineMethod = posClass.getMethod("POS_FeedLine")
            feedLineMethod.invoke(pos)
            
            // 打印每一行（记录实际写入的字节数）
            val textOutMethod = posClass.getMethod("POS_TextOut", String::class.java, 
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            var totalBytesWritten = 0
            
            lines.forEach { line ->
                val textToPrint = if (line.isEmpty()) {
                    "\r\n"
                } else {
                    "$line\r\n"
                }
                val bytesToWrite = textToPrint.toByteArray(Charsets.UTF_8).size
                
                try {
                    if (line.isEmpty()) {
                        // 空行
                        feedLineMethod.invoke(pos)
                        totalBytesWritten += 2 // \r\n
                    } else if (line == receiptData.headerTitle) {
                        // Header Title：左对齐，大字体，粗体
                        // POS_TextOut(text, font, size, bold, underline, reverse, align)
                        textOutMethod.invoke(pos, textToPrint, 3, 24, 1, 1, 0, 0)
                        totalBytesWritten += bytesToWrite
                    } else if (line.startsWith("Thank you")) {
                        // Thank you：右对齐
                        textOutMethod.invoke(pos, textToPrint, 3, 0, 0, 0, 0, 2)
                        totalBytesWritten += bytesToWrite
                    } else if (line.contains("Invoice:") || line.contains("Date:") || 
                              line.contains("Payment:") || line.contains("Program:") || 
                              line.contains("Amount:")) {
                        // 关键信息：左对齐，正常字体
                        textOutMethod.invoke(pos, textToPrint, 3, 0, 0, 0, 0, 0)
                        totalBytesWritten += bytesToWrite
                    } else if (line == "--------------------------------") {
                        // 分隔线
                        textOutMethod.invoke(pos, textToPrint, 3, 0, 0, 0, 0, 0)
                        totalBytesWritten += bytesToWrite
                    } else {
                        // 其他内容：左对齐
                        textOutMethod.invoke(pos, textToPrint, 3, 0, 0, 0, 0, 0)
                        totalBytesWritten += bytesToWrite
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "打印行异常: line=$line, bytesToWrite=$bytesToWrite", e)
                    throw e
                }
            }
            
            // 走纸
            feedLineMethod.invoke(pos)
            feedLineMethod.invoke(pos)
            totalBytesWritten += 4 // 2 * \r\n
            
            // 切纸（半切）
            val halfCutMethod = posClass.getMethod("POS_HalfCutPaper")
            halfCutMethod.invoke(pos)
            
            // 验证打印成功：至少记录实际 write 的字节数；若写入失败/为 0/异常，则判失败
            if (totalBytesWritten == 0) {
                Log.e(TAG, "========== 打印失败 ==========")
                Log.e(TAG, "原因: 实际写入字节数为 0")
                Log.e(TAG, "selectedPrinter=${currentDevice?.deviceName ?: "null"}, VID=${currentDevice?.vendorId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, PID=${currentDevice?.productId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, matched=$matched, hasPermission=$hasPermission, openOk=$openOk, bulkOut=$hasBulkOut")
                Log.e(TAG, "invoiceId=${receiptData.invoiceId}")
                Log.e(TAG, "==============================")
                return@withContext false
            }
            
            Log.d(TAG, "========== 打印成功 ==========")
            Log.d(TAG, "selectedPrinter=${currentDevice?.deviceName ?: "null"}, VID=${currentDevice?.vendorId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, PID=${currentDevice?.productId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, matched=$matched, hasPermission=$hasPermission, openOk=$openOk, bulkOut=$hasBulkOut")
            Log.d(TAG, "invoiceId=${receiptData.invoiceId}, totalBytesWritten=$totalBytesWritten")
            Log.d(TAG, "==============================")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "========== 打印异常 ==========")
            Log.e(TAG, "异常类型: ${t::class.java.name}, message=${t.message}")
            Log.e(TAG, "selectedPrinter=${currentDevice?.deviceName ?: "null"}, VID=${currentDevice?.vendorId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, PID=${currentDevice?.productId?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}, matched=$matched, hasPermission=$hasPermission, openOk=$openOk, bulkOut=$hasBulkOut")
            Log.e(TAG, "invoiceId=${receiptData.invoiceId}")
            Log.e(TAG, "==============================", t)
            false
        }
    }
    
    /**
     * 测试打印（打印样例小票）
     * @param settings 小票配置
     * @return 是否打印成功
     */
    suspend fun testPrint(settings: ReceiptSettings): Boolean = withContext(Dispatchers.IO) {
        val testReceiptData = ReceiptData(
            invoiceId = InvoiceIdGenerator.generate(),
            date = java.util.Date(),
            paymentLabel = "刷卡支付",  // 测试用支付方式标签
            programLabel = "标准版",    // 测试用程序标签
            amountCents = 500,          // 5.00€（基础套餐）
            headerTitle = settings.headerTitle,
            storeAddress = settings.storeAddress
        )
        
        return@withContext print(testReceiptData)
    }
    
    /**
     * 获取连接状态（严格检测：必须同时满足多项条件）
     * 1. 能枚举到目标 USB 打印机设备
     * 2. 已获取 USB 权限
     * 3. open 成功且内部保存的 openOk 标记为 true
     * 4. checkStatus() 能正常返回（不是异常）
     */
    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        // 如果内部标记未连接，直接返回 false
        if (!isConnected || currentUsbDevice == null || !openOk) {
            return@withContext false
        }
        
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.w(TAG, "UsbManager 不可用")
                isConnected = false
                return@withContext false
            }
            
            // 1. 检查设备是否仍在设备列表中
            val deviceList = usbManager.deviceList
            val deviceStillExists = deviceList.values.any { 
                it.vendorId == currentUsbDevice!!.vendorId && 
                it.productId == currentUsbDevice!!.productId 
            }
            if (!deviceStillExists) {
                Log.w(TAG, "打印机设备已从 USB 设备列表中移除")
                isConnected = false
                currentUsbDevice = null
                openOk = false
                return@withContext false
            }
            
            // 2. 检查权限
            if (!usbManager.hasPermission(currentUsbDevice!!)) {
                Log.w(TAG, "打印机 USB 权限已丢失")
                isConnected = false
                openOk = false
                return@withContext false
            }
            
            // 3. 检查 openOk 标记（已在开头检查）
            
            // 4. 尝试 checkStatus() 验证连接是否真的可用
            try {
                val status = checkStatus()
                if (status < 0 && status != -4) {
                    // 状态码为负数且不是"脱机"（-4），说明连接有问题
                    Log.w(TAG, "打印机 checkStatus() 返回异常状态码: $status")
                    // 不立即断开，但返回 false
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.w(TAG, "打印机 checkStatus() 异常: ${e.message}")
                // checkStatus 异常，认为连接不可用
                return@withContext false
            }
            
            // 所有检查通过
            return@withContext true
            
        } catch (t: Throwable) {
            Log.w(TAG, "检查打印机连接状态异常: ${t.message}")
            isConnected = false
            openOk = false
            return@withContext false
        }
    }
    
    /**
     * 获取连接诊断信息（用于日志，不用于 UI）
     */
    suspend fun getConnectionDiagnostics(): String = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                diagnostics.add("UsbManager=null")
            } else {
                val deviceList = usbManager.deviceList
                diagnostics.add("usbDeviceCount=${deviceList.size}")
                
                if (deviceList.isEmpty()) {
                    diagnostics.add("matchedPrinterDevice=null")
                } else {
                    // 查找匹配的打印机设备
                    var matchedDevice: UsbDevice? = null
                    var hasPrinterInterface = false
                    var hasBulkOut = false
                    
                    // 仅匹配指定的打印机设备：VID=0x0FE6、PID=0x811E
                    val TARGET_VID = 0x0FE6
                    val TARGET_PID = 0x811E
                    
                    deviceList.values.forEach { device ->
                        // 检查是否是目标设备
                        if (device.vendorId == TARGET_VID && device.productId == TARGET_PID) {
                            var deviceHasBulkOut = false
                            
                            for (i in 0 until device.interfaceCount) {
                                val usbInterface = device.getInterface(i)
                                for (j in 0 until usbInterface.endpointCount) {
                                    val endpoint = usbInterface.getEndpoint(j)
                                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                        deviceHasBulkOut = true
                                        break
                                    }
                                }
                                if (deviceHasBulkOut) break
                            }
                            
                            if (deviceHasBulkOut) {
                                matchedDevice = device
                                hasBulkOut = true
                            }
                        }
                    }
                    
                    if (matchedDevice != null) {
                        val hasPermission = usbManager.hasPermission(matchedDevice)
                        val matched = matchedDevice.vendorId == TARGET_VID && matchedDevice.productId == TARGET_PID
                        diagnostics.add("matchedPrinterDevice=${matchedDevice.deviceName} (VID=0x${matchedDevice.vendorId.toString(16).uppercase()}, PID=0x${matchedDevice.productId.toString(16).uppercase()})")
                        diagnostics.add("matched=$matched")
                        diagnostics.add("hasBulkOut=$hasBulkOut")
                        diagnostics.add("hasPermission=$hasPermission")
                    } else {
                        diagnostics.add("matchedPrinterDevice=null (未找到 VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()} 的设备)")
                    }
                }
                
                diagnostics.add("openOk=$openOk")
                diagnostics.add("mode=USB")
                if (lastError != null) {
                    diagnostics.add("lastError=$lastError")
                }
                
                // 尝试 checkStatus
                try {
                    val status = checkStatus()
                    diagnostics.add("checkStatus=$status")
                } catch (e: Exception) {
                    diagnostics.add("checkStatus=exception: ${e.message}")
                }
            }
        } catch (t: Throwable) {
            diagnostics.add("diagnosticsException: ${t.message}")
        }
        
        return@withContext diagnostics.joinToString(", ")
    }
    
    /**
     * 尝试重新连接（执行一次完整流程）
     * 枚举 -> 识别目标设备 -> 检查/申请权限（如需）-> open
     */
    suspend fun tryReconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "尝试重新连接打印机...")
            
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                lastError = "UsbManager 不可用"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            
            // 枚举 USB 设备
            val deviceList = usbManager.deviceList
            if (deviceList.isEmpty()) {
                lastError = "未检测到 USB 设备"
                Log.w(TAG, lastError!!)
                return@withContext false
            }
            
            // 打印所有 USB 设备信息（用于排查）
            Log.d(TAG, "=== USB 设备列表（共 ${deviceList.size} 个）===")
            deviceList.values.forEach { device ->
                Log.d(TAG, "USB 设备: deviceName=${device.deviceName}, VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
            }
            Log.d(TAG, "=========================================")
            
            // 仅匹配指定的打印机设备：VID=0x0FE6、PID=0x811E
            val TARGET_VID = 0x0FE6
            val TARGET_PID = 0x811E
            
            // 查找目标设备
            val candidateDevices = deviceList.values.filter { device ->
                device.vendorId == TARGET_VID && device.productId == TARGET_PID
            }
            
            if (candidateDevices.isEmpty()) {
                lastError = "未找到目标打印机设备（VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}）"
                Log.e(TAG, lastError!!)
                Log.e(TAG, "可用 USB 设备列表：")
                deviceList.values.forEach { device ->
                    Log.e(TAG, "  - deviceName=${device.deviceName}, VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
                }
                return@withContext false
            }
            
            Log.d(TAG, "找到 ${candidateDevices.size} 台候选打印机设备（VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}）")
            
            // 如果有多台候选设备，依次尝试打开，选择第一个 open 成功且具备 Bulk OUT endpoint 的
            var targetDevice: UsbDevice? = null
            var matchReason = ""
            
            for (device in candidateDevices) {
                // 检查是否有 Bulk OUT endpoint
                var hasBulkOut = false
                for (i in 0 until device.interfaceCount) {
                    val usbInterface = device.getInterface(i)
                    for (j in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(j)
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            hasBulkOut = true
                            break
                        }
                    }
                    if (hasBulkOut) break
                }
                
                if (!hasBulkOut) {
                    Log.w(TAG, "候选设备 ${device.deviceName} 没有 Bulk OUT endpoint，跳过")
                    continue
                }
                
                // 检查权限
                if (!usbManager.hasPermission(device)) {
                    Log.w(TAG, "候选设备 ${device.deviceName} 没有 USB 权限，跳过")
                    continue
                }
                
                // 尝试打开
                val opened = try {
                    val usbIOClass = usbIO!!::class.java
                    val openMethod = usbIOClass.getMethod("Open", 
                        UsbManager::class.java, 
                        UsbDevice::class.java, 
                        Context::class.java)
                    openMethod.invoke(usbIO, usbManager, device, context) as? Boolean ?: false
                } catch (e: Exception) {
                    Log.w(TAG, "尝试打开设备 ${device.deviceName} 失败: ${e.message}")
                    false
                }
                
                if (opened) {
                    // 直接使用已打开的设备，设置连接状态
                    isConnected = true
                    currentUsbDevice = device
                    openOk = true
                    lastError = null
                    targetDevice = device
                    matchReason = "VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}, hasBulkOut=true, openOk=true"
                    Log.d(TAG, "成功匹配并打开打印机设备: ${device.deviceName}, $matchReason")
                    break
                } else {
                    Log.w(TAG, "设备 ${device.deviceName} open 失败，尝试下一台")
                }
            }
            
            if (targetDevice == null) {
                lastError = "未找到可用的目标打印机设备（VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}），或所有候选设备 open 失败"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            
            // 设备已成功打开，直接返回成功
            Log.d(TAG, "重新连接成功: VID=0x${targetDevice.vendorId.toString(16).uppercase()}, PID=0x${targetDevice.productId.toString(16).uppercase()}, matched=true, openOk=$openOk")
            return@withContext true
            
        } catch (t: Throwable) {
            lastError = "${t::class.java.simpleName}: ${t.message}"
            Log.e(TAG, "重新连接异常: $lastError", t)
            return@withContext false
        }
    }
    
    /**
     * 获取当前连接的 USB 设备
     */
    fun getCurrentUsbDevice(): UsbDevice? = currentUsbDevice
}
