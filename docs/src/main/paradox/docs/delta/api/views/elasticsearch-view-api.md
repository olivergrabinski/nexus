# ElasticSearchView

This view creates an ElasticSearch `index` and stores the targeted Json resources into an ElasticSearch Document.

The documents created on each view are isolated from documents created on other views by using different ElasticSearch
indices.

A default view gets automatically created when the project is created but other views can be created.

## Processing pipeline

An asynchronous process gets triggered for every view. This process can be visualized as a pipeline with different stages.

The first stage is the input of the pipeline: a stream of events scoped for the project where the view was created.

For each event:

* The related resource is fetched
* A series of filters and transformations is then applied to this resource
* If the resource is filtered out, the previous version of the resource is removed from the index if present
* If the resource makes it to the end of the pipeline, a Json document is generated by merging the original payload, 
the data and metadata graphs and then stored in the corresponding Elasticsearch index.

[![ElasticSearchView pipeline](../assets/views/elasticsearch/elasticsearch_pipeline.png "ElasticSearchView pipeline")](../assets/views/elasticsearch/elasticsearch_pipeline.png)

## Payload

The payload includes a pipeline of transformations and filters to apply to the different resources.
The stages of the pipeline are applied sequentially on the resource as defined in the payload.

```json
{
  "@id": "{someid}",
  "@type": "ElasticSearchView",
  "resourceTag": "{tag}",
  "pipeline": [
    {
      "name" : "{pipeName}",
      "config" : _pipe_config_
    },
    ...
  ],
  "context": _context_,
  "mapping": _elasticsearch mapping_,
  "settings": _elasticsearch settings_,
  "permission": "{permission}"
}
```

where...

- `{tag}`: String - Selects only resources with the provided tag. This field is optional.
- `{pipeName}`: String - Identifier of the pipe to apply. More information about pipe is available @ref:[here](./pipes.md)
- _pipe_config_ : Json object - Configuration for the pipe `{pipeName}`. This field can be optional depending on `{pipeName}`
- ._context_ : Json - Additional JSON-LD context value applied when compacting the resource before indexing it to Elasticsearch.
- `_elasticsearch mapping_`: Json object - Defines the value types for the Json keys, as stated at the
  @link:[ElasticSearch mapping documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html#indices-put-mapping){
  open=new }.
- `_elasticssearch settings_`: Json object - defines Elasticsearch
  @link:[index settings](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html#create-index-settings){
  open=new } for the underlying Elasticsearch index. Default settings are applied, if not specified.
- `{someid}`: Iri - The @id value for this view.
- `{permission}`: String - permission required to query this view. Defaults to `views/query`.

