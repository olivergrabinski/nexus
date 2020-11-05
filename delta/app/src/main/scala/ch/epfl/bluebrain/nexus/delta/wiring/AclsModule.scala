package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Permissions}
import ch.epfl.bluebrain.nexus.delta.service.acls.{AclsConfig, AclsImpl}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Acls module wiring config.
  */
// $COVERAGE-OFF$
object AclsModule extends ModuleDef {

  make[AclsConfig].from((cfg: AppConfig) => cfg.acls)

  make[EventLog[Envelope[AclEvent]]].fromEffect { databaseEventLog[AclEvent](_, _) }

  make[Acls].fromEffect {
    (
        cfg: AclsConfig,
        eventLog: EventLog[Envelope[AclEvent]],
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        permissions: Permissions
    ) =>
      AclsImpl(cfg, permissions, eventLog)(as, scheduler, Clock[UIO])
  }

  make[AclsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new AclsRoutes(identities, acls)(baseUri, s, cr, ordering)
  }

}
// $COVERAGE-ON$