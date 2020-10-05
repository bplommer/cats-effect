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

package cats.effect

import cats.{Applicative, Eq, Order, Show}
import cats.effect.testkit.{
  AsyncGenerators,
  GenK,
  OutcomeGenerators,
  SyncGenerators,
  TestContext
}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}, Arbitrary.arbitrary

import org.specs2.execute.AsResult
import org.specs2.matcher.Matcher
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.core.Execution

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.Try

import java.util.concurrent.TimeUnit

trait Runners extends SpecificationLike with RunnersPlatform { outer =>
  import OutcomeGenerators._

  def ticked[A: AsResult](test: Ticker => A): Execution =
    Execution.result(test(Ticker(TestContext())))

  def real[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => timeout(test.unsafeToFuture()(runtime()), 10.seconds))

  implicit def cogenIO[A: Cogen](implicit ticker: Ticker): Cogen[IO[A]] =
    Cogen[Outcome[Option, Throwable, A]].contramap(unsafeRun(_))

  implicit def arbitraryIO[A: Arbitrary: Cogen](implicit ticker: Ticker): Arbitrary[IO[A]] = {
    val generators =
      new AsyncGenerators[IO] {

        val arbitraryE: Arbitrary[Throwable] =
          arbitraryThrowable

        val cogenE: Cogen[Throwable] = Cogen[Throwable]

        val F: Async[IO] = IO.asyncForIO

        def cogenCase[B: Cogen]: Cogen[OutcomeIO[B]] =
          OutcomeGenerators.cogenOutcome[IO, Throwable, B]

        val arbitraryEC: Arbitrary[ExecutionContext] = outer.arbitraryEC

        val cogenFU: Cogen[IO[Unit]] = cogenIO[Unit]

        // TODO dedup with FreeSyncGenerators
        val arbitraryFD: Arbitrary[FiniteDuration] = outer.arbitraryFD

        override def recursiveGen[B: Arbitrary: Cogen](deeper: GenK[IO]) =
          super
            .recursiveGen[B](deeper)
            .filterNot(
              _._1 == "racePair"
            ) // remove the racePair generator since it reifies nondeterminism, which cannot be law-tested
      }

    Arbitrary(generators.generators[A])
  }

  implicit def arbitrarySyncIO[A: Arbitrary: Cogen]: Arbitrary[SyncIO[A]] = {
    val generators = new SyncGenerators[SyncIO] {
      val arbitraryE: Arbitrary[Throwable] =
        arbitraryThrowable

      val cogenE: Cogen[Throwable] =
        Cogen[Throwable]

      protected val arbitraryFD: Arbitrary[FiniteDuration] =
        outer.arbitraryFD

      val F: Sync[SyncIO] =
        SyncIO.syncForSyncIO
    }

    Arbitrary(generators.generators[A])
  }

