package escapechecking.negative

import language.experimental.captureChecking

import scala.util.boundary

def labelEscapesLexically: () => Int =
  boundary[() => Int]:
    // Rejected by ordinary escape checking: the returned closure would retain
    // a Label after the boundary that owns it has already completed.
    () => boundary.break(() => 42)

