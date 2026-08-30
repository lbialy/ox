package escapechecking.negative

import language.experimental.captureChecking

import escapechecking.*
import escapechecking.either.*

def labelCapturedByFork: Either[String, Int] =
  supervised:
    either:
      val child = fork:
        // Rejected: `.ok()` captures the enclosing boundary.Label, whose
        // classifier is Control, in a closure sent to another virtual thread.
        (Left("boom"): Either[String, Int]).ok()
      child.join()

