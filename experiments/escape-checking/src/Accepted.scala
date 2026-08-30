package escapechecking

import language.experimental.captureChecking
import escapechecking.either.*

@main def acceptedExamples(): Unit =
  // Safe shape 1: the child returns an Either as a value. `.ok()` runs in the
  // supervised main body, on the same virtual thread as the enclosing label.
  val joinedThenUnwrapped = supervised:
    either:
      val child = fork(Right(21): Either[String, Int])
      child.join().ok() * 2

  assert(joinedThenUnwrapped == Right(42))

  // Safe shape 2: the child owns its own `either` boundary. The label is born,
  // used, and caught on that child virtual thread; only an Either value crosses.
  val childLocalBoundary = supervised:
    val child = fork:
      either:
        val n = (Right(21): Either[String, Int]).ok()
        n * 2
    child.join()

  assert(childLocalBoundary == Right(42))

  val childLocalFailure = supervised:
    val child = fork:
      either[String, Int]:
        "boom".fail[Int]()
    child.join()

  assert(childLocalFailure == Left("boom"))

  // Classifier exclusion is selective, not a blanket purity restriction.
  // `EventLog` is IO-classified and unrelated to Control, so it may be shared.
  val log = EventLog()
  val logged = supervised:
    val child = fork:
      log.record("ran on a virtual thread")
      42
    child.join()

  assert(logged == 42)
  assert(log.snapshot == List("ran on a virtual thread"))

  println("accepted examples passed")
