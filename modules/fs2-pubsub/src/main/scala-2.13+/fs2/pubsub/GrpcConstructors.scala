/*
 * Copyright 2019-2024 Permutive Ltd. <https://permutive.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.pubsub

import java.util.Base64

import cats.effect.Temporal
import cats.syntax.all._

import com.google.protobuf.ByteString
import com.google.pubsub.v1.pubsub.AcknowledgeRequest
import com.google.pubsub.v1.pubsub.ModifyAckDeadlineRequest
import com.google.pubsub.v1.pubsub.PublishRequest
import com.google.pubsub.v1.pubsub.Publisher
import com.google.pubsub.v1.pubsub.PubsubMessage
import com.google.pubsub.v1.pubsub.PullRequest
import com.google.pubsub.v1.pubsub.ReceivedMessage
import com.google.pubsub.v1.pubsub.Subscriber
import fs2.Chunk
import fs2.pubsub.dsl.client.PubSubClientStep
import fs2.pubsub.dsl.publisher.PubSubPublisherStep
import fs2.pubsub.dsl.subscriber.PubSubSubscriberStep
import org.http4s.Headers
import org.http4s.client.Client
import org.http4s.client.middleware.Retry
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all._

object GrpcConstructors {

  trait Publisher {

    /** Starts creating a gRPC Pub/Sub publisher in a step-by-step fashion.
      *
      * @tparam F
      *   the effect type
      * @tparam A
      *   the type of messages to be sent to Pub/Sub
      */
    def grpc[F[_]: Temporal, A: MessageEncoder]: PubSubPublisherStep[F, A] = {
      projectId => topic => uri => client => retryPolicy =>
        PubSubClient.grpc
          .projectId(projectId)
          .uri(uri)
          .httpClient(client)
          .retryPolicy(retryPolicy)
          .publisher
          .topic(topic)
    }

  }

  trait Subscriber {

    /** Starts creating a gRPC Pub/Sub subscriber in a step-by-step fashion.
      *
      * @tparam F
      *   the effect type
      */
    def grpc[F[_]: Temporal]: PubSubSubscriberStep[F] = { projectId => subscription => uri => client => retryPolicy =>
      PubSubClient.grpc
        .projectId(projectId)
        .uri(uri)
        .httpClient(client)
        .retryPolicy(retryPolicy)
        .subscriber
        .subscription(subscription)
    }

  }

  trait Client {

    /** Starts creating a gRPC Pub/Sub client in a step-by-step fashion.
      *
      * @tparam F
      *   the effect type
      */
    def grpc[F[_]: Temporal]: PubSubClientStep[F] = { projectId => uri => underlying => retryPolicy =>
      new PubSubClient[F] {

        private val httpClient = Retry.create(retryPolicy, logRetries = false) {
          Client[F](request => underlying.run(request.putHeaders(`Content-Type`(mediaType"application/grpc"))))
        }

        val subscriber = Subscriber.fromClient(httpClient, uri)
        val publisher  = Publisher.fromClient(httpClient, uri)

        override def publish[A: MessageEncoder](
            topic: Topic,
            records: Seq[PubSubRecord.Publisher[A]]
        ): F[List[MessageId]] = {
          val toPubSubMessage = (record: PubSubRecord.Publisher[A]) =>
            PubsubMessage(
              data = ByteString.copyFromUtf8(Base64.getEncoder().encodeToString(MessageEncoder[A].encode(record.data))),
              attributes = record.attributes
            )

          val request = PublishRequest.of(
            topic = show"projects/$projectId/topics/$topic",
            messages = records.map(toPubSubMessage)
          )

          publisher
            .publish(request, Headers.empty)
            .map(_.messageIds.map(MessageId(_)).toList)
        }

        override def read(
            subscription: Subscription,
            maxMessages: Int
        ): F[List[PubSubRecord.Subscriber[F, Array[Byte]]]] = {
          val request = PullRequest.of(
            subscription = show"projects/$projectId/subscriptions/$subscription",
            returnImmediately = false,
            maxMessages = maxMessages
          )

          val toPubSubRecord = (message: ReceivedMessage) =>
            PubSubRecord.Subscriber(
              message.message.map(m => m.data.toByteArray()).map(Base64.getDecoder().decode),
              message.message.map(_.attributes).orEmpty,
              message.message.map(_.messageId).map(MessageId(_)),
              message.message.flatMap(_.publishTime.map(_.asJavaInstant)),
              AckId(message.ackId),
              ack(subscription, AckId(message.ackId)),
              nack(subscription, AckId(message.ackId)),
              modifyDeadline(subscription, AckId(message.ackId), _)
            )

          subscriber
            .pull(request, Headers.empty)
            .map(_.receivedMessages.map(toPubSubRecord).toList)
        }

        override def ack(subscription: Subscription, ackIds: Chunk[AckId]): F[Unit] = {
          val request = AcknowledgeRequest.of(
            subscription = show"projects/$projectId/subscriptions/$subscription",
            ackIds = ackIds.map(_.value).toList
          )

          subscriber
            .acknowledge(request, Headers.empty)
            .void
        }

        override def modifyDeadline(subscription: Subscription, ackIds: Chunk[AckId], by: AckDeadline): F[Unit] = {
          val request = ModifyAckDeadlineRequest.of(
            subscription = show"projects/$projectId/subscriptions/$subscription",
            ackIds = ackIds.map(_.value).toList,
            ackDeadlineSeconds = by.value.toSeconds.toInt
          )

          subscriber
            .modifyAckDeadline(request, Headers.empty)
            .void
        }

      }
    }

  }

}
