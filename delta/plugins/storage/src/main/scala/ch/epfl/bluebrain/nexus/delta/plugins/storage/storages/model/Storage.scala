package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.actor.ActorSystem
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskStorageFetchFile, DiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskStorageFetchFile, RemoteDiskStorageLinkFile, RemoteDiskStorageSaveFile, RemoteStorageFetchAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3StorageFetchFile, S3StorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FetchAttributes, FetchFile, LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Secret, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json}

sealed trait Storage extends Product with Serializable {

  /**
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the project that the storage belongs to
    */
  def project: ProjectRef

  /**
    * @return the tag -> rev mapping
    */
  def tags: Map[TagLabel, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: Secret[Json]

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * @return the storage type
    */
  def tpe: StorageType = storageValue.tpe

  def storageValue: StorageValue
}

object Storage {

  /**
    * A storage that stores and fetches files from a local volume
    */
  final case class DiskStorage(
      id: Iri,
      project: ProjectRef,
      value: DiskStorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    def fetchFile: FetchFile =
      DiskStorageFetchFile

    def saveFile(implicit as: ActorSystem): SaveFile =
      new DiskStorageSaveFile(this)

  }

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      value: S3StorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {

    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    def fetchFile(implicit as: ActorSystem): FetchFile =
      new S3StorageFetchFile(value)

    def saveFile(implicit as: ActorSystem): SaveFile =
      new S3StorageSaveFile(this)

  }

  /**
    * A storage that stores and fetches files from a remote volume using a well-defined API
    */
  final case class RemoteDiskStorage(
      id: Iri,
      project: ProjectRef,
      value: RemoteDiskStorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    def fetchFile(implicit client: HttpClient, as: ActorSystem): FetchFile =
      new RemoteDiskStorageFetchFile(value)

    def saveFile(implicit client: HttpClient, as: ActorSystem): SaveFile =
      new RemoteDiskStorageSaveFile(this)

    def linkFile(implicit client: HttpClient, as: ActorSystem): LinkFile =
      new RemoteDiskStorageLinkFile(this)

    def fetchComputedAttributes(implicit client: HttpClient, as: ActorSystem): FetchAttributes =
      new RemoteStorageFetchAttributes(value)
  }

  private val secretFields = List("credentials", "accessKey", "secretKey")

  private def getOptionalKeyValue(key: String, json: Json) =
    json.hcursor.get[Option[String]](key).getOrElse(None).map(key -> _)

  def encryptSource(json: Secret[Json], crypto: Crypto): Either[String, Json] = {
    def getField(key: String) = getOptionalKeyValue(key, json.value)

    secretFields.flatMap(getField).foldM(json.value) { case (acc, (key, value)) =>
      crypto.encrypt(value).map(encrypted => acc deepMerge Json.obj(key -> encrypted.asJson))
    }
  }

  def decryptSource(json: Json, crypto: Crypto): Either[String, Secret[Json]] = {
    def getField(key: String) = getOptionalKeyValue(key, json)

    secretFields
      .flatMap(getField)
      .foldM(json) { case (acc, (key, value)) =>
        crypto.decrypt(value).map(encrypted => acc deepMerge Json.obj(key -> encrypted.asJson))
      }
      .map(Secret.apply)
  }

  implicit private[storages] val storageEncoder: Encoder.AsObject[Storage] =
    Encoder.encodeJsonObject.contramapObject { s =>
      s.storageValue.asJsonObject.addContext(s.source.value.topContextValueOrEmpty.contextObj)
    }

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, Storages.context)
}