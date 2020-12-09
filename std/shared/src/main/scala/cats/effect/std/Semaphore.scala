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

package cats
package effect
package std

import cats.effect.kernel._
// import cats.effect.std.Semaphore.TransformedSemaphore TODO
import cats.syntax.all._
import cats.effect.kernel.syntax.all._
import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional semaphore.
 *
 * A semaphore has a non-negative number of permits available. Acquiring a permit
 * decrements the current number of permits and releasing a permit increases
 * the current number of permits. An acquire that occurs when there are no
 * permits available results in semantic blocking until a permit becomes available.
 */
abstract class Semaphore[F[_]] {

  /**
   * Returns the number of permits currently available. Always non-negative.
   *
   * May be out of date the instant after it is retrieved.
   * Use `[[tryAcquire]]` or `[[tryAcquireN]]` if you wish to attempt an
   * acquire, returning immediately if the current count is not high enough
   * to satisfy the request.
   */
  def available: F[Long]

  /**
   * Obtains a snapshot of the current count. May be negative.
   *
   * Like [[available]] when permits are available but returns the number of permits
   * callers are waiting for when there are no permits available.
   */
  def count: F[Long]

  /**
   * Acquires `n` permits.
   *
   * The returned effect semantically blocks until all requested permits are
   * available. Note that acquires are statisfied in strict FIFO order, so given
   * `s: Semaphore[F]` with 2 permits available, an `acquireN(3)` will
   * always be satisfied before a later call to `acquireN(1)`.
   *
   * @param n number of permits to acquire - must be >= 0
   */
  def acquireN(n: Long): F[Unit]

  /**
   * Acquires a single permit. Alias for `[[acquireN]](1)`.
   */
  def acquire: F[Unit] = acquireN(1)

  /**
   * Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n < 0`.
   *
   * @param n number of permits to acquire - must be >= 0
   */
  def tryAcquireN(n: Long): F[Boolean]

  /**
   * Alias for `[[tryAcquireN]](1)`.
   */
  def tryAcquire: F[Boolean] = tryAcquireN(1)

  /**
   * Releases `n` permits, potentially unblocking up to `n` outstanding acquires.
   *
   * @param n number of permits to release - must be >= 0
   */
  def releaseN(n: Long): F[Unit]

  /**
   * Releases a single permit. Alias for `[[releaseN]](1)`.
   */
  def release: F[Unit] = releaseN(1)

  /**
   * Returns a [[Resource]] that acquires a permit, holds it for the lifetime of the resource, then
   * releases the permit.
   */
  def permit: Resource[F, Unit]

  /**
   * Modify the context `F` using natural transformation `f`.
    */
  // TODO
  // def mapK[G[_]: Applicative](f: F ~> G): Semaphore[G] =
  //   new TransformedSemaphore(this, f)
}

object Semaphore {

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def apply[F[_]](n: Long)(implicit F: GenConcurrent[F, _]): F[Semaphore[F]] = {
    requireNonNegative(n)
    F.ref[State[F]](Right(n)).map(stateRef => new AsyncSemaphore[F](stateRef))
  }

