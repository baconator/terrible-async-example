package io.github.baconator

import java.time.LocalDateTime
import java.util.concurrent.*
import java.util.function.Supplier

fun main(args: Array<String>) {
    val blockingExecutorForSimulatingInputOutput = ThreadPoolExecutor(20, 20, 100, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
    val sharedAsynchronousExecutor = ScheduledThreadPoolExecutor(1)
    val completables = (1..10).map {
        makeCompletable<Int>(
                sharedAsynchronousExecutor = sharedAsynchronousExecutor,
                runDelayed = { wrapped ->
                    // Create a CompletableFuture that will block one of the simulated IO threads, not one of the shared threads.
                    CompletableFuture.supplyAsync(Supplier<Int> {
                        var output: Int? = null
                        // Asynchronously resume in 2 (or more) seconds.
                        val future = sharedAsynchronousExecutor.schedule({
                            output = wrapped()
                        }, 2, TimeUnit.SECONDS)
                        // Normally you would block the calling thread, but instead, since this is a completablefuture,
                        // it can easily block/use some async feature of another thread pool.
                        println("Blocking thread #${Thread.currentThread().id} to simulate IO.")

                        // Real-world libraries pool/reuse threads/make use of selectors to do this.
                        // This blocks the _blockingExecutor_, since that's what supplying this CompletableFuture.
                        future.get()
                        output!!
                    }, blockingExecutorForSimulatingInputOutput)
                },
                id = it)
    }.toTypedArray()
    println("Starting await on thread #${Thread.currentThread().id}...")

    // Create a CompletableFuture of all the above completables, then block the main thread on it.
    CompletableFuture.allOf(*completables).get()
    println("... done on thread #${Thread.currentThread().id}!")

    sharedAsynchronousExecutor.shutdown()
    blockingExecutorForSimulatingInputOutput.shutdown()
}

private fun <T> makeCompletable(sharedAsynchronousExecutor: ScheduledThreadPoolExecutor,
                                runDelayed: (() -> T) -> CompletableFuture<T>,
                                id: T): CompletableFuture<T> = CompletableFuture.supplyAsync(Supplier<Unit> {
    // Spawning threads is expensive, hence the desire for thread pools. However, pooling has its limits: every
    // connection needs a new thread with which to block. Futures/collaborative multithreading allow you to increase
    // the number of connections a single thread can service by returning control back to the sharedAsynchronousExecutor in order to reuse
    // the thread. All state information is kept within the CompletableFuture's closure.
    println("${tag(id)} Hello from thread #${Thread.currentThread().id}!")
}, sharedAsynchronousExecutor).thenCompose {
    // This _always_ executes after the above.
    runDelayed({
        println("${tag(id)} This next execution is from ${Thread.currentThread().id}.")
        id
    })
}

private fun <T> tag(id: T) = "($id, @${LocalDateTime.now()})"


