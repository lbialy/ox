package warp

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.time.Clock

class WarpTest extends AnyFlatSpec with Matchers {
  class Trail(var trail: Vector[String] = Vector.empty) {
    def add(s: String): Unit = {
      info(s"[${Clock.systemUTC().instant()}] [${Thread.currentThread().threadId()}] $s")
      trail = trail :+ s
    }
  }

  it should "run two forks concurrently" in {
    val trail = Trail()
    Warp {
      val f1 = Warp.fork {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = Warp.fork {
        Thread.sleep(1000)
        trail.add("f2 complete")
        6
      }
      trail.add("main mid")
      trail.add(s"result = ${f1.join() + f2.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow nested forks" in {
    val trail = Trail()
    Warp {
      val f1 = Warp.fork {
        val f2 = Warp.fork {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "interrupt child fibers when parents complete" in {
    val trail = Trail()
    Warp {
      val f1 = Warp.fork {
        Warp.fork {
          try
            Thread.sleep(1000)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }

        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 interrupted", "result = 5")
  }

  it should "properly propagate fiber local values" in {
    val trail = Trail()
    val v = Warp.FiberLocal("a")
    Warp {
      val f1 = Warp.fork {
        v.forkWhere("x") {
          Thread.sleep(100L)
          trail.add(s"In f1 = ${v.get()}")
        }.join()
        v.get()
      }

      val f3 = Warp.fork {
        v.forkWhere("z") {
          Thread.sleep(100L)
          Warp.fork {
            Thread.sleep(100L)
            trail.add(s"In f3 = ${v.get()}")
          }.join()
        }.join()
        v.get()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
      trail.add(s"result = ${f3.join()}")
    }

    trail.trail shouldBe Vector("main mid", "In f1 = x", "result = a", "In f3 = z", "result = a")
  }

  it should "when interrupted, wait until uninterruptible blocks complete" in {
    val trail = Trail()
    Warp {
      val f = Warp.fork {
        trail.add(s"Fork start")

        Warp.uninterruptible {
          trail.add(s"Sleep start")
          Thread.sleep(2000)
          trail.add("Sleep done")
        }

        trail.add("Fork done")
      }

      Thread.sleep(100)
      trail.add("Cancelling ...")
      trail.add(s"Cancel result = ${f.cancel()}")
    }

    trail.trail shouldBe Vector("Fork start", "Sleep start", "Cancelling ...", "Sleep done", "Cancel result = Left(java.lang.InterruptedException)")
  }
}
