package com.payflow.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Runs tasks on a real thread pool, released together by a start gate. */
public final class Concurrently {

  public record Timed<T>(T value, long nanos) {}

  private Concurrently() {}

  /**
   * Runs {@code tasks} invocations on {@code poolSize} threads and returns results in task order.
   */
  public static <T> List<Timed<T>> run(int tasks, int poolSize, IntFunction<T> task) {
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    try {
      CountDownLatch ready = new CountDownLatch(Math.min(tasks, poolSize));
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Timed<T>>> futures = new ArrayList<>(tasks);
      for (int i = 0; i < tasks; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  if (index < poolSize) {
                    ready.countDown();
                    go.await();
                  }
                  long started = System.nanoTime();
                  T result = task.apply(index);
                  return new Timed<>(result, System.nanoTime() - started);
                }));
      }
      if (!ready.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("worker threads did not start");
      }
      go.countDown();
      List<Timed<T>> results = new ArrayList<>(tasks);
      for (Future<Timed<T>> future : futures) {
        results.add(future.get(300, TimeUnit.SECONDS));
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("concurrent task failed", e);
    } finally {
      pool.shutdownNow();
    }
  }
}
