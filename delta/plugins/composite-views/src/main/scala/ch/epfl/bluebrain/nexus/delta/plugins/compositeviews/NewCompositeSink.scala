package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.NewQueryGraph
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import monix.bio.Task
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * A sink that queries N-Triples in Blazegraph, transforms them, and pushes the result to the provided sink
  * @param queryGraph
  *   how to query the blazegraph
  * @param transform
  *   function to transform a graph into the format needed by the sink
  * @param sink
  *   function that allows
  * @param chunkSize
  *   the maximum number of elements to be pushed in ES at once
  * @param maxWindow
  *   the maximum number of elements to be pushed at once
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class NewCompositeSink[SinkFormat](
    queryGraph: NewQueryGraph,
    transform: GraphResource => Task[Option[SinkFormat]],
    sink: Chunk[Elem[SinkFormat]] => Task[Chunk[Elem[Unit]]],
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
) extends Sink {

  override type In = GraphResource
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  private def query(elements: Chunk[Elem[GraphResource]]): Task[Option[Graph]] =
    elements.mapFilter(elem => elem.toOption) match {
      case elems if elems.nonEmpty => queryGraph(elems)
      case _                       => Task.none
    }

  private def transformAndSink(elements: Chunk[Elem[GraphResource]], graph: Graph) =
    elements
      .traverse { elem =>
        elem.evalMapFilter(gr => transform(replaceGraph(gr, graph)))
      }
      .flatMap(sink)

  private def replaceGraph(gr: GraphResource, graph: Graph) =
    gr.copy(graph = graph.copy(rootNode = gr.id))

  override def apply(elements: Chunk[Elem[GraphResource]]): Task[Chunk[Elem[Unit]]] = {
    val graph = query(elements)

    graph.flatMap {
      case Some(g) => transformAndSink(elements, g)
      case None    => Task.pure(elements.map(_.dropped))
    }
  }
}
