/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.stream.scaladsl.Flow
import akka.stream.{ FlowShape, Graph, Materializer, SystemMaterializer }
import java.io.File
import java.nio.file.Path
import java.lang.{ Iterable => JIterable }
import java.util.Optional
import java.util.concurrent.{ CompletionStage, Executor }

import scala.compat.java8.FutureConverters
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable
import scala.reflect.{ ClassTag, classTag }
import akka.Done
import akka.actor.ClassicActorSystemProvider
import akka.parboiled2.CharUtils
import akka.util.{ ByteString, HashCode, OptionVal }
import akka.http.ccompat.{ pre213, since213 }
import akka.http.impl.util._
import akka.http.javadsl.{ model => jm }
import akka.http.scaladsl.util.FastFuture._
import headers._

import scala.annotation.tailrec
import scala.compat.java8.FutureConverters._
import scala.concurrent.duration._

/**
 * Common base class of HttpRequest and HttpResponse.
 */
sealed trait HttpMessage extends jm.HttpMessage {
  type Self <: HttpMessage { type Self = HttpMessage.this.Self }
  def self: Self

  def isRequest: Boolean
  def isResponse: Boolean

  def headers: immutable.Seq[HttpHeader]
  def attributes: Map[AttributeKey[_], _]
  def entity: ResponseEntity
  def protocol: HttpProtocol

  /**
   * Discards the entities data bytes by running the `dataBytes` Source contained in this HttpMessage.
   *
   * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.scaladsl.Source]]
   * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
   * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
   * just having ignored the data.
   *
   * Warning: It is not allowed to discard and/or consume the `entity.dataBytes` more than once
   * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
   * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
   * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
   * "stream can cannot be materialized more than once" exception.
   *
   * When called on `Strict` entities or sources whose values can be buffered in memory,
   * the above warnings can be ignored. Repeated materialization is not necessary in this case, avoiding
   * the mentioned exceptions due to the data being held in memory.
   *
   * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
   */
  def discardEntityBytes(mat: Materializer): HttpMessage.DiscardedEntity = entity.discardBytes()(mat)

  /** Java API */
  def discardEntityBytes(system: ClassicActorSystemProvider): HttpMessage.DiscardedEntity = entity.discardBytes()(SystemMaterializer(system).materializer)

  /** Returns a copy of this message with the list of headers set to the given ones. */
  @pre213
  def withHeaders(headers: HttpHeader*): Self = withHeaders(headers.toList)

