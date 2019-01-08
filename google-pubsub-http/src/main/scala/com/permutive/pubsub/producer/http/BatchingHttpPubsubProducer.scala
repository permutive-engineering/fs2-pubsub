package com.permutive.pubsub.producer.http

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.internal.{BatchingHttpPublisher, DefaultHttpPublisher}
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import org.http4s.client.Client

object BatchingHttpPubsubProducer {
  type Batch[A] = List[Model.Record[A]]

  def resource[F[_] : Concurrent : Timer, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    googleServiceAccountPath: String,
    config: PubsubHttpProducerConfig[F],
    batchingConfig: BatchingHttpProducerConfig,
    onPublishFailure: (Batch[A], Throwable) => F[Unit],
    httpClient: Client[F],
  ): Resource[F, PubsubProducer[F, A]] = {
    for {
      publisher <- DefaultHttpPublisher.resource(
        projectId = projectId,
        topic = topic,
        serviceAccountPath = googleServiceAccountPath,
        config = config,
        httpClient = httpClient
      )
      batching <- BatchingHttpPublisher.resource(
        publisher = publisher,
        config = batchingConfig,
        onPublishFailure = onPublishFailure,
      )
    } yield batching
  }
}
