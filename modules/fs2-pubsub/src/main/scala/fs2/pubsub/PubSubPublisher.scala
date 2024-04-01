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

import scala.concurrent.duration._

import cats.Applicative
import cats.Functor
import cats.effect.Resource
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._

import fs2.Chunk
import fs2.concurrent.Channel
import fs2.pubsub.dsl.client._
import fs2.pubsub.dsl.publisher._
import org.http4s.Uri

/** Represents a class defining a Pub/Sub publisher responsible for producing messages of type `A`.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the type of messages to be published
  */
trait PubSubPublisher[F[_], A] {

  /** Produces a single message with the provided data and optional attributes.
    *
    * @param data
    *   the data of type `A` to be sent as a message
    * @param attributes
    *   optional key-value pairs representing message attributes
    * @return
    *   the message ID associated with the published message
    */
  def publishOne(data: A, attributes: (String, String)*)(implicit F: Functor[F]): F[MessageId] =
    publishOne(data, attributes.toMap)

  /** Produces a single message with the provided data and map of attributes.
    *
    * @param data
    *   the data of type `A` to be sent as a message
    * @param attributes
    *   a map of key-value pairs representing message attributes
    * @return
    *   the message ID associated with the published message
    */
  def publishOne(data: A, attributes: Map[String, String])(implicit F: Functor[F]): F[MessageId] =
    publishOne(PubSubRecord.Publisher(data).withAttributes(attributes))

  /** Produces a single message based on the given publisher `PubSubRecord` instance.
    *
    * @param record
    *   the publisher `PubSubRecord` instance representing the message to be published
    * @return
    *   the message ID associated with the published message
    */
  def publishOne(record: PubSubRecord.Publisher[A])(implicit F: Functor[F]): F[MessageId] =
    publishMany(Seq(record)).map(_.head)

  /** Produces multiple messages based on the given sequence of `PubSubRecord.Publisher`.
    *
    * @param records
    *   a sequence of publisher `PubSubRecord` instances representing messages to be published
    * @return
    *   a list of message IDs associated with the published messages
    */
  def publishMany(records: Seq[PubSubRecord.Publisher[A]]): F[List[MessageId]]

  /** Starts configuring an asynchronous `PubSub` publisher from this `PubSubPublisher`. */
  def batching(implicit F: Temporal[F]): PubSubPublisher.Async.Builder.Default[F, A] = batchSize =>
    maxLatency => PubSubPublisher.Async.fromPubSubPublisher(batchSize, maxLatency)(this)

}

object PubSubPublisher extends GrpcConstructors.Publisher {

  /** Represents the configuration parameters for a `PubSubPublisher`.
    *
    * @param projectId
    *   the ID of the GCP project
    * @param topic
    *   the topic into which messages will be published
    * @param uri
    *   URI of the Pub/Sub API
    */
  sealed abstract class Config private (
      val projectId: ProjectId,
      val topic: Topic,
      val uri: Uri
  ) {

    /** Sets the ID of the GCP project */
    def withProjectId(projectId: ProjectId): Config = copy(projectId = projectId)

    /** Sets the topic into which messages will be published */
    def withTopic(topic: Topic): Config = copy(topic = topic)

    /** Sets the URI of the Pub/Sub API */
    def withUri(uri: Uri): Config = copy(uri = uri)

    private def copy(
        projectId: ProjectId = this.projectId,
        topic: Topic = this.topic,
        uri: Uri = this.uri
    ): Config = Config(projectId, topic, uri)

  }

  object Config {

    /** Creates the configuration parameters for a `PubSubPublisher`.
      *
      * @param projectId
      *   the ID of the GCP project
      * @param topic
      *   the topic into which messages will be published
      * @param uri
      *   URI of the Pub/Sub API
      */
    def apply(projectId: ProjectId, topic: Topic, uri: Uri): Config = new Config(projectId, topic, uri) {}

  }

