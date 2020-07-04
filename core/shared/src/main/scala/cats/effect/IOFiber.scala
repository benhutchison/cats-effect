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

import cats.~>
import cats.implicits._

import scala.annotation.{switch, tailrec}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

private[effect] final class IOFiber[A](name: String, timer: UnsafeTimer, private val initMask: Int) extends Fiber[IO, Throwable, A] {
  import IO._

  // I would rather have these on the stack, but we can't because we sometimes need to relocate our runloop to another fiber
  private var conts: ArrayStack[IOCont] = _
  private var ctxs: ArrayStack[ExecutionContext] = _

  private var heapCur: IO[Any] = _
  private var canceled: Boolean = false

  private var masks: Int = _
  private val finalizers = new ArrayStack[Outcome[IO, Throwable, Any] => IO[Unit]](16)    // TODO reason about whether or not the final finalizers are visible here

  // (Outcome[IO, Throwable, A] => Unit) | SafeArrayStack[Outcome[IO, Throwable, A] => Unit]
  private val callback: AtomicReference[AnyRef] = new AtomicReference()

  // true when semantically blocking (ensures that we only unblock *once*)
  private val suspended: AtomicBoolean = new AtomicBoolean(false)

  private val outcome: AtomicReference[Outcome[IO, Throwable, A]] =
    new AtomicReference()

  private val objectState = new ArrayStack[AnyRef](16)    // TODO tune
  private val booleanState = new BooleanArrayStack(16)    // TODO tune

  private[this] final val childCount = IOFiber.childCount

  // pre-fetching of all continuations (to avoid memory barriers)
  private[this] final val CancelationLoopK = IOFiber.CancelationLoopK
  private[this] final val CancelationLoopNodoneK = IOFiber.CancelationLoopNodoneK
  private[this] final val RunTerminusK = IOFiber.RunTerminusK
  private[this] final val AsyncK = IOFiber.AsyncK
  private[this] final val EvalOnK = IOFiber.EvalOnK
  private[this] final val MapK = IOFiber.MapK
  private[this] final val FlatMapK = IOFiber.FlatMapK
  private[this] final val HandleErrorWithK = IOFiber.HandleErrorWithK
  private[this] final val OnCaseK = IOFiber.OnCaseK
  private[this] final val OnCaseForwarderK = IOFiber.OnCaseForwarderK
  private[this] final val UncancelableK = IOFiber.UncancelableK
  private[this] final val UnmaskK = IOFiber.UnmaskK

  def this(heapCur0: IO[A], timer: UnsafeTimer, cb: Outcome[IO, Throwable, A] => Unit, initMask: Int) = {
    this("main", timer, initMask)
    heapCur = heapCur0
    callback.set(cb)
  }

  def this(heapCur0: IO[A], name: String, timer: UnsafeTimer, initMask: Int) = {
    this(name, timer, initMask)
    heapCur = heapCur0
  }

  var cancel: IO[Unit] = {
    val prelude = IO {
      canceled = true
      cancel = IO.unit
    }

    val completion = IO(suspended.compareAndSet(true, false)).ifM(
      IO {
        // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

        val oc: Outcome[IO, Throwable, Nothing] = Outcome.Canceled()
        if (outcome.compareAndSet(null, oc.asInstanceOf[Outcome[IO, Throwable, A]])) {
          done(oc.asInstanceOf[Outcome[IO, Throwable, A]])

          if (!finalizers.isEmpty()) {
            val conts = new ArrayStack[IOCont](16)    // TODO tune!
            conts.push(CancelationLoopK)

            masks += 1
            runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
          }
        }
      },
      join.void)    // someone else is in charge of the runloop; wait for them to cancel

    prelude *> completion
  }

  // this is swapped for an IO.pure(outcome.get()) when we complete
  var join: IO[Outcome[IO, Throwable, A]] =
    IO async { cb =>
      IO {
        registerListener(oc => cb(Right(oc)))
        None    // TODO maybe we can unregister the listener? (if we don't, it's probably a memory leak via the enclosing async)
      }
    }

  private def registerListener(listener: Outcome[IO, Throwable, A] => Unit): Unit = {
    if (outcome.get() == null) {
      @tailrec
      def loop(): Unit = {
        if (callback.get() == null) {
          if (!callback.compareAndSet(null, listener)) {
            loop()
          }
        } else {
          val old0 = callback.get()
          if (old0.isInstanceOf[Function1[_, _]]) {
            val old = old0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]

            val stack = new SafeArrayStack[Outcome[IO, Throwable, A] => Unit](4)
            stack.push(old)
            stack.push(listener)

            if (!callback.compareAndSet(old, stack)) {
              loop()
            }
          } else {
            val stack = old0.asInstanceOf[SafeArrayStack[Outcome[IO, Throwable, A] => Unit]]
            stack.push(listener)
          }
        }
      }

      loop()

      // double-check
      if (outcome.get() != null) {
        listener(outcome.get())    // the implementation of async saves us from double-calls
      }
    } else {
      listener(outcome.get())
    }
  }

  private[effect] def run(ec: ExecutionContext, masks: Int): Unit = {
    val cur = heapCur
    heapCur = null

    conts = new ArrayStack[IOCont](16)    // TODO tune!
    conts.push(RunTerminusK)

    ctxs = new ArrayStack[ExecutionContext](2)
    ctxs.push(ec)

    this.masks = masks
    runLoop(cur)
  }

  private def done(oc: Outcome[IO, Throwable, A]): Unit = {
    // println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    join = IO.pure(oc)

    val cb0 = callback.get()
    if (cb0.isInstanceOf[Function1[_, _]]) {
      val cb = cb0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]
      cb(oc)
    } else if (cb0.isInstanceOf[SafeArrayStack[_]]) {
      val stack = cb0.asInstanceOf[SafeArrayStack[AnyRef]]

      val bound = stack.unsafeIndex()
      val buffer = stack.unsafeBuffer()

      var i = 0
      while (i < bound) {
        buffer(i).asInstanceOf[Outcome[IO, Throwable, A] => Unit](oc)
        i += 1
      }

      callback.set(null)    // avoid leaks
    }
  }

  private def asyncContinue(state: AtomicReference[Either[Throwable, Any]], e: Either[Throwable, Any]): Unit = {
    state.lazySet(null)   // avoid leaks

    val cb = conts.pop()
    val ec = ctxs.peek()

    if (!canceled || masks != initMask) {
      ec execute { () =>
        e match {
          case Left(t) => cb(this, false, t)
          case Right(a) => cb(this, true, a)
        }

          runLoop(null)
        }
      }
    }

  // masks encoding: initMask => no masks, ++ => push, -- => pop
  private def runLoop(cur00: IO[Any]): Unit = {
    if (canceled && masks == initMask) {
      // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

      // this code is (mostly) redundant with Fiber#cancel for purposes of TCO
      val oc: Outcome[IO, Throwable, A] = Outcome.Canceled()
      if (outcome.compareAndSet(null, oc)) {
        done(oc)
        heapCur = null

        if (!finalizers.isEmpty()) {
          conts = new ArrayStack[IOCont](16)    // TODO tune!
          conts.push(CancelationLoopNodoneK)

          // suppress all subsequent cancelation on this fiber
          masks += 1
          runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
        }
      }
    } else {
      val cur0 = if (cur00 == null) {
        val back = heapCur
        heapCur = null
        back
      } else {
        cur00
      }

      // println(s"<$name> looping on $cur0")

      // cur0 will be null when we're semantically blocked
      if (!conts.isEmpty() && cur0 != null) {
        (cur0.tag: @switch) match {
          case 0 =>
            val cur = cur0.asInstanceOf[Pure[Any]]

            conts.pop()(this, true, cur.value)
            runLoop(null)

          case 1 =>
            val cur = cur0.asInstanceOf[Delay[Any]]

            val cb = conts.pop()
            try {
              cb(this, true, cur.thunk())
            } catch {
              case NonFatal(t) =>
                cb(this, false, t)
            }

            runLoop(null)

          case 2 =>
            val cur = cur0.asInstanceOf[Error]

            conts.pop()(this, false, cur.t)
            runLoop(null)

          case 3 =>
            val cur = cur0.asInstanceOf[Async[Any]]

            val done = new AtomicBoolean()

            /*
             * Four states here:
             *
             * - null (no one has completed, or everyone has)
             * - Left(null) (registration has completed without cancelToken but not callback)
             * - Right(null) (registration has completed with cancelToken but not callback)
             * - anything else (callback has completed but not registration)
             *
             * The purpose of this buffer is to ensure that registration takes
             * primacy over callbacks in the event that registration produces
             * errors in sequence. This implements a "queueing" semantic to
             * the callbacks, implying that the callback is sequenced after
             * registration has fully completed, giving us deterministic
             * serialization.
             */
            val state = new AtomicReference[Either[Throwable, Any]]()

            objectState.push(done)
            objectState.push(state)

            val next = cur.k { e =>
              // println(s"<$name> callback run with $e")
              // do an extra cancel check here just to preemptively avoid timer load
              if (!done.getAndSet(true) && !(canceled && masks == initMask)) {
                val s = state.getAndSet(e)
                if (s != null && suspended.compareAndSet(true, false)) {    // registration already completed, we're good to go
                  if (s.isRight) {
                    // we completed and were not canceled, so we pop the finalizer
                    // note that we're safe to do so since we own the runloop
                    finalizers.pop()
                  }

                  asyncContinue(state, e)
                }
              }
            }

            conts.push(AsyncK)

            runLoop(next)

          // ReadEC
          case 4 =>
            conts.pop()(this, true, ctxs.peek())
            runLoop(null)

          case 5 =>
            val cur = cur0.asInstanceOf[EvalOn[Any]]

            ctxs.push(cur.ec)
            conts.push(EvalOnK)

            cur.ec execute { () =>
              runLoop(cur.ioa)
            }

          case 6 =>
            val cur = cur0.asInstanceOf[Map[Any, Any]]

            objectState.push(cur.f)
            conts.push(MapK)

            runLoop(cur.ioe)

          case 7 =>
            val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

            objectState.push(cur.f)
            conts.push(FlatMapK)

            runLoop(cur.ioe)

          case 8 =>
            val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

            objectState.push(cur.f)
            conts.push(HandleErrorWithK)

            runLoop(cur.ioa)

          case 9 =>
            val cur = cur0.asInstanceOf[OnCase[Any]]

            val ec = ctxs.peek()
            finalizers push { oc =>
              val iou = try {
                cur.f(oc)
              } catch {
                case NonFatal(e) => IO.unit
              }

              if (ec eq ctxs.peek())
                iou
              else
                EvalOn(iou, ec)
            }
            // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

            conts.push(OnCaseK)

            runLoop(cur.ioa)

          case 10 =>
            val cur = cur0.asInstanceOf[Uncancelable[Any]]

            masks += 1
            val id = masks
            val poll = new (IO ~> IO) {
              def apply[B](ioa: IO[B]) = IO.Unmask(ioa, id)
            }

            conts.push(UncancelableK)

            runLoop(cur.body(poll))

          // Canceled
          case 11 =>
            canceled = true
            if (masks != initMask) {
              conts.pop()(this, true, ())
            }
            runLoop(null)

          case 12 =>
            val cur = cur0.asInstanceOf[Start[Any]]

            val childName = s"start-${childCount.getAndIncrement()}"

            // flip masking to negative and push it forward one tick to avoid potential conflicts with current fiber construction
            val initMask2 = if (masks != Int.MaxValue)
              -(masks + 1)
            else
              masks + 1   // will overflow

            val fiber = new IOFiber(
              cur.ioa,
              childName,
              timer,
              initMask2)

            // println(s"<$name> spawning <$childName>")

            val ec = ctxs.peek()
            ec.execute(() => fiber.run(ec, initMask2))

            conts.pop()(this, true, fiber)
            runLoop(null)

          case 13 =>
            val cur = cur0.asInstanceOf[RacePair[Any, Any]]

            val next = IO.async[Either[(Any, Fiber[IO, Throwable, Any]), (Fiber[IO, Throwable, Any], Any)]] { cb =>
              IO {
                val fiberA = new IOFiber(cur.ioa, s"racePair-left-${childCount.getAndIncrement()}", timer, initMask)
                val fiberB = new IOFiber(cur.iob, s"racePair-right-${childCount.getAndIncrement()}", timer, initMask)

                val firstError = new AtomicReference[Throwable](null)
                val firstCanceled = new AtomicBoolean(false)

                def listener(left: Boolean)(oc: Outcome[IO, Throwable, Any]): Unit = {
                  // println(s"listener fired (left = $left; oc = $oc)")

                  if (oc.isInstanceOf[Outcome.Completed[IO, Throwable, Any]]) {
                    val result = oc.asInstanceOf[Outcome.Completed[IO, Throwable, Any]].fa.asInstanceOf[IO.Pure[Any]].value

                    val wrapped = if (left)
                      Left((result, fiberB))
                    else
                      Right((fiberA, result))

                    cb(Right(wrapped))
                  } else if (oc.isInstanceOf[Outcome.Errored[IO, Throwable, Any]]) {
                    val error = oc.asInstanceOf[Outcome.Errored[IO, Throwable, Any]].e

                    if (!firstError.compareAndSet(null, error)) {
                      // we were the second to error, so report back
                      // TODO side-channel the error in firstError.get()
                      cb(Left(error))
                    } else {
                      // we were the first to error, double check to see if second is canceled and report
                      if (firstCanceled.get()) {
                        cb(Left(error))
                      }
                    }
                  } else {
                    if (!firstCanceled.compareAndSet(false, true)) {
                      // both are canceled, and we're the second, then cancel the outer fiber
                      canceled = true
                      // this is tricky, but since we forward our masks to our child, we *know* that we aren't masked, so the runLoop will just immediately run the finalizers for us
                      runLoop(null)
                    } else {
                      val error = firstError.get()
                      if (error != null) {
                        // we were canceled, and the other errored, so use its error
                        cb(Left(error))
                      }
                    }
                  }
                }

                fiberA.registerListener(listener(true))
                fiberB.registerListener(listener(false))

                val ec = ctxs.peek()

                ec.execute(() => fiberA.run(ec, masks))
                ec.execute(() => fiberB.run(ec, masks))

                Some(fiberA.cancel *> fiberB.cancel)
              }
            }

            runLoop(next)

          case 14 =>
            val cur = cur0.asInstanceOf[Sleep]

            runLoop(IO.async[Unit] { cb =>
              IO {
                val cancel = timer.sleep(cur.delay, () => cb(Right(())))
                Some(IO(cancel.run()))
              }
            })

          // RealTime
          case 15 =>
            conts.pop()(this, true, timer.nowMillis().millis)
            runLoop(null)

          // Monotonic
          case 16 =>
            conts.pop()(this, true, timer.monotonicNanos().nanos)
            runLoop(null)

          // Cede
          case 17 =>
            ctxs.peek() execute { () =>
              // println("continuing from cede ")

              conts.pop()(this, true, ())
              runLoop(null)
            }

          case 18 =>
            val cur = cur0.asInstanceOf[Unmask[Any]]

            if (masks == cur.id) {
              masks -= 1
            }

            conts.push(UnmaskK)

            runLoop(cur.ioa)
        }
      }
    }
  }
}

