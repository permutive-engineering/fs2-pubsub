package com.permutive.pubsub.producer.grpc.internal

import java.util.concurrent.TimeUnit

import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.google.api.gax.batching.BatchingSettings
import com.google.cloud.pubsub.v1.Publisher
import com.google.pubsub.v1.ProjectTopicName
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.grpc.PubsubProducerConfig
import org.threeten.bp.Duration

private[producer] object PubsubPublisher {
  def createJavaPublisher[F[_]](
    projectId: ProjectId,
    topic: Topic,
    config: PubsubProducerConfig[F]
  )(implicit
    F: Sync[F]
  ): Resource[F, Publisher] =
    Resource[F, Publisher] {
      F.delay {
        val publisherBuilder =
          Publisher
            .newBuilder(ProjectTopicName.of(projectId.value, topic.value))
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
            _ <- F.delay(publisher.shutdown())
            _ <- F.delay(publisher.awaitTermination(config.awaitTerminatePeriod.toMillis, TimeUnit.MILLISECONDS))
          } yield ()

        (publisher, shutdown)
      }
    }
}
