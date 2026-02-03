package com.carwash.carpayment.data.language

import androidx.annotation.StringRes
import com.carwash.carpayment.R

/**
 * UI 文本资源（统一入口）
 * 所有 UI 字符串必须通过此系统获取，禁止硬编码
 */
sealed class UiText {
    /**
     * 从字符串资源获取
     */
    data class Res(@StringRes val id: Int, val varArgs: Array<out Any> = emptyArray()) : UiText() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Res
            return id == other.id && varArgs.contentEquals(other.varArgs)
        }
        
        override fun hashCode(): Int {
            var result = id
            result = 31 * result + varArgs.contentHashCode()
            return result
        }
    }
    
    /**
     * 直接字符串（用于动态内容，如金额、时间等）
     */
    data class Dynamic(val text: String) : UiText()
    
    /**
     * 组合多个文本
     */
    data class Combined(val texts: List<UiText>) : UiText()
}

/**
 * 字符串资源键（用于统一管理）
 * 注意：不能使用 const val，因为 R.string.xxx 不是编译时常量
 */
object StringKeys {
    // 通用
    val APP_NAME = R.string.app_name
    val BUTTON_CONTINUE = R.string.button_continue
    val BUTTON_BACK = R.string.button_back
    val BUTTON_CANCEL = R.string.button_cancel
    val BUTTON_FINISH = R.string.button_finish
    
    // 语言
    val LANGUAGE_EN = R.string.language_en
    val LANGUAGE_DE = R.string.language_de
    
    // 首页
    val WELCOME_MESSAGE = R.string.welcome_message
    val SELECT_PROGRAM_TITLE = R.string.select_program_title
    val SELECT_PROGRAM_HINT = R.string.select_program_hint
    
    // 洗车机状态
    val STATUS_READY = R.string.status_ready
    val STATUS_FAULT = R.string.status_fault
    val STATUS_OCCUPIED = R.string.status_occupied
    val STATUS_NOT_READY = R.string.status_not_ready
    val STATUS_UNKNOWN = R.string.status_unknown
    val STATUS_UPDATING = R.string.status_updating
    
    // 支付
    val SELECT_PAYMENT_TITLE = R.string.select_payment_title
    val PAYMENT_CARD = R.string.payment_card
    val PAYMENT_CASH = R.string.payment_cash
    val BUTTON_PAY = R.string.button_pay
    val PAYMENT_SUCCESS = R.string.payment_success
    val PAYMENT_FAILED = R.string.payment_failed
    
    // 现金支付
    val CASH_INSERT_HINT = R.string.cash_insert_hint
    val CASH_CANNOT_ACCEPT_LARGE = R.string.cash_cannot_accept_large
    val CASH_TOTAL_INSERTED = R.string.cash_total_inserted
    val CASH_ACCEPTED_DENOMINATIONS = R.string.cash_accepted_denominations
    val CASH_REMAINING = R.string.cash_remaining
    val CASH_TARGET = R.string.cash_target
    val CASH_PAID = R.string.cash_paid
    
    // 错误
    val ERROR_SELECT_PAYMENT = R.string.error_select_payment
    val ERROR_CONFIRM_PAYMENT = R.string.error_confirm_payment
    val ERROR_DEVICE_NOT_CONNECTED = R.string.error_device_not_connected
    val ERROR_COMMUNICATION_FAILED = R.string.error_communication_failed
}

/**
 * 字符串提供者（根据语言获取字符串）
 */
interface StringProvider {
    fun getString(@StringRes id: Int, vararg args: Any?): String
    fun getLanguage(): AppLanguage
}
