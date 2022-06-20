package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.ProjectionOffsetRow
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import monix.bio.{IO, Task, UIO}
import fs2.Stream

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.annotation.nowarn

/**
  * Persistent operations for projections.
  */
trait ProjectionStore {

  /**
    * Saves a projection offset.
    *
    * @param name
    *   the name of the projection
    * @param project
    *   an optional project reference
    * @param resourceId
    *   an optional resource id
    * @param offset
    *   the offset to save
    */
  def save(name: String, project: Option[ProjectRef], resourceId: Option[Iri], offset: ProjectionOffset): UIO[Unit]

  /**
    * Retrieves a projection offset, defaulting to [[ProjectionOffset.empty]] when not found.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): UIO[ProjectionOffset]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): UIO[Unit]

  /**
    * @return
    *   all known projection offset entries
    */
  def entries: Stream[Task, ProjectionOffsetRow]

}

object ProjectionStore {

  final case class ProjectionOffsetRow(
      name: String,
      project: Option[ProjectRef],
      resourceId: Option[Iri],
      offset: ProjectionOffset,
      createdAt: Instant,
      updatedAt: Instant
  )
  object ProjectionOffsetRow {
    implicit val projectionOffsetRowRead: Read[ProjectionOffsetRow] = {
      Read[(String, Option[ProjectRef], Option[Iri], ProjectionOffset, Instant, Instant)].map {
        case (name, project, resourceId, offset, createdAt, updatedAt) =>
          ProjectionOffsetRow(name, project, resourceId, offset, createdAt, updatedAt)
      }
    }
  }

  @nowarn("cat=unused")
  implicit private val iriGet: Get[Iri] = Get[String].temap(str => Iri.absolute(str))
  implicit private val iriPut: Put[Iri] = Put[String].contramap(_.toString)

  def apply(xas: Transactors, config: QueryConfig): ProjectionStore =
    new ProjectionStore {
      override def save(
          name: String,
          project: Option[ProjectRef],
          resourceId: Option[Iri],
          offset: ProjectionOffset
      ): UIO[Unit] =
        IO.clock[Nothing].realTime(TimeUnit.MILLISECONDS).flatMap { ts =>
          val instant = Instant.ofEpochMilli(ts)
          sql"""INSERT INTO projection_offsets (name, project, resource_id, value, created_at, updated_at)
               |VALUES ($name, $project, $resourceId, $offset, $instant, $instant)
               |ON CONFLICT (name)
               |DO UPDATE set
               |  project = EXCLUDED.project,
               |  resource_id = EXCLUDED.resource_id,
               |  value = EXCLUDED.value,
               |  updated_at = EXCLUDED.updated_at;
               |""".stripMargin.update.run
            .transact(xas.streaming)
            .void
            .hideErrors
        }

      override def offset(name: String): UIO[ProjectionOffset] =
        sql"""SELECT value FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin
          .query[ProjectionOffset]
          .option
          .transact(xas.streaming)
          .hideErrors
          .map(_.getOrElse(ProjectionOffset.empty))

      override def delete(name: String): UIO[Unit] =
        sql"""DELETE FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin.update.run
          .transact(xas.streaming)
          .void
          .hideErrors

      override def entries: Stream[Task, ProjectionOffsetRow] =
        sql"""SELECT * from projection_offsets;"""
          .query[ProjectionOffsetRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)
    }

}