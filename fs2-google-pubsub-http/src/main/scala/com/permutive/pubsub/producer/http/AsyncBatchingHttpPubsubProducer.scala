package com.permutive.pubsub.producer.http

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.internal.{AsyncBatchingHttpPublisher, DefaultHttpPublisher}
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model}
import org.http4s.client.Client

object AsyncBatchingHttpPubsubProducer {
  type Batch[F[_], A] = List[Model.AsyncRecord[F, A]]

  def resource[F[_] : Concurrent : Timer, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    googleServiceAccountPath: String,
    config: PubsubHttpProducerConfig[F],
    batchingConfig: BatchingHttpProducerConfig,
    onPublishFailure: (Batch[F, A], Throwable) => F[Unit],
    httpClient: Client[F],
  ): Resource[F, AsyncPubsubProducer[F, A]] = {
    for {
      publisher <- DefaultHttpPublisher.resource(
        projectId = projectId,
        topic = topic,
        serviceAccountPath = googleServiceAccountPath,
        config = config,
        httpClient = httpClient
      )
      batching <- AsyncBatchingHttpPublisher.resource(
        publisher = publisher,
        config = batchingConfig,
        onPublishFailure = onPublishFailure,
      )
    } yield batching
  }
}