  /** Returns a copy of this message with the list of headers set to the given ones. */
  @since213
  def withHeaders(firstHeader: HttpHeader, otherHeaders: HttpHeader*): Self =
    withHeaders(firstHeader +: otherHeaders.toList)

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: immutable.Seq[HttpHeader]): Self

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  @pre213
  def withDefaultHeaders(defaultHeaders: HttpHeader*): Self = withDefaultHeaders(defaultHeaders.toList)

  @since213
  def withDefaultHeaders(firstHeader: HttpHeader, otherHeaders: HttpHeader*): Self =
    withDefaultHeaders(firstHeader +: otherHeaders.toList)

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  def withDefaultHeaders(defaultHeaders: immutable.Seq[HttpHeader]): Self =
    withHeaders {
      if (headers.isEmpty) defaultHeaders
      else defaultHeaders.foldLeft(headers) { (acc, h) => if (headers.exists(_ is h.lowercaseName)) acc else h +: acc }
    }

  /** Returns a copy of this message with the attributes set to the given ones. */
  def withAttributes(headers: Map[AttributeKey[_], _]): Self

  /** Returns a copy of this message with the entity set to the given one. */
  def withEntity(entity: MessageEntity): Self

  /** Returns a shareable and serializable copy of this message with a strict entity. */
  def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Self] =
    entity.toStrict(timeout).fast.map(this.withEntity)

  /** Returns a shareable and serializable copy of this message with a strict entity. */
  def toStrict(timeout: FiniteDuration, maxBytes: Long)(implicit ec: ExecutionContext, fm: Materializer): Future[Self] =
    entity.toStrict(timeout, maxBytes).fast.map(this.withEntity)

  /** Returns a copy of this message with the entity and headers set to the given ones. */
  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: MessageEntity): Self

  /** Returns a copy of this message with the list of headers transformed by the given function */
  def mapHeaders(f: immutable.Seq[HttpHeader] => immutable.Seq[HttpHeader]): Self = withHeaders(f(headers))

  /** Returns a copy of this message with the attributes transformed by the given function */
  def mapAttributes(f: Map[AttributeKey[_], _] => Map[AttributeKey[_], _]): Self = withAttributes(f(attributes))

  /**
   * The content encoding as specified by the Content-Encoding header. If no Content-Encoding header is present the
   * default value 'identity' is returned.
   */
  def encoding: HttpEncoding = header[`Content-Encoding`] match {
    case Some(x) => x.encodings.head
    case None    => HttpEncodings.identity
  }

  /** Returns the first header of the given type if there is one */
  def header[T >: Null <: jm.HttpHeader: ClassTag]: Option[T] = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    HttpHeader.fastFind[T](clazz, headers) match {
      case OptionVal.Some(h)                     => Some(h)
      case _ if clazz == classOf[`Content-Type`] => Some(`Content-Type`(entity.contentType)).asInstanceOf[Option[T]]
      case _                                     => None
    }
  }

  def header(headerName: String): Option[HttpHeader] = {
    val lowerCased = headerName.toRootLowerCase
    headers.find(_.is(lowerCased))
  }

  /** Returns all the headers of the given type **/
  def headers[T <: jm.HttpHeader: ClassTag]: immutable.Seq[T] = headers.collect {
    case h: T => h
  }

  def attribute[T](key: jm.AttributeKey[T])(implicit ev: JavaMapping[jm.AttributeKey[T], AttributeKey[T]]): Option[T] =
    attributes.get(ev.toScala(key)).map(_.asInstanceOf[T])

  /**
   * Returns true if this message is an:
   *  - HttpRequest and the client does not want to reuse the connection after the response for this request has been received
   *  - HttpResponse and the server will close the connection after this response
   */
  def connectionCloseExpected: Boolean = HttpMessage.connectionCloseExpected(protocol, header[Connection])

  /** Return a new instance with the given header added to the headers sequence. It's undefined where the header is added to the sequence */
  def addHeader(header: jm.HttpHeader): Self = withHeaders(header.asInstanceOf[HttpHeader] +: headers)

  def addAttribute[T](key: jm.AttributeKey[T], value: T): Self = {
    val ev = implicitly[JavaMapping[jm.AttributeKey[T], AttributeKey[T]]]
    mapAttributes(_.updated(ev.toScala(key), value))
  }

  def addCredentials(credentials: jm.headers.HttpCredentials): Self = addHeader(jm.headers.Authorization.create(credentials))

  /** Removes the header with the given name (case-insensitive) */
  def removeHeader(headerName: String): Self = {
    val lowerHeaderName = headerName.toRootLowerCase
    withHeaders(headers.filterNot(_.is(lowerHeaderName)))
  }

  def removeAttribute(key: jm.AttributeKey[_]): Self = {
    val ev = implicitly[JavaMapping[jm.AttributeKey[_], AttributeKey[_]]]
    mapAttributes(_ - ev.toScala(key))
  }

  def withEntity(string: String): Self = withEntity(HttpEntity(string))
  def withEntity(bytes: Array[Byte]): Self = withEntity(HttpEntity(bytes))
  def withEntity(bytes: ByteString): Self = withEntity(HttpEntity(bytes))
  def withEntity(contentType: jm.ContentType.NonBinary, string: String): Self =
    withEntity(HttpEntity(contentType.asInstanceOf[ContentType.NonBinary], string))
  def withEntity(contentType: jm.ContentType, bytes: Array[Byte]): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))
  def withEntity(contentType: jm.ContentType, bytes: ByteString): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))

  def withEntity(contentType: jm.ContentType, file: File): Self = withEntity(HttpEntity.fromPath(contentType.asInstanceOf[ContentType], file.toPath))
  def withEntity(contentType: jm.ContentType, file: Path): Self = withEntity(HttpEntity.fromPath(contentType.asInstanceOf[ContentType], file))

  def transformEntityDataBytes[M](transformer: Graph[FlowShape[ByteString, ByteString], M]): Self

  import collection.JavaConverters._
  /** Java API */
  def getHeaders: JIterable[jm.HttpHeader] = (headers: immutable.Seq[jm.HttpHeader]).asJava
  /** Java API */
  def getHeader[T <: jm.HttpHeader](headerClass: Class[T]): Optional[T] =
    HttpHeader.fastFind[jm.HttpHeader](headerClass.asInstanceOf[Class[jm.HttpHeader]], headers) match {
      case OptionVal.Some(h) => Optional.of(h.asInstanceOf[T])
      case _                 => Optional.empty()
    }
  /** Java API */
  def getHeaders[T <: jm.HttpHeader](headerClass: Class[T]): JIterable[T] = {
    headers[T](ClassTag[T](headerClass)).asJava
  }
  /** Java API */
  def getHeader(headerName: String): Optional[jm.HttpHeader] = {
    val lowerCased = headerName.toRootLowerCase
    Util.convertOption(headers.find(_.is(lowerCased))) // Upcast because of invariance
  }
  /** Java API */
  def addHeaders(headers: JIterable[jm.HttpHeader]): Self = withHeaders(this.headers ++ headers.asScala.asInstanceOf[Iterable[HttpHeader]])
  /** Java API */
  def withHeaders(headers: JIterable[jm.HttpHeader]): Self =
    withHeaders(headers.asScala.toVector.map(x => JavaMapping.toScala(x)))
  /** Java API */
  def getAttribute[T](attributeKey: jm.AttributeKey[T]): Optional[T] =
    Util.convertOption(attribute(attributeKey))

  /** Java API */
  def toStrict(timeoutMillis: Long, ec: Executor, materializer: Materializer): CompletionStage[Self] = {
    val ex = ExecutionContext.fromExecutor(ec)
    toStrict(timeoutMillis.millis)(ex, materializer).toJava
  }
  /** Java API */
  def toStrict(timeoutMillis: Long, maxBytes: Long, ec: Executor, materializer: Materializer): CompletionStage[Self] = {
    val ex = ExecutionContext.fromExecutor(ec)
    toStrict(timeoutMillis.millis, maxBytes)(ex, materializer).toJava
  }

  /** Java API */
  def toStrict(timeoutMillis: Long, system: ClassicActorSystemProvider): CompletionStage[Self] =
    toStrict(timeoutMillis.millis)(system.classicSystem.dispatcher, SystemMaterializer(system).materializer).toJava

  /** Java API */
  def toStrict(timeoutMillis: Long, maxBytes: Long, system: ClassicActorSystemProvider): CompletionStage[Self] =
    toStrict(timeoutMillis.millis, maxBytes)(system.classicSystem.dispatcher, SystemMaterializer(system).materializer).toJava
}

