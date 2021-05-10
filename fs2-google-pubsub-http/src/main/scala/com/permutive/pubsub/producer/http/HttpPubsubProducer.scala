package com.permutive.pubsub.producer.http

import cats.effect.kernel.{Async, Resource}
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.internal.DefaultHttpPublisher
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import org.typelevel.log4cats.Logger
import org.http4s.client.Client

object HttpPubsubProducer {
  def resource[F[_]: Async: Logger, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    googleServiceAccountPath: Option[String],
    config: PubsubHttpProducerConfig[F],
    httpClient: Client[F]
  ): Resource[F, PubsubProducer[F, A]] =
    DefaultHttpPublisher.resource(
      projectId = projectId,
      topic = topic,
      serviceAccountPath = googleServiceAccountPath,
      config = config,
      httpClient = httpClient
    )
}
