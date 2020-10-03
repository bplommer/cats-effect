/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.benchmarks

import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.effect.Ref

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 *     benchmarks/run-benchmark RefBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.RefConcurrentBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class RefConcurrentBenchmark {

  @Benchmark
  def getAndUpdateGeneric(): Unit = RefConcurrentBenchmark.getAndUpdateGeneric(1000, 10)

  @Benchmark
  def getAndUpdateInt(): Unit = RefConcurrentBenchmark.getAndUpdateInt(1000, 10)

  @Benchmark
  def getAndAdd(): Unit = RefConcurrentBenchmark.getAndAdd(1000, 10)
}

object RefConcurrentBenchmark {

  def getAndUpdateGeneric(iterations: Int, parallelism: Int): Unit = {
    Concurrent[IO].ref(0).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.getAndUpdate(_ + 1).flatMap(prev => loop(remaining - 1, acc + prev))
      }

      def go(remaining: Int): IO[Long] = {
        if (remaining == 0) IO(0L)
        else {
          for {
            fiber <- loop(iterations, 0L).start
            n <- go(remaining - 1)
            m <- fiber.joinAndEmbed(IO.raiseError(new AssertionError("ocrehu")))
          } yield m + n
        }
      }
      go(parallelism)
    }
  }.void.unsafeRunSync()

  def getAndUpdateInt(iterations: Int, parallelism: Int): Unit = {
    Ref[IO].of(0).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.getAndUpdate(_ + 1).flatMap(prev => loop(remaining - 1, acc + prev))
      }

      def go(remaining: Int): IO[Long] = {
        if (remaining == 0) IO(0L)
        else {
          for {
            fiber <- loop(iterations, 0L).start
            n <- go(remaining - 1)
            m <- fiber.joinAndEmbed(IO.raiseError(new AssertionError("ocrehu")))
          } yield m + n
        }
      }
      go(parallelism)
    }
  }.void.unsafeRunSync()

  def getAndAdd(iterations: Int, parallelism: Int): Unit = {
    Ref[IO]
      .of(0)
      .flatMap { ref =>
        def loop(remaining: Int, acc: Long): IO[Long] = {
          if (remaining == 0) IO(acc)
          else ref.getAndAdd(1).flatMap(prev => loop(remaining - 1, acc + prev))
        }

        def go(remaining: Int): IO[Long] = {
          if (remaining == 0) IO(0L)
          else {
            for {
              fiber <- loop(iterations, 0L).start
              n <- go(remaining - 1)
              m <- fiber.joinAndEmbed(IO.raiseError(new AssertionError("ocrehu")))
            } yield m + n
          }
        }
        go(parallelism)
      }
      .void
      .unsafeRunSync()
  }
}
