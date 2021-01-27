package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

package object model {

  /**
    * Type alias for a view specific resource.
    */
  type ElasticSearchViewResource = ResourceF[ElasticSearchView]

  /**
    * The fixed virtual schema of an ElasticSearchView.
    */
  final val schema: ResourceRef = Latest(schemas + "elasticsearchview.json")

  /**
    * The default IndexingElasticSearchView permission.
    */
  final val defaultPermission = Permission.unsafe("views/query")

}