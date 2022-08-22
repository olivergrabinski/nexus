package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.MonixBioSuite
import fs2.concurrent.Queue
import monix.bio.Task

trait ProjectionFixture { self: MonixBioSuite =>

  val projections: FunFixture[ProjectionTestContext] = FunFixture[ProjectionTestContext](
    setup = { _ =>
      val queue    = Queue.unbounded[Task, SuccessElem[String]].runSyncUnsafe()
      val registry = new ReferenceRegistry()
      registry.register(Naturals)
      registry.register(Strings)
      registry.register(Evens)
      registry.register(Odds)
      registry.register(TimesN)
      registry.register(FailEveryN)
      registry.register(IntToString)
      registry.register(Log(queue))
      ProjectionTestContext(registry, queue)
    },
    teardown = { ctx =>
      ctx.queue.tryDequeueChunk1(Int.MaxValue).void.runSyncUnsafe()
    }
  )

}