object HttpMessage {
  private[http] def connectionCloseExpected(protocol: HttpProtocol, connectionHeader: Option[Connection]): Boolean =
    protocol match {
      case HttpProtocols.`HTTP/1.1` => connectionHeader.isDefined && connectionHeader.get.hasClose
      case HttpProtocols.`HTTP/1.0` => connectionHeader.isEmpty || !connectionHeader.get.hasKeepAlive
      case _                        => throw new UnsupportedOperationException(s"HttpMessage does not support ${protocol.value}.")
    }

  /**
   * Represents the currently being-drained HTTP Entity which triggers completion of the contained
   * Future once the entity has been drained for the given HttpMessage completely.
   */
  final class DiscardedEntity(f: Future[Done]) extends akka.http.javadsl.model.HttpMessage.DiscardedEntity {
    /**
     * This future completes successfully once the underlying entity stream has been
     * successfully drained (and fails otherwise).
     */
    def future: Future[Done] = f

    /**
     * This future completes successfully once the underlying entity stream has been
     * successfully drained (and fails otherwise).
     */
    def completionStage: CompletionStage[Done] = FutureConverters.toJava(f)
  }
  val AlreadyDiscardedEntity = new DiscardedEntity(Future.successful(Done))

  /** Adds Scala DSL idiomatic methods to [[HttpMessage]], e.g. versions of methods with an implicit [[Materializer]]. */
  implicit final class HttpMessageScalaDSLSugar(val httpMessage: HttpMessage) extends AnyVal {
    /**
     * Discards the entities data bytes by running the `dataBytes` Source contained by the `entity` of this HTTP message.
     *
     * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.scaladsl.Source]]
     * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
     * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
     * just having ignored the data.
     *
     * Warning: It is not allowed to discard and/or consume the `entity.dataBytes` more than once
     * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
     * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
     * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
     * "stream can cannot be materialized more than once" exception.
     *
     * When called on `Strict` entities or sources whose values can be buffered in memory,
     * the above warnings can be ignored. Repeated materialization is not necessary in this case, avoiding
     * the mentioned exceptions due to the data being held in memory.
     *
     * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
     */
    def discardEntityBytes()(implicit mat: Materializer): HttpMessage.DiscardedEntity =
      httpMessage.discardEntityBytes(mat)
  }
}

