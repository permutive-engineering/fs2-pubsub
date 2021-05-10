package com.permutive.pubsub.producer.http.internal

import java.util.Base64

import alleycats.syntax.foldable._
import cats.effect._
import cats.syntax.all._
import cats.{Applicative, Foldable, Traverse}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.http.oauth.{AccessToken, DefaultTokenProvider}
import com.permutive.pubsub.http.util.RefreshableEffect
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.PubsubHttpProducerConfig
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import org.typelevel.log4cats.Logger
import org.http4s.Method._
import org.http4s.Uri._
import org.http4s._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers._

private[http] class DefaultHttpPublisher[F[_]: Logger, A: MessageEncoder] private (
  baseApiUrl: Uri,
  client: Client[F],
  tokenF: F[AccessToken],
)(implicit
  F: Async[F]
) extends PubsubProducer[F, A]
    with Http4sClientDsl[F] {
  import DefaultHttpPublisher._

  final private[this] val publishRoute =
    Uri.unsafeFromString(s"${baseApiUrl.renderString}:publish")

  final override def produce(record: A, metadata: Map[String, String], uniqueId: String): F[MessageId] =
    produceMany[List](List(Model.SimpleRecord(record, metadata, uniqueId))).map(_.head)

  final override def produceMany[G[_]: Traverse](records: G[Model.Record[A]]): F[List[MessageId]] =
    for {
      msgs <- records.traverse(recordToMessage)
      json <- F.delay(writeToArray(MessageBundle(msgs)))
      resp <- sendHttpRequest(json)
    } yield resp

  private def sendHttpRequest(json: Array[Byte]): F[List[MessageId]] =
    for {
      token <- tokenF
      req = POST(
        json,
        publishRoute.withQueryParam("access_token", token.accessToken),
        `Content-Type`(MediaType.application.json)
      )
      resp <- client.expectOr[Array[Byte]](req)(onError)
      resp <- F.delay(readFromArray[MessageIds](resp)).onError { case _ =>
        Logger[F].error(s"Publish response from PubSub was invalid. Body: ${new String(resp)}")
      }
    } yield resp.messageIds

  @inline
  private def recordToMessage(record: Model.Record[A]): F[Message] =
    F.fromEither(
      MessageEncoder[A]
        .encode(record.value)
        .map(toMessage(_, record.uniqueId, record.metadata))
    )

  @inline
  private def toMessage(bytes: Array[Byte], uniqueId: String, attributes: Map[String, String]): Message =
    Message(
      data = Base64.getEncoder.encodeToString(bytes),
      messageId = uniqueId,
      attributes = attributes
    )

  @inline
  private def onError(resp: Response[F]): F[Throwable] =
    resp.as[String].map(FailedRequestToPubsub(resp.status, _))
}

private[http] object DefaultHttpPublisher {

  def resource[F[_]: Async: Logger, A: MessageEncoder](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    serviceAccountPath: Option[String],
    config: PubsubHttpProducerConfig[F],
    httpClient: Client[F]
  ): Resource[F, PubsubProducer[F, A]] =
    for {
      accessToken <-
        if (config.isEmulator) Resource.pure[F, F[AccessToken]](DefaultTokenProvider.noAuth.accessToken)
        else
          serviceAccountPath.fold(
            Resource.pure[F, F[AccessToken]](DefaultTokenProvider.instanceMetadata(httpClient).accessToken)
          )(path =>
            for {
              tokenProvider <- Resource.eval(DefaultTokenProvider.google(path, httpClient))
              accessTokenRefEffect <- RefreshableEffect.createRetryResource(
                refresh = tokenProvider.accessToken,
                refreshInterval = config.oauthTokenRefreshInterval,
                onRefreshSuccess = config.onTokenRefreshSuccess.getOrElse(Applicative[F].unit),
                onRefreshError = config.onTokenRefreshError,
                retryDelay = config.oauthTokenFailureRetryDelay,
                retryNextDelay = config.oauthTokenFailureRetryNextDelay,
                retryMaxAttempts = config.oauthTokenFailureRetryMaxAttempts,
                onRetriesExhausted = config.onTokenRetriesExhausted,
              )
            } yield accessTokenRefEffect.value
          )
    } yield new DefaultHttpPublisher[F, A](
      baseApiUrl = createBaseApiUri(projectId, topic, config),
      client = httpClient,
      tokenF = accessToken
    )

  def createBaseApiUri[F[_]](
    projectId: Model.ProjectId,
    topic: Model.Topic,
    config: PubsubHttpProducerConfig[F]
  ): Uri =
    Uri(
      scheme = Option(if (config.port == 443) Uri.Scheme.https else Uri.Scheme.http),
      authority = Option(Uri.Authority(host = RegName(config.host), port = Option(config.port))),
      path = Uri.Path.unsafeFromString(s"/v1/projects/${projectId.value}/topics/${topic.value}")
    )

  case class Message(
    data: String,
    messageId: String,
    attributes: Map[String, String]
  )

  case class MessageBundle[G[_]](
    messages: G[Message]
  )

  case class MessageIds(
    messageIds: List[MessageId]
  )

  implicit final def foldableMessagesCodec[G[_]](implicit G: Foldable[G]): JsonValueCodec[G[Message]] =
    new JsonValueCodec[G[Message]] {
      override def decodeValue(in: JsonReader, default: G[Message]): G[Message] = ???

      override def encodeValue(x: G[Message], out: JsonWriter): Unit = {
        out.writeArrayStart()
        x.foreach(MessageCodec.encodeValue(_, out))
        out.writeArrayEnd()
      }

      override def nullValue: G[Message] = ???
    }

  implicit final val MessageCodec: JsonValueCodec[Message] =
    JsonCodecMaker.make[Message](CodecMakerConfig)

  implicit final def messageBundleCodec[G[_]: Foldable]: JsonValueCodec[MessageBundle[G]] =
    JsonCodecMaker.make[MessageBundle[G]](CodecMakerConfig)

  implicit final val MessageIdsCodec: JsonValueCodec[MessageIds] =
    JsonCodecMaker.make[MessageIds](CodecMakerConfig)

  case class FailedRequestToPubsub(status: Status, response: String)
      extends Throwable(s"Failed request to pubsub. Response was: $response")
}
