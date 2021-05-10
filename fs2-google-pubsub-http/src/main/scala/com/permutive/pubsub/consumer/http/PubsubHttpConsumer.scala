package com.permutive.pubsub.consumer.http

import cats.effect.kernel.Async
import cats.syntax.all._
import com.permutive.pubsub.consumer.ConsumerRecord
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.consumer.http.internal.PubsubSubscriber
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.http4s.client.Client
import org.http4s.client.middleware.RetryPolicy
import org.http4s.client.middleware.RetryPolicy.{exponentialBackoff, recklesslyRetriable}

import java.util.Base64
import scala.concurrent.duration._

object PubsubHttpConsumer {

  /**
    * Subscribe with manual acknowledgement
    *
    * @param projectId          google cloud project id
    * @param subscription       name of the subscription
    * @param serviceAccountPath path to the Google Service account file (json)
    * @param errorHandler       upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribe[F[_]: Async: Logger, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: Option[String],
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    httpClientRetryPolicy: RetryPolicy[F] = recklesslyRetryPolicy[F]
  ): Stream[F, ConsumerRecord[F, A]] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient, httpClientRetryPolicy)
      .flatMap { record =>
        MessageDecoder[A].decode(Base64.getDecoder.decode(record.value.data.getBytes)) match {
          case Left(e)  => Stream.exec(errorHandler(record.value, e, record.ack, record.nack))
          case Right(v) => Stream.emit(record.toConsumerRecord(v))
        }
      }

  /**
    * Subscribe with automatic acknowledgement
    *
    * @param projectId          google cloud project id
    * @param subscription       name of the subscription
    * @param serviceAccountPath path to the Google Service account file (json)
    * @param errorHandler       upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribeAndAck[F[_]: Async: Logger, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: Option[String],
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    httpClientRetryPolicy: RetryPolicy[F] = recklesslyRetryPolicy[F]
  ): Stream[F, A] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient, httpClientRetryPolicy)
      .flatMap { record =>
        MessageDecoder[A].decode(Base64.getDecoder.decode(record.value.data.getBytes)) match {
          case Left(e)  => Stream.exec(errorHandler(record.value, e, record.ack, record.nack))
          case Right(v) => Stream.eval(record.ack >> v.pure)
        }
      }

  /**
    * Subscribe to the raw stream, receiving the the message as retrieved from PubSub
    */
  final def subscribeRaw[F[_]: Async: Logger](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: Option[String],
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    httpClientRetryPolicy: RetryPolicy[F] = recklesslyRetryPolicy[F],
  ): Stream[F, ConsumerRecord[F, PubsubMessage]] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient, httpClientRetryPolicy)
      .map(msg => msg.toConsumerRecord(msg.value))

  /*
    Pub/Sub requests are `POST` and thus are not considered idempotent by http4s, therefore we must
    use a different retry behaviour than the default.
   */
  def recklesslyRetryPolicy[F[_]]: RetryPolicy[F] =
    RetryPolicy(exponentialBackoff(maxWait = 5.seconds, maxRetry = 3), (_, result) => recklesslyRetriable(result))
}
