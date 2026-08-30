import language.experimental.captureChecking
import caps.*

import scala.util.boundary.Label

def requireControlSubtype[T <: Control]: Unit = ()

val boundaryLabelIsControl: Unit = requireControlSubtype[Label[Int]]

