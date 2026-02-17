package com.carwash.carpayment.data.transaction

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carwash.carpayment.R
import com.carwash.carpayment.data.washmode.WashMode
import com.carwash.carpayment.data.washmode.WashModeDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用数据库
 */
@Database(
    entities = [Transaction::class, WashMode::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun transactionDao(): TransactionDao
    abstract fun washModeDao(): WashModeDao
    
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
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "car_wash_payment_database"
                )
                    .addMigrations(MIGRATION_1_2)  // 添加迁移脚本
                    .fallbackToDestructiveMigration()  // 开发阶段使用，生产环境应提供迁移策略
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
            
            private suspend fun populateDatabase(context: Context) {
                android.util.Log.d("HomeDebug", "开始插入默认数据...")
                val database = getDatabase(context)
                val washModeDao = database.washModeDao()
                
                // 检查是否已有数据（使用 first() 获取第一个值）
                val existingModes = washModeDao.getAllWashModes().first()
                android.util.Log.d("HomeDebug", "检查现有数据: ${existingModes.size} 条记录")
                
                if (existingModes.isEmpty()) {
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
                    android.util.Log.d("HomeDebug", "✅ 默认数据插入完成: ${defaultWashModes.size} 条记录")
                } else {
                    android.util.Log.d("HomeDebug", "数据库已有数据，跳过插入")
                }
            }
        }
    }
}