  def applyNew[F[_]](n: Long)(implicit F: GenConcurrent[F, _]): F[Semaphore[F]] = {
    import scala.collection.immutable.{Queue => Q}

    case class Request(n: Long, gate: Deferred[F, Unit]) {
      // the number of permits can change if the request is partially fulfilled
      def sameAs(r: Request) = r.gate eq gate
      def of(newN: Long) = Request(newN, gate)
      def wait_ = gate.get
      def complete = gate.complete(())
    }
    object Request {
      def mk = F.deferred[Unit].map(Request(0, _))
    }
    /*
     * Invariant:
     *    (waiting.empty && permits >= 0) || (permits.nonEmpty && permits == 0)
     */
    case class State(permits: Long, waiters: Q[Request])

    sealed trait Action
    case object Wait extends Action
    case object Done extends Action

    F.ref(State(n, Q())).map { state =>
      new Semaphore[F] {
              def acquireN(n: Long): F[Unit] = {
        requireNonNegative(n)

        if(n == 0) F.unit
        else F.uncancelable { poll =>
          Request.mk.flatMap { req =>
            state.modify {
              case State(permits, waiters) =>
                val (newState, decision) =
                  if (waiters.nonEmpty) State(0, waiters :+ req.of(n)) -> Wait
                  else {
                    val diff = permits - n
                    if (diff >= 0) State(diff, Q()) -> Done
                    else State(0, req.of(diff).pure[Q]) -> Wait
                  }

                val cleanup = state.modify {
                  case State(permits, waiters) =>
                    // both hold correctly even if the Request gets canceled
                    // after having been fulfilled
                    val permitsAcquiredSoFar = n - waiters.find(_ sameAs req).foldMap(_.n)
                    val newWaiters = waiters.filterNot(_ sameAs req)

                    // releaseN is commutative, so it doesn't matter that it accesses the Ref again
                    State(permits, newWaiters) -> releaseN(permitsAcquiredSoFar)
                  }.flatten

                val action = decision match {
                  case Done => F.unit
                  case Wait => poll(req.wait_).onCancel(cleanup)
                }

                newState -> action
            }.flatten
          }
        }
      }

      def releaseN(n: Long): F[Unit] = {
        requireNonNegative(n)

        if (n == 0) F.unit
        else state.modify {
          case State(permits, waiting) =>
            if (waiting.isEmpty) State(permits + n, waiting) -> F.unit
            else {

              def fulfil(n: Long, requests: Q[Request], wakeup: Q[Request]): (Long, Q[Request], Q[Request]) = {
                val (req, tail) = requests.dequeue
                if (n < req.n) // partially fulfil one request
                  (0, req.of(req.n - n) +: tail, Q())
                else { // fulfil as many requests as `n` allows
                  val newN = n - req.n
                  val newWakeup = wakeup.enqueue(req)

                  if (tail.isEmpty || newN == 0) (newN, tail, newWakeup)
                  else fulfil(newN, tail, newWakeup)
                }

              }

              val (newN, waitingNow, wakeup) = fulfil(n, waiting, Q())

              State(newN, waitingNow) -> wakeup.traverse_(_.complete)
            }
        }.flatten
      }

        def available: F[Long] = 0L.pure[F]
        def count: F[Long] = 0L.pure[F]
        def permit: Resource[F, Unit] = Resource.eval(F.unit)
        def tryAcquireN(n: Long): F[Boolean] = false.pure[F]
      }
    }
  }
  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * like `apply` but initializes state using another effect constructor
   */
  def in[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Async[G]): F[Semaphore[G]] = {
    requireNonNegative(n)
    Ref.in[F, G, State[G]](Right(n)).map(stateRef => new AsyncSemaphore[G](stateRef))
  }

  private def requireNonNegative(n: Long): Unit =
    require(n >= 0, s"n must be nonnegative, was: $n")

  private final case class Request[F[_]](n: Long, gate: Deferred[F, Unit])

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[ScalaQueue[Request[F]], Long]

  private final case class Permit[F[_]](await: F[Unit], release: F[Unit])

