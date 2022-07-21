/*
 * Copyright 2018 Permutive
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

package com.permutive.pubsub.producer.grpc.internal

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import com.google.api.gax.batching.BatchingSettings
import com.google.cloud.pubsub.v1.Publisher
import com.google.pubsub.v1.ProjectTopicName
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.grpc.PubsubProducerConfig
import org.threeten.bp.Duration

import java.util.concurrent.TimeUnit

private[producer] object PubsubPublisher {
  def createJavaPublisher[F[_]: Sync](
    projectId: ProjectId,
    topic: Topic,
    config: PubsubProducerConfig[F]
  ): Resource[F, Publisher] =
    Resource[F, Publisher] {
      Sync[F].delay {
        val topicName = ProjectTopicName.of(projectId.value, topic.value)

        val publisherBuilder =
          Publisher
            .newBuilder(topicName)
            .setBatchingSettings(
              BatchingSettings
                .newBuilder()
                .setElementCountThreshold(config.batchSize)
                .setRequestByteThreshold(
                  config.requestByteThreshold.getOrElse[Long](config.batchSize * config.averageMessageSize * 2L)
                )
                .setDelayThreshold(Duration.ofMillis(config.delayThreshold.toMillis))
                .build()
            )

        val publisher =
          config.customizePublisher
            .map(f => f(publisherBuilder))
            .getOrElse(publisherBuilder)
            .build()

        val shutdown =
          for {
            _ <- Sync[F].blocking(publisher.shutdown())
            _ <- Sync[F].blocking(
              publisher.awaitTermination(config.awaitTerminatePeriod.toMillis, TimeUnit.MILLISECONDS)
            )
          } yield ()

        (publisher, shutdown)
      }
    }
}