  implicit def arbitraryGenResource[F[_], E, A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]]): Arbitrary[GenResource[F, E, A]] =
    Arbitrary(Gen.delay(genGenResource[F, E, A]))

  implicit def arbitraryGenResourceParallel[F[_], E, A](
      implicit A: Arbitrary[GenResource[F, E, A]]
  ): Arbitrary[GenResource.Par[F, E, A]] =
    Arbitrary(A.arbitrary.map(GenResource.Par.apply))

  // Consider improving this a strategy similar to Generators.
  def genGenResource[F[_], E, A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]]): Gen[GenResource[F, E, A]] = {
    def genAllocate: Gen[GenResource[F, E, A]] =
      for {
        alloc <- arbitrary[F[A]]
        dispose <- arbitrary[F[Unit]]
      } yield GenResource(alloc.map(a => a -> dispose))

    def genBind: Gen[GenResource[F, E, A]] =
      genAllocate.map(_.flatMap(a => GenResource.pure[F, E, A](a)))

    def genSuspend: Gen[GenResource[F, E, A]] =
      genAllocate.map(r => GenResource.suspend(r.pure[F]))

    Gen.frequency(
      5 -> genAllocate,
      1 -> genBind,
      1 -> genSuspend
    )
  }

  implicit lazy val arbitraryFD: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS)

    Arbitrary {
      genTU flatMap { u => Gen.choose[Long](0L, 48L).map(FiniteDuration(_, u)) }
    }
  }

  implicit lazy val arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary(Arbitrary.arbitrary[Int].map(TestException))

  implicit def arbitraryEC(implicit ticker: Ticker): Arbitrary[ExecutionContext] =
    Arbitrary(Gen.const(ticker.ctx.derive()))

  implicit lazy val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]

  implicit lazy val shThrowable: Show[Throwable] =
    Show.fromToString[Throwable]

  implicit lazy val eqEC: Eq[ExecutionContext] =
    Eq.fromUniversalEquals[ExecutionContext]

  implicit def ordIOFD(implicit ticker: Ticker): Order[IO[FiniteDuration]] =
    Order by { ioa => unsafeRun(ioa).fold(None, _ => None, fa => fa) }

  implicit def eqIOA[A: Eq](implicit ticker: Ticker): Eq[IO[A]] =
    Eq.by(unsafeRun(_))
  /*Eq instance { (left: IO[A], right: IO[A]) =>
      val leftR = unsafeRun(left)
      val rightR = unsafeRun(right)

      val back = leftR eqv rightR

      if (!back) {
        println(s"$left != $right")
        println(s"$leftR != $rightR")
      }

      back
    }*/

  /**
   * Defines equality for a `GenResource`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqGenResource[F[_], E, A](
      implicit E: Eq[F[A]],
      F: GenResource.Bracket[F, E]): Eq[GenResource[F, E, A]] =
    new Eq[GenResource[F, E, A]] {
      def eqv(x: GenResource[F, E, A], y: GenResource[F, E, A]): Boolean =
        E.eqv(x.use(F.pure), y.use(F.pure))
    }

  /**
   * Defines equality for `GenResource.Par`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqGenResourcePar[F[_], E, A](
      implicit E: Eq[GenResource[F, E, A]]): Eq[GenResource.Par[F, E, A]] =
    new Eq[GenResource.Par[F, E, A]] {
      import GenResource.Par.unwrap
      def eqv(x: GenResource.Par[F, E, A], y: GenResource.Par[F, E, A]): Boolean =
        E.eqv(unwrap(x), unwrap(y))
    }

  def unsafeRunSyncIOEither[A](io: SyncIO[A]): Either[Throwable, A] =
    Try(io.unsafeRunSync()).toEither

  implicit def eqSyncIOA[A: Eq]: Eq[SyncIO[A]] =
    Eq.instance { (left, right) =>
      unsafeRunSyncIOEither(left) === unsafeRunSyncIOEither(right)
    }

  // feel the rhythm, feel the rhyme...
  implicit def boolRunnings(iob: IO[Boolean])(implicit ticker: Ticker): Prop =
    Prop(unsafeRun(iob).fold(false, _ => false, _.getOrElse(false)))

  implicit def boolRunningsSync(iob: SyncIO[Boolean]): Prop =
    Prop {
      try iob.unsafeRunSync()
      catch {
        case _: Throwable => false
      }
    }

  def completeAs[A: Eq: Show](expected: A)(implicit ticker: Ticker): Matcher[IO[A]] =
    tickTo(Outcome.Succeeded(Some(expected)))

  def completeAsSync[A: Eq: Show](expected: A): Matcher[SyncIO[A]] = { (ioa: SyncIO[A]) =>
    val a = ioa.unsafeRunSync()
    (a eqv expected, s"${a.show} !== ${expected.show}")
  }

  def failAs(expected: Throwable)(implicit ticker: Ticker): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Errored(expected))

  def failAsSync[A](expected: Throwable): Matcher[SyncIO[A]] = { (ioa: SyncIO[A]) =>
    val t =
      (try ioa.unsafeRunSync()
      catch {
        case t: Throwable => t
      }).asInstanceOf[Throwable]
    (t eqv expected, s"${t.show} !== ${expected.show}")
  }

  def nonTerminate(implicit ticker: Ticker): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Succeeded(None))

  def tickTo[A: Eq: Show](expected: Outcome[Option, Throwable, A])(
      implicit ticker: Ticker): Matcher[IO[A]] = { (ioa: IO[A]) =>
    val oc = unsafeRun(ioa)
    (oc eqv expected, s"${oc.show} !== ${expected.show}")
  }

  def unsafeRun[A](ioa: IO[A])(implicit ticker: Ticker): Outcome[Option, Throwable, A] =
    try {
      var results: Outcome[Option, Throwable, A] = Outcome.Succeeded(None)

      ioa.unsafeRunAsync {
        case Left(t) => results = Outcome.Errored(t)
        case Right(a) => results = Outcome.Succeeded(Some(a))
      }(unsafe.IORuntime(ticker.ctx, ticker.ctx, scheduler, () => ()))

      ticker.ctx.tickAll(3.days)

      /*println("====================================")
      println(s"completed ioa with $results")
      println("====================================")*/

      results
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        throw t
    }

  implicit def materializeRuntime(implicit ticker: Ticker): unsafe.IORuntime =
    unsafe.IORuntime(ticker.ctx, ticker.ctx, scheduler, () => ())

  def scheduler(implicit ticker: Ticker): unsafe.Scheduler =
    new unsafe.Scheduler {
      import ticker.ctx

      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }

      def nowMillis() = ctx.now().toMillis
      def monotonicNanos() = ctx.now().toNanos
    }

  private def timeout[A](f: Future[A], duration: FiniteDuration): Future[A] = {
    val p = Promise[A]()
    val r = runtime()
    implicit val ec = r.compute

    val cancel = r.scheduler.sleep(duration, { () => p.tryFailure(new TimeoutException); () })

    f.onComplete { result =>
      p.tryComplete(result)
      cancel.run()
    }

    p.future
  }

  @implicitNotFound(
    "could not find an instance of Ticker; try using `in ticked { implicit ticker =>`")
  case class Ticker(ctx: TestContext)
}