/**
 * The immutable model HTTP request model.
 */
final class HttpRequest(
  val method:     HttpMethod,
  val uri:        Uri,
  val headers:    immutable.Seq[HttpHeader],
  val attributes: Map[AttributeKey[_], _],
  val entity:     RequestEntity,
  val protocol:   HttpProtocol)
  extends jm.HttpRequest with HttpMessage {

  HttpRequest.verifyUri(uri)
  require(entity.isKnownEmpty || method.isEntityAccepted, s"Requests with method '${method.value}' must have an empty entity")
  require(
    protocol != HttpProtocols.`HTTP/1.0` || !entity.isChunked,
    "HTTP/1.0 requests must not have a chunked entity")

  type Self = HttpRequest
  def self: Self = this

  override def isRequest = true
  override def isResponse = false

  @deprecated("use the constructor that includes an attributes parameter instead", "10.2.0")
  private[model] def this(method: HttpMethod, uri: Uri, headers: immutable.Seq[HttpHeader], entity: RequestEntity, protocol: HttpProtocol) =
    this(method, uri, headers, Map.empty, entity, protocol)

  /**
   * Resolve this request's URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   *
   * Throws an [[IllegalUriException]] if the URI is relative and the `headers` don't
   * include a valid [[akka.http.scaladsl.model.headers.Host]] header or if URI authority and [[akka.http.scaladsl.model.headers.Host]] header don't match.
   */
  def effectiveUri(securedConnection: Boolean, defaultHostHeader: Host = Host.empty): Uri =
    HttpRequest.effectiveUri(uri, headers, securedConnection, defaultHostHeader)

  /**
   * Returns a copy of this request with the URI resolved according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   */
  def withEffectiveUri(securedConnection: Boolean, defaultHostHeader: Host = Host.empty): HttpRequest =
    copyImpl(uri = effectiveUri(securedConnection, defaultHostHeader))

  /**
   * All cookies provided by the client in one or more `Cookie` headers.
   */
  def cookies: immutable.Seq[HttpCookiePair] = for (`Cookie`(cookies) <- headers; cookie <- cookies) yield cookie

  /**
   * Determines whether this request can be safely retried, which is the case only of the request method is idempotent.
   */
  def canBeRetried = method.isIdempotent

  override def withHeaders(headers: immutable.Seq[HttpHeader]): HttpRequest =
    if (headers eq this.headers) this else copyImpl(headers = headers)

  override def withAttributes(attributes: Map[AttributeKey[_], _]): HttpRequest =
    if (attributes eq this.attributes) this else copyImpl(attributes = attributes)

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: RequestEntity): HttpRequest = copyImpl(headers = headers, entity = entity)
  override def withEntity(entity: jm.RequestEntity): HttpRequest = copyImpl(entity = entity.asInstanceOf[RequestEntity])
  override def withEntity(entity: MessageEntity): HttpRequest = copyImpl(entity = entity)

  def mapEntity(f: RequestEntity => RequestEntity): HttpRequest = withEntity(f(entity))

  override def withMethod(method: akka.http.javadsl.model.HttpMethod): HttpRequest = copyImpl(method = method.asInstanceOf[HttpMethod])
  override def withProtocol(protocol: akka.http.javadsl.model.HttpProtocol): HttpRequest = copyImpl(protocol = protocol.asInstanceOf[HttpProtocol])
  override def withUri(path: String): HttpRequest = withUri(Uri(path))
  def withUri(uri: Uri): HttpRequest = copyImpl(uri = uri)

  def transformEntityDataBytes[M](transformer: Graph[FlowShape[ByteString, ByteString], M]): HttpRequest = copyImpl(entity = entity.transformDataBytes(Flow.fromGraph(transformer)))

  import JavaMapping.Implicits._
  /** Java API */
  override def getUri: jm.Uri = uri.asJava
  /** Java API */
  override def withUri(uri: jm.Uri): HttpRequest = copyImpl(uri = uri.asScala)

  /* Manual Case Class things, to easen bin-compat */

  @deprecated("Use the `withXYZ` methods instead. Kept for binary compatibility", "10.2.0")
  def copy(
    method:   HttpMethod                = method,
    uri:      Uri                       = uri,
    headers:  immutable.Seq[HttpHeader] = headers,
    entity:   RequestEntity             = entity,
    protocol: HttpProtocol              = protocol) = copyImpl(method, uri, headers, entity = entity, protocol = protocol)

  private def copyImpl(
    method:     HttpMethod                = method,
    uri:        Uri                       = uri,
    headers:    immutable.Seq[HttpHeader] = headers,
    attributes: Map[AttributeKey[_], _]   = attributes,
    entity:     RequestEntity             = entity,
    protocol:   HttpProtocol              = protocol
  ) = new HttpRequest(method, uri, headers, attributes, entity, protocol)

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, method)
    result = HashCode.hash(result, uri)
    result = HashCode.hash(result, headers)
    result = HashCode.hash(result, attributes)
    result = HashCode.hash(result, entity)
    result = HashCode.hash(result, protocol)
    result
  }

  override def equals(obj: scala.Any): Boolean = obj match {
    case request @ HttpRequest(_method, _uri, _headers, _entity, _protocol) =>
      method == _method &&
        uri == _uri &&
        headers == _headers &&
        attributes == request.attributes &&
        entity == _entity &&
        protocol == _protocol
    case _ => false
  }

  override def toString = s"""HttpRequest(${_1},${_2},${_3},${_4},${_5})"""

  // name-based unapply accessors
  def _1 = method
  def _2 = uri
  def _3 = headers
  def _4 = entity
  def _5 = protocol

}

