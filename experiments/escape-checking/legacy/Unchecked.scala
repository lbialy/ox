package escapechecking.legacy

import language.experimental.captureChecking

import escapechecking.either
import escapechecking.either.*
import java.util.concurrent.{ExecutionException, FutureTask}
import java.util.concurrent.atomic.AtomicBoolean

/** This intentionally retains an ordinary impure by-name parameter (`=> A`).
  * Capture checking therefore has no cross-thread policy to enforce.
  */
def uncheckedFork[A](body: => A): A =
  val task = FutureTask[A](() => body)
  Thread.ofVirtual().start(task)
  try task.get()
  catch
    case wrapped: ExecutionException => throw wrapped.getCause

@main def legacyProblemStillReproduces(): Unit =
  val reachedAfterFork = AtomicBoolean(false)

  val result = either:
    uncheckedFork:
      (Left("boom"): Either[String, Int]).ok()
    reachedAfterFork.set(true)
    42

  assert(result == Left("boom"))
  assert(!reachedAfterFork.get())
  println("legacy signature allowed a Label to cross a virtual thread")

