package ch.epfl.bluebrain.nexus.delta.sourcing.model

import doobie.{Get, Put}
import io.circe.Encoder

/**
  * Entity type
  */
final case class EntityType(value: String) extends AnyVal {
  override def toString: String = value
}

object EntityType {
  implicit val entityTypeGet: Get[EntityType] = Get[String].map(EntityType(_))
  implicit val entityTypePut: Put[EntityType] = Put[String].contramap(_.value)

  implicit val entityTypeEncoder: Encoder[EntityType] =
    Encoder.encodeString.contramap(_.value)
}
