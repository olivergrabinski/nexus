package ch.epfl.bluebrain.nexus.delta.sdk

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingMode.{Async, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, Elem, Projection}
import monix.bio.{IO, Task, UIO}

import scala.concurrent.duration._

trait IndexingAction {

  implicit private val bc: BatchConfig = BatchConfig.individual

  /**
    * The maximum duration accepted to perform the synchronous indexing
    * @return
    */
  def timeout: FiniteDuration

  /**
    * Initialize the indexing projections to perform for the given element
    * @param project
    *   the project where the view to fetch live
    * @param elem
    *   the element to index
    */
  def projections(project: ProjectRef, elem: Elem[GraphResource]): ElemStream[CompiledProjection]

  def apply(project: ProjectRef, elem: Elem[GraphResource]): Task[Unit] = {
    for {
      // We build and start the projections where the resource will apply
      _ <- projections(project, elem)
             .evalMap {
               case s: SuccessElem[CompiledProjection] =>
                 runProjection(s.value, _ => UIO.unit, elem)
               case _: DroppedElem                     => UIO.unit
               case _: FailedElem                      => UIO.unit
             }
             .compile
             .toList
    } yield ()
  }

  private def runProjection(compiled: CompiledProjection, saveFailedElems: List[FailedElem] => UIO[Unit], elem: Elem[GraphResource]) = {
    for {
      projection <- Projection(compiled, UIO.none, _ => UIO.unit, saveFailedElems)
      _          <- UIO.delay(println(s"Starting projection for elem ${elem.id}"))
      _          <- projection.waitForCompletion(timeout)
      _          <- UIO.delay(println(s"Projection completed for elem ${elem.id}"))
      // We stop the projection if it has not complete yet
      _          <- projection.stop()
      _          <- UIO.delay(println(s"Projection stopped for elem ${elem.id}"))
    } yield ()
  }
}

object IndexingAction {

  type Execute[A] = (ProjectRef, ResourceF[A], IndexingMode) => Task[Unit]

  /**
    * Does not perform any action
    */
  def noop[A]: Execute[A] = (_, _, _) => IO.unit

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]] s in parallel.
    */
  final class AggregateIndexingAction(private val internal: NonEmptyList[IndexingAction])(implicit
      cr: RemoteContextResolution
  ) {

    def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode)(implicit
        shift: ResourceShift[_, A, _]
    ): Task[Unit] =
      indexingMode match {
        case Async => IO.unit
        case Sync  =>
          for {
            // We create the GraphResource wrapped in an `Elem`
            _    <- Task.delay(println(s"Starting for ${res.id}"))
            elem <- shift.toGraphResourceElem(project, res).toBIOThrowable
            _    <- Task.delay(println(s"Intermediate for ${res.id}"))
            _    <- internal.traverse(_.apply(project, elem))
            _    <- Task.delay(println(s"End for ${res.id}"))
          } yield ()
      }
  }

  object AggregateIndexingAction {
    def apply(
        internal: NonEmptyList[IndexingAction]
    )(implicit cr: RemoteContextResolution): AggregateIndexingAction =
      new AggregateIndexingAction(internal)
  }
}
