package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.ExitCase
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import com.typesafe.scalalogging.Logger
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.query.Query0
import fs2.Stream
import io.circe.Json
import monix.bio.Task

import java.time.Instant

/**
  * Provide utility methods to stream results from the database according to a [[QueryConfig]].
  */
object StreamingQuery {

  private val logger: Logger = Logger[StreamingQuery.type]

  private val newState = "newState"

  /**
    * Streams states and tombstones as [[Elem]] s.
    *
    * State values are decoded via the provided function. If the function succeeds they will be streamed as
    * [[SuccessElem[A]] ]. If the function fails, they will be streames as [[FailedElem]]
    *
    * Tombstones are translated as [[DroppedElem]].
    *
    * The stream termination depends on the provided [[QueryConfig]]
    *
    * @param project
    *   the project of the states / tombstones
    * @param tag
    *   the tag to follow
    * @param start
    *   the offset to start with
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    * @param decodeValue
    *   the function to decode states
    */
  def elems[A](
      project: ProjectRef,
      tag: Tag,
      start: Offset,
      cfg: QueryConfig,
      xas: Transactors,
      decodeValue: (EntityType, Json) => Task[A]
  ): Stream[Task, Elem[A]] = {
    def query(offset: Offset): Query0[Elem[Json]] = {
      val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), offset.asFragment)
      sql"""((SELECT 'newState', type, id, org, project, value, instant, ordering, rev
           |FROM public.scoped_states
           |$where
           |ORDER BY ordering)
           |UNION
           |(SELECT 'tombstone', type, id, org, project, null, instant, ordering, -1
           |FROM public.scoped_tombstones
           |$where
           |ORDER BY ordering)
           |ORDER BY ordering)
           |""".stripMargin.query[(String, EntityType, String, Label, Label, Option[Json], Instant, Long, Int)].map {
        case (`newState`, entityType, id, org, project, Some(json), instant, offset, rev) =>
          SuccessElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), json, rev)
        case (_, entityType, id, org, project, _, instant, offset, rev)                   =>
          DroppedElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), rev)
      }
    }
    StreamingQuery[Elem[Json]](start, query, _.offset, cfg, xas)
      .evalMapChunk {
        case success: SuccessElem[Json] =>
          decodeValue(success.tpe, success.value).map(success.success).onErrorHandleWith { err =>
            Task
              .delay(
                logger.error(
                  s"An error occurred while decoding value with id '${success.id}' of type '${success.tpe}' in project '$project'.",
                  err
                )
              )
              .as(success.failed(err))
          }
        case dropped: DroppedElem       => Task.pure(dropped)
        case failed: FailedElem         => Task.pure(failed)
      }
  }

  /**
    * Streams the results of a query starting with the provided offset.
    *
    * The stream termination depends on the provided [[QueryConfig]].
    *
    * @param start
    *   the offset to start with
    * @param query
    *   the query to execute depending on the offset
    * @param extractOffset
    *   how to extract the offset from an [[A]] to be able to pursue the stream
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    */
  def apply[A](
      start: Offset,
      query: Offset => Query0[A],
      extractOffset: A => Offset,
      cfg: QueryConfig,
      xas: Transactors
  ): Stream[Task, A] = {
    def onComplete() = Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    def onError(th: Throwable) = Task.delay(
      logger.error(s"Single evaluation of query '${query(start).sql}' failed.", th)
    )

    def onCancel() = Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    cfg.refreshStrategy match {
      case RefreshStrategy.Stop         =>
        query(start)
          .streamWithChunkSize(cfg.batchSize)
          .transact(xas.streaming)
          .onFinalizeCase {
            case ExitCase.Completed => onComplete()
            case ExitCase.Error(th) => onError(th)
            case ExitCase.Canceled  => onCancel()
          }
      case RefreshStrategy.Delay(delay) =>
        Stream.eval(Ref.of[Task, Offset](start)).flatMap { ref =>
          Stream
            .eval(ref.get)
            .flatMap { offset =>
              query(offset)
                .streamWithChunkSize(cfg.batchSize)
                .transact(xas.streaming)
                .evalTapChunk { a => ref.set(extractOffset(a)) }
                .onFinalizeCase {
                  case ExitCase.Completed =>
                    onComplete() >> Task.sleep(delay) // delay for success
                  case ExitCase.Error(th) =>
                    onError(th) >> Task.sleep(delay) // delay for failure
                  case ExitCase.Canceled =>
                    onCancel()
                }
            }
            .repeat
        }
    }
  }

}
