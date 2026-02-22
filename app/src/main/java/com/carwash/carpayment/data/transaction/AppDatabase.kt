package com.carwash.carpayment.data.transaction

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carwash.carpayment.R
import com.carwash.carpayment.data.database.Migrations
import com.carwash.carpayment.data.user.UserEntity
import com.carwash.carpayment.data.user.UserDao
import com.carwash.carpayment.data.user.UserRole
import com.carwash.carpayment.data.washmode.WashMode
import com.carwash.carpayment.data.washmode.WashModeDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 应用数据库（V3.4 规范：包含 User 表）
 */
@Database(
    entities = [Transaction::class, WashMode::class, UserEntity::class],
    version = 4,  // 修复 Room schema hash 冲突
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun transactionDao(): TransactionDao
    abstract fun washModeDao(): WashModeDao
    abstract fun userDao(): UserDao  // V3.4: 添加 User DAO
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * 数据库迁移：从版本1到版本2（添加 imageResId 字段）
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                android.util.Log.d("HomeDebug", "执行迁移 1->2: 添加 imageResId 列")
                database.execSQL("ALTER TABLE wash_mode ADD COLUMN imageResId INTEGER")
            }
        }
        
        /**
         * 数据库迁移：从版本2到版本3（V3.4: 添加 User 表）
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                android.util.Log.d("AppDatabase", "执行迁移 2->3: 添加 users 表")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS users (
                        userId TEXT PRIMARY KEY NOT NULL,
                        username TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        role TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_users_username ON users(username)")
            }
        }
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "car_wash_payment_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, Migrations.MIGRATION_3_4)  // 添加迁移脚本（包含 3->4）
                    // ⚠️ 移除 fallbackToDestructiveMigration()，使用迁移策略修复 schema hash 冲突
                    .addCallback(DatabaseCallback(context))
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * 数据库回调：在创建时插入默认数据
         */
        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                android.util.Log.d("HomeDebug", "========== 数据库创建，准备插入默认数据 ==========")
                applicationScope.launch {
                    populateDatabase(context)
                }
            }
            
            /**
             * 新增：每次数据库打开时执行
             * 确保即使数据库已存在但没有用户（例如通过迁移升级后没有插入用户），也会在第一次打开时补上
             */
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                android.util.Log.d("HomeDebug", "========== 数据库打开，检查并确保默认用户 ==========")
                applicationScope.launch {
                    ensureDefaultUser(context)
                }
            }
            
            private suspend fun populateDatabase(context: Context) {
                try {
                    android.util.Log.d("HomeDebug", "========== DatabaseCallback.onCreate: 开始插入默认数据 ==========")
                    android.util.Log.d("HomeDebug", "当前线程: ${Thread.currentThread().name}")
                    
                    val database = getDatabase(context)
                    val washModeDao = database.washModeDao()
                    
                    // ⚠️ 幂等性检查：使用 count() 快速检查（比 getAllWashModes().first() 更高效）
                    val count = washModeDao.count()
                    android.util.Log.d("HomeDebug", "检查现有数据: count=$count")
                    
                    if (count == 0) {
                        android.util.Log.d("HomeDebug", "数据库为空，插入默认数据...")
                        // 插入默认数据
                        val defaultWashModes = listOf(
                            WashMode(
                                name = "basic_wash",
                                description = "basic_desc",
                                price = 5.0,
                                sortOrder = 1,
                                durationMinutes = 10,
                                isActive = true,
                                imageResId = R.drawable.banner_basic_fixed
                            ),
                            WashMode(
                                name = "standard_wash",
                                description = "standard_desc",
                                price = 10.0,
                                sortOrder = 2,
                                durationMinutes = 15,
                                isActive = true,
                                imageResId = R.drawable.banner_standard_fixed
                            ),
                            WashMode(
                                name = "premium_wash",
                                description = "premium_desc",
                                price = 15.0,
                                sortOrder = 3,
                                durationMinutes = 20,
                                isActive = true,
                                imageResId = R.drawable.banner_premium_fixed
                            ),
                            WashMode(
                                name = "vip_wash",
                                description = "vip_desc",
                                price = 20.0,
                                sortOrder = 4,
                                durationMinutes = 30,
                                isActive = true,
                                imageResId = R.drawable.banner_vip_fixed
                            )
                        )
                        washModeDao.insertAll(defaultWashModes)
                        
                        // 验证插入结果
                        val verifyCount = washModeDao.count()
                        android.util.Log.d("HomeDebug", "✅ 默认数据插入完成: 插入 ${defaultWashModes.size} 条记录，验证后总数 = $verifyCount")
                        
                        if (verifyCount < defaultWashModes.size) {
                            android.util.Log.w("HomeDebug", "⚠️ 警告: 插入的记录数($verifyCount)少于预期(${defaultWashModes.size})")
                        }
                    } else {
                        android.util.Log.d("HomeDebug", "数据库已有数据（count=$count），跳过插入")
                    }
                    
                    // 调用确保默认用户的方法
                    ensureDefaultUser(context)
                    
                    android.util.Log.d("HomeDebug", "========== DatabaseCallback.onCreate: 默认数据插入完成 ==========")
                } catch (e: Exception) {
                    android.util.Log.e("HomeDebug", "❌ DatabaseCallback.onCreate: 插入默认数据失败", e)
                    android.util.Log.e("HomeDebug", "异常类型: ${e::class.java.name}")
                    android.util.Log.e("HomeDebug", "异常消息: ${e.message}")
                    android.util.Log.e("HomeDebug", "异常堆栈:", e)
                    // 不重新抛出异常，避免影响数据库创建
                }
            }
            
            /**
             * 新增：确保默认管理员用户存在
             * 幂等性保证：通过 userCount == 0 判断，即使多次调用也不会重复插入
             */
            private suspend fun ensureDefaultUser(context: Context) {
                try {
                    val database = getDatabase(context)
                    val userDao = database.userDao()
                    val userCount = userDao.getUserCount()
                    
                    if (userCount == 0) {
                        android.util.Log.d("AppDatabase", "V3.4: 创建默认 Admin 用户")
                        val defaultPassword = "admin123"  // 首次安装默认密码，强制修改
                        val passwordHash = hashPassword(defaultPassword)
                        val defaultAdmin = UserEntity(
                            userId = java.util.UUID.randomUUID().toString(),
                            username = "admin",
                            passwordHash = passwordHash,
                            role = UserRole.ADMIN.name,
                            isActive = true,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        userDao.insert(defaultAdmin)
                        android.util.Log.d("AppDatabase", "✅ V3.4: 默认 Admin 用户创建完成 (username=admin, password=admin123)")
                    } else {
                        android.util.Log.d("AppDatabase", "V3.4: 用户表已有数据（count=$userCount），跳过创建默认用户")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AppDatabase", "❌ 确保默认用户失败", e)
                    android.util.Log.e("AppDatabase", "异常类型: ${e::class.java.name}")
                    android.util.Log.e("AppDatabase", "异常消息: ${e.message}")
                    android.util.Log.e("AppDatabase", "异常堆栈:", e)
                    // 不重新抛出异常，避免影响数据库打开
                }
            }
            
            /**
             * 密码哈希（SHA-256）
             * V3.4: 简单的密码加密，生产环境应使用更安全的方案（如 bcrypt）
             */
            private fun hashPassword(password: String): String {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(password.toByteArray())
                return hash.joinToString("") { "%02x".format(it) }
            }
        }
    }
}
