package ch.epfl.bluebrain.nexus.delta.sdk.views

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityCheck, EntityDependencyStore}
import monix.bio.IO

trait ValidateAggregate[Rejection] {

  /**
    * Validate the reference tree to look up for unknown references and validates the number of nodes
    */
  def apply(references: NonEmptySet[ViewRef]): IO[Rejection, Unit]

}

object ValidateAggregate {

  def apply[Rejection](
      entityType: EntityType,
      ifUnknown: Set[ViewRef] => Rejection,
      maxViewRefs: Int,
      ifTooManyRefs: (Int, Int) => Rejection,
      xas: Transactors
  ): ValidateAggregate[Rejection] = (references: NonEmptySet[ViewRef]) =>
    EntityCheck.raiseMissingOrDeprecated[Iri, Rejection](
      entityType,
      references.value.map { v => v.project -> v.viewId },
      missing => ifUnknown(missing.map { case (p, id) => ViewRef(p, id) }),
      xas
    ) >> references.value.toList
      .foldLeftM(references.value.size) { (acc, ref) =>
        EntityDependencyStore.recursiveList(ref.project, ref.viewId, xas).map { r =>
          acc + r.size
        }
      }
      .flatMap { totalRefs =>
        IO.raiseWhen(totalRefs > maxViewRefs)(ifTooManyRefs(totalRefs, maxViewRefs))
      }

}