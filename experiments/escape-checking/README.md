# Capture checking for `either` across virtual-thread boundaries

## Result

Scala 3's current capture-calculus direction can prevent the Ox failure mode,
provided the concurrency API adopts a capture-aware signature.

The decisive feature is not lexical escape checking by itself. A structured
child can finish before its enclosing `either` scope and still use the label on
the wrong thread. The missing constraint is the capability-classifier exclusion
now available in the 3.10 nightly:

```scala
def supervised[A](body: ForkScope ?->{any.except[Control]} A): A
def fork[A](body: ->{any.except[Control]} A)(using ForkScope): Fork[A]
```

`scala.util.boundary.Label` now extends `caps.Control`. When `.ok()` uses an
enclosing label inside a fork body, that body is inferred to capture the label.
It cannot conform to a by-name computation whose allowed capture set excludes
`Control`, so compilation fails before any virtual thread starts.

The experiment uses exactly:

```text
3.10.1-RC1-bin-20260830-c853a31-NIGHTLY
```

## How the experiment developed

I started by reducing the Ox problem to the smallest executable example. The
[unchecked version](legacy/Unchecked.scala) keeps the usual `body: => A`
signature, starts a real virtual thread, and unwraps the exception thrown by
`boundary.break`. It compiles with capture checking enabled and reproduces the
bug: the child thread reaches the enclosing `either` label and skips the code
after the fork.

I then tried ordinary escape checking. It correctly rejects a closure which is
returned after retaining a local label, as shown by
[LabelEscapesLexically.scala](negative/LabelEscapesLexically.scala). That did not
solve the thread problem, however: a joined child can use the label while the
outer boundary is still alive. The label has not escaped its lexical lifetime;
it has moved to the wrong thread.

Next I changed the concurrency boundary itself. The
[capture-aware DSL](src/Dsl.scala) marks both `supervised` and `fork` bodies as
allowing any capture except `Control`. This worked: the Ox-shaped `.ok()` case in
[LabelCapturedByFork.scala](negative/LabelCapturedByFork.scala) and the matching
`.fail()` case in [FailCapturedByFork.scala](negative/FailCapturedByFork.scala)
are rejected at compile time because they would carry the enclosing label into
the child.

I checked that the rule was not too restrictive. The
[accepted examples](src/Accepted.scala) show three useful alternatives: return
an `Either` from the child and unwrap it after joining, put the complete `either`
scope inside the child, or capture an ordinary thread-safe application
capability. All three compile and run.

I then tested the edges of the result. A label cannot cross the main-body thread
hop made by [`supervised`](negative/BoundaryOutsideSupervised.scala), and an
unrelated custom [`Control` capability](negative/ControlCapabilityCaptured.scala)
is rejected too, showing that the check is not special-cased for `Either`.
[UnknownEffectsCaptured.scala](negative/UnknownEffectsCaptured.scala) also shows
that an ordinary function with untracked effects cannot be smuggled through the
capture-aware API.

Finally, I ran small compatibility probes across Scala releases. The
[prerequisite probe](compat/Prerequisites.scala) shows that Scala 3.9 already
classifies `boundary.Label` as `Control`, while the
[feature probe](compat/FeatureAvailable.scala) shows that 3.9 cannot express the
needed `any.except[Control]` policy. The first published nightly where both the
positive probe and the [label-rejection probe](compat/LabelRejected.scala)
behave as required is the 2026-07-11 Scala 3.10 nightly. The complete sequence
can be rerun with [verify.sh](verify.sh).

## Minimum Scala version

The earliest published compiler artifact with the required open-world policy is:

```text
3.10.0-RC1-bin-20260711-702fa74-NIGHTLY
```

The earliest non-nightly release is:

```text
3.10.0-RC1
```

