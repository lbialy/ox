package escapechecking.negative

import language.experimental.captureChecking

import escapechecking.*
import escapechecking.either.*

def failCapturedByFork: Either[String, Int] =
  supervised:
    either[String, Int]:
      val child = fork:
        // Rejected just like `.ok()`: `.fail()` would break using the label
        // owned by the enclosing virtual thread.
        "boom".fail[Int]()
      child.join()

