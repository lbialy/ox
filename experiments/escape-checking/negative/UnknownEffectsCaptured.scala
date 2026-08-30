package escapechecking.negative

import language.experimental.captureChecking

import escapechecking.*

def unknownEffectsAreRejected(effect: () => Unit): Unit =
  supervised:
    fork:
      // Rejected conservatively: an ordinary `=>` function may hide Control
      // capabilities. Libraries need capture-aware callback types end-to-end.
      effect()
    ()

