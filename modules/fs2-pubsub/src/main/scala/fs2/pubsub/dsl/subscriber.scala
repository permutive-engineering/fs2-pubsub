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

package fs2.pubsub.dsl

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import cats.Applicative
import cats.effect.Temporal
import cats.syntax.all._

import fs2.Stream
import fs2.pubsub.MessageDecoder
import fs2.pubsub.PubSubRecord
import fs2.pubsub.PubSubSubscriber
import fs2.pubsub.PubSubSubscriber.Operation
import fs2.pubsub.Subscription

object subscriber {

  trait PubSubSubscriberStep[F[_]] extends PubSubSubscriber.Builder.Default[F] {

    /** Create a `PubSubSubscriber` from a configuration.
      *
      * @param config
      *   the configuration to use
      * @param F
      *   the effect type
      * @return
      *   a builder for the subscriber
      */
    def fromConfig(config: PubSubSubscriber.Config)(implicit
        F: Temporal[F]
    ): PubSubSubscriber.Builder.FromConfig[F] = { client => retryPolicy => errorHandler =>
      PubSubSubscriber
        .http[F]
        .projectId(config.projectId)
        .subscription(config.subscription)
        .uri(config.uri)
        .httpClient(client)
        .retryPolicy(retryPolicy)
        .errorHandler(errorHandler)
        .batchSize(config.batchSize)
        .maxLatency(config.maxLatency)
        .readMaxMessages(config.readMaxMessages)
        .readConcurrency(config.readConcurrency)

    }

  }

  trait SubscriptionStep[A] {

    /** Sets the subscription from which messages will be read.
      *
      * @param subscription
      *   the subscription
      * @return
      *   the next step in the builder
      */
    def subscription(subscription: Subscription): A

  }

  trait BatchSizeStep[A] {

    /** Sets the maximum size of message batches when acknowledging.
      *
      * @param batchSize
      *   the maximum size of message batches
      * @return
      *   the next step in the builder
      */
    def batchSize(batchSize: Int): A

  }

  object BatchSizeStep {

    implicit class BatchSizeStepOps[F[_]](step: PubSubSubscriber.Builder.WithDefaults[F]) {

      /** Configures the builder with the default values:
        *
        *   - `batchSize` = 100
        *   - `maxLatency` = 1 second
        *   - `readMaxMessages` = 1000
        *   - `readConcurrency` = 1
        */
      def withDefaults: SubscriberStep[F] =
        step
          .batchSize(100)
          .maxLatency(1.second)
          .readMaxMessages(1000)
          .readConcurrency(1)

    }

  }

  trait MaxLatencyStep[A] {

    /** Sets the maximum duration allowed for latency in message acknowledging.
      *
      * @param maxLatency
      *   the maximum duration allowed for latency in message acknowledging
      * @return
      *   the next step in the builder
      */
    def maxLatency(maxLatency: FiniteDuration): A

  }

  trait ReadMaxMessagesStep[A] {

    /** Sets the maximum number of messages to read at once.
      *
      * @param maxMessages
      *   the maximum number of messages
      * @return
      *   the next step in the builder
      */
    def readMaxMessages(maxMessages: Int): A

  }

  trait ReadConcurrencyStep[A] {

    /** Sets the concurrency level for reading messages.
      *
      * @param readConcurrency
      *   the concurrency level
      * @return
      *   the next step in the builder
      */
    def readConcurrency(readConcurrency: Int): A

  }

  sealed abstract class SubscriberStep[F[_]] private (
      stream: Stream[F, PubSubRecord.Subscriber[F, Array[Byte]]],
      errorHandler: PartialFunction[(PubSubSubscriber.Operation[F], Throwable), F[Unit]]
  ) {

    /** Returns a stream of raw Pub/Sub records. */
    def raw(implicit F: Applicative[F]): Stream[F, PubSubRecord.Subscriber[F, Array[Byte]]] =
      decodeTo[Array[Byte]].subscribe

    /** Returns a stream of Pub/Sub records decoded to a specific type. */
    def decodeTo[A: MessageDecoder](implicit F: Applicative[F]) = SubscribeStep {
      stream
        .evalMapChunk[F, Option[PubSubRecord.Subscriber[F, A]]] { record =>
          record
            .as[A]
            .fold(
              {
                case e if errorHandler.isDefinedAt((Operation.Decode(record), e)) =>
                  errorHandler((Operation.Decode(record), e)).as(None)
                case _ => Option.empty[PubSubRecord.Subscriber[F, A]].pure[F]
              },
              _.some.pure[F]
            )
        }
        .unNone
    }

  }

  object SubscriberStep {

    private[pubsub] def apply[F[_]](
        stream: Stream[F, PubSubRecord.Subscriber[F, Array[Byte]]],
        errorHandler: PartialFunction[(PubSubSubscriber.Operation[F], Throwable), F[Unit]]
    ): SubscriberStep[F] =
      new SubscriberStep[F](stream, errorHandler) {}

  }

  trait ErrorHandlerStep[F[_], A] {

    /** Sets the error handler for the subscriber. The error handler is called whenever an error occurs while processing
      * a message. Possible scenarios are: decoding a message, acknowledging a message, or negatively acknowledging a
      * message.
      *
      * @param handler
      *   the error handler
      * @return
      *   the next step in the builder
      */
    def errorHandler(handler: PartialFunction[(PubSubSubscriber.Operation[F], Throwable), F[Unit]]): A

    /** Disables error handling for the subscriber.
      *
      * @return
      *   the next step in the builder
      */
    def noErrorHandling(implicit F: Applicative[F]): A = errorHandler { case (_, _) => Applicative[F].unit }

  }

  sealed abstract class SubscribeStep[F[_], A](stream: Stream[F, PubSubRecord.Subscriber[F, A]]) {

    /** Subscribes to the Pub/Sub topic and returns a stream of subscriber records. It is up to the user to acknowledge
      * records.
      */
    def subscribe: Stream[F, PubSubRecord.Subscriber[F, A]] = stream

    /** Subscribes to the Pub/Sub topic and returns a stream of decoded messages. Records will be acknowledge after
      * being decoded
      */
    def subscribeAndAck: Stream[F, Option[A]] = subscribe.evalTap(_.ack).map(_.value)

  }

  object SubscribeStep {

    private[pubsub] def apply[F[_], A](stream: Stream[F, PubSubRecord.Subscriber[F, A]]): SubscribeStep[F, A] =
      new SubscribeStep[F, A](stream) {}

  }

}
