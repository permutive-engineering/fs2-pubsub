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
import com.permutive.pubsub.consumer.{Model => ConsumerModel}
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumer
import com.permutive.pubsub.producer.{Model => ProducerModel}
import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.grpc.GooglePubsubProducer
import fs2.Stream
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.http4s.client.Client
import org.http4s.okhttp.client.OkHttpBuilder
import org.scalactic.TripleEquals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.log4cats.Logger
import com.permutive.pubsub.producer.grpc.PubsubProducerConfig

import scala.concurrent.duration._
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig
import com.permutive.pubsub.consumer.ConsumerRecord

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
            .forAddress("0.0.0.0", container.mappedPort(8085))
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
    project: String = project,
    topic: String = topic
  ): Resource[IO, PubsubProducer[IO, ValueHolder]] =
    providers
      .flatMap { case (transportChannelProvider, credentialsProvider) =>
        GooglePubsubProducer.of[IO, ValueHolder](
          ProducerModel.ProjectId(project),
          ProducerModel.Topic(topic),
          PubsubProducerConfig[IO](
            batchSize = 100,
            delayThreshold = 100.millis,
            awaitTerminatePeriod = 5.seconds,
            onFailedTerminate = e => IO.println(s"Failed to terminate: got error $e"),
            customizePublisher = Some {
              _.setChannelProvider(transportChannelProvider).setCredentialsProvider(credentialsProvider)
            }
          )
        )
      }

  def consumer(
    project: String = project,
    subscription: String = subscription,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    for {
      (transportChannelProvider, credentialsProvider) <- Stream.resource(providers)
      records <- PubsubGoogleConsumer.subscribe[IO, ValueHolder](
        ConsumerModel.ProjectId(project),
        ConsumerModel.Subscription(subscription),
        (_, _, _, _) => IO.unit,
        PubsubGoogleConsumerConfig[IO](
          onFailedTerminate = _ => IO.unit,
          customizeSubscriber = Some {
            _.setChannelProvider(transportChannelProvider).setCredentialsProvider(credentialsProvider)
          }
        )
      )
    } yield records

  def updateEnv(name: String, value: String): Unit = {
    val env   = System.getenv
    val field = env.getClass.getDeclaredField("m")
    field.setAccessible(true)
    field.get(env).asInstanceOf[java.util.Map[String, String]].put(name, value)
    ()
  }
}
