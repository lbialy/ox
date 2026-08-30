import language.experimental.captureChecking
import caps.*

trait ProbeIO extends SharedCapability, Classifier

def captureAwareFork[A](body: ->{any.except[Control]} A): A = body

def nonControlCaptureIsAccepted(io: ProbeIO): String =
  captureAwareFork(io.toString)
