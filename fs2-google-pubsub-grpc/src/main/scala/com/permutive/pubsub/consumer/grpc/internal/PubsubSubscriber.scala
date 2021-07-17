package com.permutive.pubsub.consumer.grpc.internal

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import cats.effect.{Blocker, ContextShift, Resource, Sync}
import cats.syntax.all._
import com.google.api.core.ApiService
import com.google.api.gax.batching.FlowControlSettings
import com.google.common.util.concurrent.MoreExecutors
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
  ): Resource[F, BlockingQueue[Either[Throwable, Model.Record[F]]]] =
    Resource[F, BlockingQueue[Either[Throwable, Model.Record[F]]]] {
      Sync[F].delay {
        val messages = new LinkedBlockingQueue[Either[Throwable, Model.Record[F]]](config.maxQueueSize)
        val receiver = new MessageReceiver {
          override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
            messages.put(Right(Model.Record(message, Sync[F].delay(consumer.ack()), Sync[F].delay(consumer.nack()))))
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
        sub.addListener(new PubsubErrorListener(messages), MoreExecutors.directExecutor)

        val service = sub.startAsync()
        val shutdown =
          F.delay(
            service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS)
          ).handleErrorWith(config.onFailedTerminate)

        (messages, shutdown)
      }
    }

  class PubsubErrorListener[R](messages: BlockingQueue[Either[Throwable, R]]) extends ApiService.Listener {
    override def failed(from: ApiService.State, failure: Throwable): Unit =
      messages.put(Left(failure))
  }

  def subscribe[F[_]: Sync: ContextShift](
    blocker: Blocker,
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config))
      next  <- Stream.repeatEval(blocker.delay(queue.take()))
      msg   <- next.fold(Stream.raiseError(_), Stream.emit(_))
    } yield msg
}
