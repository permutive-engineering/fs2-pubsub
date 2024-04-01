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

import cats.effect.Temporal

import fs2.pubsub.MessageEncoder
import fs2.pubsub.PubSubPublisher
import fs2.pubsub.Topic

object publisher {

  trait PubSubPublisherStep[F[_], A] extends PubSubPublisher.Builder.Default[F, A] {

    /** Create a `PubSubPublisher` from a configuration.
      *
      * @param config
      *   the configuration to use
      * @param F
      *   the effect type
      * @param A
      *   the message type
      * @return
      *   a builder for the publisher
      */
    def fromConfig(
        config: PubSubPublisher.Config
    )(implicit
        F: Temporal[F],
        A: MessageEncoder[A]
    ): PubSubPublisher.Builder.FromConfig[F, A] = {
      PubSubPublisher
        .http[F, A]
        .projectId(config.projectId)
        .topic(config.topic)
        .uri(config.uri)
    }

    /** Create an async `PubSubPublisher` from a configuration.
      *
      * @param config
      *   the configuration to use
      * @param F
      *   the effect type
      * @param A
      *   the message type
      * @return
      *   a builder for the publisher
      */
    def fromConfig(
        config: PubSubPublisher.Async.Config
    )(implicit
        F: Temporal[F],
        A: MessageEncoder[A]
    ): PubSubPublisher.Async.Builder.FromConfig[F, A] = { client => retryPolicy =>
      PubSubPublisher
        .http[F, A]
        .projectId(config.projectId)
        .topic(config.topic)
        .uri(config.uri)
        .httpClient(client)
        .retryPolicy(retryPolicy)
        .batching
        .batchSize(config.batchSize)
        .maxLatency(config.maxLatency)
    }

  }

  trait TopicStep[A] {

    /** Sets the Pub/Sub topic to which messages will be published.
      *
      * @param topic
      *   the Pub/Sub topic
      * @return
      *   the next step in the builder
      */
    def topic(topic: Topic): A

  }

  trait BatchSizeStep[A] {

    /** Sets the maximum size of message batches for publishing.
      *
      * @param batchSize
      *   the maximum size of message batches
      * @return
      *   the next step in the builder
      */
    def batchSize(batchSize: Int): A

  }

  trait MaxLatencyStep[A] {

    /** Sets the maximum duration allowed for latency in message publishing.
      *
      * @param maxLatency
      *   the maximum duration allowed for latency
      * @return
      *   the next step in the builder
      */
    def maxLatency(maxLatency: FiniteDuration): A

  }

}
