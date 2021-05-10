package com.permutive.pubsub.consumer.http.internal

import cats.effect._
import cats.syntax.all._
import cats.effect.std.Queue
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.http.{PubsubHttpConsumerConfig, PubsubMessage}
import com.permutive.pubsub.consumer.http.internal.HttpPubsubReader.PubSubError
import com.permutive.pubsub.consumer.http.internal.Model.{AckId, InternalRecord}
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.http4s.client.Client
import org.http4s.client.middleware.{Retry, RetryPolicy}

import scala.concurrent.duration.FiniteDuration

private[http] object PubsubSubscriber {

  def subscribe[F[_]: Logger](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: Option[String],
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
    httpClientRetryPolicy: RetryPolicy[F]
  )(implicit
    F: Async[F]
  ): Stream[F, InternalRecord[F]] = {
    val errorHandler: Throwable => F[Unit] = {
      case PubSubError.NoAckIds =>
        Logger[F].warn(s"[PubSub/Ack] a message was sent with no ids in it. This is likely a bug.")
      case PubSubError.Unknown(e) =>
        Logger[F].error(s"[PubSub] Unknown PubSub error occurred. Body is: $e")
      case PubSubError.UnparseableBody(body) =>
        Logger[F].error(s"[PubSub] A response from PubSub could not be parsed. Body is: $body")
      case e =>
        Logger[F].error(e)(s"[PubSub] An unknown error occurred")
    }

    for {
      ackQ  <- Stream.eval(Queue.unbounded[F, AckId])
      nackQ <- Stream.eval(Queue.unbounded[F, AckId])
      reader <- Stream.resource(
        HttpPubsubReader.resource(
          projectId = projectId,
          subscription = subscription,
          serviceAccountPath = serviceAccountPath,
          config = config,
          httpClient = Retry(httpClientRetryPolicy)(httpClient)
        )
      )
      source =
        if (config.readConcurrency == 1) Stream.repeatEval(reader.read)
        else Stream.emit(reader.read).repeat.covary[F].mapAsyncUnordered(config.readConcurrency)(identity)
      rec <-
        source
          .concurrently(
            Stream
              .repeatEval(ackQ.take)
              .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
              .evalMap(ids => reader.ack(ids.toList).handleErrorWith(errorHandler))
              .onFinalize(Logger[F].debug("[PubSub] Ack queue has exited."))
          )
          .concurrently(
            Stream
              .repeatEval(nackQ.take)
              .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
              .evalMap(ids => reader.nack(ids.toList).handleErrorWith(errorHandler))
              .onFinalize(Logger[F].debug("[PubSub] Nack queue has exited."))
          )
      msg <- Stream.emits(
        rec.receivedMessages.map { msg =>
          new InternalRecord[F] {
            override val value: PubsubMessage                        = msg.message
            override val ack: F[Unit]                                = ackQ.offer(msg.ackId)
            override val nack: F[Unit]                               = nackQ.offer(msg.ackId)
            override def extendDeadline(by: FiniteDuration): F[Unit] = reader.modifyDeadline(List(msg.ackId), by)
          }
        }
      )
    } yield msg
  }

}
