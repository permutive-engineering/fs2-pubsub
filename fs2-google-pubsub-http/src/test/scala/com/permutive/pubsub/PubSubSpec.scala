/*
 * Copyright 2018 Permutive
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

package com.permutive.pubsub

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{FixedTransportChannelProvider, TransportChannelProvider}
import com.google.cloud.pubsub.v1.{
  SubscriptionAdminClient,
  SubscriptionAdminSettings,
  TopicAdminClient,
  TopicAdminSettings
}
import com.google.pubsub.v1._
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import com.permutive.pubsub.consumer.http.{PubsubHttpConsumer, PubsubHttpConsumerConfig}
import com.permutive.pubsub.consumer.{ConsumerRecord, Model}
import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.http.{HttpPubsubProducer, PubsubHttpProducerConfig}
import fs2.Stream
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.http4s.client.Client
import org.http4s.okhttp.client.OkHttpBuilder
import org.scalactic.TripleEquals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.log4cats.Logger

trait PubSubSpec extends AnyFlatSpec with ForAllTestContainer with Matchers with TripleEquals {

  implicit val logger: Logger[IO]
  implicit val ioRuntime: IORuntime = IORuntime.global

  val project      = "test-project"
  val topic        = "example-topic"
  val subscription = "example-subscription"

  override val container: GenericContainer =
    GenericContainer(
      "google/cloud-sdk:311.0.0", // newer version don't work for some reason
      exposedPorts = Seq(8085),
      waitStrategy = Wait.forLogMessage("(?s).*started.*$", 1),
      command = s"gcloud beta emulators pubsub start --project=$project --host-port 0.0.0.0:8085"
        .split(" ")
        .toSeq
    )

  override def afterStart(): Unit =
    updateEnv("PUBSUB_EMULATOR_HOST", s"localhost:${container.mappedPort(8085)}")

  def providers: Resource[IO, (TransportChannelProvider, CredentialsProvider)] =
    Resource
      .make(
        IO {
          ManagedChannelBuilder
            .forAddress("localhost", container.mappedPort(8085))
            .usePlaintext()
            .build(): ManagedChannel
        }
      )(ch => IO.blocking(ch.shutdown()).void)
      .map { channel =>
        val channelProvider: FixedTransportChannelProvider =
          FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider: NoCredentialsProvider = NoCredentialsProvider.create

        (channelProvider: TransportChannelProvider, credentialsProvider: CredentialsProvider)
      }

  def topicAdminClient(
    transportChannelProvider: TransportChannelProvider,
    credentialsProvider: CredentialsProvider,
  ): Resource[IO, TopicAdminClient] =
    Resource.fromAutoCloseable(
      IO(
        TopicAdminClient.create(
          TopicAdminSettings
            .newBuilder()
            .setTransportChannelProvider(transportChannelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        )
      )
    )

  def createTopic(projectId: String, topicId: String): IO[Topic] =
    providers
      .flatMap { case (transport, creds) => topicAdminClient(transport, creds) }
      .use(client => IO.blocking(client.createTopic(TopicName.of(projectId, topicId))))
      .flatTap(topic => IO.println(s"Topic: $topic"))

  def deleteTopic(client: TopicAdminClient, topic: TopicName): IO[Unit] =
    IO.blocking(client.deleteTopic(topic))

  def subscriptionAdminClient(
    transportChannelProvider: TransportChannelProvider,
    credentialsProvider: CredentialsProvider,
  ): Resource[IO, SubscriptionAdminClient] =
    Resource.fromAutoCloseable(
      IO(
        SubscriptionAdminClient.create(
          SubscriptionAdminSettings
            .newBuilder()
            .setTransportChannelProvider(transportChannelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        )
      )
    )

  def deleteSubscription(client: SubscriptionAdminClient, sub: ProjectSubscriptionName): IO[Unit] =
    IO.blocking(client.deleteSubscription(sub))

  def createSubscription(
    projectId: String,
    topicId: String,
    subscription: String,
    ackDeadlineSeconds: Int,
  ): IO[Subscription] =
    providers
      .flatMap { case (transport, creds) => subscriptionAdminClient(transport, creds) }
      .use(client =>
        IO.blocking(
          client.createSubscription(
            ProjectSubscriptionName.format(projectId, subscription),
            TopicName.format(projectId, topicId),
            PushConfig.getDefaultInstance,
            ackDeadlineSeconds
          )
        )
      )
      .flatTap(sub => IO.println(s"Sub: $sub"))

  def client: Resource[IO, Client[IO]] =
    OkHttpBuilder
      .withDefaultClient[IO]
      .flatMap(_.resource)

  def producer(
    client: Client[IO],
    project: String = project,
    topic: String = topic
  ): Resource[IO, PubsubProducer[IO, ValueHolder]] =
    HttpPubsubProducer.resource[IO, ValueHolder](
      com.permutive.pubsub.producer.Model.ProjectId(project),
      com.permutive.pubsub.producer.Model.Topic(topic),
      Some("/path/to/service/account"),
      config = PubsubHttpProducerConfig(
        host = container.host,
        port = container.mappedPort(8085),
        isEmulator = true,
      ),
      client
    )

  def consumer(
    client: Client[IO],
    project: String = project,
    subscription: String = subscription,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    PubsubHttpConsumer.subscribe[IO, ValueHolder](
      Model.ProjectId(project),
      Model.Subscription(subscription),
      Some("/path/to/service/account"),
      PubsubHttpConsumerConfig(
        host = container.host,
        port = container.mappedPort(8085),
        isEmulator = true
      ),
      client,
      (msg, err, ack, _) => IO.println(s"Msg $msg got error $err") >> ack
    )

  def updateEnv(name: String, value: String): Unit = {
    val env   = System.getenv
    val field = env.getClass.getDeclaredField("m")
    field.setAccessible(true)
    field.get(env).asInstanceOf[java.util.Map[String, String]].put(name, value)
    ()
  }
}
