package escapechecking.negative

import language.experimental.captureChecking

import escapechecking.*
import scala.util.boundary

def boundaryOutsideSupervised: Int =
  boundary[Int]:
    // Rejected for the same reason at the outer structured-scope transition:
    // the supervised body itself runs on a new virtual thread.
    supervised:
      boundary.break(42)

