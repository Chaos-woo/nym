package com.cw2.nym.core.result

import com.cw2.nym.core.exception.NymError

/**
 * 通用结果类型系统 - Generic Result type system
 * 
 * 提供函数式错误处理能力，避免异常抛出，使错误处理更加明确和类型安全
 * Provides functional error handling capabilities, avoiding exception throwing for more explicit and type-safe error handling
 */
sealed class Result<out T> {
    
    /**
     * 成功结果 - Success result
     */
    data class Success<T>(val data: T) : Result<T>()
    
    /**
     * 错误结果 - Error result
     */
    data class Error(val error: NymError) : Result<Nothing>()
    
    /**
     * 判断是否为成功结果 - Check if result is success
     */
    val isSuccess: Boolean get() = this is Success
    
    /**
     * 判断是否为错误结果 - Check if result is error
     */
    val isError: Boolean get() = this is Error
    
    /**
     * 获取成功数据，如果是错误则返回 null - Get success data, return null if error
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    /**
     * 获取错误信息，如果是成功则返回 null - Get error, return null if success
     */
    fun errorOrNull(): NymError? = when (this) {
        is Success -> null
        is Error -> error
    }
    
    // 注意：由于协变类型参数，某些方法被移除以避免类型冲突
    // Note: Some methods removed due to covariant type parameter to avoid type conflicts
    
    /**
     * 映射成功值 - Map success value
     * 如果当前结果是成功，则对数据应用转换函数；如果是错误，则保持错误不变
     * If current result is success, apply transformation function to data; if error, keep error unchanged
     */
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
    
    /**
     * 映射错误值 - Map error value
     * 如果当前结果是错误，则对错误应用转换函数；如果是成功，则保持成功不变
     * If current result is error, apply transformation function to error; if success, keep success unchanged
     */
    inline fun mapError(transform: (NymError) -> NymError): Result<T> = when (this) {
        is Success -> this
        is Error -> Error(transform(error))
    }
    
    /**
     * 平铺映射成功值 - Flat map success value
     * 如果当前结果是成功，则对数据应用转换函数（该函数返回 Result）；如果是错误，则保持错误不变
     * If current result is success, apply transformation function to data (function returns Result); if error, keep error unchanged
     */
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Error -> this
    }
    
    /**
     * 折叠结果 - Fold result
     * 对成功和错误情况分别应用不同的处理函数
     * Apply different handling functions for success and error cases respectively
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (NymError) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(error)
    }
    
    /**
     * 在成功时执行副作用操作 - Execute side effect on success
     * 不改变结果值，但在成功时执行指定操作
     * Does not change result value, but executes specified operation on success
     */
    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) {
            action(data)
        }
        return this
    }
    
    /**
     * 在错误时执行副作用操作 - Execute side effect on error
     * 不改变结果值，但在错误时执行指定操作
     * Does not change result value, but executes specified operation on error
     */
    inline fun onError(action: (NymError) -> Unit): Result<T> {
        if (this is Error) {
            action(error)
        }
        return this
    }
    
    // 恢复相关方法由于协变类型参数限制被移除
    // Recovery methods removed due to covariant type parameter constraints
    
    /**
     * 过滤成功值 - Filter success value
     * 如果成功值不满足条件，则转换为指定错误
     * If success value does not meet condition, convert to specified error
     */
    inline fun filter(predicate: (T) -> Boolean, error: NymError): Result<T> = when (this) {
        is Success -> if (predicate(data)) this else Error(error)
        is Error -> this
    }
    
    /**
     * 转换为可空值 - Convert to nullable value
     * 成功时返回数据，错误时返回 null
     * Return data on success, null on error
     */
    fun toNullable(): T? = getOrNull()
    
    companion object {
        /**
         * 创建成功结果 - Create success result
         */
        fun <T> success(data: T): Result<T> = Success(data)
        
        /**
         * 创建错误结果 - Create error result
         */
        fun <T> error(error: NymError): Result<T> = Error(error)
        
        /**
         * 从可能抛出异常的代码块创建结果 - Create result from code block that might throw exception
         * 将异常转换为 NymError.Unknown
         * Convert exception to NymError.Unknown
         */
        inline fun <T> catching(block: () -> T): Result<T> = try {
            Success(block())
        } catch (e: Exception) {
            Error(NymError.Unknown("执行过程中发生未知错误", e))
        }
        
        /**
         * 从可空值创建结果 - Create result from nullable value
         */
        fun <T> fromNullable(value: T?, error: NymError): Result<T> = 
            value?.let { Success(it) } ?: Error(error)
        
        /**
         * 组合多个结果 - Combine multiple results
         * 只有当所有结果都成功时才返回成功，否则返回第一个错误
         * Return success only when all results are successful, otherwise return first error
         */
        fun <T> combine(results: List<Result<T>>): Result<List<T>> {
            val data = mutableListOf<T>()
            for (result in results) {
                when (result) {
                    is Success -> data.add(result.data)
                    is Error -> return result
                }
            }
            return Success(data)
        }
        
        /**
         * 组合两个结果 - Combine two results
         */
        inline fun <T1, T2, R> combine(
            result1: Result<T1>,
            result2: Result<T2>,
            transform: (T1, T2) -> R
        ): Result<R> = when {
            result1 is Success && result2 is Success -> Success(transform(result1.data, result2.data))
            result1 is Error -> result1
            result2 is Error -> result2
            else -> Error(NymError.Unknown("组合结果时发生未知错误"))
        }
        
        /**
         * 组合三个结果 - Combine three results
         */
        inline fun <T1, T2, T3, R> combine(
            result1: Result<T1>,
            result2: Result<T2>,
            result3: Result<T3>,
            transform: (T1, T2, T3) -> R
        ): Result<R> = when {
            result1 is Success && result2 is Success && result3 is Success -> 
                Success(transform(result1.data, result2.data, result3.data))
            result1 is Error -> result1
            result2 is Error -> result2
            result3 is Error -> result3
            else -> Error(NymError.Unknown("组合结果时发生未知错误"))
        }
    }
}

/**
 * 扩展函数：将可空值转换为 Result - Extension function: convert nullable to Result
 */
fun <T> T?.toResult(error: NymError): Result<T> = Result.fromNullable(this, error)

/**
 * 扩展函数：安全执行代码块 - Extension function: safely execute code block
 */
inline fun <T> safeCall(block: () -> T): Result<T> = Result.catching(block)

/**
 * 扩展函数：序列操作的 Result 版本 - Extension function: Result version of sequence operations
 */
fun <T> Sequence<Result<T>>.combineToResult(): Result<List<T>> {
    val results = this.toList()
    return Result.combine(results)
}

/**
 * 扩展函数：列表操作的 Result 版本 - Extension function: Result version of list operations
 */
fun <T> List<Result<T>>.combineToResult(): Result<List<T>> = Result.combine(this)