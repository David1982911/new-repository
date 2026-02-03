package com.carwash.carpayment.data.pos

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USDK 反射诊断工具
 * 用于在运行时打印 SDK 的方法签名和参数类信息，帮助定位真实的 API 调用方式
 */
internal object UsdkDebugDump {
    private const val TAG = "UsdkDebugDump"
    private const val DEBUG_USDK_DUMP = true  // 可通过 BuildConfig.DEBUG 控制
    
    // 按 reason 去重，避免刷屏但不会错过关键点
    private val dumpedReasons = ConcurrentHashMap<String, AtomicBoolean>()
    
    /**
     * 打印 UBoard 支付相关方法签名
     * @param board UBoard 实例
     */
    fun dumpUBoardPaymentApis(board: Any) {
        if (!DEBUG_USDK_DUMP) return
        
        val cls = board.javaClass
        val keys = listOf("mp", "pay", "payment", "trade", "consume", "purchase", "pos", "pm")
        val ms = cls.methods
            .filter { m ->
                val n = m.name.lowercase()
                keys.any { k -> n.contains(k) }
            }
            .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
        
        Log.w(TAG, "================ DUMP UBoard Payment-like APIs ================")
        Log.w(TAG, "UBoard class: ${cls.name}")
        Log.w(TAG, "Matched methods: ${ms.size}")
        for (m in ms) {
            val params = m.parameterTypes.joinToString(", ") { it.name }
            Log.w(TAG, "UBoard#${m.name}($params) -> ${m.returnType.name}")
        }
        Log.w(TAG, "===============================================================")
    }
    
    /**
     * 打印类的成员（字段和方法）
     * @param className 类的全限定名
     */
    fun dumpClassMembers(className: String) {
        if (!DEBUG_USDK_DUMP) return
        
        try {
            val cls = Class.forName(className)
        Log.w(TAG, "================ DUMP Class Members: $className ================")
        
        // 打印 public fields
        val publicFields = cls.fields
        if (publicFields.isNotEmpty()) {
            Log.w(TAG, "Public fields (${publicFields.size}):")
            for (f in publicFields.sortedBy { it.name }) {
                Log.w(TAG, "$className field: ${f.name}:${f.type.simpleName}")
            }
        }
        
        // 打印 declared fields（包括 private）
        val declaredFields = cls.declaredFields
        if (declaredFields.isNotEmpty()) {
            Log.w(TAG, "Declared fields (${declaredFields.size}):")
            for (f in declaredFields.sortedBy { it.name }) {
                f.isAccessible = true
                Log.w(TAG, "$className declaredField: ${f.name}:${f.type.simpleName}")
            }
        }
        
        // 打印 public methods（重点输出 setter/getter）
        val publicMethods = cls.methods
            .filter { it.name.startsWith("set") || it.name.startsWith("get") || it.name.startsWith("is") }
            .sortedBy { it.name }
        
        if (publicMethods.isNotEmpty()) {
            Log.w(TAG, "Public methods (setters/getters, ${publicMethods.size}):")
            for (m in publicMethods) {
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                Log.w(TAG, "$className method: ${m.name}($params) -> ${m.returnType.simpleName}")
            }
        }
        
        Log.w(TAG, "===============================================================")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class NOT FOUND: $className")
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping class members: $className", e)
        }
    }
    
    /**
     * 打印类的构造函数签名
     * @param className 类的全限定名
     */
    fun dumpConstructors(className: String) {
        if (!DEBUG_USDK_DUMP) return
        
        try {
            val cls = Class.forName(className)
            val constructors = cls.declaredConstructors
            
            Log.w(TAG, "================ DUMP Constructors: $className ================")
            if (constructors.isEmpty()) {
                Log.w(TAG, "No constructors found")
            } else {
                Log.w(TAG, "Constructors (${constructors.size}):")
                for (ctor in constructors) {
                    ctor.isAccessible = true
                    val params = ctor.parameterTypes.joinToString(", ") { it.simpleName }
                    val modifiers = java.lang.reflect.Modifier.toString(ctor.modifiers)
                    Log.w(TAG, "  $modifiers ${cls.simpleName}($params)")
                }
            }
            Log.w(TAG, "===============================================================")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class NOT FOUND: $className")
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping constructors: $className", e)
        }
    }
    
