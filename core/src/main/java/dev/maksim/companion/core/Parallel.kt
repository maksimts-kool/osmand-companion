package dev.maksim.companion.core

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * One pool for the requests that go out side by side (a stop's live feeds, a trip's stops, the planner's questions),
 * so each doesn't start threads of its own. It grows as needed, so a task can wait on others it started without
 * running out of threads, and lets them go once idle.
 */
object Parallel {

    private val count = AtomicInteger()

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "Parallel-${count.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Starts [task] on the pool; [await] its answer. */
    fun <T> submit(task: () -> T): Future<T> = pool.submit(Callable(task))

    /** [future]'s answer, or what it threw, as it threw it (rather than wrapped). */
    fun <T> await(future: Future<T>): T = try {
        future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /**
     * [transform] of each of [items], at most [parallelism] at a time (this thread being one of them), in their
     * order; each one's failure is its own.
     */
    fun <T, R> map(items: List<T>, parallelism: Int, transform: (T) -> R): List<Result<R>> {
        if (items.isEmpty()) return emptyList()
        val results = arrayOfNulls<Result<R>>(items.size)
        val next = AtomicInteger()
        val work = {
            while (true) {
                val i = next.getAndIncrement()
                if (i >= items.size) break
                results[i] = runCatching { transform(items[i]) }
            }
        }
        val helpers = (1 until minOf(parallelism, items.size)).map { submit(work) }
        work()
        helpers.forEach { await(it) }
        return results.map { it!! }
    }
}
