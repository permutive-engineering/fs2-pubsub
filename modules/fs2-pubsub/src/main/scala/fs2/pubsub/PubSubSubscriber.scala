/*
 * Copyright 2019-2025 Permutive Ltd. <https://permutive.com>
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

import scala.concurrent.duration._

import cats.Applicative
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.syntax.all._
import cats.syntax.all._

import fs2.Chunk
import fs2.Stream
import fs2.concurrent.Channel
import fs2.pubsub.dsl.client._
import fs2.pubsub.dsl.subscriber._
import org.http4s.Uri

/** Contains method for creating Pub/Sub subscribers. */
object PubSubSubscriber {

  /** Represents the configuration used by a `PubSubClient`.
    *
    * @param projectId
    *   the ID of the GCP project
    * @param subscription
    *   the subscription from which messages will be read
    * @param uri
    *   URI of the Pub/Sub API
    * @param batchSize
    *   the maximum size of message batches for processing
    * @param maxLatency
    *   the maximum duration allowed for latency in message processing
    * @param readMaxMessages
    *   the maximum number of messages to read at once
    * @param readConcurrency
    *   the concurrency level for reading messages
    */
  sealed abstract class Config private (
      val projectId: ProjectId,
      val subscription: Subscription,
      val uri: Uri,
      val batchSize: Int,
      val maxLatency: FiniteDuration,
      val readMaxMessages: Int,
      val readConcurrency: Int
  ) {

    /** Sets the ID of the GCP project */
    def withProjectId(projectId: ProjectId): Config = copy(projectId = projectId)

    /** Sets the subscription from which messages will be read */
    def withSubscription(subscription: Subscription): Config = copy(subscription = subscription)

    /** Sets the URI of the Pub/Sub API */
    def withUri(uri: Uri): Config = copy(uri = uri)

    /** Sets the maximum size of message batches for processing */
    def withBatchSize(batchSize: Int): Config = copy(batchSize = batchSize)

    /** Sets the maximum duration allowed for latency in message processing */
    def withMaxLatency(maxLatency: FiniteDuration): Config = copy(maxLatency = maxLatency)

    /** Sets the maximum number of messages to read at once */
    def withReadMaxMessages(readMaxMessages: Int): Config = copy(readMaxMessages = readMaxMessages)

    /** Sets the concurrency level for reading messages */
    def withReadConcurrency(readConcurrency: Int): Config = copy(readConcurrency = readConcurrency)

    private def copy(
        projectId: ProjectId = this.projectId,
        subscription: Subscription = this.subscription,
        uri: Uri = this.uri,
        batchSize: Int = this.batchSize,
        maxLatency: FiniteDuration = this.maxLatency,
        readMaxMessages: Int = this.readMaxMessages,
        readConcurrency: Int = this.readConcurrency
    ): Config =
      Config(projectId, subscription, uri, batchSize, maxLatency, readMaxMessages, readConcurrency)

  }

  object Config {

    /** Creates the configuration used by a `PubSubClient`.
      *
      * @param projectId
      *   the ID of the GCP project
      * @param subscription
      *   the subscription from which messages will be read
      * @param uri
      *   URI of the Pub/Sub API
      * @param batchSize
      *   the maximum size of message batches for processing. Defaults to 100
      * @param maxLatency
      *   the maximum duration allowed for latency in message processing. Defaults to 1 second
      * @param readMaxMessages
      *   the maximum number of messages to read at once. Defaults to 1000
      * @param readConcurrency
      *   the concurrency level for reading messages. Defaults to 1 (no concurrency)
      */
    def apply(
        projectId: ProjectId,
        subscription: Subscription,
        uri: Uri,
        batchSize: Int = 100,
        maxLatency: FiniteDuration = 1.second,
        readMaxMessages: Int = 1000,
        readConcurrency: Int = 1
    ): Config = new Config(projectId, subscription, uri, batchSize, maxLatency, readMaxMessages, readConcurrency) {}

  }

  /** Starts creating an HTTP Pub/Sub subscriber in a step-by-step fashion.
    *
    * @tparam F
    *   the effect type
    */
  def http[F[_]: Temporal]: PubSubSubscriberStep[F] = { projectId => subscription => uri => client => retryPolicy =>
    PubSubClient.http
      .projectId(projectId)
      .uri(uri)
      .httpClient(client)
      .retryPolicy(retryPolicy)
      .subscriber
      .subscription(subscription)
  }

