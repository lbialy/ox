package ox.channels.jox

import java.util.concurrent.locks.LockSupport

/** One step of waiting for another thread to move a cell out of a transient state; returns the updated step count.
  *
  * On Scala Native, `Thread.onSpinWait` and `Thread.yield` don't unmount a virtual thread, so a spinning VT keeps its carrier. When the
  * thread it waits for is itself queued for a carrier (e.g. a select owner that must run `cleanup` after `done()` found its stale clause),
  * every carrier can end up spinning and nothing progresses. After a short spin, park briefly: that unmounts the VT and frees the carrier.
  */
private[jox] object Backoff:
  private final val SpinLimit = 64

  def apply(spins: Int): Int =
    if spins < SpinLimit then Thread.onSpinWait() else LockSupport.parkNanos(10_000)
    spins + 1
