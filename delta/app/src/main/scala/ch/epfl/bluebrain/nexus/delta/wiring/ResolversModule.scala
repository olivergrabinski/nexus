package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Resolver, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Resolvers].from {
    (
        fetchContext: FetchContext[ContextRejection],
        resolverContextResolution: ResolverContextResolution,
        config: AppConfig,
        xas: Transactors,
        api: JsonLdApi,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResolversImpl(
        fetchContext.mapRejection(ProjectContextRejection),
        resolverContextResolution,
        config.resolvers,
        xas
      )(api, clock, uuidF)
  }

  make[MultiResolution].from {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        resolvers: Resolvers,
        shifts: ResourceShifts
    ) =>
      MultiResolution(
        fetchContext.mapRejection(ProjectContextRejection),
        ResolverResolution(aclCheck, resolvers, shifts)
      )
  }

  make[ResolversRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        multiResolution: MultiResolution,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ResolversRoutes(identities, aclCheck, resolvers, multiResolution, schemeDirectives, indexingAction)(
        baseUri,
        config.resolvers.pagination,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => ResolverEvent.sseEncoder(base) }

  make[ResolverScopeInitialization]
  many[ScopeInitialization].ref[ResolverScopeInitialization]

  many[ApiMappings].add(Resolvers.mappings)

  many[ResourceToSchemaMappings].add(Resolvers.resourcesToSchemas)

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/resolvers-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      resolversCtx     <- ContextValue.fromFile("contexts/resolvers.json")
      resolversMetaCtx <- ContextValue.fromFile("contexts/resolvers-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.resolvers         -> resolversCtx,
      contexts.resolversMetadata -> resolversMetaCtx
    )
  )
  many[PriorityRoute].add { (route: ResolversRoutes) =>
    PriorityRoute(pluginsMaxPriority + 9, route.routes, requiresStrictEntity = true)
  }

  many[ResourceShift[_, _, _]].add { (resolvers: Resolvers, base: BaseUri) =>
    Resolver.shift(resolvers)(base)
  }
}
