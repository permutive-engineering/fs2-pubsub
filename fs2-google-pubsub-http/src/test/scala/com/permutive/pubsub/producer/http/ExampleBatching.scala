package com.permutive.pubsub.producer.http

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.asynchttpclient.AsyncHttpClient

import scala.concurrent.duration._
import scala.util.Try

object ExampleBatching extends IOApp {

  private[this] final implicit val unsafeLogger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

  final implicit val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig())

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String,
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val mkProducer = BatchingHttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/service/account",
      config = PubsubHttpProducerConfig(
        host = "localhost",
        port = 8085,
        oauthTokenRefreshInterval = 30.minutes,
        isEmulator = true,
      ),
      batchingConfig = BatchingHttpProducerConfig(
        batchSize = 10,
        maxLatency = 100.millis,

        retryTimes = 0,
        retryInitialDelay = 0.millis,
        retryNextDelay = _ + 250.millis,
      ),
      onPublishFailure = (batch, e) => {
        Logger[IO].error(e)(s"Failed to publish ${batch.length} messages")
      },
      _
    )

    AsyncHttpClient
      .resource[IO]()
      .flatMap(mkProducer)
      .use { producer =>
        val value = producer.produce(
          record = ExampleObject("1f9774be-9d7c-4dd9-8d97-855b681938a9", "example.com")
        )

        value >> value >> value >> IO.never
      }
      .map(_ => ExitCode.Success)
  }
}
