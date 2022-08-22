package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.{Chain, NonEmptyChain}
import cats.effect.Resource
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceIdPipeChainId
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Naturals.NaturalsConfig
import ch.epfl.bluebrain.nexus.testkit._
import fs2.Stream
import monix.bio.Task

import scala.concurrent.duration._

class SupervisionSuite
    extends MonixBioSuite
    with ProjectionFixture
    with DoobieFixture
    with DoobieAssertions
    with CollectionAssertions
    with EitherAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ProjectionStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val cfg = ProjectionConfig(3, 1, 10.millis, 10.millis)

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    store.entries.evalTap(row => store.delete(row.name)).compile.drain.runSyncUnsafe()
  }

  def supervisorResource: Resource[Task, Supervisor] =
    Resource.make(Supervisor(store, cfg))(s => s.stop())

  private def stopOnceWhen(
      compiled: CompiledProjection,
      stopped: Ref[Task, Boolean],
      f: Elem[Unit] => Boolean
  ): CompiledProjection =
    compiled.copy(
      streamF = offset =>
        status =>
          signal =>
            compiled.streamF(offset)(status)(signal).evalTap { elem =>
              stopped.get.ifM(
                ifTrue = Task.unit,
                ifFalse = if (f(elem)) stopped.set(true) >> status.update(_.stopped) >> signal.set(true) else Task.unit
              )
            }
    )

  private def failOnceWhen(
      compiled: CompiledProjection,
      failed: Ref[Task, Boolean],
      f: Elem[Unit] => Boolean
  ): CompiledProjection =
    compiled.copy(
      streamF = offset =>
        status =>
          signal =>
            compiled.streamF(offset)(status)(signal).evalTap { elem =>
              failed.get.ifM(
                ifTrue = Task.unit,
                ifFalse =
                  if (f(elem))
                    failed.set(true) >> Task.raiseError(new RuntimeException(s"Throw error for elem '${elem.offset}'"))
                  else Task.unit
              )
            }
    )

  projections.test("Restart projections when they stop") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 0.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined = ProjectionDef("naturals", None, None, sources, pipes)

    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(10L))

    supervisorResource.use { supervisor =>
      for {
        stopped  <- Ref.of[Task, Boolean](false)
        stopsOnce = stopOnceWhen(compiled, stopped, elem => elem.offset == Offset.at(4L))
        _        <- stopsOnce.supervise(supervisor, ExecutionStrategy.EveryNode)
        elems    <- ctx.waitForNElements(11, 50.millis)
        _         = assert(elems.size == 11, "Should have observed at 11 elements")
        _        <- Task.sleep(50.millis)
        elems    <- ctx.currentElements
        _         = assert(elems.size >= 3, "Should have observed at least another 3 elements")
        _        <- supervisor.status("naturals").assertSome(ExecutionStatus.Completed(offset))
      } yield ()
    }
  }

  projections.test("Restart projections when they fail") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 0.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined = ProjectionDef("naturals", None, None, sources, pipes)

    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(10L))

    supervisorResource.use { supervisor =>
      for {
        failed   <- Ref.of[Task, Boolean](false)
        failsOnce = failOnceWhen(compiled, failed, elem => elem.offset == Offset.at(4L))
        _        <- failsOnce.supervise(supervisor, ExecutionStrategy.EveryNode)
        elems    <- ctx.waitForNElements(15, 50.millis) // the log pipe sees already the 5L when 4L fails
        _         = assert(elems.size == 15, "Should have observed exactly 14 elements")
        _        <- Task.sleep(50.millis)
        elems    <- ctx.currentElements
        _         = assert(elems.isEmpty, "Should not observe any more elements")
        _        <- supervisor.status("naturals").assertSome(ExecutionStatus.Completed(offset))
      } yield ()
    }
  }

  projections.test("Stop running projections") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )

    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue

    supervisorResource.use { supervisor =>
      for {
        _     <- compiled.supervise(supervisor, ExecutionStrategy.EveryNode)
        elems <- ctx.waitForNElements(1, 50.millis)
        _      = assert(elems.nonEmpty, "Should have observed at least an element")
        _     <- supervisor.stop()
        _     <- Task.sleep(50.millis)
        elems <- ctx.currentElements
        _      = assert(elems.size < 9, "Should have observed less than 10 total elements")
      } yield ()
    }
  }

  projections.test("Ignore projections that do not match the node index") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )

    supervisorResource.use { supervisor =>
      for {
        name    <- Stream // get a name that hashcode modulo cluster size is different than the node index
                     .repeatEval(Task.delay(genString()))
                     .filter(_.hashCode % cfg.clusterSize != cfg.nodeIndex)
                     .take(1)
                     .compile
                     .toList
                     .map(_.head)
        defined  = ProjectionDef(name, None, None, sources, pipes)
        compiled = defined.compile(ctx.registry).rightValue
        _       <- compiled.supervise(supervisor, ExecutionStrategy.SingleNode(persistOffsets = false))
        elems   <- ctx.waitForNElements(1, 50.millis)
        _        = assert(elems.isEmpty, "Should not observe any elements")
        _       <- supervisor.status(name).assertSome(ExecutionStatus.Ignored)
      } yield ()
    }
  }

  projections.test("Run projections that match the node index") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )

    supervisorResource.use { supervisor =>
      for {
        name    <- Stream // get a name that hashcode modulo cluster size is equal to the node index
                     .repeatEval(Task.delay(genString()))
                     .filter(_.hashCode % cfg.clusterSize == cfg.nodeIndex)
                     .take(1)
                     .compile
                     .toList
                     .map(_.head)
        defined  = ProjectionDef(name, None, None, sources, pipes)
        compiled = defined.compile(ctx.registry).rightValue
        _       <- compiled.supervise(supervisor, ExecutionStrategy.SingleNode(persistOffsets = false))
        elems   <- ctx.waitForNElements(1, 50.millis)
        _        = assert(elems.nonEmpty, "Should observe at least an element")
      } yield ()
    }
  }

  projections.test("Run projections from the persisted offset") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 100.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )

    supervisorResource.use { supervisor =>
      for {
        name    <- Stream // get a name that hashcode modulo cluster size is equal to the node index
                     .repeatEval(Task.delay(genString()))
                     .filter(_.hashCode % cfg.clusterSize == cfg.nodeIndex)
                     .take(1)
                     .compile
                     .toList
                     .map(_.head)
        defined  = ProjectionDef(name, None, None, sources, pipes)
        compiled = defined.compile(ctx.registry).rightValue
        initial  = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(5L))
        _       <- store.save(name, compiled.project, compiled.resourceId, initial)
        _       <- compiled.supervise(supervisor, ExecutionStrategy.SingleNode(persistOffsets = true))
        elems   <- ctx.waitForNElements(4, 50.millis)
        _        = assert(elems.size == 4, "Should observe exactly 4 elements")
        _       <- Task.sleep(150.millis)
        elems   <- ctx.currentElements
        _        = assert(elems.size == 1, "Should observe exactly 1 element")
        written <- store.offset(name)
        nine     = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(9L))
        ten      = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(10L))
        _        = assert(written == nine || written == ten, "The new persisted offset should be either 9 or 10")
      } yield ()
    }
  }

  projections.test("UnSupervise projections") { ctx =>
    val sources = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )

    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue

    supervisorResource.use { supervisor =>
      for {
        _     <- compiled.supervise(supervisor, ExecutionStrategy.EveryNode)
        elems <- ctx.waitForNElements(1, 50.millis)
        _      = assert(elems.nonEmpty, "Should have observed at least an element")
        _     <- supervisor.unSupervise("naturals")
        _     <- Task.sleep(50.millis)
        elems <- ctx.currentElements
        _      = assert(elems.size < 9, "Should have observed less than 10 total elements")
        _     <- supervisor.status("naturals").assertNone
      } yield ()
    }
  }

}