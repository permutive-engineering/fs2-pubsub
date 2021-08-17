package com.permutive.pubsub.consumer.grpc

import cats.Applicative
import cats.effect.{Blocker, Concurrent, ContextShift}
import cats.syntax.all._
import com.google.pubsub.v1.PubsubMessage
import com.permutive.pubsub.consumer.{ConsumerRecord, Model}
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.consumer.grpc.internal.PubsubSubscriber
import fs2.Stream

object PubsubGoogleConsumer {

  /**
    * Indicates the underlying Java PubSub consumer has failed.
    *
    * @param cause the cause of the failure
    */
  case class InternalPubSubError(cause: Throwable) extends Throwable("Internal Java PubSub consumer failed", cause)

  /**
    * Subscribe with manual acknowledgement.
    *
    * The stream fails with an [[InternalPubSubError]] if the underlying Java consumer fails.
    *
    * @param blocker
    * @param projectId    google cloud project id
    * @param subscription name of the subscription
    * @param errorHandler upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribe[F[_]: Concurrent: ContextShift, A: MessageDecoder](
    blocker: Blocker,
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    config: PubsubGoogleConsumerConfig[F]
  ): Stream[F, ConsumerRecord[F, A]] =
    PubsubSubscriber
      .subscribe(blocker, projectId, subscription, config)
      .flatMap { case internal.Model.Record(msg, ack, nack) =>
        MessageDecoder[A].decode(msg.getData.toByteArray) match {
          case Left(e)  => Stream.eval_(errorHandler(msg, e, ack, nack))
          case Right(v) => Stream.emit(ConsumerRecord(v, ack, nack, _ => Applicative[F].unit))
        }
      }

  /**
    * Subscribe with automatic acknowledgement
    *
    * The stream fails with an [[InternalPubSubError]] if the underlying Java consumer fails.
    *
    * @param blocker
    * @param projectId    google cloud project id
    * @param subscription name of the subscription
    * @param errorHandler upon failure to decode, an exception is thrown. Allows acknowledging the message.
    */
  final def subscribeAndAck[F[_]: Concurrent: ContextShift, A: MessageDecoder](
    blocker: Blocker,
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit],
    config: PubsubGoogleConsumerConfig[F]
  ): Stream[F, A] =
    PubsubSubscriber
      .subscribe(blocker, projectId, subscription, config)
      .flatMap { case internal.Model.Record(msg, ack, nack) =>
        MessageDecoder[A].decode(msg.getData.toByteArray) match {
          case Left(e)  => Stream.eval_(errorHandler(msg, e, ack, nack))
          case Right(v) => Stream.eval(ack >> v.pure)
        }
      }

  /**
    * Subscribe to the raw stream, receiving the the message as retrieved from PubSub
    *
    * The stream fails with an [[InternalPubSubError]] if the underlying Java consumer fails.
    */
  final def subscribeRaw[F[_]: Concurrent: ContextShift](
    blocker: Blocker,
    projectId: Model.ProjectId,
    subscription: Model.Subscription,
    config: PubsubGoogleConsumerConfig[F]
  ): Stream[F, ConsumerRecord[F, PubsubMessage]] =
    PubsubSubscriber
      .subscribe(blocker, projectId, subscription, config)
      .map(msg => ConsumerRecord(msg.value, msg.ack, msg.nack, _ => Applicative[F].unit))
}
