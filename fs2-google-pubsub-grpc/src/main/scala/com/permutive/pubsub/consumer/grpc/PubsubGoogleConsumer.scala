package com.permutive.pubsub.consumer.grpc

import cats.effect.{Concurrent, ContextShift}
import cats.syntax.all._
import com.google.pubsub.v1.PubsubMessage
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.consumer.grpc.internal.PubsubSubscriber
import fs2.Stream

object PubsubGoogleConsumer {

  /**
    * Subscribe with manual acknowledgement
    *
    * @param projectId    google cloud project id
    * @param subscription name of the subscription
    * @param errorHandler upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribe[F[_] : Concurrent : ContextShift, A: MessageDecoder](
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    config: PubsubGoogleConsumerConfig[F]
  ): Stream[F, Model.Record[F, A]] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, config)
      .flatMap {
        case internal.Model.Record(msg, ack, nack) =>
          MessageDecoder[A].decode(msg.getData.toByteArray) match {
            case Left(e) => Stream.eval_(errorHandler(msg, e, ack, nack))
            case Right(v) => Stream.emit(Model.Record(v, ack, nack))
          }
      }
  }

  /**
    * Subscribe with automatic acknowledgement
    *
    * @param projectId    google cloud project id
    * @param subscription name of the subscription
    * @param errorHandler upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribeAndAck[F[_] : Concurrent : ContextShift, A: MessageDecoder](
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, A] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, config)
      .flatMap {
        case internal.Model.Record(msg, ack, nack) =>
          MessageDecoder[A].decode(msg.getData.toByteArray) match {
            case Left(e) => Stream.eval_(errorHandler(msg, e, ack, nack))
            case Right(v) => Stream.eval(ack >> v.pure)
          }
      }
  }

  /**
    * Subscribe to the raw stream, receiving the the message as retrieved from PubSub
    */
  final def subscribeRaw[F[_] : Concurrent : ContextShift](
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F, PubsubMessage]] = {
    PubsubSubscriber
      .subscribe(projectId, subscription, config)
      .map(msg => Model.Record(msg.value, msg.ack, msg.nack))
  }
}