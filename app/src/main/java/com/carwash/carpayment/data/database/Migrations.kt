package com.carwash.carpayment.data.database

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移脚本
 * 
 * 用于修复 schema hash 冲突问题
 */
object Migrations {
    
    private const val TAG = "Migrations"
    
    /**
     * 数据库迁移：从版本3到版本4
     * 
     * 目的：修复 Room schema hash 冲突
     * 问题：表结构与实体类不匹配（wash_mode 和 users 表）
     * 
     * 修复要点：
     * 1. isActive 列均无 DEFAULT 值（与实体类一致）
     * 2. 不创建任何索引（实体类没有 @Index 注解）
     * 3. 列顺序按照实体类定义的顺序
     * 4. 同时处理 wash_mode 和 users 表
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            Log.d(TAG, "========== 执行迁移 3->4: 修复 schema hash 冲突 ==========")
            Log.d(TAG, "当前数据库版本: 3")
            Log.d(TAG, "目标数据库版本: 4")
            
            try {
                // 1. 删除旧表（因结构已乱，重建是最干净的）
                database.execSQL("DROP TABLE IF EXISTS wash_mode")
                database.execSQL("DROP TABLE IF EXISTS users")
                Log.d(TAG, "步骤1: 旧表已删除（wash_mode 和 users）")
                
                // 2. 删除旧索引（如果存在）
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_wash_mode_sortOrder")
                    database.execSQL("DROP INDEX IF EXISTS index_wash_mode_isActive")
                    database.execSQL("DROP INDEX IF EXISTS index_users_username")
                    Log.d(TAG, "步骤2: 旧索引已删除")
                } catch (e: Exception) {
                    Log.d(TAG, "索引不存在，跳过删除: ${e.message}")
                }
                
                // 3. 创建 wash_mode 表（按实体类定义，无默认值，无索引）
                database.execSQL("""
                    CREATE TABLE wash_mode (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT,
                        price REAL NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        durationMinutes INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        imageResId INTEGER
                    )
                """.trimIndent())
                Log.d(TAG, "步骤3: wash_mode 表已创建（按实体类定义，isActive 无 DEFAULT，无索引）")
                
                // 4. 创建 users 表（严格按实体类定义，无额外列，无默认值，无索引）
                database.execSQL("""
                    CREATE TABLE users (
                        userId TEXT NOT NULL PRIMARY KEY,
                        username TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        role TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                Log.d(TAG, "步骤4: users 表已创建（按实体类定义，isActive 无 DEFAULT，无索引）")
                
                // 5. 注意：不创建任何索引！因为实体类目前没有 @Index 注解
                //    如果将来实体类添加了索引，再在迁移中同步添加 CREATE INDEX
                
                Log.d(TAG, "✅ 迁移 3->4 完成: wash_mode 和 users 表结构已重建，与实体类定义完全一致")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 迁移 3->4 失败", e)
                Log.e(TAG, "异常类型: ${e::class.java.name}")
                Log.e(TAG, "异常消息: ${e.message}")
                Log.e(TAG, "异常堆栈:", e)
                // 重新抛出异常，让 Room 知道迁移失败
                throw e
            }
            
            Log.d(TAG, "========== 迁移 3->4 执行完成 ==========")
        }
    }
}