object HttpRequest {
  /**
   * Determines the effective request URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   *
   * Throws an [[IllegalUriException]] if the URI is relative and the `headers` don't
   * include a valid [[akka.http.scaladsl.model.headers.Host]] header or if URI authority and [[akka.http.scaladsl.model.headers.Host]] header don't match.
   */
  def effectiveUri(uri: Uri, headers: immutable.Seq[HttpHeader], securedConnection: Boolean, defaultHostHeader: Host): Uri = {
    @tailrec def findHostAndWsUpgrade(it: Iterator[HttpHeader], host: OptionVal[Host] = OptionVal.None, wsUpgrade: Option[Boolean] = None): (OptionVal[Host], Boolean) =
      if (host.isDefined && wsUpgrade.isDefined || !it.hasNext)
        (host, wsUpgrade.contains(true))
      else
        it.next() match {
          case h: Host    => findHostAndWsUpgrade(it, OptionVal.Some(h), wsUpgrade)
          case u: Upgrade => findHostAndWsUpgrade(it, host, Some(u.hasWebSocket))
          case _          => findHostAndWsUpgrade(it, host, wsUpgrade)
        }
    val (hostHeader, isWebsocket) = findHostAndWsUpgrade(headers.iterator)
    if (uri.isRelative) {
      def fail(detail: String) =
        throw IllegalUriException(
          s"Cannot establish effective URI of request to `$uri`, request has a relative URI and $detail",
          "consider setting `akka.http.server.default-host-header`")
      val Host(hostHeaderHost, hostHeaderPort) = hostHeader match {
        case OptionVal.Some(x) if x.isEmpty => if (defaultHostHeader.isEmpty) fail("an empty `Host` header") else defaultHostHeader
        case OptionVal.Some(x)              => x
        case _                              => if (defaultHostHeader.isEmpty) fail("is missing a `Host` header") else defaultHostHeader
      }
      val defaultScheme =
        if (isWebsocket) Uri.websocketScheme(securedConnection)
        else Uri.httpScheme(securedConnection)
      uri.toEffectiveRequestUri(hostHeaderHost, hostHeaderPort, defaultScheme)
    } else // http://tools.ietf.org/html/rfc7230#section-5.4
    if (hostHeader.isEmpty || uri.authority.isEmpty && hostHeader.get.isEmpty ||
      hostHeader.get.host.equalsIgnoreCase(uri.authority.host) && hostHeader.get.port == uri.authority.port) uri
    else throw IllegalUriException(
      s"'Host' header value of request to `$uri` doesn't match request target authority",
      s"Host header: $hostHeader\nrequest target authority: ${uri.authority}")
  }