Please note that for retro-compatibility purposes, omitting the pipeline will apply a default one including 
@ref:[filtering deprecated resources](./pipes.md#filter-deprecated), @ref:[discarding metadata](./pipes.md#discard-metadata) and @ref:[selecting default label predicates](./pipes.md#select-predicates)

## Legacy payload

Retro-compatibility is ensured with the legacy payload as defined @link:[here](https://bluebrainnexus.io/v1.5.x/docs/delta/api/views/elasticsearch-view-api.html)

The legacy payload is now deprecated and will be removed in an upcoming version.

## **Example**

The following example creates an ElasticSearch view that will index resources validated against the schema with id
`https://bluebrain.github.io/nexus/schemas/myschema`. If a resource is deprecated, it won't be selected for indexing.

The resulting ElasticSearch Documents fields will be indexed according to the provided mapping rules and they won't
include the resource metadata fields.

```json
{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myview",
  "@type": [
    "ElasticSearchView"
  ],
  "mapping": {
    "dynamic": false,
    "properties": {
      "@id": {
        "type": "keyword"
      },
      "@type": {
        "type": "keyword"
      },
      "name": {
        "type": "keyword"
      },
      "number": {
        "type": "long"
      },
      "bool": {
        "type": "boolean"
      }
    }
  },
  "pipeline": [
    {
      "name" : "filterDeprecated"
    },
    {
      "name" : "filterBySchema",
      "config" : {
        "types" : [
          "https://bluebrain.github.io/nexus/schemas/myschema"
        ]
      }
    },
    {
      "name" : "discardMetadata"
    }
  ]
}
```

## Create using POST

```
POST /v1/views/{org_label}/{project_label}
  {...}
```

The json payload:

- If the `@id` value is found on the payload, this `@id` will be used.
- If the `@id` value is not found on the payload, an `@id` will be generated as follows: `base:{UUID}`. The `base` is the
  `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](../assets/views/elasticsearch/create.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload.json)

Response
:   @@snip [created.json](../assets/views/elasticsearch/created.json)

## Create using PUT

This alternative endpoint to create a view is useful in case the json payload does not contain an `@id` but you want to
specify one. The `@id` will be specified in the last segment of the endpoint URI.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}
  {...}
```

Note that if the payload contains an `@id` different from the `{view_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](../assets/views/elasticsearch/create-put.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload.json)

Response
:   @@snip [created.json](../assets/views/elasticsearch/created-put.json)

## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the view, the last revision needs to be passed as a query parameter.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
  {...}
```

... where `{previous_rev}` is the last known revision number for the view.

@@@ note { .warning }

Updating a view creates a new Elasticsearch index and deletes the existing one. The indexing process will start from the
beginning.

@@@

**Example**

Request
:   @@snip [update.sh](../assets/views/elasticsearch/update.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload.json)

Response
:   @@snip [updated.json](../assets/views/elasticsearch/updated.json)

## Tag

Links a view revision to a specific name.

Tagging a view is considered to be an update as well.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```

... where

- `{previous_rev}`: is the last known revision number for the resource.
- `{name}`: String - label given to the view at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](../assets/views/blazegraph/tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [tagged.json](../assets/views/elasticsearch/tagged.json)

## Deprecate

Locks the view, so no further operations can be performed. It also stops indexing any more resources into it and deletes the underlying index.

Deprecating a view is considered to be an update as well.

@@@ note { .warning }

Deprecating a view deletes the view index, making the view not searchable.

@@@

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [deprecate.sh](../assets/views/elasticsearch/deprecate.sh)

Response
:   @@snip [deprecated.json](../assets/views/elasticsearch/deprecated.json)

## Fetch

```
GET /v1/views/{org_label}/{project_label}/{view_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](../assets/views/elasticsearch/fetch.sh)

Response
:   @@snip [fetched.json](../assets/views/elasticsearch/fetched.json)

If the @ref:[redirect to Fusion feature](../../../getting-started/running-nexus/configuration/index.md#fusion-configuration) is enabled and
if the `Accept` header is set to `text/html`, a redirection to the fusion representation of the resource will be returned.

Note that for retro-compatibility purposes, fetching an elasticsearch view returns legacy fields.
## Fetch original payload

```
GET /v1/views/{org_label}/{project_label}/{view_id}/source?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchSource.sh](../assets/views/elasticsearch/fetch-source.sh)

Response
:   @@snip [fetched.json](../assets/views/elasticsearch/payload.json)

## Search

```
POST /v1/views/{org_label}/{project_label}/{view_id}/_search
  {...}
```

The supported payload is defined on the
@link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){
open=new }

The string `documents` is used as a prefix of the default ElasticSearch `view_id`

**Example**

Request
:   @@snip [search.sh](../assets/views/elasticsearch/search.sh)

Payload
:   @@snip [search-payload.json](../assets/views/elasticsearch/search-payload.json)

Response
:   @@snip [search-results.json](../assets/views/elasticsearch/search-results.json)

## Fetch tags

```
GET /v1/views/{org_label}/{project_label}/{view_id}/tags?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch_tags.sh](../assets/views/elasticsearch/tags.sh)

Response
:   @@snip [tags.json](../assets/tags.json)

## Fetch statistics

```
GET /v1/views/{org_label}/{project_label}/{view_id}/statistics
```

**Example**

Request
:   @@snip [statistics.sh](../assets/views/statistics.sh)

Response
:   @@snip [statistics.json](../assets/views/statistics.json)

where...

- `totalEvents` - total number of events in the project
- `processedEvents` - number of events that have been considered by the view
- `remainingEvents` - number of events that remain to be considered by the view
- `discardedEvents` - number of events that have been discarded (were not evaluated due to filters, e.g. did not match
  schema, tag or type defined in the view)
- `evaluatedEvents` - number of events that have been used to update an index
- `lastEventDateTime` - timestamp of the last event in the project
- `lastProcessedEventDateTime` - timestamp of the last event processed by the view
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp

## Fetch indexing

```
GET /v1/views/{org_label}/{project_label}/{view_id}/offset
```

**Example**

Request
:   @@snip [offset.sh](../assets/views/elasticsearch/offset.sh)

Response
:   @@snip [offset.json](../assets/views/elasticsearch/offset.json)

where...

- `instant` - timestamp of the last event processed by the view
- `value` - the value of the offset

## Restart indexing

This endpoint restarts the view indexing process. It does not delete the created indices but it overrides the resource
Document when going through the event log.

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/offset
```

**Example**

Request
:   @@snip [restart.sh](../assets/views/elasticsearch/restart.sh)

Response
:   @@snip [restart.json](../assets/views/elasticsearch/restart.json)