package com.github.jing332.script.result

/**
 * 迷你 Result 实现（替代第三方 com.github.michaelbull:kotlin-result 依赖）。
 * 仅覆盖本工程使用的 API 形状：Result / Ok / Err / onSuccess / onFailure。
 */
sealed class Result<out V, out E> {
    data class Ok<out V>(val value: V) : Result<V, Nothing>()
    data class Err<out E>(val error: E) : Result<Nothing, E>()
}

fun <V> Ok(value: V): Result<V, Nothing> = Result.Ok(value)

fun <E> Err(error: E): Result<Nothing, E> = Result.Err(error)

inline fun <V, E> Result<V, E>.onSuccess(action: (V) -> Unit): Result<V, E> =
    this.also { result -> if (result is Result.Ok) action(result.value) }

inline fun <V, E> Result<V, E>.onFailure(action: (E) -> Unit): Result<V, E> =
    this.also { result -> if (result is Result.Err) action(result.error) }