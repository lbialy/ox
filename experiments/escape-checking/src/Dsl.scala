package escapechecking

import language.experimental.captureChecking
import caps.*

import java.util.concurrent.{ConcurrentLinkedQueue, ExecutionException, FutureTask}
import scala.util.boundary
import scala.util.boundary.{Label, break}

/** A capability classifier for values which may safely be shared with a fork.
  *
  * It is deliberately unrelated to [[caps.Control]]. A fork body may therefore
  * capture a `ForkScope`, while `Label` and `CanThrow` capabilities are excluded.
  */
trait Concurrency extends SharedCapability, Classifier

/** Evidence that new forks belong to the current structured concurrency scope. */
trait ForkScope extends Concurrency:
  private[escapechecking] def register(fork: Fork[?]): Unit

private final class DefaultForkScope extends ForkScope:
  private val children = ConcurrentLinkedQueue[Fork[?]]()

  private[escapechecking] def register(fork: Fork[?]): Unit =
    children.add(fork)
    ()

  def joinChildren(): Unit =
    var firstFailure: Throwable | Null = null
    var child = children.poll()
    while child != null do
      try child.join()
      catch
        case failure: Throwable if firstFailure == null => firstFailure = failure
        case _: Throwable                               => ()
      child = children.poll()
    if firstFailure != null then throw firstFailure

final class Fork[A] private[escapechecking] (private val task: FutureTask[A]):
  def join(): A =
    try task.get()
    catch
      case wrapped: ExecutionException => throw wrapped.getCause

/** Run a structured scope body on a virtual thread.
  *
  * `any.except[Control]` is the important part: the body can capture ordinary,
  * classified application capabilities, but it cannot carry a stack-bound
  * `Label`/`CanThrow` capability to the new virtual thread.
  */
def supervised[A](body: ForkScope ?->{any.except[Control]} A): A =
  val scope = DefaultForkScope()
  val main = fork(body(using scope))(using scope)
  try main.join()
  finally scope.joinChildren()

/** Start a virtual thread in the current scope.
  *
  * The by-name capture set is the capture-checking counterpart of Ox's current
  * `f: => T`. Excluding `Control` rejects boundary breaks at the call site.
  */
def fork[A](body: ->{any.except[Control]} A)(using scope: ForkScope): Fork[A] =
  val task = FutureTask[A](() => body)
  val result = Fork(task)
  scope.register(result)
  Thread.ofVirtual().start(task)
  result

/** Minimal Ox-shaped `either` DSL, implemented with the standard boundary API. */
object either:
  inline def apply[E, A](inline body: Label[Either[E, A]] ?=> A): Either[E, A] =
    boundary[Either[E, A]](Right(body))

  extension [E, A](value: Either[E, A])
    def ok()(using label: Label[Either[E, A]]): A =
      value match
        case Left(error)  => break(Left(error))
        case Right(value) => value

  extension [E](error: E)
    def fail[A]()(using label: Label[Either[E, A]]): Nothing =
      break(Left(error))

/** An example of a non-control capability which is safe to share with forks. */
trait IO extends SharedCapability, Classifier

final class EventLog extends IO:
  private val events = ConcurrentLinkedQueue[String]()

  def record(event: String): Unit =
    events.add(event)
    ()

  def snapshot: List[String] =
    import scala.jdk.CollectionConverters.*
    events.iterator().asScala.toList