  /** Starts creating an HTTP Pub/Sub publisher in a step-by-step fashion.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the type of messages to be sent to Pub/Sub
    */
  def http[F[_]: Temporal, A: MessageEncoder]: PubSubPublisherStep[F, A] = {
    projectId => topic => uri => client => retryPolicy =>
      PubSubClient.http
        .projectId(projectId)
        .uri(uri)
        .httpClient(client)
        .retryPolicy(retryPolicy)
        .publisher
        .topic(topic)
  }

  /** Creates a builder for configuring a `PubSubPublisher` by leveraging an existing `PubSubClient`.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the type of messages to be sent to Pub/Sub
    * @param pubSubClient
    *   the Pub/Sub client used for producing messages
    * @return
    *   a builder instance sourcing configuration from the provided `PubSubClient`
    */
  def fromPubSubClient[F[_], A: MessageEncoder](pubSubClient: PubSubClient[F]): Builder.FromPubSubClient[F, A] =
    topic => records => pubSubClient.publish[A](topic, records)

  object Builder {

    type Default[F[_], A] = ProjectIdStep[TopicStep[UriStep[ClientStep[F, RetryPolicyStep[F, PubSubPublisher[F, A]]]]]]

    type FromConfig[F[_], A] = ClientStep[F, RetryPolicyStep[F, PubSubPublisher[F, A]]]

    type FromPubSubClient[F[_], A] = TopicStep[PubSubPublisher[F, A]]

  }

  /** Represents a Pub/Sub publisher capable of producing messages of type `A` asynchronously.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the type of messages to be handled asynchronously
    */
  trait Async[F[_], A] {

    /** Produces a single message with the provided data and optional attributes.
      *
      * @param data
      *   the data of type `A` to be sent as a message
      * @param attributes
      *   optional key-value pairs representing message attributes
      */
    def publishOne(data: A, attributes: (String, String)*)(implicit F: Applicative[F]): F[Unit] =
      publishOne(data, attributes.toMap)

    /** Produces a single message with the provided data and map of attributes.
      *
      * @param data
      *   the data of type `A` to be sent as a message
      * @param attributes
      *   a map of key-value pairs representing message attributes
      */
    def publishOne(data: A, attributes: Map[String, String])(implicit F: Applicative[F]): F[Unit] =
      publishOne(PubSubRecord.Publisher(data).withAttributes(attributes))

    /** Produces a single message with the provided data, callback function, and optional attributes.
      *
      * @param data
      *   the data of type `A` to be sent as a message
      * @param callback
      *   the callback function to execute after producing the message
      * @param attributes
      *   optional key-value pairs representing message attributes
      */
    def publishOne(data: A, callback: Either[Throwable, Unit] => F[Unit], attributes: (String, String)*): F[Unit] =
      publishOne(data, callback, attributes.toMap)

    /** Produces a single message with the provided data, callback function, and map of attributes.
      *
      * @param data
      *   the data of type `A` to be sent as a message
      * @param callback
      *   the callback function to execute after producing the message
      * @param attributes
      *   a map of key-value pairs representing message attributes
      */
    def publishOne(data: A, callback: Either[Throwable, Unit] => F[Unit], attributes: Map[String, String]): F[Unit] =
      publishOne(PubSubRecord.Publisher(data).withAttributes(attributes).withCallback(callback))

    /** Produces a single message based on the given `PubSubRecord.Publisher` containing a callback.
      *
      * @param record
      *   the publisher `PubSubRecord` instance with a callback function for handling published message
      */
    def publishOne(record: PubSubRecord.Publisher.WithCallback[F, A]): F[Unit] =
      publishMany(Seq(record))

    /** Produces a single message based on the provided publisher `PubSubRecord` instance.
      *
      * @param record
      *   the publisher `PubSubRecord` instance representing the message to be published
      */
    def publishOne(record: PubSubRecord.Publisher[A])(implicit F: Applicative[F]): F[Unit] =
      publishOne(record.withCallback(_ => Applicative[F].unit))

