package com.carwash.carpayment.data.pos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// USDK SDK 导入（使用反射方式，避免编译时依赖）
// 注意：实际使用时需要确保 JAR 文件已正确添加到 classpath
// import cc.uling.usdk.USDK
// import cc.uling.usdk.board.UBoard
// import cc.uling.usdk.board.mdb.para.HCReplyPara
// import cc.uling.usdk.board.mdb.para.MPReplyPara
// import cc.uling.usdk.board.mdb.para.PMReplyPara
// import cc.uling.usdk.board.mdb.para.PayReplyPara

/**
 * USDK POS 支付服务实现
 * 
 * 基于 USDK SDK v1.0.4 实现 POS 机支付功能
 * 
 * 参考文档：USDK接口说明文档v1.0.4_2024112601.pdf
 */
class UsdkPosPaymentService(
    private val context: Context,
    private val commId: String? = null,  // 串口名，如果为 null 则自动扫描
    private val baudrate: Int = 9600       // 波特率，固定为 9600
) : PosPaymentService {
    
    companion object {
        private const val TAG = "UsdkPosPaymentService"
        private const val REFLECT_TAG = "UsdkReflect"
        private const val PAYMENT_TIMEOUT_MS = 90000L  // 支付超时 90 秒（MDB 支付板固定 90s）
        private const val POLL_INTERVAL_MS = 300L     // 轮询间隔 300ms（200-500ms 范围内）
        
        // 常见的串口设备路径（Android 系统）
        // 注意：优先尝试 /dev/ttyS2（根据实际设备连接情况调整）
        private val COMMON_SERIAL_PORTS = listOf(
            "/dev/ttyS2",  // 优先尝试（实际 POS 设备通常连接在此）
            "/dev/ttyS3",
            "/dev/ttyS1",
            "/dev/ttyS0",  // 最后尝试（避免默认挂载）
            "/dev/ttyUSB0",
            "/dev/ttyUSB1",
            "/dev/ttyUSB2",
            "/dev/ttyUSB3",
            "/dev/ttyACM0",
            "/dev/ttyACM1"
        )
        
        /**
         * 安全读取布尔字段或方法（优先方法，再字段）
         * @param obj 目标对象
         * @param candidates 候选名称列表（方法名或字段名）
         * @param default 默认值（如果都读取失败）
         * @return 读取到的布尔值，失败返回 default
         */
        private fun readAnyBooleanFieldOrMethod(
            obj: Any,
            candidates: List<String>,
            default: Boolean = false
        ): Boolean {
            val clazz = obj.javaClass
            
            // 1) 优先：尝试方法（无参）：isXxx / getXxx
            for (name in candidates) {
                val methodCandidates = listOf("is${cap(name)}", "get${cap(name)}", name)
                for (methodName in methodCandidates) {
                    try {
                        val method = clazz.getMethod(methodName)
                        val result = method.invoke(obj)
                        if (result is Boolean) {
                            Log.d(REFLECT_TAG, "readAnyBooleanFieldOrMethod OK via method: ${clazz.name}#$methodName() -> $result")
                            return result
                        }
                    } catch (_: NoSuchMethodException) {
                        // 继续尝试下一个
                    } catch (e: Exception) {
                        // 其他异常也忽略，继续尝试
                        // 只在 DEBUG 模式下输出详细日志（避免堆栈刷屏）
                        if (android.util.Log.isLoggable(REFLECT_TAG, android.util.Log.DEBUG)) {
                            Log.d(REFLECT_TAG, "readAnyBooleanFieldOrMethod method failed: ${clazz.name}#$methodName()", e)
                        }
                    }
                }
            }
            
            // 2) 尝试字段：先 getDeclaredField（isAccessible=true），再 getField
            for (name in candidates) {
                try {
                    // 先尝试 declared field（private）
                    try {
                        val field = clazz.getDeclaredField(name)
                        field.isAccessible = true
                        val result = field.get(obj)
                        if (result is Boolean) {
                            Log.d(REFLECT_TAG, "readAnyBooleanFieldOrMethod OK via declared field: ${clazz.name}#$name -> $result")
                            return result
                        }
                    } catch (_: NoSuchFieldException) {
                        // 继续尝试 public field
                    }
                    
                    // 再尝试 public field
                    try {
                        val field = clazz.getField(name)
                        val result = field.get(obj)
                        if (result is Boolean) {
                            Log.d(REFLECT_TAG, "readAnyBooleanFieldOrMethod OK via public field: ${clazz.name}#$name -> $result")
                            return result
                        }
                    } catch (_: NoSuchFieldException) {
                        // 继续尝试下一个候选
                    }
                } catch (e: Exception) {
                    // 其他异常忽略，继续尝试
                    // 只在 DEBUG 模式下输出详细日志（避免堆栈刷屏）
                    if (android.util.Log.isLoggable(REFLECT_TAG, android.util.Log.DEBUG)) {
                        Log.d(REFLECT_TAG, "readAnyBooleanFieldOrMethod field failed: ${clazz.name}#$name", e)
                    }
                }
            }
            
            // 全部失败，返回默认值
            return default
        }
        
        /**
         * 安全读取字段（任意类型）
         * @param obj 目标对象
         * @param candidates 候选字段名列表
         * @return 读取到的值，失败返回 null
         */
        private fun readAnyField(
            obj: Any,
            candidates: List<String>
        ): Any? {
            val clazz = obj.javaClass
            
            // 依次尝试字段名：getDeclaredField -> getField
            for (name in candidates) {
                try {
                    // 先尝试 declared field（private）
                    try {
                        val field = clazz.getDeclaredField(name)
                        field.isAccessible = true
                        val result = field.get(obj)
                        Log.d(REFLECT_TAG, "readAnyField OK via declared field: ${clazz.name}#$name -> $result")
                        return result
                    } catch (_: NoSuchFieldException) {
                        // 继续尝试 public field
                    }
                    
                    // 再尝试 public field
                    try {
                        val field = clazz.getField(name)
                        val result = field.get(obj)
                        Log.d(REFLECT_TAG, "readAnyField OK via public field: ${clazz.name}#$name -> $result")
                        return result
                    } catch (_: NoSuchFieldException) {
                        // 继续尝试下一个候选
                    }
                } catch (e: Exception) {
                    // 其他异常忽略，继续尝试
                    // 只在 DEBUG 模式下输出详细日志（避免堆栈刷屏）
                    if (android.util.Log.isLoggable(REFLECT_TAG, android.util.Log.DEBUG)) {
                        Log.d(REFLECT_TAG, "readAnyField failed: ${clazz.name}#$name", e)
                    }
                }
            }
            
            // 全部失败，返回 null
            return null
        }
        
        /**
         * 通用：给对象写入一个"属性值"，兼容：
         * 1) setXxx(value) / setxxx(value)
         * 2) 字段 xxx (public)
         * 3) 字段 xxx (private -> getDeclaredField + isAccessible=true)
         *
         * @param target 目标对象（比如 MPPara / MPReq / Whatever）
         * @param candidateNames 可能的属性名（不带 set 前缀），如 listOf("value","amount","amt","money","price")
         * @param value 要写入的值（Int/Long/Boolean/String/ByteArray 等）
         * @return true=写入成功；false=全部尝试失败
         */
        fun reflectSetProperty(
            target: Any,
            candidateNames: List<String>,
            value: Any?
        ): Boolean {
            val clazz = target.javaClass

            // 1) 优先：尝试 setter：setXxx(...)
            for (name in candidateNames) {
                val setterCandidates = buildSetterNames(name)
                for (setterName in setterCandidates) {
                    val ok = tryInvokeBestSetter(clazz, target, setterName, value)
                    if (ok) {
                        Log.d(REFLECT_TAG, "reflectSetProperty OK via setter: ${clazz.name}#$setterName(${value?.javaClass?.name})")
                        return true
                    }
                }
            }

            // 2) 尝试 public field
            for (name in candidateNames) {
                try {
                    val f = clazz.getField(name)
                    if (trySetField(f, target, value)) {
                        Log.d(REFLECT_TAG, "reflectSetProperty OK via public field: ${clazz.name}#$name")
                        return true
                    }
                } catch (_: NoSuchFieldException) {
                    // ignore
                } catch (e: Exception) {
                    Log.w(REFLECT_TAG, "public field set failed: ${clazz.name}#$name", e)
                }
            }

            // 3) 尝试 declared field（private）
            for (name in candidateNames) {
                try {
                    val f = clazz.getDeclaredField(name)
                    f.isAccessible = true
                    if (trySetField(f, target, value)) {
                        Log.d(REFLECT_TAG, "reflectSetProperty OK via declared field: ${clazz.name}#$name")
                        return true
                    }
                } catch (_: NoSuchFieldException) {
                    // ignore
                } catch (e: Exception) {
                    Log.w(REFLECT_TAG, "declared field set failed: ${clazz.name}#$name", e)
                }
            }

            Log.e(REFLECT_TAG, "reflectSetProperty FAILED: class=${clazz.name}, candidates=$candidateNames, valueType=${value?.javaClass?.name}")
            return false
        }

        /** 读取布尔属性（优先 isXxx/getXxx，再 field） */
        fun reflectGetBoolean(
            target: Any,
            candidateNames: List<String>
        ): Boolean? {
            val clazz = target.javaClass

            // 1) isXxx / getXxx
            for (name in candidateNames) {
                val methods = listOf("is${cap(name)}", "get${cap(name)}", name)
                for (m in methods) {
                    try {
                        val method = clazz.getMethod(m)
                        val v = method.invoke(target)
                        if (v is Boolean) {
                            Log.d(REFLECT_TAG, "reflectGetBoolean OK via method: ${clazz.name}#$m() -> $v")
                            return v
                        }
                    } catch (_: NoSuchMethodException) {
                    } catch (e: Exception) {
                        Log.w(REFLECT_TAG, "reflectGetBoolean method failed: ${clazz.name}#$m()", e)
                    }
                }
            }

            // 2) field
            for (name in candidateNames) {
                val v = tryGetFieldValue(target, name)
                if (v is Boolean) {
                    Log.d(REFLECT_TAG, "reflectGetBoolean OK via field: ${clazz.name}#$name -> $v")
                    return v
                }
            }

            Log.w(REFLECT_TAG, "reflectGetBoolean NOT FOUND: class=${clazz.name}, candidates=$candidateNames")
            return null
        }

        private fun buildSetterNames(prop: String): List<String> {
            val c = cap(prop)
            // 兼容大小写：POS/Pos 这种也会出现，所以都试一下
            return listOf(
                "set$c",
                "set${prop.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
            ).distinct()
        }

        private fun cap(s: String): String {
            if (s.isEmpty()) return s
            return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        /** 选择最匹配的 setter 重载并调用 */
        private fun tryInvokeBestSetter(clazz: Class<*>, target: Any, setterName: String, value: Any?): Boolean {
            val methods = clazz.methods.filter { it.name == setterName && it.parameterTypes.size == 1 }
            if (methods.isEmpty()) return false

            // 如果 value 是 null，只能找 Object / boxed 类型的 setter（基本类型不行）
            if (value == null) {
                val m = methods.firstOrNull { !it.parameterTypes[0].isPrimitive }
                return tryInvokeMethod(target, m, null)
            }

            // 优先匹配同类型
            val valueType = value.javaClass
            methods.firstOrNull { it.parameterTypes[0].isAssignableFrom(valueType) }?.let { m ->
                return tryInvokeMethod(target, m, value)
            }

            // 尝试做基础类型转换（Int->Long 等）
            for (m in methods) {
                val p = m.parameterTypes[0]
                val converted = tryConvert(value, p) ?: continue
                if (tryInvokeMethod(target, m, converted)) return true
            }

            return false
        }

        private fun tryInvokeMethod(target: Any, m: Method?, arg: Any?): Boolean {
            if (m == null) return false
            return try {
                m.invoke(target, arg)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun trySetField(f: Field, target: Any, value: Any?): Boolean {
            return try {
                val converted = if (value == null) null else (tryConvert(value, f.type) ?: value)
                f.set(target, converted)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun tryGetFieldValue(target: Any, fieldName: String): Any? {
            val clazz = target.javaClass
            // public
            try {
                return clazz.getField(fieldName).get(target)
            } catch (_: Exception) {}
            // private
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Exception) {}
            return null
        }
        
        /**
         * 优先使用 getter 方法读取字段值（getXxx() / isXxx()），如果不存在则尝试直接读取字段
         * @param target 目标对象
         * @param candidateNames 可能的字段名（不带 get/is 前缀）
         * @return 字段值，如果未找到则返回 null
         */
        private fun tryGetFieldValueViaGetter(target: Any, candidateNames: List<String>): Any? {
            val clazz = target.javaClass
            
            // 1. 优先尝试 getter 方法：getXxx()
            for (name in candidateNames) {
                try {
                    val getterName = "get${cap(name)}"
                    val method = clazz.getMethod(getterName)
                    val value = method.invoke(target)
                    Log.d(REFLECT_TAG, "tryGetFieldValueViaGetter OK via getter: ${clazz.name}#$getterName() -> $value")
                    return value
                } catch (e: NoSuchMethodException) {
                    // 继续尝试 isXxx()
                } catch (e: Exception) {
                    Log.w(REFLECT_TAG, "getter method failed: ${clazz.name}#get${cap(name)}()", e)
                }
                
                // 尝试 isXxx()（用于布尔类型）
                try {
                    val isterName = "is${cap(name)}"
                    val method = clazz.getMethod(isterName)
                    val value = method.invoke(target)
                    Log.d(REFLECT_TAG, "tryGetFieldValueViaGetter OK via getter: ${clazz.name}#$isterName() -> $value")
                    return value
                } catch (e: NoSuchMethodException) {
                    // 继续尝试字段
                } catch (e: Exception) {
                    Log.w(REFLECT_TAG, "getter method failed: ${clazz.name}#is${cap(name)}()", e)
                }
            }
            
            // 2. 如果 getter 都不存在，尝试直接读取字段（fallback）
            return tryGetFieldValue(target, candidateNames)
        }
        
        /** 读取字段值（支持多个候选名） */
        private fun tryGetFieldValue(target: Any, candidateNames: List<String>): Any? {
            for (name in candidateNames) {
                val value = tryGetFieldValue(target, name)
                if (value != null) {
                    Log.d(REFLECT_TAG, "tryGetFieldValue OK: ${target.javaClass.name}#$name -> $value")
                    return value
                }
            }
            Log.w(REFLECT_TAG, "tryGetFieldValue NOT FOUND: class=${target.javaClass.name}, candidates=$candidateNames")
            return null
        }

        /**
         * PMReplyPara 状态数据类
         */
        private data class PmState(
            val resultCode: Int = -1,
            val errorMsg: String = "",
            val payType: Int = 0,
            val status: Int = -1,
            val multiple: Int = 0,
            val cancel: Int = 0,
            val fault: Int = 0,
            val isOK: Boolean = false
        )
        
        /**
         * 解析 PMReplyPara 状态（优先使用 resultCode + errorMsg）
         * @param pmReply PMReplyPara 实例
         * @param pmReplyClass PMReplyPara 类
         * @return 解析后的状态信息
         */
        private fun parsePmState(pmReply: Any, pmReplyClass: Class<*>): PmState {
            // 1. 优先读取 resultCode 和 errorMsg（使用 getter 或反射）
            val resultCode = try {
                pmReplyClass.getMethod("getResultCode").invoke(pmReply) as? Int ?: -1
            } catch (e: NoSuchMethodException) {
                // 尝试反射字段
                tryGetFieldValueViaGetter(pmReply, listOf("resultCode", "result", "code", "errorCode")) as? Int ?: -1
            } catch (e: Exception) {
                -1
            }
            
            val errorMsg = try {
                pmReplyClass.getMethod("getErrorMsg").invoke(pmReply) as? String ?: ""
            } catch (e: NoSuchMethodException) {
                // 尝试反射字段
                tryGetFieldValueViaGetter(pmReply, listOf("errorMsg", "error", "message", "msg")) as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            
            // 2. 读取其他字段（作为辅助判断）
            val isOK = try {
                pmReplyClass.getMethod("isOK").invoke(pmReply) as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
            
            val payType = tryGetFieldValueViaGetter(pmReply, listOf("payType", "paytype", "type", "paymentType")) as? Int ?: 0
            val status = tryGetFieldValueViaGetter(pmReply, listOf("status", "state", "stat")) as? Int ?: -1
            val multiple = tryGetFieldValueViaGetter(pmReply, listOf("multiple", "mult", "value", "amount", "amt", "money", "price", "sum")) as? Int ?: 0
            val cancel = tryGetFieldValueViaGetter(pmReply, listOf("cancel", "cancelled", "isCancel", "isCancelled")) as? Int ?: 0
            val fault = tryGetFieldValueViaGetter(pmReply, listOf("fault", "error", "err", "faultCode", "errorCode")) as? Int ?: 0
            
            return PmState(
                resultCode = resultCode,
                errorMsg = errorMsg,
                payType = payType,
                status = status,
                multiple = multiple,
                cancel = cancel,
                fault = fault,
                isOK = isOK
            )
        }
        
        /**
         * 从 PMReplyPara 类里读取 POS 常量（不要写死 payType==4）
         * @return POS 常量值，如果未找到则返回 4（fallback）
         */
        private fun resolvePayTypeConst(typeName: String): Int {
            return try {
                val pmReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PMReplyPara")
                // 尝试读取静态字段：POS, POS_CASH, POS_COIN 等
                val fieldNames = listOf(
                    typeName,
                    "PAY_TYPE_$typeName",
                    "${typeName}_TYPE",
                    "TYPE_$typeName"
                )
                
                for (fieldName in fieldNames) {
                    try {
                        val field = pmReplyClass.getField(fieldName)
                        val value = field.get(null) as? Int
                        if (value != null) {
                            Log.d(TAG, "从 PMReplyPara 读取 $fieldName = $value")
                            return value
                        }
                    } catch (e: NoSuchFieldException) {
                        // 继续尝试下一个
                    }
                }
                
                // 如果没找到，尝试从 UBoard 类读取
                val boardClass = Class.forName("cc.uling.usdk.board.UBoard")
                for (fieldName in fieldNames) {
                    try {
                        val field = boardClass.getField(fieldName)
                        val value = field.get(null) as? Int
                        if (value != null) {
                            Log.d(TAG, "从 UBoard 读取 $fieldName = $value")
                            return value
                        }
                    } catch (e: NoSuchFieldException) {
                        // 继续尝试下一个
                    }
                }
                
                // Fallback：根据类型名返回默认值
                when (typeName) {
                    "POS" -> 4
                    "POS_CASH" -> 5
                    "POS_COIN" -> 6
                    "POS_CASH_COIN" -> 7
                    else -> 4
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法读取 $typeName 常量，使用默认值", e)
                when (typeName) {
                    "POS" -> 4
                    "POS_CASH" -> 5
                    "POS_COIN" -> 6
                    "POS_CASH_COIN" -> 7
                    else -> 4
                }
            }
        }
        
        /**
         * 映射 ResultCode 到支付状态类型（仅用于日志，不用于判断支付是否成功）
         * @param resultCode ResultCode 值
         * @return 状态类型："OK", "CONTINUE", "CANCELLED", "FAILED", "TIMEOUT", "VALIDATION_MISMATCH", "UNKNOWN"
         * 
         * 注意：resultCode=0 不能直接映射 SUCCESS，支付是否成功只看 payType/multiple/cancel/status
         * 215 Validation code does not match 应映射为 VALIDATION_MISMATCH
         * 204 数据返回超时 才是"通讯超时"
         */
        private fun mapResultCode(resultCode: Int): String {
            return when (resultCode) {
                0 -> "OK"  // resultCode=0 仅表示通讯正常，不表示支付成功
                215 -> "VALIDATION_MISMATCH"  // Validation code does not match
                204 -> "TIMEOUT"  // 数据返回超时（通讯超时）
                // TODO: 需要厂商确认具体的 ResultCode 值
                // 暂时根据常见约定：
                // 1-99: 一般错误
                // 100-199: 取消相关
                // 200-299: 超时相关（但 204 和 215 已单独处理）
                // 其他: 失败
                in 100..199 -> "CANCELLED"
                in 200..299 -> if (resultCode == 204 || resultCode == 215) {
                    // 已单独处理，不应该到这里
                    "FAILED"
                } else {
                    "TIMEOUT"
                }
                else -> if (resultCode > 0) "FAILED" else "UNKNOWN"
            }
        }
        
        /** 常见数值/布尔/String 的简单转换 */
        private fun tryConvert(value: Any, targetType: Class<*>): Any? {
            return try {
                when {
                    targetType == Int::class.javaPrimitiveType || targetType == Int::class.javaObjectType ->
                        (value as? Number)?.toInt()

                    targetType == Long::class.javaPrimitiveType || targetType == Long::class.javaObjectType ->
                        (value as? Number)?.toLong()

                    targetType == Short::class.javaPrimitiveType || targetType == Short::class.javaObjectType ->
                        (value as? Number)?.toShort()

                    targetType == Byte::class.javaPrimitiveType || targetType == Byte::class.javaObjectType ->
                        (value as? Number)?.toByte()

                    targetType == Double::class.javaPrimitiveType || targetType == Double::class.javaObjectType ->
                        (value as? Number)?.toDouble()

                    targetType == Float::class.javaPrimitiveType || targetType == Float::class.javaObjectType ->
                        (value as? Number)?.toFloat()

                    targetType == Boolean::class.javaPrimitiveType || targetType == Boolean::class.javaObjectType -> when (value) {
                        is Boolean -> value
                        is Number -> value.toInt() != 0
                        is String -> value.equals("true", true) || value == "1"
                        else -> null
                    }

                    targetType == String::class.java ->
                        value.toString()

                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
        
        /**
         * 尝试查找请求参数类（如 MPPara/MPReqPara/MPOrderPara 等）
         * @param boardClass UBoard 类
         * @return 请求参数类，如果未找到则返回 null
         */
        private fun tryFindRequestParaClass(boardClass: Class<*>): Class<*>? {
            val candidateNames = listOf(
                "cc.uling.usdk.board.mdb.para.MPPara",
                "cc.uling.usdk.board.mdb.para.MPReqPara",
                "cc.uling.usdk.board.mdb.para.MPOrderPara",
                "cc.uling.usdk.board.mdb.para.MPRequestPara",
                "cc.uling.usdk.board.mdb.para.MPInitPara",
                "cc.uling.usdk.board.mdb.para.MPPayPara"
            )
            
            for (name in candidateNames) {
                try {
                    val cls = Class.forName(name)
                    Log.d(TAG, "找到请求参数类: $name")
                    return cls
                } catch (e: ClassNotFoundException) {
                    // 继续尝试下一个
                }
            }
            
            Log.w(TAG, "未找到请求参数类，候选名称: $candidateNames")
            return null
        }
        
        /**
         * 通用的反射创建实例方法（自动选择可用构造函数）
         * @param clazz 要实例化的类
         * @param no 商品编号（货道）
         * @param multiple 倍数
         * @return 创建的实例，如果失败则返回 null
         */
        private fun createIpReplyInstance(clazz: Class<*>, no: Short, multiple: Int): Any? {
            // 先打印所有构造函数
            val constructors = clazz.declaredConstructors
            Log.w(TAG, "================ IPReplyPara Constructors ================")
            for (ctor in constructors) {
                val params = ctor.parameterTypes.joinToString(", ") { it.simpleName }
                Log.w(TAG, "Constructor: ${clazz.simpleName}($params)")
            }
            Log.w(TAG, "============================================================")
            
            // 按优先顺序尝试构造
            // 1. (Short.TYPE, Int.TYPE) → newInstance(no, multiple)
            try {
                val ctor = clazz.getConstructor(Short::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                ctor.isAccessible = true
                val instance = ctor.newInstance(no, multiple)
                Log.d(TAG, "成功使用构造函数: (Short, Int) -> newInstance($no, $multiple)")
                return instance
            } catch (e: Exception) {
                Log.d(TAG, "构造函数 (Short, Int) 不可用: ${e.message}")
            }
            
            // 2. (Int.TYPE, Short.TYPE) → newInstance(multiple, no)（防止顺序反）
            try {
                val ctor = clazz.getConstructor(Int::class.javaPrimitiveType, Short::class.javaPrimitiveType)
                ctor.isAccessible = true
                val instance = ctor.newInstance(multiple, no)
                Log.d(TAG, "成功使用构造函数: (Int, Short) -> newInstance($multiple, $no)")
                return instance
            } catch (e: Exception) {
                Log.d(TAG, "构造函数 (Int, Short) 不可用: ${e.message}")
            }
            
            // 3. (Int.TYPE) → newInstance(multiple)
            try {
                val ctor = clazz.getConstructor(Int::class.javaPrimitiveType)
                ctor.isAccessible = true
                val instance = ctor.newInstance(multiple)
                Log.d(TAG, "成功使用构造函数: (Int) -> newInstance($multiple)")
                return instance
            } catch (e: Exception) {
                Log.d(TAG, "构造函数 (Int) 不可用: ${e.message}")
            }
            
            // 4. (Short.TYPE) → newInstance(no)
            try {
                val ctor = clazz.getConstructor(Short::class.javaPrimitiveType)
                ctor.isAccessible = true
                val instance = ctor.newInstance(no)
                Log.d(TAG, "成功使用构造函数: (Short) -> newInstance($no)")
                return instance
            } catch (e: Exception) {
                Log.d(TAG, "构造函数 (Short) 不可用: ${e.message}")
            }
            
            // 5. 尝试任何只有 2 个参数且可赋值（short/int/byte 等）→ 尝试填充（no/multiple/0）
            for (ctor in constructors) {
                if (ctor.parameterTypes.size == 2) {
                    val paramTypes = ctor.parameterTypes
                    val canAssign = paramTypes.all { type ->
                        type == Short::class.javaPrimitiveType || 
                        type == Int::class.javaPrimitiveType || 
                        type == Byte::class.javaPrimitiveType ||
                        type == Short::class.javaObjectType || 
                        type == Int::class.javaObjectType || 
                        type == Byte::class.javaObjectType
                    }
                    if (canAssign) {
                        try {
                            ctor.isAccessible = true
                            // 尝试不同的参数组合
                            val args = arrayOfNulls<Any?>(2)
                            // 尝试 (no, multiple)
                            args[0] = tryConvert(no, paramTypes[0])
                            args[1] = tryConvert(multiple, paramTypes[1])
                            if (args[0] != null && args[1] != null) {
                                val instance = ctor.newInstance(*args)
                                Log.d(TAG, "成功使用构造函数: ${paramTypes.joinToString { it.simpleName }} -> newInstance(${args[0]}, ${args[1]})")
                                return instance
                            }
                            // 尝试 (multiple, no)
                            args[0] = tryConvert(multiple, paramTypes[0])
                            args[1] = tryConvert(no, paramTypes[1])
                            if (args[0] != null && args[1] != null) {
                                val instance = ctor.newInstance(*args)
                                Log.d(TAG, "成功使用构造函数: ${paramTypes.joinToString { it.simpleName }} -> newInstance(${args[0]}, ${args[1]})")
                                return instance
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "构造函数 ${paramTypes.joinToString { it.simpleName }} 调用失败: ${e.message}")
                        }
                    }
                }
            }
            
            // 6. 兜底：尝试无参构造函数
            try {
                val ctor = clazz.getConstructor()
                ctor.isAccessible = true
                val instance = ctor.newInstance()
                Log.d(TAG, "成功使用无参构造函数")
                return instance
            } catch (e: Exception) {
                Log.d(TAG, "无参构造函数不可用: ${e.message}")
            }
            
            // 7. 最后兜底：使用 Unsafe.allocateInstance 绕过构造创建对象
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)
                val allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val instance = allocateInstanceMethod.invoke(unsafe, clazz)
                Log.w(TAG, "使用 Unsafe.allocateInstance 创建实例（绕过构造函数）")
                return instance
            } catch (e: Exception) {
                Log.e(TAG, "Unsafe.allocateInstance 失败: ${e.message}")
            }
            
            Log.e(TAG, "所有构造函数尝试均失败，无法创建实例")
            return null
        }
        
        /**
         * 尝试查找 initPayment 方法（可能有多种重载）
         * @param boardClass UBoard 类
         * @param requestParaClass 请求参数类
         * @param replyParaClass 回复参数类
         * @return 找到的方法，如果未找到则返回 null
         */
        private fun tryFindInitPaymentMethod(
            boardClass: Class<*>,
            requestParaClass: Class<*>?,
            replyParaClass: Class<*>
        ): Method? {
            val methodName = "initPayment"
            val methods = boardClass.methods.filter { it.name == methodName }
            
            if (methods.isEmpty()) {
                Log.w(TAG, "未找到 $methodName 方法")
                return null
            }
            
            // 优先查找带请求参数的方法
            if (requestParaClass != null) {
                val methodWithRequest = methods.firstOrNull { m ->
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == requestParaClass &&
                    m.parameterTypes[1] == replyParaClass
                }
                if (methodWithRequest != null) {
                    Log.d(TAG, "找到带请求参数的方法: $methodName(${requestParaClass.simpleName}, ${replyParaClass.simpleName})")
                    return methodWithRequest
                }
            }
            
            // 查找只带回复参数的方法（向后兼容）
            val methodWithReply = methods.firstOrNull { m ->
                m.parameterTypes.size == 1 && m.parameterTypes[0] == replyParaClass
            }
            if (methodWithReply != null) {
                Log.d(TAG, "找到只带回复参数的方法: $methodName(${replyParaClass.simpleName})")
                return methodWithReply
            }
            
            Log.w(TAG, "未找到匹配的 $methodName 方法，可用方法: ${methods.map { it.parameterTypes.joinToString { it.simpleName } }}")
            return null
        }
    }
    
    // USDK SDK 实例（使用 Any 类型，避免编译时依赖）
    // 实际类型：cc.uling.usdk.board.UBoard
    private var board: Any? = null
    private var isInitialized = false
    private var currentCommId: String? = null  // 当前使用的串口路径
    private var hasPOSCapability: Boolean = false  // 是否检测到 POS 能力
    private var currentPaymentAmountCents: Int = 0
    private var paymentCallback: ((PaymentResult) -> Unit)? = null
    
    private val _paymentStatus = MutableStateFlow<PaymentStatus>(PaymentStatus.IDLE)
    val paymentStatus: StateFlow<PaymentStatus> = _paymentStatus.asStateFlow()
    
    // 一次性结算门闩：确保一次支付只会结算一次
    private val finished = AtomicBoolean(false)
    
    // 后台任务 Job（用于取消）
    private var pollJob: Job? = null
    private var timeoutJob: Job? = null
    private val paymentScope = CoroutineScope(Dispatchers.IO)
    
    // pmReplyDumped 改成全局只 dump 一次（移到类成员，避免每次进方法都重置）
    private val pmReplyDumped = AtomicBoolean(false)
    
    // notifyResult 方法缺失标志（用于控制日志级别）
    private val notifyResultMissingLogged = AtomicBoolean(false)
    
    // 时间点追踪变量
    private var initPaymentStartTime: Long = 0
    private var firstNonZeroPayTypeTime: Long = 0
    private var firstNonZeroMultipleTime: Long = 0
    
    // 交易上下文：保存当前交易的 no/validation，用于后续轮询/取消
    private var currentTxNo: Short? = null
    private var txNoCounter: Short = 0  // 自增交易编号（如果 SDK 没提供 no 的回传）
    
    /**
     * 扫描可用的串口设备，找到支持 POS 机的设备
     * @return 成功连接的串口路径，如果未找到则返回 null
     */
    suspend fun scanAndConnectSerialPort(): String? {
        Log.d(TAG, "========== 开始自动扫描串口设备 ==========")
        Log.d(TAG, "波特率: $baudrate (固定)")
        Log.d(TAG, "扫描策略: 自动扫描所有常见串口路径，找到支持 POS 机的设备")
        
        // 如果指定了串口，先尝试指定的串口
        val portsToTry = if (commId != null) {
            Log.d(TAG, "指定串口: $commId，将优先尝试")
            listOf(commId) + COMMON_SERIAL_PORTS.filter { it != commId }
        } else {
            COMMON_SERIAL_PORTS
        }
        
        Log.d(TAG, "待扫描串口列表 (共 ${portsToTry.size} 个): ${portsToTry.joinToString(", ")}")
        Log.d(TAG, "注意: 将按顺序尝试每个串口，直到找到支持 POS 机的设备")
        
        var successCount = 0
        var failCount = 0
        
        for ((index, port) in portsToTry.withIndex()) {
            Log.d(TAG, "========== 扫描进度: ${index + 1}/${portsToTry.size} ==========")
            Log.d(TAG, "当前尝试串口: $port")
            
            try {
                // 尝试初始化该串口
                val success = initializeWithPort(port)
                if (success) {
                    successCount++
                    Log.d(TAG, "========== 串口连接成功 ==========")
                    Log.d(TAG, "成功连接的串口: $port")
                    Log.d(TAG, "波特率: $baudrate")
                    Log.d(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
                    Log.d(TAG, "注意: 如果这不是您期望的串口，请检查设备连接或手动指定串口路径")
                    
                    // 检查是否检测到 POS 设备（isWithPOS）
                    // initializeWithPort 会设置 board 实例和 hasPOSCapability 标志
                    // 如果 isWithPOS=true，锁定该端口，停止扫描
                    // 如果 isWithPOS=false，继续尝试其他串口（但当前串口已初始化成功）
                    if (hasPOSCapability) {
                        Log.d(TAG, "检测到 POS 设备，锁定该端口，停止扫描")
                        currentCommId = port
                        return port
                    } else {
                        Log.w(TAG, "当前串口未检测到 POS 设备，继续尝试其他串口...")
                        // 先保存当前串口（作为备选），继续扫描
                        if (currentCommId == null) {
                            currentCommId = port
                        }
                        // 继续扫描下一个串口
                    }
                } else {
                    failCount++
                    Log.w(TAG, "串口 $port 连接失败，继续扫描下一个串口...")
                    Log.w(TAG, "失败原因: 可能是设备不存在、不支持 POS 功能、或权限不足")
                    Log.w(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
                }
            } catch (e: ClassNotFoundException) {
                failCount++
                Log.w(TAG, "串口 $port: USDK SDK 类未找到，跳过该串口", e)
                Log.w(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
            } catch (e: NoSuchMethodException) {
                failCount++
                Log.w(TAG, "串口 $port: 方法未找到，跳过该串口", e)
                Log.w(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
            } catch (e: Exception) {
                failCount++
                Log.w(TAG, "串口 $port 连接异常: ${e.message}，继续扫描下一个串口...", e)
                Log.w(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
            }
            
            // 短暂延迟，避免连续尝试导致问题
            if (index < portsToTry.size - 1) {
                delay(100)
            }
        }
        
        // 如果扫描完所有串口都没有找到 isWithPOS=true 的设备
        // 但至少有一个串口初始化成功（board != null），则使用该串口
        if (board != null && currentCommId != null) {
            Log.w(TAG, "========== 串口扫描完成，未找到支持 POS 的设备 ==========")
            Log.w(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
            Log.w(TAG, "已尝试的串口: ${portsToTry.joinToString(", ")}")
            Log.w(TAG, "注意: 虽然未检测到 POS 设备（isWithPOS=false），但串口和板通讯已通")
            Log.w(TAG, "使用已初始化的串口: $currentCommId")
            Log.w(TAG, "提示: 未检测到外挂 POS 设备，用户可选择其他支付方式")
            return currentCommId
        }
        
        Log.e(TAG, "========== 串口扫描完成，未找到可用的设备 ==========")
        Log.e(TAG, "扫描统计: 成功=$successCount, 失败=$failCount")
        Log.e(TAG, "已尝试的串口: ${portsToTry.joinToString(", ")}")
        Log.e(TAG, "可能的原因：")
        Log.e(TAG, "1. POS 设备未连接或未上电")
        Log.e(TAG, "2. 设备驱动未安装或权限不足")
        Log.e(TAG, "3. 设备不在常见串口路径中（实际串口可能是其他路径）")
        Log.e(TAG, "4. 设备不支持 POS 功能")
        Log.e(TAG, "5. USDK SDK 未正确加载")
        Log.e(TAG, "建议: 如果您的设备实际连接在 /dev/ttyS2，请检查：")
        Log.e(TAG, "  - 设备是否已正确连接并上电")
        Log.e(TAG, "  - 应用是否有串口访问权限")
        Log.e(TAG, "  - 设备是否支持 POS 功能（isWithPOS=true）")
        return null
    }
    
    /**
     * 使用指定的串口路径初始化设备
     * @param port 串口路径
     * @return 是否初始化成功
     */
    private suspend fun initializeWithPort(port: String): Boolean {
        return try {
            Log.d(TAG, "========== 尝试初始化串口 ==========")
            Log.d(TAG, "串口路径: $port")
            Log.d(TAG, "波特率: $baudrate")
            
            // 使用反射方式调用 USDK SDK
            // 步骤1: 获取 USDK 类
            val usdkClass = try {
                Class.forName("cc.uling.usdk.USDK")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "USDK SDK 类未找到: cc.uling.usdk.USDK", e)
                Log.e(TAG, "请确保 usdk_v2024112602.jar 已正确添加到 classpath")
                throw e
            }
            
            // 步骤2: 获取 USDK 单例实例
            Log.d(TAG, "获取 USDK 单例实例...")
            val getInstanceMethod = try {
                usdkClass.getMethod("getInstance")
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "USDK.getInstance() 方法未找到", e)
                throw e
            }
            
            val usdk = try {
                getInstanceMethod.invoke(null)
            } catch (e: Exception) {
                Log.e(TAG, "调用 USDK.getInstance() 失败", e)
                throw e
            }
            
            if (usdk == null) {
                Log.e(TAG, "USDK.getInstance() 返回 null")
                return false
            }
            
            Log.d(TAG, "USDK 单例实例获取成功")
            
            // 步骤3: 检查 USDK 是否已初始化
            // 注意: USDK 应在 Application.onCreate() 中初始化，这里不再重复初始化
            Log.d(TAG, "检查 USDK 初始化状态...")
            Log.d(TAG, "注意: USDK 应在 Application.onCreate() 中初始化，这里不再重复调用 init()")
            
            // 步骤4: 创建 UBoard 实例（使用 create(commid) 带串口名参数）
            // 根据文档，USDK.getInstance().create(commid) 需要传入串口名字符串
            Log.d(TAG, "创建 UBoard 实例 (create(commid))...")
            Log.d(TAG, "串口名: $port")
            
            val createMethod = try {
                // 尝试使用 create(String) 方法（带串口名参数）
                usdkClass.getMethod("create", String::class.java)
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "USDK.create(String) 方法未找到，尝试查找所有 create 方法...", e)
                // 尝试查找所有 create 方法
                val methods = usdkClass.methods.filter { it.name == "create" }
                Log.e(TAG, "找到的 create 方法: ${methods.map { it.parameterTypes.joinToString { it.simpleName } }}")
                throw e
            }
            
            val tempBoard = try {
                createMethod.invoke(usdk, port)
            } catch (e: Exception) {
                Log.e(TAG, "调用 USDK.create($port) 失败", e)
                Log.e(TAG, "错误详情: ${e.message}")
                throw e
            }
            
            if (tempBoard == null) {
                Log.e(TAG, "创建 UBoard 实例失败: USDK.create() 返回 null")
                return false
            }
            
            Log.d(TAG, "UBoard 实例创建成功 (使用串口: $port)")
            
            // 步骤5: 打开串口（波特率固定为 9600）
            // 注意: 根据文档，EF_OpenDev(commid, baudrate) 返回 int 类型
            // openRet == 0 表示成功，openRet != 0 表示错误码（如 201 串口打开失败、214 串口未打开通讯失败等）
            Log.d(TAG, "打开串口: $port, 波特率: $baudrate")
            val boardClass = tempBoard::class.java
            val openDevMethod = try {
                boardClass.getMethod("EF_OpenDev", String::class.java, Int::class.java)
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "UBoard.EF_OpenDev(String, Int) 方法未找到", e)
                throw e
            }
            
            val openRet = try {
                val result = openDevMethod.invoke(tempBoard, port, baudrate)
                // EF_OpenDev 返回 int 类型，不是对象
                when (result) {
                    is Int -> result
                    is Number -> result.toInt()
                    null -> -1
                    else -> {
                        Log.w(TAG, "EF_OpenDev 返回类型异常: ${result::class.java.name}，尝试转换为 int")
                        try {
                            result.toString().toIntOrNull() ?: -1
                        } catch (e: Exception) {
                            -1
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "调用 EF_OpenDev($port, $baudrate) 失败", e)
                // 检查是否是 SELinux avc denied
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("avc") || errorMsg.contains("denied") || errorMsg.contains("SELinux")) {
                    Log.w(TAG, "检测到 SELinux 相关错误，当前系统可能是 permissive 模式")
                    Log.w(TAG, "注意: 当前系统 SELinux permissive=1，虽然能 open 成功，但在 enforcing 模式可能会失败")
                    Log.w(TAG, "需要主板/系统层开放 serial_device 权限或 sepolicy 适配")
                }
                throw e
            }
            
            Log.d(TAG, "EF_OpenDev return = $openRet")
            
            // 判断串口打开是否成功：0 表示成功，非 0 表示错误码
            if (openRet != 0) {
                Log.w(TAG, "========== EF_OpenDev 失败 ==========")
                Log.w(TAG, "串口: $port")
                Log.w(TAG, "波特率: $baudrate")
                Log.w(TAG, "错误码: $openRet")
                Log.w(TAG, "错误码说明（参考文档）：")
                when (openRet) {
                    201 -> Log.w(TAG, "  201: 串口打开失败")
                    214 -> Log.w(TAG, "  214: 串口未打开通讯失败")
                    else -> Log.w(TAG, "  $openRet: 其他错误（请参考文档错误码表）")
                }
                Log.w(TAG, "可能的原因：")
                Log.w(TAG, "1. 串口不存在或已被占用")
                Log.w(TAG, "2. 设备未连接或未上电")
                Log.w(TAG, "3. 权限不足（需要 root 或串口权限）")
                Log.w(TAG, "4. 波特率不匹配")
                return false
            }
            
            Log.d(TAG, "========== 串口打开成功 ==========")
            Log.d(TAG, "串口: $port")
            Log.d(TAG, "波特率: $baudrate")
            Log.d(TAG, "EF_OpenDev return = 0 (成功)")
            
            // 步骤6: 读取硬件配置（验证设备连接）
            Log.d(TAG, "读取硬件配置（验证设备连接）...")
            val hcReplyClass = try {
                Class.forName("cc.uling.usdk.board.mdb.para.HCReplyPara")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "HCReplyPara 类未找到", e)
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    // 忽略关闭错误
                }
                return false
            }
            
            val hcReply = try {
                hcReplyClass.getConstructor().newInstance()
            } catch (e: Exception) {
                Log.e(TAG, "创建 HCReplyPara 实例失败", e)
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    // 忽略关闭错误
                }
                return false
            }
            
            val readHardwareConfigMethod = try {
                boardClass.getMethod("readHardwareConfig", hcReplyClass)
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "UBoard.readHardwareConfig(HCReplyPara) 方法未找到", e)
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    // 忽略关闭错误
                }
                return false
            }
            
            try {
                readHardwareConfigMethod.invoke(tempBoard, hcReply)
            } catch (e: Exception) {
                Log.e(TAG, "调用 readHardwareConfig() 失败", e)
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    // 忽略关闭错误
                }
                return false
            }
            
            // 使用 BaseClsPar.isOK() 和 getResultCode() 判断通讯是否成功
            val hcIsOK = try {
                hcReplyClass.getMethod("isOK").invoke(hcReply) as? Boolean ?: false
            } catch (e: Exception) {
                Log.e(TAG, "调用 HCReplyPara.isOK() 失败", e)
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    // 忽略关闭错误
                }
                return false
            }
            
            val hcResultCode = try {
                hcReplyClass.getMethod("getResultCode").invoke(hcReply) as? Int ?: -1
            } catch (e: Exception) {
                -1
            }
            
            Log.d(TAG, "readHardwareConfig isOK=$hcIsOK, resultCode=$hcResultCode")
            
            // 如果 hc.isOK()==false 或 hc.getResultCode()!=0：认为探测失败
            if (!hcIsOK || hcResultCode != 0) {
                Log.w(TAG, "========== readHardwareConfig 失败 ==========")
                Log.w(TAG, "串口: $port")
                Log.w(TAG, "isOK: $hcIsOK")
                Log.w(TAG, "resultCode: $hcResultCode")
                Log.w(TAG, "错误码说明（参考文档）：")
                when (hcResultCode) {
                    201 -> Log.w(TAG, "  201: 串口打开失败")
                    214 -> Log.w(TAG, "  214: 串口未打开通讯失败")
                    else -> Log.w(TAG, "  $hcResultCode: 其他错误（请参考文档错误码表）")
                }
                Log.w(TAG, "可能的原因：")
                Log.w(TAG, "1. 设备未正确连接")
                Log.w(TAG, "2. 设备通信协议不匹配")
                Log.w(TAG, "3. 设备未响应")
                
                // 关闭已打开的串口
                try {
                    val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                    closeDevMethod.invoke(tempBoard)
                } catch (e: Exception) {
                    Log.w(TAG, "关闭串口失败", e)
                }
                return false
            }
            
            Log.d(TAG, "硬件配置读取成功 (isOK=true, resultCode=0)")
            
            // 步骤7: 检查是否支持 POS 机
            // 注意: 使用 isWithPOS() 方法而不是字段，并添加多重 fallback
            Log.d(TAG, "检查设备是否支持 POS 机...")
            
            // 多重 fallback 策略：优先使用 isWithPOS() 方法
            val withPOS = try {
                // 优先：调用 isWithPOS() 方法（注意 "POS" 大写）
                try {
                    val isWithPOSMethod = hcReplyClass.getMethod("isWithPOS")
                    val result = isWithPOSMethod.invoke(hcReply) as? Boolean ?: false
                    Log.d(TAG, "使用 isWithPOS() 方法获取结果: $result")
                    result
                } catch (e: NoSuchMethodException) {
                    // 其次：尝试 getWithPOS()（以防某些版本用 get 前缀）
                    try {
                        val getWithPOSMethod = hcReplyClass.getMethod("getWithPOS")
                        val result = getWithPOSMethod.invoke(hcReply) as? Boolean ?: false
                        Log.d(TAG, "使用 getWithPOS() 方法获取结果: $result")
                        result
                    } catch (e2: NoSuchMethodException) {
                        // 再其次：尝试 isWithPos() / getWithPos()（以防厂商大小写不一致）
                        try {
                            val isWithPosMethod = hcReplyClass.getMethod("isWithPos")
                            val result = isWithPosMethod.invoke(hcReply) as? Boolean ?: false
                            Log.d(TAG, "使用 isWithPos() 方法获取结果: $result")
                            result
                        } catch (e3: NoSuchMethodException) {
                            try {
                                val getWithPosMethod = hcReplyClass.getMethod("getWithPos")
                                val result = getWithPosMethod.invoke(hcReply) as? Boolean ?: false
                                Log.d(TAG, "使用 getWithPos() 方法获取结果: $result")
                                result
                            } catch (e4: NoSuchMethodException) {
                                // 最后兜底：尝试读字段 withPOS（但要用 getDeclaredField 并 isAccessible=true）
                                try {
                                    val withPOSField = hcReplyClass.getDeclaredField("withPOS")
                                    withPOSField.isAccessible = true
                                    val result = withPOSField.get(hcReply) as? Boolean ?: false
                                    Log.d(TAG, "使用 withPOS 字段获取结果: $result")
                                    result
                                } catch (e5: Exception) {
                                    Log.w(TAG, "无法获取 withPOS 信息（所有方法都失败），使用默认值 false", e5)
                                    false
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取 withPOS 信息异常，使用默认值 false", e)
                false
            }
            
            Log.d(TAG, "POS capability: isWithPOS=$withPOS")
            
            // 如果最终拿不到 withPOS 信息，不要直接判定"初始化失败"
            // 而是打印 warning，并继续走后续（至少串口/板通讯已通）
            if (!withPOS) {
                Log.w(TAG, "========== 未检测到外挂 POS 设备 ==========")
                Log.w(TAG, "串口: $port")
                Log.w(TAG, "isWithPOS=false")
                Log.w(TAG, "注意: 根据文档和厂商回复，withPOS=false 只表示是否有外挂设备，不一定导致 init 失败")
                Log.w(TAG, "串口通 + readHardwareConfig ok = 初始化成功")
                Log.w(TAG, "isWithPOS=false：只提示'未检测到外挂 POS'，并继续允许用户选择其它支付方式")
                Log.w(TAG, "不报'初始化失败'，而是提示'未检测到外挂 POS'")
                
                // 不关闭串口，不返回 false，继续初始化流程
                // 只是标记为"无外挂 POS"，但不判定为失败
                // 保存 board 实例，但标记为"无 POS"
                board = tempBoard
                hasPOSCapability = false
                // 注意: 这里不返回 false，让调用方决定是否继续扫描
                // 返回 true 表示初始化成功（串口和板通讯已通），但无 POS 能力
            } else {
                Log.d(TAG, "设备支持 POS 机 (isWithPOS=true)")
                Log.d(TAG, "锁定该端口，停止扫描")
                // 保存 board 实例，并标记为有 POS 能力
                board = tempBoard
                hasPOSCapability = true
                // 返回 true，表示找到 POS 设备，可以停止扫描
            }
            
            // 读取其他硬件配置信息（可选，用于日志）- 使用安全读取
            val version = readAnyField(hcReply, listOf("version", "ver", "hwVersion", "hardwareVersion"))
            val code = readAnyField(hcReply, listOf("code", "hwCode", "deviceCode"))
            val withCoin = readAnyBooleanFieldOrMethod(hcReply, listOf("withCoin", "coin", "hasCoin"), default = false)
            val withCash = readAnyBooleanFieldOrMethod(hcReply, listOf("withCash", "cash", "hasCash"), default = false)
            val withPulse = readAnyBooleanFieldOrMethod(hcReply, listOf("withPulse", "pulse", "hasPulse"), default = false)
            
            // 如果关键字段读取失败，打印警告（但不影响初始化）
            if (version == null || code == null) {
                val missingFields = mutableListOf<String>()
                if (version == null) missingFields.add("version")
                if (code == null) missingFields.add("code")
                Log.w(TAG, "读取硬件配置字段失败/缺失（${missingFields.joinToString("/")}），忽略，不影响初始化")
            }
            
            Log.d(TAG, "========== POS 设备初始化成功 ==========")
            Log.d(TAG, "串口: $port, 波特率: $baudrate")
            Log.d(TAG, "硬件配置: version=${version ?: "Unknown"}, isWithPOS=$withPOS, code=${code ?: "Unknown"}")
            Log.d(TAG, "withCoin=$withCoin, withCash=$withCash, withPulse=$withPulse")
            
            // 初始化成功（无论 isWithPOS 是 true 还是 false）
            // isWithPOS=false 只表示无外挂设备，但不影响串口和板通讯
            true
            
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "USDK SDK 类未找到，使用模拟模式: $port")
            // 模拟模式：允许继续
            board = null
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化串口异常: $port", e)
            return false
        }
    }
    
    override suspend fun initialize(): Boolean {
        Log.d(TAG, "========== 初始化 USDK POS 设备 ==========")
        Log.d(TAG, "commId=${commId ?: "自动扫描"}, baudrate=$baudrate (固定)")
        
        // 如果已经初始化，直接返回
        if (isInitialized && board != null) {
            Log.d(TAG, "POS 设备已初始化，使用现有连接: $currentCommId")
            return true
        }
        
        // 自动扫描串口
        val connectedPort = scanAndConnectSerialPort()
        if (connectedPort == null) {
            Log.e(TAG, "========== POS 设备初始化失败 ==========")
            Log.e(TAG, "未找到可用的 POS 设备，请检查：")
            Log.e(TAG, "1. POS 设备是否已连接并上电")
            Log.e(TAG, "2. 设备驱动是否已安装")
            Log.e(TAG, "3. 设备权限是否正确（可能需要 root 或串口权限）")
            Log.e(TAG, "4. 设备是否支持 POS 功能")
            Log.e(TAG, "5. USDK SDK 是否正确加载")
            isInitialized = false
            board = null
            return false
        }
        
        isInitialized = true
        _paymentStatus.value = PaymentStatus.IDLE
        Log.d(TAG, "========== POS 设备初始化完成 ==========")
        Log.d(TAG, "成功连接的串口: $connectedPort")
        Log.d(TAG, "波特率: $baudrate")
        
        // 打印支付相关方法签名，用于调试和锁定正确 API
        if (board != null) {
            dumpPaymentMethods(board!!)
            // 可选：初始化成功后也 dump 一次 USDK 诊断（只 dump 一次，避免刷屏）
            UsdkDebugDump.dumpFullDiagnostics(board!!)
        }
        
        return true
    }
    
    /**
     * 完成支付流程（一次性结算门闩）
     * @param reason 完成原因（用于日志）
     * @return true=成功获取门闩（可以继续执行），false=已被其他分支完成（跳过）
     */
    private fun finishOnce(reason: String): Boolean {
        val acquired = finished.compareAndSet(false, true)
        if (!acquired) {
            Log.w(TAG, "支付流程已完成（$reason），跳过重复结算")
        } else {
            Log.d(TAG, "获取结算门闩成功: $reason")
        }
        return acquired
    }
    
    /**
     * 取消所有后台任务
     */
    private fun cancelAllJobs() {
        pollJob?.cancel()
        timeoutJob?.cancel()
        pollJob = null
        timeoutJob = null
        Log.d(TAG, "已取消所有后台任务（pollJob, timeoutJob）")
    }
    
    /**
     * 打印支付相关方法签名（调试用）
     * 用于锁定正确的 API 调用方式
     */
    private fun dumpPaymentMethods(board: Any) {
        val cls = board.javaClass
        val methods = cls.methods
            .filter { m ->
                val n = m.name.lowercase()
                n.contains("mp") || n.contains("pay") || n.contains("payment") ||
                n.contains("trade") || n.contains("consume") || n.contains("pos")
            }
            .sortedBy { it.name }
        
        Log.d(TAG, "==== dumpPaymentMethods: class=${cls.name}, count=${methods.size} ====")
        for (m in methods) {
            val params = m.parameterTypes.joinToString(",") { it.simpleName }
            Log.d(TAG, "method: ${m.name}($params) -> ${m.returnType.simpleName}")
        }
        Log.d(TAG, "==== dumpPaymentMethods END ====")
    }
    
    override suspend fun initiatePayment(
        amountCents: Int,
        onPaymentResult: (PaymentResult) -> Unit
    ) {
        // 重置一次性结算门闩（新支付开始时）
        finished.set(false)
        pollJob = null
        timeoutJob = null
        currentTxNo = null  // 重置交易编号
        // 注意：pmReplyDumped 不重置，保持全局只 dump 一次
        // 注意：notifyResultMissingLogged 不重置，保持全局只警告一次
        initPaymentStartTime = 0L
        firstNonZeroPayTypeTime = 0L
        firstNonZeroMultipleTime = 0L
        
        // 使用局部变量避免 smart cast 问题
        val localBoard = board ?: run {
            Log.e(TAG, "POS 设备未初始化，无法发起支付 (board==null)")
            onPaymentResult(PaymentResult.Failure("POS 设备未初始化"))
            _paymentStatus.value = PaymentStatus.FAILED
            return
        }
        
        if (!isInitialized) {
            Log.e(TAG, "POS 设备未初始化，无法发起支付")
            onPaymentResult(PaymentResult.Failure("POS 设备未初始化"))
            return
        }
        
        // 硬判断：如果设备确实没有外挂 POS，直接返回失败
        if (!hasPOSCapability) {
            Log.e(TAG, "未检测到外挂 POS 设备，无法发起支付")
            onPaymentResult(PaymentResult.Failure("未检测到外挂 POS 设备，请检查设备连接"))
            _paymentStatus.value = PaymentStatus.FAILED
            return
        }
        
        Log.d(TAG, "========== 发起 POS 支付 ==========")
        Log.d(TAG, "支付金额: ${amountCents}分 (${amountCents / 100.0}€)")
        
        currentPaymentAmountCents = amountCents
        paymentCallback = onPaymentResult
        _paymentStatus.value = PaymentStatus.PROCESSING
        
        try {
            // 检查 SDK 类是否存在（用于决定是否走模拟模式）
            val isSdkAvailable = try {
                Class.forName("cc.uling.usdk.board.mdb.para.MPReplyPara")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
            
            if (!isSdkAvailable) {
                // 如果 SDK 未加载，使用模拟模式
                Log.w(TAG, "USDK SDK 未加载，使用模拟模式")
                delay(2000)
                onPaymentResult(PaymentResult.Success(amountCents))
                _paymentStatus.value = PaymentStatus.SUCCESS
                return
            }
            
            val boardClass = localBoard::class.java
            
            // 1. 获取最小面额基数
            val mpReplyClass = try {
                Class.forName("cc.uling.usdk.board.mdb.para.MPReplyPara")
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "USDK SDK 类未找到，使用模拟模式")
                delay(2000)
                onPaymentResult(PaymentResult.Success(amountCents))
                _paymentStatus.value = PaymentStatus.SUCCESS
                return
            }
            
            val mpReply = mpReplyClass.getConstructor().newInstance()
            
            try {
                val getMinPayoutAmountMethod = boardClass.getMethod("getMinPayoutAmount", mpReplyClass)
                getMinPayoutAmountMethod.invoke(localBoard, mpReply)
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "未找到 getMinPayoutAmount 方法签名：${e.message}", e)
                // 执行详细的 USDK 诊断（强制 dump）
                val reason = "GET_MIN_PAYOUT_AMOUNT_NOSUCHMETHOD"
                UsdkDebugDump.dumpAll(context, localBoard, reason = reason, force = true)
                throw e
            }
            
            val mpIsOK = mpReplyClass.getMethod("isOK").invoke(mpReply) as? Boolean ?: false
            if (!mpIsOK) {
                val mpGetResultCodeMethod = mpReplyClass.getMethod("getResultCode")
                val errorCode = mpGetResultCodeMethod.invoke(mpReply) as? Int ?: -1
                Log.e(TAG, "获取最小面额失败: errorCode=$errorCode")
                onPaymentResult(PaymentResult.Failure("获取最小面额失败 (errorCode=$errorCode)"))
                return
            }
            
            // 根据厂商确认：最小面额固定 0.01，3.50€ 对应 multiple=350
            // 所以 multiple 直接等于 amountCents（金额以分为单位）
            // 例如：3.50€ = 350 分 = multiple=350
            val targetMultiple = amountCents
            
            Log.d(TAG, "金额单位: 分（cents）")
            Log.d(TAG, "支付金额: ${amountCents}分 (${amountCents / 100.0}元)")
            Log.d(TAG, "目标倍数: $targetMultiple（根据厂商：最小面额固定 0.01，multiple=amountCents）")
            
            // 2. 设置支付通道为 POS 模式（mode=2）
            // 根据厂商确认：setPayChannel "仅需一次"，但我们可以每次支付前再设置一次保证确定性
            // POS=2, 自动=0, 现金=1（根据常见 MDB 协议）
            try {
                // 尝试查找 setPayChannel 方法
                val setPayChannelMethod = try {
                    boardClass.getMethod("setPayChannel", Int::class.javaPrimitiveType)
                } catch (e: NoSuchMethodException) {
                    // 尝试其他可能的方法名
                    try {
                        boardClass.getMethod("setChannel", Int::class.javaPrimitiveType)
                    } catch (e2: NoSuchMethodException) {
                        null
                    }
                }
                
                if (setPayChannelMethod != null) {
                    val posMode = 2  // POS 模式 = 2
                    setPayChannelMethod.invoke(localBoard, posMode)
                    Log.d(TAG, "设置支付通道成功: POS 模式 (mode=$posMode)")
                } else {
                    Log.w(TAG, "未找到 setPayChannel/setChannel 方法，使用默认通道（自动选择）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "设置支付通道失败: ${e.message}，继续使用默认通道")
            }
            
            // 3. 发起收款（initPayment）
            // 根据厂商回复：使用 UBoard.initPayment(IPReplyPara)
            // 发起支付只需：商品编号 + 金额（无商品编号可用 0；最小金额 0.01）
            // 调用 initPayment 即代表交易开始
            // 使用 IPReplyPara 作为 initPayment 的参数
            val ipReplyClass = try {
                Class.forName("cc.uling.usdk.board.mdb.para.IPReplyPara")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "IPReplyPara 类未找到，尝试使用 MPReplyPara", e)
                // 如果 IPReplyPara 不存在，fallback 到 MPReplyPara
                val fallbackClass = mpReplyClass
                val fallbackInstance = fallbackClass.getConstructor().newInstance()
                val okValue = reflectSetProperty(fallbackInstance, listOf("multiple", "mult", "value", "amount", "amt", "money", "price", "sum"), targetMultiple)
                if (!okValue) {
                    Log.e(TAG, "无法写入金额字段（核心字段），终止支付")
                    onPaymentResult(PaymentResult.Failure("无法写入金额字段"))
                    return
                }
                try {
                    val method = boardClass.getMethod("initPayment", fallbackClass)
                    method.invoke(localBoard, fallbackInstance)
                    // 检查结果
                    val initIsOK = fallbackClass.getMethod("isOK").invoke(fallbackInstance) as? Boolean ?: false
                    if (!initIsOK) {
                        val errorCode = fallbackClass.getMethod("getResultCode").invoke(fallbackInstance) as? Int ?: -1
                        Log.e(TAG, "发起收款失败: errorCode=$errorCode")
                        onPaymentResult(PaymentResult.Failure("发起收款失败 (errorCode=$errorCode)"))
                        return
                    }
                    Log.d(TAG, "发起收款成功: 目标倍数=$targetMultiple")
                } catch (e: NoSuchMethodException) {
                    Log.e(TAG, "未找到目标支付方法签名：${e.message}", e)
                    UsdkDebugDump.dumpAll(context, localBoard, reason = "INIT_PAYMENT_NOSUCHMETHOD", force = true)
                    throw e
                }
                // 继续后续流程（跳过 IPReplyPara 的处理）
                null
            }
            
            // 如果 IPReplyPara 存在，使用它
            if (ipReplyClass != null) {
                Log.d(TAG, "使用 IPReplyPara 作为 initPayment 参数")
                
                // 先 dump IPReplyPara 的成员和构造函数，方便确认字段
                UsdkDebugDump.dumpClassMembers("cc.uling.usdk.board.mdb.para.IPReplyPara")
                UsdkDebugDump.dumpConstructors("cc.uling.usdk.board.mdb.para.IPReplyPara")
                
                // 使用通用方法创建 IPReplyPara 实例
                // 根据厂商回复：无商品编号可用 0
                // 但为了避免 Validation code mismatch，使用自增交易编号
                txNoCounter++
                val no: Short = txNoCounter  // 使用自增交易编号，每笔交易唯一
                currentTxNo = no  // 保存当前交易的 no，用于后续轮询/取消
                Log.d(TAG, "使用交易编号: no=$no (自增计数器)")
                val ip = createIpReplyInstance(ipReplyClass, no, targetMultiple)
                
                if (ip == null) {
                    Log.e(TAG, "无法创建 IPReplyPara 实例，终止支付")
                    onPaymentResult(PaymentResult.Failure("无法创建 IPReplyPara 实例"))
                    return
                }
                
                // 强制写入 no 和 multiple（因为 dump 已经证明这两个 setter 存在）
                // 先调用 setNo(short)（如果有）
                try {
                    val setNoMethod = ipReplyClass.getMethod("setNo", Short::class.javaPrimitiveType)
                    setNoMethod.invoke(ip, no)
                    Log.d(TAG, "成功调用 setNo($no)")
                } catch (e: NoSuchMethodException) {
                    // 如果 setNo 不存在，尝试其他方式
                    val okNo = reflectSetProperty(ip, listOf("no", "number", "num", "id", "channel"), no)
                    if (!okNo) {
                        Log.w(TAG, "IPReplyPara no 字段无法写入（可能为只读），继续流程")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "调用 setNo 失败: ${e.message}")
                }
                
                // 再调用 setMultiple(int)（如果有）
                try {
                    val setMultipleMethod = ipReplyClass.getMethod("setMultiple", Int::class.javaPrimitiveType)
                    setMultipleMethod.invoke(ip, targetMultiple)
                    Log.d(TAG, "成功调用 setMultiple($targetMultiple)")
                } catch (e: NoSuchMethodException) {
                    // 如果 setMultiple 不存在，尝试其他方式
                    val okMultiple = reflectSetProperty(ip, listOf("multiple", "mult"), targetMultiple)
                    if (!okMultiple) {
                        Log.e(TAG, "无法写入金额字段（核心字段），终止支付")
                        onPaymentResult(PaymentResult.Failure("无法写入金额字段"))
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "调用 setMultiple 失败: ${e.message}")
                    onPaymentResult(PaymentResult.Failure("无法写入金额字段: ${e.message}"))
                    return
                }
                
                // 调用 initPayment(IPReplyPara)
                try {
                    // 时间点日志：initPayment 起点
                    initPaymentStartTime = System.currentTimeMillis()
                    firstNonZeroPayTypeTime = 0L
                    firstNonZeroMultipleTime = 0L
                    Log.d(TAG, "⏱️ [时间点] initPayment 起点: ${initPaymentStartTime}")
                    
                    val initPaymentMethod = boardClass.getMethod("initPayment", ipReplyClass)
                    initPaymentMethod.invoke(localBoard, ip)
                    
                    // 检查 IPReplyPara 的状态
                    val ipIsOK = try {
                        ipReplyClass.getMethod("isOK").invoke(ip) as? Boolean ?: false
                    } catch (e: NoSuchMethodException) {
                        Log.w(TAG, "isOK 方法不存在，尝试其他方式")
                        false
                    }
                    
                    // 尝试从 IPReplyPara 读取返回的 no（如果有）
                    try {
                        val getNoMethod = ipReplyClass.getMethod("getNo")
                        val returnedNo = getNoMethod.invoke(ip) as? Short
                        if (returnedNo != null && returnedNo != 0.toShort()) {
                            currentTxNo = returnedNo
                            Log.d(TAG, "从 IPReplyPara 读取返回的 no: $returnedNo")
                        }
                    } catch (e: NoSuchMethodException) {
                        // getNo 不存在，继续使用我们设置的 no
                    } catch (e: Exception) {
                        Log.w(TAG, "读取 IPReplyPara.getNo() 失败: ${e.message}")
                    }
                    
                    // 尝试从其他字段读取 no/reg/extData
                    try {
                        val reg = tryGetFieldValueViaGetter(ip, listOf("reg", "Reg", "REG", "validation", "Validation", "VALIDATION"))
                        if (reg != null) {
                            Log.d(TAG, "从 IPReplyPara 读取 reg/validation: $reg")
                            // 如果 reg 是 Short 类型，保存为 currentTxNo
                            if (reg is Short) {
                                currentTxNo = reg
                            } else if (reg is Number) {
                                currentTxNo = reg.toShort()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "读取 IPReplyPara reg/validation 失败: ${e.message}")
                    }
                    
                    if (!ipIsOK) {
                        val errorCode = try {
                            ipReplyClass.getMethod("getResultCode").invoke(ip) as? Int ?: -1
                        } catch (e: NoSuchMethodException) {
                            -1
                        }
                        val errorMsg = try {
                            ipReplyClass.getMethod("getErrorMsg").invoke(ip) as? String ?: ""
                        } catch (e: NoSuchMethodException) {
                            ""
                        }
                        Log.e(TAG, "发起收款失败: errorCode=$errorCode, errorMsg=$errorMsg")
                        onPaymentResult(PaymentResult.Failure("发起收款失败 (errorCode=$errorCode${if (errorMsg.isNotEmpty()) ", $errorMsg" else ""})"))
                        return
                    }
                    
                    Log.d(TAG, "发起收款成功: 目标倍数=$targetMultiple, 交易编号=$currentTxNo")
                } catch (e: NoSuchMethodException) {
                    Log.e(TAG, "未找到目标支付方法签名：${e.message}", e)
                    // 执行详细的 USDK 诊断（强制 dump，这是最关键的）
                    val reason = "INIT_PAYMENT_NOSUCHMETHOD"
                    UsdkDebugDump.dumpAll(context, localBoard, reason = reason, force = true)
                    throw e
                }
            }
            
            // 4. 轮询支付状态（getPayAmount）
            val pmReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PMReplyPara")
            
            // 从 PMReplyPara 类里读取 POS 常量（不要写死 payType==4）
            val POS_CONST = resolvePayTypeConst("POS")
            Log.d(TAG, "POS 常量值: $POS_CONST (从 PMReplyPara/UBoard 读取)")
            
            // 首次收到 PMReplyPara 时，dump 其字段和方法（全局只 dump 一次）
            if (!pmReplyDumped.getAndSet(true)) {
                Log.w(TAG, "================ 首次收到 PMReplyPara，开始 dump ================")
                UsdkDebugDump.dumpClassMembers("cc.uling.usdk.board.mdb.para.PMReplyPara")
                Log.w(TAG, "================ PMReplyPara dump 完成 ================")
            }
            
            val startTime = System.currentTimeMillis()
            
            // 启动超时任务（显式 Job，以便可以取消）
            timeoutJob = paymentScope.launch {
                delay(PAYMENT_TIMEOUT_MS)
                // 超时分支在执行前必须再次检查门闩（防止刚成功就超时回调抢进来）
                if (!finished.get()) {
                    if (finishOnce("TIMEOUT")) {
                        Log.e(TAG, "========== POS 支付超时 ==========")
                        Log.e(TAG, "超时时间: ${PAYMENT_TIMEOUT_MS}ms (${PAYMENT_TIMEOUT_MS / 1000}秒)")
                        Log.e(TAG, "ResultCode: 超时（TIMEOUT）- MDB 支付板固定 90s 超时")
                        
                        // 根据厂商流程：超时视为取消，notifyResult(false) → notifyPayment(取消)
                        // 1. 先调用 notifyResult(false)
                        try {
                            val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                            notifyResultMethod.invoke(localBoard, false)
                            Log.d(TAG, "notifyResult(false) 调用成功（超时）")
                        } catch (e: NoSuchMethodException) {
                            Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                        } catch (e: Exception) {
                            Log.w(TAG, "notifyResult 调用失败: ${e.message}")
                        }
                        
                        // 2. 再调用 notifyPayment(false) 取消收款
                        val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                        val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(false)  // false=取消收款
                        
                        val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                        notifyPaymentMethod.invoke(localBoard, payReply)
                        
                        // 检查通知结果
                        val payIsOK = payReplyClass.getMethod("isOK").invoke(payReply) as? Boolean ?: false
                        if (!payIsOK) {
                            val errorCode = try {
                                payReplyClass.getMethod("getResultCode").invoke(payReply) as? Int ?: -1
                            } catch (e: Exception) {
                                -1
                            }
                            Log.w(TAG, "notifyPayment(false) 失败: errorCode=$errorCode")
                        } else {
                            Log.d(TAG, "notifyPayment(false) 成功，超时取消流程完成")
                        }
                        
                        onPaymentResult(PaymentResult.Cancelled("TIMEOUT: 支付超时（${PAYMENT_TIMEOUT_MS/1000}秒内未完成支付，MDB 支付板固定 90s 超时）"))
                        _paymentStatus.value = PaymentStatus.CANCELLED
                    }
                } else {
                    Log.d(TAG, "超时任务触发，但支付已完成，跳过超时处理")
                }
            }
            
            // 轮询必须在后台线程（IO），避免 UI 卡顿
            pollJob = paymentScope.launch {
                while (System.currentTimeMillis() - startTime < PAYMENT_TIMEOUT_MS && !finished.get()) {
                    val pmReply = pmReplyClass.getConstructor().newInstance()
                    
                    val getPayAmountMethod = boardClass.getMethod("getPayAmount", pmReplyClass)
                    getPayAmountMethod.invoke(localBoard, pmReply)
                    
                    // 解析 PMReplyPara 状态（优先使用 resultCode + errorMsg）
                    val pmState = parsePmState(pmReply, pmReplyClass)
                    
                    // 必须新增的日志：每次轮询打印 resultCode 和 errorMsg
                    Log.d(TAG, "========== 轮询支付状态 ==========")
                    Log.d(TAG, "resultCode=${pmState.resultCode}, errorMsg=${if (pmState.errorMsg.isNotEmpty()) pmState.errorMsg else "(空)"}")
                    
                    // 当 status==0 时，fault 字段标注为 ignored 或不打印
                    if (pmState.status == 0) {
                        Log.d(TAG, "isOK=${pmState.isOK}, payType=${pmState.payType}, status=${pmState.status}, multiple=${pmState.multiple}, cancel=${pmState.cancel}, fault=${pmState.fault} (ignored, status=0)")
                    } else {
                        Log.d(TAG, "isOK=${pmState.isOK}, payType=${pmState.payType}, status=${pmState.status}, multiple=${pmState.multiple}, cancel=${pmState.cancel}, fault=${pmState.fault}")
                    }
                    
                    // 时间点日志：payType 首次非 0
                    if (pmState.payType != 0 && firstNonZeroPayTypeTime == 0L) {
                        firstNonZeroPayTypeTime = System.currentTimeMillis()
                        val elapsed = firstNonZeroPayTypeTime - initPaymentStartTime
                        Log.d(TAG, "⏱️ [时间点] payType 首次非 0: payType=${pmState.payType}, 耗时=${elapsed}ms (距 initPayment 起点)")
                    }
                    
                    // 时间点日志：multiple 首次>0
                    if (pmState.multiple > 0 && firstNonZeroMultipleTime == 0L) {
                        firstNonZeroMultipleTime = System.currentTimeMillis()
                        val elapsed = firstNonZeroMultipleTime - initPaymentStartTime
                        Log.d(TAG, "⏱️ [时间点] multiple 首次>0: multiple=${pmState.multiple}, 耗时=${elapsed}ms (距 initPayment 起点)")
                    }
                    
                    // 映射 ResultCode 到状态类型
                    val resultCodeType = mapResultCode(pmState.resultCode)
                    Log.d(TAG, "ResultCode 映射: $resultCodeType")
                    
                    // resultCode=215 直接判失败并停止轮询
                    // 若 pmState.resultCode == 215：立刻 finishOnce("FAILED")、cancelAllJobs()、回调 Failure("Validation code does not match")
                    if (pmState.resultCode == 215) {
                        // 一次性结算门闩检查（第一行）
                        if (!finishOnce("FAILED")) {
                            Log.w(TAG, "交易校验不匹配，但已被其他分支完成，跳过重复结算")
                            return@launch
                        }
                        
                        Log.e(TAG, "========== POS 支付失败：交易校验不匹配 ==========")
                        Log.e(TAG, "resultCode=215: Validation code does not match")
                        Log.e(TAG, "errorMsg=${pmState.errorMsg}")
                        Log.e(TAG, "可能原因：交易编号(no)不匹配，或交易已过期")
                        
                        // 失败后立即取消所有后台任务
                        cancelAllJobs()
                        
                        // 根据厂商流程：失败时 notifyResult(false) → notifyPayment(取消)
                        try {
                            val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                            notifyResultMethod.invoke(localBoard, false)
                            Log.d(TAG, "notifyResult(false) 调用成功（校验失败）")
                        } catch (e: NoSuchMethodException) {
                            Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                        } catch (e: Exception) {
                            Log.w(TAG, "notifyResult 调用失败: ${e.message}")
                        }
                        
                        val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                        val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(false)
                        val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                        notifyPaymentMethod.invoke(localBoard, payReply)
                        
                        onPaymentResult(PaymentResult.Failure("交易校验不匹配 (resultCode=215, Validation code does not match): ${pmState.errorMsg}", 215))
                        _paymentStatus.value = PaymentStatus.FAILED
                        return@launch
                    }
                    
                    // 轮询停止条件（强制落地）
                    // 1. 成功：payType==POS_CONST && multiple>=targetMultiple && cancel==0 && status==0
                    if (pmState.payType == POS_CONST && pmState.multiple >= targetMultiple && pmState.cancel == 0 && pmState.status == 0) {
                        // 一次性结算门闩检查（第一行）
                        if (!finishOnce("SUCCESS")) {
                            Log.w(TAG, "支付成功，但已被其他分支完成，跳过重复结算")
                            return@launch
                        }
                        
                        Log.d(TAG, "========== POS 支付成功 ==========")
                        Log.d(TAG, "支付倍数: ${pmState.multiple} (目标: $targetMultiple)")
                        Log.d(TAG, "resultCode=${pmState.resultCode}, errorMsg=${pmState.errorMsg}")
                        
                        // 时间点日志：最终成功
                        val finalTime = System.currentTimeMillis()
                        val elapsed = finalTime - initPaymentStartTime
                        Log.d(TAG, "⏱️ [时间点] 最终成功: 耗时=${elapsed}ms (距 initPayment 起点)")
                        
                        // 成功结算后立即取消所有后台任务
                        cancelAllJobs()
                        
                        // 根据厂商流程：达账后 notifyResult(true) → notifyPayment(完成)
                        // 1. 先调用 notifyResult(true)
                        try {
                            val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                            notifyResultMethod.invoke(localBoard, true)
                            Log.d(TAG, "notifyResult(true) 调用成功")
                        } catch (e: NoSuchMethodException) {
                            Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                        } catch (e: Exception) {
                            Log.w(TAG, "notifyResult 调用失败: ${e.message}")
                        }
                        
                        // 2. 再调用 notifyPayment(true) 完成收款
                        val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                        val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(true)  // true=完成收款
                        
                        val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                        notifyPaymentMethod.invoke(localBoard, payReply)
                        
                        val payIsOK = payReplyClass.getMethod("isOK").invoke(payReply) as? Boolean ?: false
                        if (!payIsOK) {
                            Log.w(TAG, "notifyPayment(true) 失败")
                        } else {
                            Log.d(TAG, "notifyPayment(true) 成功，支付流程完成")
                        }
                        
                        onPaymentResult(PaymentResult.Success(amountCents))
                        _paymentStatus.value = PaymentStatus.SUCCESS
                        return@launch
                    }
                    
                    // 2. 取消：cancel!=0（含 POS/屏幕取消）或 ResultCode 表示取消
                    if (pmState.cancel != 0 || resultCodeType == "CANCELLED") {
                        // 一次性结算门闩检查（第一行）
                        if (!finishOnce("CANCELLED")) {
                            Log.w(TAG, "支付取消，但已被其他分支完成，跳过重复结算")
                            return@launch
                        }
                        
                        val cancelReason = when {
                            pmState.cancel == 4 -> "POS_CANCEL: POS 端取消"
                            resultCodeType == "CANCELLED" -> "CANCELLED: ResultCode=${pmState.resultCode}"
                            else -> "CANCELLED: cancel=${pmState.cancel}, resultCode=${pmState.resultCode}"
                        }
                        Log.d(TAG, "========== POS 支付取消 ==========")
                        Log.d(TAG, "取消原因: $cancelReason")
                        Log.d(TAG, "resultCode=${pmState.resultCode}, errorMsg=${pmState.errorMsg}")
                        
                        // 时间点日志：最终取消
                        val finalTime = System.currentTimeMillis()
                        val elapsed = finalTime - initPaymentStartTime
                        Log.d(TAG, "⏱️ [时间点] 最终取消: 耗时=${elapsed}ms (距 initPayment 起点)")
                        
                        // 取消后立即取消所有后台任务
                        cancelAllJobs()
                        
                        // 根据厂商流程：取消时 notifyResult(false) → notifyPayment(取消)
                        // 1. 先调用 notifyResult(false)
                        try {
                            val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                            notifyResultMethod.invoke(localBoard, false)
                            Log.d(TAG, "notifyResult(false) 调用成功")
                        } catch (e: NoSuchMethodException) {
                            // notifyResult NoSuchMethodException：只在第一次打印 warn，之后降级为 debug
                            if (notifyResultMissingLogged.compareAndSet(false, true)) {
                                Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                            } else {
                                Log.d(TAG, "notifyResult 方法不存在，跳过（已记录，不再警告）")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "notifyResult 调用失败: ${e.message}")
                        }
                        
                        // 2. 再调用 notifyPayment(false) 取消收款
                        val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                        val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(false)  // false=取消收款
                        
                        val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                        notifyPaymentMethod.invoke(localBoard, payReply)
                        
                        val payIsOK = payReplyClass.getMethod("isOK").invoke(payReply) as? Boolean ?: false
                        if (!payIsOK) {
                            val notifyErrorCode = try {
                                payReplyClass.getMethod("getResultCode").invoke(payReply) as? Int ?: -1
                            } catch (e: Exception) {
                                -1
                            }
                            Log.w(TAG, "notifyPayment(false) 失败: errorCode=$notifyErrorCode")
                        } else {
                            Log.d(TAG, "notifyPayment(false) 成功，取消流程完成")
                        }
                        
                        onPaymentResult(PaymentResult.Cancelled("$cancelReason (errorMsg=${pmState.errorMsg})"))
                        _paymentStatus.value = PaymentStatus.CANCELLED
                        return@launch
                    }
                    
                    // 3. 失败：status==1 或 ResultCode 映射为失败
                    // 故障判断规则：status==1 才检查 fault（status==0 时忽略 fault）
                    if (pmState.status == 1 || resultCodeType == "FAILED") {
                        // 一次性结算门闩检查（第一行）
                        if (!finishOnce("FAILED")) {
                            Log.w(TAG, "支付失败，但已被其他分支完成，跳过重复结算")
                            return@launch
                        }
                        
                        val faultMsg = if (pmState.status == 1) {
                            // status==1 时才检查 fault
                            when (pmState.fault) {
                                0 -> "支付板未连接任何支付设备"
                                1 -> "硬币器故障"
                                2 -> "纸币器故障"
                                3 -> "硬币器/纸币器故障"
                                4 -> "POS 机故障"
                                else -> "未知故障 (fault=${pmState.fault})"
                            }
                        } else {
                            // ResultCode 映射为失败
                            "ResultCode=${pmState.resultCode}${if (pmState.errorMsg.isNotEmpty()) ": ${pmState.errorMsg}" else ""}"
                        }
                        
                        Log.e(TAG, "========== POS 支付失败 ==========")
                        Log.e(TAG, "失败原因: $faultMsg")
                        // 当 status==0 时，不打印 fault（避免误导）
                        if (pmState.status == 0) {
                            Log.e(TAG, "status=${pmState.status}, resultCode=${pmState.resultCode}, errorMsg=${pmState.errorMsg} (fault 字段已忽略，因为 status=0)")
                        } else {
                            Log.e(TAG, "status=${pmState.status}, fault=${pmState.fault}, resultCode=${pmState.resultCode}, errorMsg=${pmState.errorMsg}")
                        }
                        
                        // 时间点日志：最终失败
                        val finalTime = System.currentTimeMillis()
                        val elapsed = finalTime - initPaymentStartTime
                        Log.e(TAG, "⏱️ [时间点] 最终失败: 耗时=${elapsed}ms (距 initPayment 起点)")
                        
                        // 失败后立即取消所有后台任务
                        cancelAllJobs()
                        
                        // 根据厂商流程：失败时 notifyResult(false) → notifyPayment(取消)
                        // 1. 先调用 notifyResult(false)
                        try {
                            val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                            notifyResultMethod.invoke(localBoard, false)
                            Log.d(TAG, "notifyResult(false) 调用成功（失败）")
                        } catch (e: NoSuchMethodException) {
                            // notifyResult NoSuchMethodException：只在第一次打印 warn，之后降级为 debug
                            if (notifyResultMissingLogged.compareAndSet(false, true)) {
                                Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                            } else {
                                Log.d(TAG, "notifyResult 方法不存在，跳过（已记录，不再警告）")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "notifyResult 调用失败: ${e.message}")
                        }
                        
                        // 2. 再调用 notifyPayment(false) 取消收款
                        val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                        val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(false)  // false=取消收款
                        
                        val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                        notifyPaymentMethod.invoke(localBoard, payReply)
                        
                        val payIsOK = payReplyClass.getMethod("isOK").invoke(payReply) as? Boolean ?: false
                        if (!payIsOK) {
                            val notifyErrorCode = try {
                                payReplyClass.getMethod("getResultCode").invoke(payReply) as? Int ?: -1
                            } catch (e: Exception) {
                                -1
                            }
                            Log.w(TAG, "notifyPayment(false) 失败: errorCode=$notifyErrorCode")
                        } else {
                            Log.d(TAG, "notifyPayment(false) 成功，失败流程完成")
                        }
                        
                        onPaymentResult(PaymentResult.Failure("支付失败: $faultMsg", pmState.resultCode.takeIf { it > 0 }))
                        _paymentStatus.value = PaymentStatus.FAILED
                        return@launch
                    }
                    
                    // 4. 继续轮询（支付进行中或等待状态）
                    if (pmState.payType == POS_CONST) {
                        Log.d(TAG, "支付进行中: 已支付倍数=${pmState.multiple}, 目标倍数=$targetMultiple, payType=$POS_CONST")
                    } else if (pmState.payType == 0 && pmState.resultCode == 0) {
                        // payType=0 且 resultCode=0 可能是初始状态，继续等待
                        Log.d(TAG, "支付状态：payType=0, resultCode=0，继续等待 POS 设备响应...")
                    } else if (pmState.resultCode != 0) {
                        // resultCode!=0 表示有错误，但如果不是 215，可能是其他错误，记录日志但继续等待（除非是致命错误）
                        if (resultCodeType == "FAILED" || resultCodeType == "VALIDATION_MISMATCH") {
                            Log.w(TAG, "支付状态异常: payType=${pmState.payType}, resultCode=${pmState.resultCode}, errorMsg=${pmState.errorMsg}，但已处理，继续等待...")
                        } else {
                            Log.d(TAG, "支付状态: payType=${pmState.payType}, resultCode=${pmState.resultCode}, 继续等待...")
                        }
                    } else {
                        Log.d(TAG, "支付类型不是 POS 机: payType=${pmState.payType} (期望 $POS_CONST)，继续等待...")
                    }
                    
                    delay(POLL_INTERVAL_MS)
                }
            }
            
            // 启动 pollJob 与 timeoutJob 后必须等待完成
            // 用 join() 阻塞当前协程，直到 finished=true，再退出方法
            // 确保方法不会"启动任务就继续往下跑"
            Log.d(TAG, "等待轮询和超时任务完成...")
            pollJob?.join()
            timeoutJob?.join()
            Log.d(TAG, "轮询和超时任务已完成，退出支付流程")
            
        } catch (e: Exception) {
            Log.e(TAG, "发起 POS 支付异常", e)
            onPaymentResult(PaymentResult.Failure("支付异常: ${e.message}"))
            _paymentStatus.value = PaymentStatus.FAILED
        } finally {
            paymentCallback = null
        }
    }
    
    override suspend fun cancelPayment(): Boolean {
        Log.d(TAG, "========== 取消 POS 支付（UI_CANCEL） ==========")
        
        // 停止所有后台任务（轮询、超时）
        cancelAllJobs()
        
        // 使用一次性结算门闩，防止重复结算
        if (!finishOnce("CANCEL")) {
            Log.w(TAG, "支付流程已完成，跳过重复取消")
            return true
        }
        
        if (!isInitialized || board == null) {
            Log.e(TAG, "POS 设备未初始化，直接标记为取消")
            _paymentStatus.value = PaymentStatus.CANCELLED
            paymentCallback?.invoke(PaymentResult.Cancelled("UI_CANCEL: 用户从屏幕取消"))
            paymentCallback = null
            return true
        }
        
        try {
            val localBoard = board ?: run {
                // 如果 SDK 未加载，使用模拟模式
                Log.w(TAG, "USDK SDK 未加载，使用模拟模式取消")
                _paymentStatus.value = PaymentStatus.CANCELLED
                paymentCallback?.invoke(PaymentResult.Cancelled("UI_CANCEL: 用户从屏幕取消"))
                paymentCallback = null
                return true
            }
            
            // 根据厂商流程：用户取消时 notifyResult(false) → notifyPayment(取消)
            val boardClass = localBoard::class.java
            
            // 尝试调用设备侧取消方法（如果 SDK 支持）
            var deviceCancelSupported = false
            try {
                // 优先尝试：cancelPayment() / cancel() / stopPay() / abort()
                val cancelMethodCandidates = listOf("cancelPayment", "cancel", "stopPay", "abort")
                for (methodName in cancelMethodCandidates) {
                    try {
                        val cancelMethod = boardClass.getMethod(methodName)
                        cancelMethod.invoke(localBoard)
                        Log.d(TAG, "设备侧取消方法调用成功: $methodName()")
                        deviceCancelSupported = true
                        break
                    } catch (_: NoSuchMethodException) {
                        // 继续尝试下一个
                    }
                }
                
                if (!deviceCancelSupported) {
                    Log.d(TAG, "设备侧取消方法不存在，使用本地取消（停止轮询 + 回调取消）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "尝试调用设备侧取消方法异常: ${e.message}")
                // 继续执行本地取消逻辑
            }
            
            // 1. 先调用 notifyResult(false)（如果方法存在）
            try {
                val notifyResultMethod = boardClass.getMethod("notifyResult", Boolean::class.javaPrimitiveType)
                notifyResultMethod.invoke(localBoard, false)
                Log.d(TAG, "notifyResult(false) 调用成功（UI 取消）")
            } catch (e: NoSuchMethodException) {
                // notifyResult NoSuchMethodException：只在第一次打印 warn，之后降级为 debug
                if (notifyResultMissingLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "notifyResult 方法不存在，跳过（可能不是必需）")
                } else {
                    Log.d(TAG, "notifyResult 方法不存在，跳过（已记录，不再警告）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "notifyResult 调用失败: ${e.message}")
            }
            
            // 2. 再调用 notifyPayment(false) 取消收款
            try {
                val payReplyClass = Class.forName("cc.uling.usdk.board.mdb.para.PayReplyPara")
                val payReply = payReplyClass.getConstructor(Boolean::class.java).newInstance(false)  // false=取消收款
                
                val notifyPaymentMethod = boardClass.getMethod("notifyPayment", payReplyClass)
                notifyPaymentMethod.invoke(localBoard, payReply)
                
                val payIsOK = payReplyClass.getMethod("isOK").invoke(payReply) as? Boolean ?: false
                if (!payIsOK) {
                    val payGetResultCodeMethod = payReplyClass.getMethod("getResultCode")
                    val errorCode = payGetResultCodeMethod.invoke(payReply) as? Int ?: -1
                    Log.w(TAG, "notifyPayment(false) 返回失败: errorCode=$errorCode，但继续执行本地取消")
                } else {
                    Log.d(TAG, "notifyPayment(false) 调用成功（取消收款）")
                }
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "PayReplyPara 类未找到，跳过 notifyPayment 调用")
            } catch (e: Exception) {
                Log.w(TAG, "notifyPayment 调用失败: ${e.message}，但继续执行本地取消")
            }
            
            // 3. 本地取消：更新状态并回调
            Log.d(TAG, "取消支付成功（${if (deviceCancelSupported) "设备侧+本地" else "仅本地"}取消）")
            _paymentStatus.value = PaymentStatus.CANCELLED
            paymentCallback?.invoke(PaymentResult.Cancelled("用户取消"))
            paymentCallback = null
            return true
            
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "USDK SDK 类未找到，使用本地取消")
            _paymentStatus.value = PaymentStatus.CANCELLED
            paymentCallback?.invoke(PaymentResult.Cancelled("用户取消"))
            paymentCallback = null
            return true
        } catch (e: Exception) {
            Log.e(TAG, "取消 POS 支付异常", e)
            // 即使异常，也要执行本地取消
            _paymentStatus.value = PaymentStatus.CANCELLED
            paymentCallback?.invoke(PaymentResult.Cancelled("取消异常: ${e.message}"))
            paymentCallback = null
            return true
        }
    }
    
    override suspend fun getPaymentStatus(): PaymentStatus {
        return _paymentStatus.value
    }
    
    override suspend fun close() {
        Log.d(TAG, "========== 关闭 USDK POS 设备连接 ==========")
        Log.d(TAG, "当前串口: $currentCommId")
        
        try {
            if (board != null) {
                // 关闭串口
                val boardClass = board!!::class.java
                val closeDevMethod = boardClass.getMethod("EF_CloseDev")
                closeDevMethod.invoke(board)
                Log.d(TAG, "串口已关闭: $currentCommId")
            }
            board = null
            currentCommId = null
            
            isInitialized = false
            _paymentStatus.value = PaymentStatus.IDLE
            paymentCallback = null
            
            Log.d(TAG, "USDK POS 设备连接已关闭")
            
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "USDK SDK 类未找到，跳过关闭操作")
            board = null
            currentCommId = null
            isInitialized = false
            _paymentStatus.value = PaymentStatus.IDLE
            paymentCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭 USDK POS 设备连接异常", e)
            board = null
            currentCommId = null
            isInitialized = false
            _paymentStatus.value = PaymentStatus.IDLE
            paymentCallback = null
        }
    }
    
    /**
     * 获取当前连接的串口路径
     */
    fun getCurrentCommId(): String? = currentCommId
}
