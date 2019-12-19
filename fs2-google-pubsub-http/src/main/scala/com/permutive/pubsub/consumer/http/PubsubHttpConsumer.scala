package com.permutive.pubsub.consumer.http

import java.util.Base64

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.permutive.pubsub.consumer.ConsumerRecord
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.consumer.http.internal.PubsubSubscriber
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.http4s.client.Client

object PubsubHttpConsumer {

  /**
    * Subscribe with manual acknowledgement
    *
    * @param projectId          google cloud project id
    * @param subscription       name of the subscription
    * @param serviceAccountPath path to the Google Service account file (json)
    * @param errorHandler       upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribe[F[_]: Concurrent: Timer: Logger, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit]
  ): Stream[F, ConsumerRecord[F, A]] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .flatMap { record =>
        MessageDecoder[A].decode(Base64.getDecoder.decode(record.value.data.getBytes)) match {
          case Left(e)  => Stream.eval_(errorHandler(record.value, e, record.ack, record.nack))
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
  final def subscribeAndAck[F[_]: Concurrent: Timer: Logger, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit]
  ): Stream[F, A] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .flatMap { record =>
        MessageDecoder[A].decode(Base64.getDecoder.decode(record.value.data.getBytes)) match {
          case Left(e)  => Stream.eval_(errorHandler(record.value, e, record.ack, record.nack))
          case Right(v) => Stream.eval(record.ack >> v.pure)
        }
      }

  /**
    * Subscribe to the raw stream, receiving the the message as retrieved from PubSub
    */
  final def subscribeRaw[F[_]: Concurrent: Timer: Logger](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F]
  ): Stream[F, ConsumerRecord[F, PubsubMessage]] =
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .map(msg => msg.toConsumerRecord(msg.value))
}
