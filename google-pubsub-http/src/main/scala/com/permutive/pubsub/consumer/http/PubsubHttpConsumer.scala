package com.permutive.pubsub.consumer.http

import java.util.Base64

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.consumer.http.internal.PubsubSubscriber
import fs2.Stream
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
  final def subscribe[F[_] : Concurrent : Timer, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
  ): Stream[F, Model.Record[F, A]] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .flatMap {
        case internal.Model.Record(msg, ack, nack) =>
          MessageDecoder[A].decode(Base64.getDecoder.decode(msg.data.getBytes)) match {
            case Left(e) => Stream.eval(errorHandler(msg, e, ack, nack)) >> Stream.empty
            case Right(v) => Stream.emit(Model.Record(v, ack, nack))
          }
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
  final def subscribeAndAck[F[_] : Concurrent : Timer, A: MessageDecoder](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
  ): Stream[F, A] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .flatMap {
        case internal.Model.Record(msg, ack, nack) =>
          MessageDecoder[A].decode(Base64.getDecoder.decode(msg.data.getBytes)) match {
            case Left(e) => Stream.eval(errorHandler(msg, e, ack, nack)) >> Stream.empty
            case Right(v) => Stream.eval(ack >> v.pure)
          }
      }
  }

  /**
    * Subscribe to the raw stream, receiving the the message as retrieved from PubSub
    */
  final def subscribeRaw[F[_] : Concurrent : Timer](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
  ): Stream[F, Model.Record[F, PubsubMessage]] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, serviceAccountPath, config, httpClient)
      .map(msg => Model.Record(msg.value, msg.ack, msg.nack))
  }
}
