/*
 * Copyright 2019-2024 Permutive Ltd. <https://permutive.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.pubsub

import java.time.Instant
import java.util.Base64

import scala.util.control.NonFatal

import cats.effect.Temporal
import cats.syntax.all._

import com.permutive.common.types.gcp.http4s._
import fs2.Chunk
import fs2.pubsub.dsl.client._
import fs2.pubsub.exceptions.PubSubRequestError
import io.circe.Decoder
import io.circe.Json
import io.circe.syntax._
import org.http4s.Method._
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.middleware.Retry
import org.http4s.headers.`Idempotency-Key`

/** Represents a class designed to handle connections to Pub/Sub APIs and perform various operations on them. */
trait PubSubClient[F[_]] {

  /** Publishes a sequence of records to the specified topic in Pub/Sub.
    *
    * @param topic
    *   the topic to publish the records to
    * @param records
    *   a sequence of publisher records to be published
    * @return
    *   a list of message IDs associated with the published records
    */
  def publish[A: MessageEncoder](topic: Topic, records: Seq[PubSubRecord.Publisher[A]]): F[List[MessageId]]

  /** Reads a specified number of messages from the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription to read messages from
    * @param maxMessages
    *   the maximum number of messages to read
    * @return
    *   a list of subscriber records containing the read messages
    */
  def read(
      subscription: Subscription,
      maxMessages: Int
  ): F[List[PubSubRecord.Subscriber[F, Array[Byte]]]]

  /** Acknowledges the receipt of a message in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the message to acknowledge
    * @param ackId
    *   the acknowledgment ID of the message to be acknowledged
    */
  def ack(subscription: Subscription, ackId: AckId): F[Unit] = ack(subscription, Chunk(ackId))

  /** Acknowledges the receipt of multiple messages in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the messages to acknowledge
    * @param ackIds
    *   the acknowledgment IDs of the messages to be acknowledged
    */

  def ack(subscription: Subscription, ackIds: Chunk[AckId]): F[Unit]

  /** Negatively acknowledges the receipt of a message in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the message to negatively acknowledge
    * @param ackId
    *   the acknowledgment ID of the message to be negatively acknowledged
    */
  def nack(subscription: Subscription, ackId: AckId): F[Unit] = nack(subscription, Chunk(ackId))

  /** Negatively acknowledges the receipt of multiple messages in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the messages to negatively acknowledge
    * @param ackIds
    *   the acknowledgment IDs of the messages to be negatively acknowledged
    */
  def nack(subscription: Subscription, ackIds: Chunk[AckId]): F[Unit] =
    modifyDeadline(subscription: Subscription, ackIds, AckDeadline.zero)

  /** Modifies the deadline for acknowledging a specific message in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the message for which the deadline will be modified
    * @param ackId
    *   the acknowledgment ID of the message for which the deadline will be modified
    * @param by
    *   the duration by which to modify the deadline
    */
  def modifyDeadline(subscription: Subscription, ackId: AckId, by: AckDeadline): F[Unit] =
    modifyDeadline(subscription, Chunk(ackId), by)

  /** Modifies the deadline for acknowledging multiple messages in the subscription in Pub/Sub.
    *
    * @param subscription
    *   the subscription containing the messages for which the deadline will be modified
    * @param ackIds
    *   the acknowledgment IDs of the messages for which the deadline will be modified
    * @param by
    *   the deadline duration by which to modify the deadlines
    */
  def modifyDeadline(subscription: Subscription, ackIds: Chunk[AckId], by: AckDeadline): F[Unit]

  /** Returns a `PubSubPublisher.Builder` to configure and create a publisher for a specific message type within
    * Pub/Sub.
    *
    * @tparam A
    *   the type of message for which to create the publisher
    * @return
    *   a `PubSubPublisher.Builder` instance for the specified message type
    */
  def publisher[A: MessageEncoder]: PubSubPublisher.Builder.FromPubSubClient[F, A] =
    PubSubPublisher.fromPubSubClient(this)

  /** Returns a `PubSubSubscriber.Builder` to configure and create a subscriber for handling messages within Pub/Sub. */
  def subscriber(implicit F: Temporal[F]): PubSubSubscriber.Builder.FromPubSubClient[F] =
    PubSubSubscriber.fromPubSubClient(this)

}

object PubSubClient extends GrpcConstructors.Client {

  /** Represents the configuration used by a `PubSubClient`.
    *
    * @param projectId
    *   the ID of the GCP project
    * @param uri
    *   URI of the Pub/Sub API
    */
  sealed abstract class Config private (
      val projectId: ProjectId,
      val uri: Uri
  ) {

    /** Sets the ID of the GCP project */
    def withProjectId(projectId: ProjectId): Config = copy(projectId = projectId)

    /** Sets the URI of the Pub/Sub API */
    def withUri(uri: Uri): Config = copy(uri = uri)

    private def copy(
        projectId: ProjectId = this.projectId,
        uri: Uri = this.uri
    ): Config = Config(projectId, uri)

  }

