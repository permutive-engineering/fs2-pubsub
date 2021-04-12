package com.permutive.pubsub.consumer.grpc.internal

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import com.google.api.gax.batching.FlowControlSettings
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}
import com.permutive.pubsub.consumer.{Model => PublicModel}
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig
import fs2.Stream
import org.threeten.bp.Duration

private[consumer] object PubsubSubscriber {

  def createSubscriber[F[_]](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F]
  )(implicit
    F: Sync[F]
  ): Resource[F, BlockingQueue[Model.Record[F]]] =
    Resource[F, BlockingQueue[Model.Record[F]]] {
      Sync[F].delay {
        val messages = new LinkedBlockingQueue[Model.Record[F]](config.maxQueueSize)
        val receiver = new MessageReceiver {
          override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
            messages.put(Model.Record(message, Sync[F].delay(consumer.ack()), Sync[F].delay(consumer.nack())))
        }
        val subscriptionName = ProjectSubscriptionName.of(projectId.value, subscription.value)

        // build subscriber with "normal" settings
        val builder =
          Subscriber
            .newBuilder(subscriptionName, receiver)
            .setFlowControlSettings(
              FlowControlSettings
                .newBuilder()
                .setMaxOutstandingElementCount(config.maxQueueSize.toLong)
                .build()
            )
            .setParallelPullCount(config.parallelPullCount)
            .setMaxAckExtensionPeriod(Duration.ofMillis(config.maxAckExtensionPeriod.toMillis))

        // if provided, use subscriber transformer to modify subscriber
        val sub =
          config.customizeSubscriber
            .map(f => f(builder))
            .getOrElse(builder)
            .build()

        val service = sub.startAsync()
        val shutdown =
          F.delay(
            service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS)
          ).handleErrorWith(config.onFailedTerminate)

        (messages, shutdown)
      }
    }

  def subscribe[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config))
      msg   <- Stream.repeatEval(Sync[F].blocking(queue.take()))
    } yield msg
}
