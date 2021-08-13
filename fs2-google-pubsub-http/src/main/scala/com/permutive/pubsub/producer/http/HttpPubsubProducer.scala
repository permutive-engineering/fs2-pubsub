package com.permutive.pubsub.producer.http

import cats.effect.kernel.{Async, Resource}
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.internal.DefaultHttpPublisher
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import org.typelevel.log4cats.Logger
import org.http4s.client.Client

object HttpPubsubProducer {

  /**
    * Create an HTTP PubSub producer which does not batch.
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
