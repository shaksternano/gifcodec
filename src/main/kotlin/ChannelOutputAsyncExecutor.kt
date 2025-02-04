package io.github.shaksternano.gifcodec

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.EmptyCoroutineContext

class ChannelOutputAsyncExecutor<T, R>(
    maxConcurrency: Int,
    scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext),
    task: suspend (T) -> R,
) : AsyncExecutor<T, R>(
    maxConcurrency = maxConcurrency,
    scope = scope,
    task = task,
    onOutput = {},
) {

    val output: Channel<Result<R>> = Channel(maxConcurrency)

    override suspend fun onOutputFunction(toOutput: Result<R>) {
        output.send(toOutput)
    }

    override suspend fun close() {
        super.close()
        output.close()
    }
}
