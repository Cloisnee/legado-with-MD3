package com.github.jing332.compat.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 替代 com.drake.net.utils.withIO / withMain
 * 注意：保持与原版一致 lambda 形态（suspend CoroutineScope.() -> T），
 * 调用方内部可继续使用 isActive 等 CoroutineScope 成员。
 */
suspend fun <T> withIO(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.IO) { block() }

suspend fun <T> withMain(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Main) { block() }
