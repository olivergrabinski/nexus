package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, Tag}
import doobie.implicits._
import doobie.{ConnectionIO, Get, Put}

object ScopedStateGet {

  def apply[Id: Put, S: Get](tpe: EntityType, project: ProjectRef, id: Id, tag: Tag): ConnectionIO[Option[S]] =
    sql"""SELECT value FROM scoped_states WHERE type = $tpe AND org = ${project.organization} AND project = ${project.project}  AND id = $id AND tag = $tag"""
      .query[S]
      .option

  def latest[Id: Put, S: Get](tpe: EntityType, project: ProjectRef, id: Id): ConnectionIO[Option[S]] =
    apply(tpe, project, id, Latest)

}