    /**
     * 打印一组候选参数类的成员信息
     * @param prefix 类名前缀（如 "cc.uling.usdk.board.mdb.para"）
     */
    fun dumpParaCandidates(prefix: String) {
        if (!DEBUG_USDK_DUMP) return
        
        val candidateNames = listOf(
            "$prefix.MPPara",
            "$prefix.MPReqPara",
            "$prefix.MPOrderPara",
            "$prefix.MPRequestPara",
            "$prefix.MPInitPara",
            "$prefix.MPPayPara",
            "$prefix.MPReplyPara",
            "$prefix.IPReplyPara",
            "$prefix.PMReplyPara",
            "$prefix.PayReplyPara",
            "$prefix.HCReplyPara"
        )
        
        Log.w(TAG, "================ DUMP Para Candidates ================")
        Log.w(TAG, "Prefix: $prefix")
        
        for (className in candidateNames) {
            try {
                val cls = Class.forName(className)
                Log.w(TAG, "FOUND: $className")
                dumpClassMembers(className)
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "NOT FOUND: $className")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking class: $className", e)
            }
        }
        
        Log.w(TAG, "===============================================================")
    }
    
    /**
     * 执行完整的 USDK 诊断（UBoard 方法 + 参数类成员）
     * @param board UBoard 实例
     * @param prefix 参数类前缀
     * @param reason dump 原因（用于去重）
     * @param force 是否强制 dump（即使之前已经 dump 过）
     */
    fun dumpFullDiagnostics(
        board: Any,
        prefix: String = "cc.uling.usdk.board.mdb.para",
        reason: String = "MANUAL",
        force: Boolean = false
    ) {
        if (!DEBUG_USDK_DUMP) return
        
        // 按 reason 去重，除非 force=true
        if (!force) {
            val alreadyDumped = dumpedReasons.computeIfAbsent(reason) { AtomicBoolean(false) }
            if (alreadyDumped.get()) {
                Log.w(TAG, "Diagnostics already dumped for reason=$reason, skipping...")
                return
            }
            alreadyDumped.set(true)
        }
        
        Log.w(TAG, "================ USDK Full Diagnostics START (reason=$reason) ================")
        dumpUBoardPaymentApis(board)
        dumpParaCandidates(prefix)
        Log.w(TAG, "================ USDK Diagnostics END (reason=$reason) ================")
    }
    
    /**
     * 执行详细的 USDK 诊断（包含特定方法过滤和类扫描）
     * @param context Android Context（用于扫描 Dex）
     * @param board UBoard 实例
     * @param reason dump 原因
     * @param force 是否强制 dump
     * @param filterNames 要过滤的方法名列表
     */
    fun dumpAll(
        context: Context?,
        board: Any,
        reason: String = "MANUAL",
        force: Boolean = false,
        filterNames: List<String> = listOf("initPayment", "getPayAmount", "notifyPayment", "getMinPayoutAmount")
    ) {
        if (!DEBUG_USDK_DUMP) return
        
        // 按 reason 去重，除非 force=true
        if (!force) {
            val alreadyDumped = dumpedReasons.computeIfAbsent(reason) { AtomicBoolean(false) }
            if (alreadyDumped.get()) {
                Log.w(TAG, "Diagnostics already dumped for reason=$reason, skipping...")
                return
            }
            alreadyDumped.set(true)
        }
        
        Log.w(TAG, "================ USDK Detailed Diagnostics START (reason=$reason, force=$force) ================")
        
        // 1. 打印 UBoard 特定方法
        dumpUBoardMethods(board, filterNames)
        
        // 2. 打印所有支付相关方法
        dumpUBoardPaymentApis(board)
        
        // 3. 打印参数类成员
        dumpParaCandidates("cc.uling.usdk.board.mdb.para")
        
        // 4. 如果提供了 context，扫描 Dex 中的类
        if (context != null) {
            dumpUsdkClassesFromDex(context, keywordRegex = "(?i)(UBoard|MP|PM|Pay|Payment|pos)")
        }
        
        Log.w(TAG, "================ USDK Detailed Diagnostics END (reason=$reason) ================")
    }
    
    /**
     * 打印 UBoard 特定方法签名
     * @param board UBoard 实例
     * @param filterNames 要过滤的方法名列表
     */
    fun dumpUBoardMethods(board: Any, filterNames: List<String>) {
        if (!DEBUG_USDK_DUMP) return
        
        val cls = board.javaClass
        val methods = cls.methods
            .filter { m -> filterNames.any { filterName -> m.name.contains(filterName, ignoreCase = true) } }
            .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
        
        Log.w(TAG, "================ DUMP UBoard Specific Methods ================")
        Log.w(TAG, "UBoard class: ${cls.name}")
        Log.w(TAG, "Filter names: ${filterNames.joinToString(", ")}")
        Log.w(TAG, "Matched methods: ${methods.size}")
        for (m in methods) {
            val params = m.parameterTypes.joinToString(", ") { it.name }
            Log.w(TAG, "UBoard#${m.name}($params) -> ${m.returnType.name}")
        }
        Log.w(TAG, "===============================================================")
    }
    
    /**
     * 从 Dex 中扫描并打印 USDK 相关类
     * @param context Android Context
     * @param keywordRegex 关键词正则表达式
     */
    fun dumpUsdkClassesFromDex(context: Context, keywordRegex: String) {
        if (!DEBUG_USDK_DUMP) return
        
        try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            val appInfo = packageInfo.applicationInfo
            val dexPath = appInfo?.sourceDir ?: "unknown"
            
            Log.w(TAG, "================ DUMP USDK Classes from Dex ================")
            Log.w(TAG, "Dex path: $dexPath")
            Log.w(TAG, "Keyword regex: $keywordRegex")
            
            // 尝试从 classpath 中查找类
            val classLoader = context.classLoader
            val usdkPackage = "cc.uling.usdk"
            
            // 扫描已知的 USDK 包路径
            val candidatePackages = listOf(
                "$usdkPackage.board",
                "$usdkPackage.board.mdb",
                "$usdkPackage.board.mdb.para"
            )
            
            val foundClasses = mutableListOf<String>()
            for (pkg in candidatePackages) {
                try {
                    // 尝试加载包中的类
                    val classes = scanPackageForClasses(classLoader, pkg, keywordRegex)
                    foundClasses.addAll(classes)
                } catch (e: Exception) {
                    Log.w(TAG, "Error scanning package $pkg: ${e.message}")
                }
            }
            
            if (foundClasses.isNotEmpty()) {
                Log.w(TAG, "Found classes (${foundClasses.size}):")
                for (className in foundClasses.sorted()) {
                    Log.w(TAG, "  $className")
                }
            } else {
                Log.w(TAG, "No classes found matching regex: $keywordRegex")
            }
            
            Log.w(TAG, "===============================================================")
        } catch (e: Exception) {
            Log.w(TAG, "Error dumping classes from Dex: ${e.message}", e)
        }
    }
    
    /**
     * 扫描包中的类（简化实现，主要依赖反射）
     */
    private fun scanPackageForClasses(classLoader: ClassLoader, packageName: String, keywordRegex: String): List<String> {
        val foundClasses = mutableListOf<String>()
        val regex = keywordRegex.toRegex()
        
        // 已知的类名列表（从常见错误中推断）
        val knownClasses = listOf(
            "cc.uling.usdk.USDK",
            "cc.uling.usdk.board.UBoard",
            "cc.uling.usdk.board.mdb.para.MPPara",
            "cc.uling.usdk.board.mdb.para.MPReqPara",
            "cc.uling.usdk.board.mdb.para.MPOrderPara",
            "cc.uling.usdk.board.mdb.para.MPRequestPara",
            "cc.uling.usdk.board.mdb.para.MPInitPara",
            "cc.uling.usdk.board.mdb.para.MPPayPara",
            "cc.uling.usdk.board.mdb.para.MPReplyPara",
            "cc.uling.usdk.board.mdb.para.PMReplyPara",
            "cc.uling.usdk.board.mdb.para.PayReplyPara",
            "cc.uling.usdk.board.mdb.para.HCReplyPara"
        )
        
        for (className in knownClasses) {
            if (className.startsWith(packageName) && regex.containsMatchIn(className)) {
                try {
                    Class.forName(className, false, classLoader)
                    foundClasses.add(className)
                } catch (e: ClassNotFoundException) {
                    // 类不存在，跳过
                } catch (e: Exception) {
                    // 其他错误，记录但继续
                    Log.w(TAG, "Error loading class $className: ${e.message}")
                }
            }
        }
        
        return foundClasses
    }
    
    /**
     * 重置 dump 标志（用于测试或重新诊断）
     */
    fun resetDumpFlag(reason: String? = null) {
        if (reason != null) {
            dumpedReasons.remove(reason)
        } else {
            dumpedReasons.clear()
        }
    }
}