private object IOFiber {
  private val childCount = new AtomicInteger(0)

  ////////////////////////////////////////////////
  // preallocation of all necessary continuations
  ////////////////////////////////////////////////

  private object CancelationLoopK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val oc = outcome.get()

      if (!finalizers.isEmpty()) {
        conts.push(this)
        runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
      } else {
        done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
      }
    }
  }

  private object CancelationLoopNodoneK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      if (!finalizers.isEmpty()) {
        conts.push(this)
        runLoop(finalizers.pop()(outcome.get().asInstanceOf[Outcome[IO, Throwable, Any]]))
      }
    }
  }

  private object RunTerminusK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      if (canceled)   // this can happen if we don't check the canceled flag before completion
        outcome.compareAndSet(null, Outcome.Canceled())
      else if (success)
        outcome.compareAndSet(null, Outcome.Completed(IO.pure(result.asInstanceOf[A])))
      else
        outcome.compareAndSet(null, Outcome.Errored(result.asInstanceOf[Throwable]))

      done(outcome.get())
    }
  }

  private object AsyncK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val state = objectState.pop().asInstanceOf[AtomicReference[Either[Throwable, Any]]]
      val done = objectState.pop().asInstanceOf[AtomicBoolean]

      if (!success) {
        if (!done.getAndSet(true)) {
          // if we get an error before the callback, then propagate
          conts.pop()(self, success, result)
        } else {
          // we got the error *after* the callback, but we have queueing semantics
          // therefore, side-channel the callback results
          // println(state.get())

          asyncContinue(state, Left(result.asInstanceOf[Throwable]))
        }
      } else {
        if (masks == initMask) {
          result.asInstanceOf[Option[IO[Unit]]] match {
            case Some(cancelToken) =>
              finalizers.push(_.fold(cancelToken, _ => IO.unit, _ => IO.unit))

              // indicate the presence of the cancel token by pushing Right instead of Left
              if (!state.compareAndSet(null, Right(null))) {
                // the callback was invoked before registration
                finalizers.pop()
                asyncContinue(state, state.get())
              } else {
                suspended.set(true)
              }

            case None =>
              if (!state.compareAndSet(null, Left(null))) {
                // the callback was invoked before registration
                asyncContinue(state, state.get())
              } else {
                suspended.set(true)
              }
          }
        } else {
          // if we're masked, then don't even bother registering the cancel token
          if (!state.compareAndSet(null, Left(null))) {
            // the callback was invoked before registration
            asyncContinue(state, state.get())
          } else {
            suspended.set(true)
          }
        }
      }
    }
  }

  private object EvalOnK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      ctxs.pop()

      // special cancelation check to ensure we don't accidentally fork the runloop here
      if (!canceled || masks != initMask) {
        ctxs.peek() execute { () =>
          conts.pop()(self, success, result)
          runLoop(null)
        }
      }
    }
  }

  // NB: this means repeated map is stack-unsafe
  private object MapK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val cb = conts.pop()
      val f = objectState.pop().asInstanceOf[Any => Any]

      if (success) {
        try {
          // TODO cur state
          cb(self, true, f(result))
        } catch {
          case NonFatal(t) =>
            cb(self, false, t)
        }
      } else {
        cb(self, success, result)
      }
    }
  }

  private object FlatMapK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val f = objectState.pop().asInstanceOf[Any => IO[Any]]

      if (success) {
        try {
          heapCur = f(result)
        } catch {
          case NonFatal(t) =>
            conts.pop()(self, false, t)
        }
      } else {
        conts.pop()(self, success, result)    // I think this means error handling is stack-unsafe
      }
    }
  }

  private object HandleErrorWithK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val f = objectState.pop().asInstanceOf[Throwable => IO[Any]]

      if (success)
        conts.pop()(self, success, result)    // if it's *not* an error, just pass it along
      else
        // TODO try/catch
        heapCur = f(result.asInstanceOf[Throwable])
    }
  }

  private object OnCaseK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      val oc: Outcome[IO, Throwable, Any] = if (success)
        Outcome.Completed(IO.pure(result))
      else
        Outcome.Errored(result.asInstanceOf[Throwable])

      // println(s"popping from finalizers: length = ${finalizers.unsafeIndex()}")
      val f = finalizers.pop()
      // println(s"continuing onCase with $result ==> ${f(oc)}")

      heapCur = f(oc)

      booleanState.push(success)
      objectState.push(result.asInstanceOf[AnyRef])

      conts.push(OnCaseForwarderK)
    }
  }

  private object OnCaseForwarderK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._
      conts.pop()(self, booleanState.pop(), objectState.pop())
    }
  }

  private object UncancelableK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      masks -= 1
      conts.pop()(self, success, result)
    }
  }

  // [10] Unmask
  private object UnmaskK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any): Unit = {
      import self._

      masks += 1
      conts.pop()(self, success, result)
    }
  }
}