No Scala 3.9 release has classifier exclusion. Scala 3.9.0 already has the
earlier prerequisites—`caps.Control` exists and `boundary.Label` is a subtype of
it—but `any.except[Control]` is not valid syntax. Classifier exclusion was added
by [scala/scala3#26383](https://github.com/scala/scala3/pull/26383), targeted at
the 3.10.0 milestone. The PR was merged on 2026-07-09. The 2026-07-07 nightly
still rejects the syntax, while the next published nightly, from 2026-07-11,
accepts non-control captures and rejects a captured `Label`.

| Compiler | Prerequisites | `any.except[Control]` | Label-crossing result |
| --- | --- | --- | --- |
| `3.9.0-RC4` | present | syntax error | unavailable |
| `3.9.0-RC1-bin-20260526-cf3cc7a-NIGHTLY` | present | syntax error | unavailable |
| `3.9.0` | present | syntax error | unavailable |
| `3.10.0-RC1-bin-20260707-a4dab1a-NIGHTLY` | present | syntax error | unavailable |
| `3.10.0-RC1-bin-20260711-702fa74-NIGHTLY` | present | accepted | rejected as intended |
| `3.10.0-RC1` | present | accepted | rejected as intended |
| `3.10.1-RC1-bin-20260830-c853a31-NIGHTLY` | present | accepted | rejected as intended |

The probes are in `compat/`:

- [Prerequisites.scala](compat/Prerequisites.scala) proves that 3.9 already
  classifies `Label` as `Control`.
- [FeatureAvailable.scala](compat/FeatureAvailable.scala) checks that a
  non-control capability is accepted by `any.except[Control]`.
- [LabelRejected.scala](compat/LabelRejected.scala) must fail specifically
  because the local label cannot flow into `any.except[Control]`.

Scala 3.9 can implement a much narrower workaround by requiring a completely
pure fork body, or by enumerating every admitted capture explicitly. It cannot
express Ox's useful open-world rule “allow arbitrary capabilities except
stack-bound control capabilities.”

## Layout

- [src/Dsl.scala](src/Dsl.scala) contains the minimal `supervised` / `fork` /
  `either` DSL. Both scope bodies and child bodies really run on JDK virtual
  threads.
- [src/Accepted.scala](src/Accepted.scala) contains safe arrangements that
  compile and run.
- [legacy/Unchecked.scala](legacy/Unchecked.scala) deliberately uses
  `body: => A` and reproduces the illegal cross-thread boundary jump despite
  capture checking being enabled.
- [`negative/`](negative/) contains programs which must not compile.
- [verify.sh](verify.sh) runs the accepted cases, reproduces the legacy bug, and
  checks all expected compilation failures independently.

Run everything with:

```bash
bash verify.sh
```

Or inspect individual cases:

```bash
scala-cli run project.scala src/Dsl.scala src/Accepted.scala --server=false
scala-cli run project.scala src/Dsl.scala legacy/Unchecked.scala --server=false
scala-cli compile project.scala src/Dsl.scala negative/LabelCapturedByFork.scala --server=false
```

The last command is expected to fail with a diagnostic equivalent to:

```text
Found:    () ?->{local} Int
Required: () ?->{any.except[Control]} Int
Note that capability `local` cannot flow into capture set {any.except[Control]}.
```

## What each case establishes

| Case | Expected | What it establishes |
| --- | --- | --- |
| Join `Fork[Either[E, A]]`, then call `.ok()` | accepted | The error value crosses the thread; the label does not. |
| Put a complete `either` boundary inside the child | accepted | The label is created, used, and caught on one virtual thread. |
| Capture an `IO`-classified `EventLog` in a child | accepted | Excluding `Control` does not force fork bodies to be pure. |
| Use `.ok()` or `.fail()` with an enclosing label in a child | rejected | The concrete Ox-shaped failures are prevented. |
| Put a boundary outside `supervised` | rejected | Ox-style supervision must also declare its own main-body thread hop. |
| Capture any custom `Control` capability | rejected | The rule is semantic and is not special-cased to `Label`. |
| Return a closure retaining a label | rejected | Ordinary scoped-capability escape checking also works. |
| Pass an effect-polymorphic `() => Unit` through the boundary | rejected | Unknown effects are conservatively assumed capable of hiding `Control`. |
| Keep the old impure `body: => A` signature | accepted, unsafe | Capture checking only enforces policies represented in API types. |

## Implications for Ox

This is a positive feasibility result, not a drop-in patch for current Ox.

1. `either` can continue to use `scala.util.boundary`; the nightly standard
   library already classifies its `Label` as `Control`.
2. Every operation which moves a computation to another thread needs a callback
   or by-name type capped by `any.except[Control]`. For Ox this includes `fork`
   variants and `supervised`, because the latter runs its main body on a new
   virtual thread.
3. The structured-concurrency scope capability needs explicit treatment so that
   nested forks remain expressible. It can use a classifier unrelated to
   `Control`, as `ForkScope` does here, or extend `Control` and be supplied as a
   contextual parameter to each thread body. The final section examines this
   choice.
4. Higher-order APIs called from fork bodies also need useful capture-aware
   signatures. An ordinary impure function value may conceal a `Control`
   capability and is therefore rejected conservatively.
5. Existing source and binary compatibility, inference through Ox's inline
   combinators, `Fork[T]` result capture tracking, and interaction with all fork
   variants still need production-level design and migration work.
6. Capture checking and classifier exclusion remain experimental. Syntax and
   inference behavior may still change before stabilization.

The strongest conclusion supported here is: the nightly type system can express
and enforce “this computation may cross a thread boundary only if it captures no
stack-bound control capability,” and it recognizes `boundary.Label` as exactly
such a capability. That directly covers the family of label/checked-exception/
control-token escapes described in the issue.

## Primary sources

- [Capture Checking Basics](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/basics.html)
  defines pure arrows, capture sets, by-name capture types, and lexical escape
  checking.
- [Scoped Capabilities](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/scoped-capabilities.html)
  explains scope levels, local `any`, `fresh`, and why scoped capabilities cannot
  be widened into longer-lived results.
- [Capability Classifiers](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/classifiers.html)
  gives the cross-thread `any.except[Control]` rule and explicitly identifies
  `boundary.Label` and `CanThrow` as stack-bound controls.
- [Current `boundary.scala`](https://github.com/scala/scala3/blob/main/library/src/scala/util/boundary.scala)
  declares `Label[-T] extends caps.Control`.
- [Scala nightly repository announcement](https://www.scala-lang.org/news/new-scala-nightlies-repo.html)
  documents the resolver needed for post-2025 nightly artifacts.

## The `Control` classifier is broader than `Label`

`any.except[Control]` removes the whole `Control` branch, not only
`boundary.Label`. In the standard library shipped with the tested nightly, the
direct implementations are:

```text
SharedCapability
└── Control
    ├── boundary.Label[T]
    └── CanThrow[E]
```

Consequently, the proposed fork boundary also rejects capture-checked
`CanThrow` evidence. It does not reject capabilities under unrelated classifiers
such as `IO`, `Mutable`, or `Unscoped`. A value known only as
`SharedCapability`, however, is rejected conservatively because its type does
not prove that it is unrelated to the open `Control` branch. Despite the similar
name, `scala.util.control.ControlThrowable` is not a subtype of `caps.Control`.

The [classifier documentation](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/classifiers.html)
also presents Gears' `Async` capability as extending `Control`, because it can
suspend the current computation. `Async.Spawn` would then be a transitive member
of the same branch. This is currently a design example rather than the released
Gears definition: [Gears `main`](https://github.com/lampepfl/gears/blob/a8f9188d238ed30962abd80b92ce4f1e780fbfb5/shared/src/main/scala/async/Async.scala#L33)
does not enable capture checking, while its experimental
[`add-cc` branch](https://github.com/lampepfl/gears/blob/9df9f135be86f683b95ca2b95415670cbee8a2f0/shared/src/main/scala/async/Async.scala#L35)
currently classifies `Async` only as `SharedCapability`.

This matters because Gears' `Async` and Ox's `Ox` play similar roles as scoped
structured-concurrency capabilities. If `Ox` itself extends `Control`, the
existing Ox-shaped signature below rejects nested forks: the child body captures
the ambient `Ox` along with every other `Control` capability.

```scala
def fork[T](body: ->{any.except[Control]} T)(using Ox): Fork[T]
```

There are two viable ways to avoid that over-restriction:

1. Keep `Ox` under a classifier unrelated to `Control`, as this experiment does
   with [`Concurrency` and `ForkScope`](src/Dsl.scala). This treats `Ox` as a
   thread-safe scope handle which may deliberately be shared with its children.
2. Let `Ox` extend `Control`, but make the child body a contextual function which
   receives its `Ox` instead of capturing the outer instance:

   ```scala
   def fork[T](body: Ox ?->{any.except[Control]} T)(using Ox): Fork[T]
   ```

   Tested on the requested nightly, this form accepts nested forks while still
   rejecting an enclosing `boundary.Label`. Gears' experimental
   [`Future.apply`](https://github.com/lampepfl/gears/blob/9df9f135be86f683b95ca2b95415670cbee8a2f0/shared/src/main/scala/async/futures.scala#L248-L254)
   uses the same contextual-body shape for `Async.Spawn`.

The experiment therefore establishes that classifier exclusion can stop the
unsafe label transfer, but it does not by itself settle how Ox should classify
its own scope capability. That choice must be made together with the final
capture-aware signatures for `supervised`, `fork`, and their variants.
