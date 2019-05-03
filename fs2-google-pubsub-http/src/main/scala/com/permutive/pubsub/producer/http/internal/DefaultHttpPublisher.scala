package com.permutive.pubsub.producer.http.internal

import java.util.Base64

import alleycats.syntax.foldable._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.all._
import cats.{Foldable, Traverse}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.http.oauth.{AccessToken, DefaultTokenProvider}
import com.permutive.pubsub.http.util.RefreshableRef
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.PubsubHttpProducerConfig
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.http4s.Method._
import org.http4s.Uri._
import org.http4s._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers._

import scala.util.control.NoStackTrace

private[http] class DefaultHttpPublisher[F[_], A: MessageEncoder] private(
  baseApiUrl: Uri,
  topic: Model.Topic,
  client: Client[F],
  tokenRef: Ref[F, AccessToken],
)(
  implicit F: Async[F]
) extends PubsubProducer[F, A] with Http4sClientDsl[F] {
  import DefaultHttpPublisher._

  private[this] final val publishRoute = baseApiUrl.copy(path = baseApiUrl.path.concat(":publish"))

  override final def produce(record: A, metadata: Map[String, String], uniqueId: String): F[MessageId] = {
    produceMany[List](List(Model.Record(record, metadata, uniqueId))).map(_.head)
  }

  override final def produceMany[G[_]: Traverse](records: G[Model.Record[A]]): F[List[MessageId]] = {
    for {
      msgs <- records.traverse(recordToMessage)
      json <- F.delay(writeToArray(MessageBundle(msgs)))
      resp <- sendHttpRequest(json)
    } yield resp
  }

  private def sendHttpRequest(json: Array[Byte]): F[List[MessageId]] = {
    for {
      token <- tokenRef.get
      req   <- POST(
        json,
        publishRoute.withQueryParam("access_token", token.accessToken),
        `Content-Type`(MediaType.application.json)
      )
      resp  <- client.expectOr[Array[Byte]](req)(onError)
      resp  <- F.delay(readFromArray[MessageIds](resp))
    } yield resp.messageIds
  }

  @inline
  private def recordToMessage(record: Model.Record[A]): F[Message] = {
    F.fromEither(
      MessageEncoder[A]
        .encode(record.value)
        .map(toMessage(_, record.uniqueId, record.metadata))
    )
  }

  @inline
  private def toMessage(bytes: Array[Byte], uniqueId: String, attributes: Map[String, String]): Message =
    Message(
      data = Base64.getEncoder.encodeToString(bytes),
      messageId = uniqueId,
      attributes = attributes,
    )

  @inline
  private def onError(resp: Response[F]): F[Throwable] = {
    resp.as[String].map(FailedRequestToPubsub.apply)
  }
}

private[http] object DefaultHttpPublisher {

  def resource[F[_] : Concurrent : Timer : Logger, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    serviceAccountPath: String,
    config: PubsubHttpProducerConfig[F],
    httpClient: Client[F],
  ): Resource[F, PubsubProducer[F, A]] = {

    def retryRefreshToken(provider: F[AccessToken]): F[AccessToken] = {
      Stream
        .retry(
          provider,
          delay = config.oauthTokenFailureRetryDelay,
          nextDelay = config.oauthTokenFailureRetryNextDelay,
          maxAttempts = config.oauthTokenFailureRetryMaxAttempts,
        )
        .compile
        .lastOrError
    }

    for {
      tokenProvider <- Resource.liftF(
        if (config.isEmulator) DefaultTokenProvider.noAuth.pure[F]
        else DefaultTokenProvider.google(serviceAccountPath, httpClient)
      )
      accessTokenRef <- RefreshableRef.resource[F, AccessToken](
        refresh = retryRefreshToken(tokenProvider.accessToken),
        refreshInterval = config.oauthTokenRefreshInterval,
        onRefreshError = config.onTokenRefreshError,
      )
    } yield new DefaultHttpPublisher[F, A](
      baseApiUrl = createBaseApiUri(projectId, topic, config),
      topic = topic,
      client = httpClient,
      tokenRef = accessTokenRef.ref,
    )
  }

  def createBaseApiUri[F[_]](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    config: PubsubHttpProducerConfig[F],
  ): Uri = {
    Uri(
      scheme = Option(if (config.port == 443) Uri.Scheme.https else Uri.Scheme.http),
      authority = Option(Uri.Authority(host = RegName(config.host), port = Option(config.port))),
      path = s"/v1/projects/${projectId.value}/topics/${topic.value}"
    )
  }

  case class Message(
    data: String,
    messageId: String,
    attributes: Map[String, String]
  )

  case class MessageBundle[G[_]](
    messages: G[Message],
  )

  case class MessageIds(
    messageIds: List[MessageId],
  )

  final implicit def foldableMessagesCodec[G[_]](implicit G: Foldable[G]): JsonValueCodec[G[Message]] =
    new JsonValueCodec[G[Message]] {
      override def decodeValue(in: JsonReader, default: G[Message]): G[Message] = ???

      override def encodeValue(x: G[Message], out: JsonWriter): Unit = {
        out.writeArrayStart()
        x.foreach(MessageCodec.encodeValue(_, out))
        out.writeArrayEnd()
      }

      override def nullValue: G[Message] = ???
    }


  final implicit val MessageCodec: JsonValueCodec[Message] =
    JsonCodecMaker.make[Message](CodecMakerConfig())

  final implicit def messageBundleCodec[G[_] : Foldable]: JsonValueCodec[MessageBundle[G]] =
    JsonCodecMaker.make[MessageBundle[G]](CodecMakerConfig())

  final implicit val MessageIdsCodec: JsonValueCodec[MessageIds] =
    JsonCodecMaker.make[MessageIds](CodecMakerConfig())

  case class FailedRequestToPubsub(response: String) extends Throwable(s"Failed request to pubsub. Response was: $response") with NoStackTrace
}
