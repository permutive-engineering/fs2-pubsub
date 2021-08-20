package com.permutive.pubsub.producer.http

import cats.effect.{Concurrent, Resource, Timer}
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.internal.{BatchingHttpPublisher, DefaultHttpPublisher}
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model}
import io.chrisdavenport.log4cats.Logger
import org.http4s.client.Client

object BatchingHttpPubsubProducer {

  /**
    * Create an HTTP PubSub producer which produces in batches.
    *
    * @param projectId                google cloud project id
    * @param topic                    the topic to produce to
    * @param googleServiceAccountPath path to the Google Service account file (json), if not specified then the GCP
    *                                 metadata endpoint is used to retrieve the `default` service account access token
    *
    * See the following for documentation on GCP metadata endpoint and service accounts:
    *  - https://cloud.google.com/compute/docs/storing-retrieving-metadata
    *  - https://cloud.google.com/compute/docs/metadata/default-metadata-values
    *  - https://cloud.google.com/compute/docs/access/create-enable-service-accounts-for-instances
    */
  def resource[F[_]: Concurrent: Timer: Logger, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    googleServiceAccountPath: Option[String],
    config: PubsubHttpProducerConfig[F],
    batchingConfig: BatchingHttpProducerConfig,
    httpClient: Client[F]
  ): Resource[F, AsyncPubsubProducer[F, A]] =
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
        config = batchingConfig
      )
    } yield batching
}
