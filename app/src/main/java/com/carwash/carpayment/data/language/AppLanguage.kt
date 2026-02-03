package com.carwash.carpayment.data.language

/**
 * 应用支持的语言
 */
enum class AppLanguage(val code: String, val displayName: String) {
    EN("en", "English"),
    DE("de", "Deutsch");
    
    companion object {
        /**
         * 从代码获取语言
         */
        fun fromCode(code: String): AppLanguage {
            return values().find { it.code == code } ?: EN
        }
        
        /**
         * 默认语言
         */
        val DEFAULT = EN
    }
}
