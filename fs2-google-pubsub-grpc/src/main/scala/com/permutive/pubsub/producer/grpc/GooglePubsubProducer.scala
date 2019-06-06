package com.permutive.pubsub.producer.grpc

import cats.effect.{Async, Resource}
import com.permutive.pubsub.JavaExecutor
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.grpc.internal.{DefaultPublisher, PubsubPublisher}

object GooglePubsubProducer {
  def of[F[_]: Async, A: MessageEncoder](
    projectId: ProjectId,
    topic: Topic,
    config: PubsubProducerConfig[F],
  ): Resource[F, PubsubProducer[F, A]] =
    for {
      publisher <- PubsubPublisher.createJavaPublisher(projectId, topic, config)
      executor  <- JavaExecutor.fixedThreadPool(config.callbackExecutors)
    } yield new DefaultPublisher(publisher, executor)
}
