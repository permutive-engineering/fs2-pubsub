package com.permutive.pubsub.http.crypto

import java.nio.file.{Files, Path}
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.regex.Pattern

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

import scala.util.Try

object GoogleAccountParser {
  case class JsonGoogleServiceAccount(
    `type`: String,
    projectId: String,
    privateKeyId: String,
    privateKey: String,
    clientEmail: String,
    authUri: String,
  )

  object JsonGoogleServiceAccount {
    final implicit val codec: JsonValueCodec[JsonGoogleServiceAccount] =
      JsonCodecMaker.make[JsonGoogleServiceAccount](CodecMakerConfig(
        fieldNameMapper = JsonCodecMaker.enforce_snake_case
      ))
  }

  final def parse(path: Path): Either[Throwable, GoogleServiceAccount] = {
    Try {
      val serviceAccount = readFromArray[JsonGoogleServiceAccount](Files.readAllBytes(path))
      val spec = new PKCS8EncodedKeySpec(loadPem(serviceAccount.privateKey))
      val kf = KeyFactory.getInstance("RSA")
      GoogleServiceAccount(
        clientEmail = serviceAccount.clientEmail,
        privateKey = kf.generatePrivate(spec).asInstanceOf[RSAPrivateKey]
      )
    }.toEither
  }

  private[this] final val privateKeyPattern = Pattern.compile("(?m)(?s)^---*BEGIN.*---*$(.*)^---*END.*---*$.*")

  private def loadPem(pem: String): Array[Byte] = {
    val encoded = privateKeyPattern.matcher(pem).replaceFirst("$1")
    Base64.getMimeDecoder.decode(encoded)
  }
}

case class GoogleServiceAccount(clientEmail: String, privateKey: RSAPrivateKey)