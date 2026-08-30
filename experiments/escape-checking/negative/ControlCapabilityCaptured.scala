package escapechecking.negative

import language.experimental.captureChecking
import caps.*

import escapechecking.*

trait Abort extends Control:
  def abort(): Nothing

final class AbortImpl extends Abort:
  def abort(): Nothing = throw AssertionError("must not run")

def arbitraryControlIsRejected: Nothing =
  supervised:
    val abort = AbortImpl()
    fork:
      // Rejected without knowing anything about boundary's implementation:
      // all capabilities classified as Control are excluded by `fork`.
      abort.abort()
    throw AssertionError("unreachable")

