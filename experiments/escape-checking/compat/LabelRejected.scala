import language.experimental.captureChecking
import caps.*

import scala.util.boundary

def captureAwareFork[A](body: ->{any.except[Control]} A): A = body

def labelCaptureMustFail: Int =
  boundary[Int]:
    captureAwareFork(boundary.break(42))