  abstract private class AbstractSemaphore[F[_]](state: Ref[F, State[F]])(
      implicit F: GenSpawn[F, _])
      extends Semaphore[F] {
    protected def mkGate: F[Deferred[F, Unit]]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(()).void

    def count: F[Long] = state.get.map(count_)

    private def count_(s: State[F]): Long =
      s match {
        case Left(waiting) => -waiting.map(_.n).sum
        case Right(available) => available
      }

    def acquireN(n: Long): F[Unit] =
      F.bracketCase(acquireNInternal(n))(_.await) {
        case (promise, Outcome.Canceled()) => promise.release
        case _ => F.unit
      }

    def acquireNInternal(n: Long): F[Permit[F]] = {
      requireNonNegative(n)
      if (n == 0) F.pure(Permit(F.unit, F.unit))
      else {
        mkGate.flatMap { gate =>
          state
            .updateAndGet {
              case Left(waiting) => Left(waiting :+ Request(n, gate))
              case Right(m) =>
                if (n <= m) {
                  Right(m - n)
                } else {
                  Left(ScalaQueue(Request(n - m, gate)))
                }
            }
            .map {
              case Left(_) =>
                val cleanup = state.modify {
                  case Left(waiting) =>
                    waiting.find(_.gate eq gate).map(_.n) match {
                      case None => (Left(waiting), releaseN(n))
                      case Some(m) =>
                        // releaseN can modify this to indicate partial fullfilment
                        // which is why the filter is on the gate, and releaseN needs
                        // to subtract
                        (Left(waiting.filterNot(_.gate eq gate)), releaseN(n - m))
                    }
                  case Right(m) => (Right(m + n), F.unit)
                }.flatten

                Permit(gate.get, cleanup)

              case Right(_) => Permit(F.unit, releaseN(n))
            }
        }
      }
    }

    def tryAcquireN(n: Long): F[Boolean] = {
      requireNonNegative(n)
      if (n == 0) F.pure(true)
      else
        state.modify {
          case Right(m) if m >= n => (Right(m - n), true)
          case other => (other, false)
        }
    }

    def releaseN(n: Long): F[Unit] = {
      requireNonNegative(n)
      if (n == 0) F.unit
      else
        state
          .modify { old =>
            val u = old match {
              case Left(waiting) =>
                // just figure out how many to strip from waiting queue,
                // but don't run anything here inside the modify
                var m = n
                var waiting2 = waiting
                while (waiting2.nonEmpty && m > 0) {
                  val Request(k, gate) = waiting2.head
                  if (k > m) {
                    waiting2 = Request(k - m, gate) +: waiting2.tail
                    m = 0
                  } else {
                    m -= k
                    waiting2 = waiting2.tail
                  }
                }
                if (waiting2.nonEmpty) Left(waiting2)
                else Right(m)
              case Right(m) => Right(m + n)
            }
            (u, (old, u))
          }
          .flatMap {
            case (Left(waiting), now) =>
              // invariant: count_(now) == count_(previous) + n
              // now compare old and new sizes to figure out which actions to run
              val newSize = now match {
                case Left(w) => w.size
                case Right(_) => 0
              }
              waiting.dropRight(newSize).traverse(request => open(request.gate)).void
            case (Right(_), _) => F.unit
          }
    }

    def available: F[Long] =
      state.get.map {
        case Left(_) => 0
        case Right(n) => n
      }

    val permit: Resource[F, Unit] =
      Resource.makeFull { (poll: Poll[F]) =>
        acquireNInternal(1).flatTap(x => poll(x.await))
      }{ _.release }.void
  }

  final private class AsyncSemaphore[F[_]](state: Ref[F, State[F]])(
      implicit F: GenConcurrent[F, _])
      extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred[F, Unit]
  }

  // TODO
  // final private[std] class TransformedSemaphore[F[_], G[_]](
  //     underlying: Semaphore[F],
  //     trans: F ~> G
  // ) extends Semaphore[G] {
  //   override def available: G[Long] = trans(underlying.available)
  //   override def count: G[Long] = trans(underlying.count)
  //   override def acquireN(n: Long): G[Unit] = trans(underlying.acquireN(n))
  //   override def tryAcquireN(n: Long): G[Boolean] = trans(underlying.tryAcquireN(n))
  //   override def releaseN(n: Long): G[Unit] = trans(underlying.releaseN(n))
  //   override def permit: Resource[G, Unit] = underlying.permit.mapK(trans)
  // }
}