  /**
   * Verifies that the given [[Uri]] is non-empty and has either scheme `http`, `https`, `ws`, `wss` or no scheme at all.
   * If any of these conditions is not met the method throws an [[IllegalUriException]].
   */
  def verifyUri(uri: Uri): Unit =
    if (uri.isEmpty) throw IllegalUriException("`uri` must not be empty")
    else {
      def c(i: Int) = CharUtils.toLowerCase(uri.scheme charAt i)
      uri.scheme.length match {
        case 0 => // ok
        case 4 if c(0) == 'h' && c(1) == 't' && c(2) == 't' && c(3) == 'p' => // ok
        case 5 if c(0) == 'h' && c(1) == 't' && c(2) == 't' && c(3) == 'p' && c(4) == 's' => // ok
        case 2 if c(0) == 'w' && c(1) == 's' => // ok
        case 3 if c(0) == 'w' && c(1) == 's' && c(2) == 's' => // ok
        case _ => throw IllegalUriException("""`uri` must have scheme "http", "https", "ws", "wss" or no scheme""")
      }
    }

  /* Manual Case Class things, to ease bin-compat */

  def apply(
    method:   HttpMethod                = HttpMethods.GET,
    uri:      Uri                       = Uri./,
    headers:  immutable.Seq[HttpHeader] = Nil,
    entity:   RequestEntity             = HttpEntity.Empty,
    protocol: HttpProtocol              = HttpProtocols.`HTTP/1.1`) = new HttpRequest(method, uri, headers, Map.empty, entity, protocol)

  def unapply(any: HttpRequest) = new OptHttpRequest(any)
}

/**
 * The immutable HTTP response model.
 */