    /** Produces multiple messages based on the given sequence of publisher `PubSubRecord` with callback instances.
      *
      * @param records
      *   a sequence of publisher `PubSubRecord` with callback instances
      */
    def publishMany(records: Seq[PubSubRecord.Publisher.WithCallback[F, A]]): F[Unit]

  }

  object Async {

    /** Represents the configuration parameters for an async `PubSubPublisher`.
      *
      * @param projectId
      *   the ID of the GCP project
      * @param topic
      *   the topic into which messages will be published
      * @param uri
      *   URI of the Pub/Sub API
      * @param batchSize
      *   the maximum size of message batches for processing
      * @param maxLatency
      *   the maximum duration allowed for latency in message processing
      */
    sealed abstract class Config private (
        val projectId: ProjectId,
        val topic: Topic,
        val uri: Uri,
        val batchSize: Int,
        val maxLatency: FiniteDuration
    ) {

      /** Sets the ID of the GCP project */
      def withProjectId(projectId: ProjectId): Config = copy(projectId = projectId)

      /** Sets the topic into which messages will be published */
      def withTopic(topic: Topic): Config = copy(topic = topic)

      /** Sets the URI of the Pub/Sub API */
      def withUri(uri: Uri): Config = copy(uri = uri)

      /** Sets the maximum size of message batches for processing */
      def withBatchSize(batchSize: Int): Config = copy(batchSize = batchSize)

      /** Sets the maximum duration allowed for latency in message processing */
      def withMaxLatency(maxLatency: FiniteDuration): Config = copy(maxLatency = maxLatency)

      private def copy(
          projectId: ProjectId = this.projectId,
          topic: Topic = this.topic,
          uri: Uri = this.uri,
          batchSize: Int = this.batchSize,
          maxLatency: FiniteDuration = this.maxLatency
      ): Config = Config(projectId, topic, uri, batchSize, maxLatency)

    }

    object Config {

      /** Creates the configuration parameters for an async `PubSubPublisher`.
        *
        * @param projectId
        *   the ID of the GCP project
        * @param topic
        *   the topic into which messages will be published
        * @param uri
        *   URI of the Pub/Sub API
        * @param batchSize
        *   the maximum size of message batches for processing. Defaults to 100
        * @param maxLatency
        *   the maximum duration allowed for latency in message processing. Defaults to 1 second
        */
      def apply(
          projectId: ProjectId,
          topic: Topic,
          uri: Uri,
          batchSize: Int = 100,
          maxLatency: FiniteDuration = 1.second
      ): Config = new Config(projectId, topic, uri, batchSize, maxLatency) {}

    }

    object Builder {

      type Default[F[_], A] =
        BatchSizeStep[MaxLatencyStep[Resource[F, PubSubPublisher.Async[F, A]]]]

      type FromConfig[F[_], A] =
        ClientStep[F, RetryPolicyStep[F, Resource[F, PubSubPublisher.Async[F, A]]]]

    }

    private[pubsub] def fromPubSubPublisher[F[_]: Temporal, A](
        batchSize: Int,
        maxLatency: FiniteDuration
    )(underlying: PubSubPublisher[F, A]): Resource[F, PubSubPublisher.Async[F, A]] = {
      val publish = (records: Chunk[PubSubRecord.Publisher.WithCallback[F, A]]) =>
        underlying
          .publishMany(records.toList.map(_.noCallback))
          .void
          .attempt
          .flatMap(result => records.traverse_(_.callback(result)))

      val channelAndFiber = Channel
        .unbounded[F, PubSubRecord.Publisher.WithCallback[F, A]]
        .mproduct(_.stream.groupWithin(batchSize, maxLatency).evalMap(publish).compile.drain.start)

      Resource
        .make(channelAndFiber) { case (channel, fiber) => channel.close.void >> fiber.join.void }
        .map { case (channel, _) => _.toList.traverse_(channel.send).void }
    }

  }

}