  /** Starts creating a gRPC Pub/Sub subscriber in a step-by-step fashion.
    *
    * @tparam F
    *   the effect type
    */
  def grpc[F[_]: Temporal]: PubSubSubscriberStep[F] = { projectId => subscription => uri => client => retryPolicy =>
    PubSubClient.grpc
      .projectId(projectId)
      .uri(uri)
      .httpClient(client)
      .retryPolicy(retryPolicy)
      .subscriber
      .subscription(subscription)
  }

  /** Creates a builder for configuring a Pub/Sub subscriber by leveraging an existing `PubSubClient`.
    *
    * @tparam F
    *   the effect type
    * @param pubSubClient
    *   the Pub/Sub client used for reading messages
    * @return
    *   a builder instance sourcing configuration from the provided `PubSubClient`
    */
  def fromPubSubClient[F[_]: Temporal](pubSubClient: PubSubClient[F]): Builder.FromPubSubClient[F] = {
    subscription => (errorHandler: PartialFunction[(PubSubSubscriber.Operation[F], Throwable), F[Unit]]) => batchSize =>
      maxLatency => readMaxMessages => readConcurrency =>
        def ackChannel(processRecords: Chunk[PubSubRecord.Subscriber[F, Array[Byte]]] => F[Unit]) =
          Stream.resource(Resource {
            Channel
              .unbounded[F, PubSubRecord.Subscriber[F, Array[Byte]]]
              .mproduct(_.stream.groupWithin(batchSize, maxLatency).evalMap(processRecords).compile.drain.start)
              .map { case (channel, fiber) => (channel, channel.close.void >> fiber.join.void) }
          })

        val stream = for {
          ack <- ackChannel { records =>
                   pubSubClient
                     .ack(subscription, records.map(_.ackId))
                     .handleErrorWith {
                       case e if errorHandler.isDefinedAt((Operation.Ack(records), e)) =>
                         errorHandler((Operation.Ack(records), e))
                       case _ => Applicative[F].unit
                     }
                 }
          nack <- ackChannel { records =>
                    pubSubClient
                      .nack(subscription, records.map(_.ackId))
                      .handleErrorWith {
                        case e if errorHandler.isDefinedAt((Operation.Nack(records), e)) =>
                          errorHandler((Operation.Nack(records), e))
                        case _ => Applicative[F].unit
                      }
                  }
          record <-
            Stream
              .emit(pubSubClient.read(subscription, readMaxMessages))
              .repeat
              .covary[F]
              .mapAsyncUnordered(readConcurrency)(identity)
              .flatMap(Stream.emits)
        } yield record.withAck(ack.send(record).void).withNack(nack.send(record).void)

        SubscriberStep(stream, errorHandler)
  }

  object Builder {

    type Default[F[_]] =
      ProjectIdStep[SubscriptionStep[UriStep[ClientStep[F, RetryPolicyStep[F, ErrorHandlerStep[F, WithDefaults[F]]]]]]]

    type WithDefaults[F[_]] =
      BatchSizeStep[MaxLatencyStep[ReadMaxMessagesStep[ReadConcurrencyStep[SubscriberStep[F]]]]]

    type FromConfig[F[_]] =
      ClientStep[F, RetryPolicyStep[F, ErrorHandlerStep[F, SubscriberStep[F]]]]

    type FromPubSubClient[F[_]] =
      SubscriptionStep[ErrorHandlerStep[F, WithDefaults[F]]]

  }

  /** Represents various operations that can fail while consuming messages from Pub/Sub.
    *
    * @tparam F
    *   the effect type for the operations
    */
  sealed trait Operation[F[_]]

  object Operation {

    /** Represents the operation to acknowledge specific records.
      *
      * @param records
      *   the chunk of subscriber records to be acknowledged
      */
    final case class Ack[F[_]](val records: Chunk[PubSubRecord.Subscriber[F, Array[Byte]]]) extends Operation[F]

    /** Represents the operation to negatively acknowledge specific records.
      *
      * @param records
      *   the chunk of subscriber records to be negatively acknowledged
      */
    final case class Nack[F[_]](val records: Chunk[PubSubRecord.Subscriber[F, Array[Byte]]]) extends Operation[F]

    /** Represents the operation to decode a single record.
      *
      * @param record
      *   the subscriber record to be decoded
      */
    final case class Decode[F[_]](val record: PubSubRecord.Subscriber[F, Array[Byte]]) extends Operation[F]

  }

}