final class HttpResponse(
  val status:     StatusCode,
  val headers:    immutable.Seq[HttpHeader],
  val attributes: Map[AttributeKey[_], _],
  val entity:     ResponseEntity,
  val protocol:   HttpProtocol)
  extends jm.HttpResponse with HttpMessage {

  require(entity.isKnownEmpty || status.allowsEntity, "Responses with this status code must have an empty entity")
  require(
    protocol != HttpProtocols.`HTTP/1.0` || !entity.isChunked,
    "HTTP/1.0 responses must not have a chunked entity")

  type Self = HttpResponse
  def self = this

  override def isRequest = false
  override def isResponse = true

  @deprecated("use the constructor that includes an attributes parameter instead", "10.2.0")
  private[model] def this(status: StatusCode, headers: immutable.Seq[HttpHeader], entity: ResponseEntity, protocol: HttpProtocol) =
    this(status, headers, Map.empty, entity, protocol)

  override def withHeaders(headers: immutable.Seq[HttpHeader]): HttpResponse =
    if (headers eq this.headers) this else copyImpl(headers = headers)

  def withAttributes(attributes: Map[AttributeKey[_], _]): HttpResponse =
    if (attributes eq this.attributes) this else copyImpl(attributes = attributes)

  override def withProtocol(protocol: akka.http.javadsl.model.HttpProtocol): akka.http.javadsl.model.HttpResponse = withProtocol(protocol.asInstanceOf[HttpProtocol])
  def withProtocol(protocol: HttpProtocol): HttpResponse = copyImpl(protocol = protocol)
  override def withStatus(statusCode: Int): HttpResponse = copyImpl(status = statusCode)
  override def withStatus(statusCode: akka.http.javadsl.model.StatusCode): HttpResponse = copyImpl(status = statusCode.asInstanceOf[StatusCode])

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: MessageEntity): HttpResponse = withHeadersAndEntity(headers, entity: ResponseEntity)
  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: ResponseEntity): HttpResponse = copyImpl(headers = headers, entity = entity)
  override def withEntity(entity: jm.ResponseEntity): HttpResponse = copyImpl(entity = entity.asInstanceOf[ResponseEntity])
  override def withEntity(entity: MessageEntity): HttpResponse = copyImpl(entity = entity)
  override def withEntity(entity: jm.RequestEntity): HttpResponse = withEntity(entity: jm.ResponseEntity)

  def mapEntity(f: ResponseEntity => ResponseEntity): HttpResponse = withEntity(f(entity))

  def transformEntityDataBytes[T](transformer: Graph[FlowShape[ByteString, ByteString], T]): HttpResponse = copyImpl(entity = entity.transformDataBytes(Flow.fromGraph(transformer)))

  /* Manual Case Class things, to ease bin-compat */
  @deprecated("Use the `withXYZ` methods instead", "10.2.0")
  def copy(
    status:   StatusCode                = status,
    headers:  immutable.Seq[HttpHeader] = headers,
    entity:   ResponseEntity            = entity,
    protocol: HttpProtocol              = protocol) = copyImpl(status, headers, entity = entity, protocol = protocol)

  private def copyImpl(
    status:     StatusCode                = status,
    headers:    immutable.Seq[HttpHeader] = headers,
    attributes: Map[AttributeKey[_], _]   = attributes,
    entity:     ResponseEntity            = entity,
    protocol:   HttpProtocol              = protocol
  ) = new HttpResponse(status, headers, attributes, entity, protocol)

  override def equals(obj: scala.Any): Boolean = obj match {
    case response @ HttpResponse(_status, _headers, _entity, _protocol) =>
      status == _status &&
        headers == _headers &&
        attributes == response.attributes &&
        entity == _entity &&
        protocol == _protocol
    case _ => false
  }

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, status)
    result = HashCode.hash(result, headers)
    result = HashCode.hash(result, attributes)
    result = HashCode.hash(result, entity)
    result = HashCode.hash(result, protocol)
    result
  }

  override def toString = s"""HttpResponse(${_1},${_2},${_3},${_4})"""

  // name-based unapply accessors
  def _1 = this.status
  def _2 = this.headers
  def _3 = this.entity
  def _4 = this.protocol

}

object HttpResponse {
  /* Manual Case Class things, to easen bin-compat */

  def apply(
    status:   StatusCode                = StatusCodes.OK,
    headers:  immutable.Seq[HttpHeader] = Nil,
    entity:   ResponseEntity            = HttpEntity.Empty,
    protocol: HttpProtocol              = HttpProtocols.`HTTP/1.1`) = new HttpResponse(status, headers, Map.empty, entity, protocol)

  def unapply(any: HttpResponse): OptHttpResponse = new OptHttpResponse(any)
}

final class OptHttpRequest(val get: HttpRequest) extends AnyVal {
  def isEmpty: Boolean = get == null
}

final class OptHttpResponse(val get: HttpResponse) extends AnyVal {
  def isEmpty: Boolean = get == null
}
