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
    authUri: String
  )

  object JsonGoogleServiceAccount {
    implicit final val codec: JsonValueCodec[JsonGoogleServiceAccount] =
      JsonCodecMaker.make[JsonGoogleServiceAccount](
        CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
      )
  }

  final def parse(path: Path): Either[Throwable, GoogleServiceAccount] =
    Try {
      val serviceAccount = readFromArray[JsonGoogleServiceAccount](Files.readAllBytes(path))
      val spec           = new PKCS8EncodedKeySpec(loadPem(serviceAccount.privateKey))
      val kf             = KeyFactory.getInstance("RSA")
      GoogleServiceAccount(
        clientEmail = serviceAccount.clientEmail,
        privateKey = kf.generatePrivate(spec).asInstanceOf[RSAPrivateKey]
      )
    }.toEither

  final def parseString(input: String): Either[Throwable, GoogleServiceAccount] =
    Try {
      val serviceAccount = readFromString[JsonGoogleServiceAccount](input)
      val spec           = new PKCS8EncodedKeySpec(loadPem(serviceAccount.privateKey))
      val kf             = KeyFactory.getInstance("RSA")
      GoogleServiceAccount(
        clientEmail = serviceAccount.clientEmail,
        privateKey = kf.generatePrivate(spec).asInstanceOf[RSAPrivateKey]
      )
    }.toEither

  final private[this] val privateKeyPattern = Pattern.compile("(?m)(?s)^---*BEGIN.*---*$(.*)^---*END.*---*$.*")

  private def loadPem(pem: String): Array[Byte] = {
    val encoded = privateKeyPattern.matcher(pem).replaceFirst("$1")
    Base64.getMimeDecoder.decode(encoded)
  }
}

case class GoogleServiceAccount(clientEmail: String, privateKey: RSAPrivateKey)