  object Config {

    def apply(projectId: ProjectId, uri: Uri): Config = new Config(projectId, uri) {}

  }

  /** Starts creating an HTTP Pub/Sub client in a step-by-step fashion.
    *
    * @tparam F
    *   the effect type
    */
  def http[F[_]: Temporal]: PubSubClientStep[F] = { projectId => uri => underlying => retryPolicy =>
    new PubSubClient[F] with Http4sClientDsl[F] {

      private val httpClient = Retry.create(retryPolicy, logRetries = false)(underlying)

      override def publish[A: MessageEncoder](
          topic: Topic,
          records: Seq[PubSubRecord.Publisher[A]]
      ): F[List[MessageId]] = {
        val body = Json.obj(
          "messages" := records.map { record =>
            val data = MessageEncoder[A].encode(record.data)

            Json.obj(
              "data"       := Base64.getEncoder().encodeToString(data),
              "attributes" := record.attributes
            )
          }
        )

        val request = POST(body, uri / "v1" / "projects" / projectId / "topics" / show"$topic:publish")

        httpClient
          .expectOr[Json](request)(PubSubRequestError.from(_, request).widen)
          .flatMap(_.hcursor.get[List[MessageId]]("messageIds").liftTo[F])
          .adaptError {
            case e: PubSubRequestError => e
            case NonFatal(e)           => PubSubRequestError("Failed to publish records to PubSub", request, e)
          }
      }

      def read(
          subscription: Subscription,
          maxMessages: Int
      ): F[List[PubSubRecord.Subscriber[F, Array[Byte]]]] = {
        val body = Json.obj(
          "maxMessages" := maxMessages
        )

        val request = POST(
          body,
          uri / "v1" / "projects" / projectId / "subscriptions" / show"$subscription:pull",
          `Idempotency-Key`("pull")
        )

        implicit val decoder: Decoder[PubSubRecord.Subscriber[F, Array[Byte]]] = cursor =>
          for {
            ackId       <- cursor.get[AckId]("ackId")
            message      = cursor.downField("message")
            data        <- message.get[Option[String]]("data")
            attributes  <- message.get[Option[Map[String, String]]]("attributes")
            messageId   <- message.get[Option[MessageId]]("messageId")
            publishTime <- message.get[Option[Instant]]("publishTime")
          } yield PubSubRecord.Subscriber(
            data.map(Base64.getDecoder().decode),
            attributes.orEmpty,
            messageId,
            publishTime,
            ackId,
            ack(subscription, ackId),
            nack(subscription, ackId),
            modifyDeadline(subscription, ackId, _)
          )

        httpClient
          .expectOr[Json](request)(PubSubRequestError.from(_, request).widen)
          .flatMap(_.hcursor.get[Option[List[PubSubRecord.Subscriber[F, Array[Byte]]]]]("receivedMessages").liftTo[F])
          .map(_.getOrElse(Nil))
          .adaptError {
            case e: PubSubRequestError => e
            case NonFatal(e)           => PubSubRequestError("Failed to pull messages from PubSub", request, e)
          }
      }

      final def ack(subscription: Subscription, ackIds: Chunk[AckId]): F[Unit] = {
        val body = Json.obj("ackIds" := ackIds.toList)

        val request =
          POST(
            body,
            uri / "v1" / "projects" / projectId / "subscriptions" / show"$subscription:acknowledge",
            `Idempotency-Key`("acknowledge")
          )

        httpClient
          .expectOr[Unit](request)(PubSubRequestError.from(_, request).widen)
          .void
          .adaptError {
            case e: PubSubRequestError => e
            case NonFatal(e)           => PubSubRequestError("Failed to acknowledge messages in PubSub", request, e)
          }
      }

      final def modifyDeadline(subscription: Subscription, ackIds: Chunk[AckId], by: AckDeadline): F[Unit] = {
        val body = Json.obj("ackIds" := ackIds.toList, "ackDeadlineSeconds" := by.value.toSeconds)

        val request =
          POST(
            body,
            uri / "v1" / "projects" / projectId / "subscriptions" / show"$subscription:modifyAckDeadline",
            `Idempotency-Key`("modifyAckDeadline")
          )

        httpClient
          .expectOr[Unit](request)(PubSubRequestError.from(_, request).widen)
          .void
          .adaptError {
            case e: PubSubRequestError => e
            case NonFatal(e)           => PubSubRequestError("Failed to modify ACK deadline in PubSub", request, e)
          }
      }

    }

  }

  // format: off
  type FromConfigBuilder[F[_]] = 
    ClientStep[F, RetryPolicyStep[F, PubSubClient[F]]]
  // format: on

  // format: off
  type Builder[F[_]] = 
    ProjectIdStep[UriStep[ClientStep[F, RetryPolicyStep[F, PubSubClient[F]]]]]
  // format: on

}
